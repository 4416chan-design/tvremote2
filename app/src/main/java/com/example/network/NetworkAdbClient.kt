package com.example.network

import com.example.data.model.TvImeKeycodeMap
import com.example.data.model.TvImeStatus
import com.example.data.model.TvImeType
import com.example.data.model.TvKeyboardLanguage
import com.example.data.model.TvKeyCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

enum class AdbConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHORIZING,
    CONNECTED,
    UNAVAILABLE
}

data class AdbBrightnessResult(
    val adbConnected: Boolean,
    val hardwareApplied: Boolean,
    val message: String? = null
)

enum class AdbAppLaunchOutcome {
    LAUNCHED_APP,
    OPENED_PLAY_STORE,
    FAILED
}

data class AdbAppLaunchResult(
    val outcome: AdbAppLaunchOutcome,
    val detail: String? = null
)

/**
 * Lightweight pure-Kotlin ADB-over-TCP (port 5555) client supporting RSA authentication,
 * shell command execution, TV IME language inspection, key/text injection, and TV app launches.
 */
class NetworkAdbClient(
    private val keyManager: AdbKeyManager
) {
    private val _adbState = MutableStateFlow(AdbConnectionState.DISCONNECTED)
    val adbState: StateFlow<AdbConnectionState> = _adbState.asStateFlow()

    private val _adbStatusDetail = MutableStateFlow<String?>(null)
    val adbStatusDetail: StateFlow<String?> = _adbStatusDetail.asStateFlow()

    private val ioMutex = Mutex()
    private val streamIdCounter = AtomicInteger(1)

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var input: InputStream? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var connectedHost: String? = null

    @Volatile
    private var peerMaxPayload: Int = MAX_PAYLOAD

    @Volatile
    private var lastVerifiedHardwareBacklightSupport: Boolean? = null

    @Volatile
    internal var testShellRunner: (suspend (String) -> Result<String>)? = null

    val isConnected: Boolean
        get() = _adbState.value == AdbConnectionState.CONNECTED && (
            testShellRunner != null || (socket?.isConnected == true && socket?.isClosed == false)
            )

    internal fun configureForTest(
        connected: Boolean,
        shellRunner: (suspend (String) -> Result<String>)?
    ) {
        testShellRunner = shellRunner
        _adbState.value = if (connected) AdbConnectionState.CONNECTED else AdbConnectionState.DISCONNECTED
        _adbStatusDetail.value = if (connected) "ADB 테스트 연결됨" else null
    }

    suspend fun connect(host: String, port: Int = DEFAULT_ADB_PORT): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (connectedHost == host && isConnected) {
                true
            } else if (testShellRunner != null && _adbState.value == AdbConnectionState.CONNECTED) {
                true
            } else {
                closeSocketOnly()
                lastVerifiedHardwareBacklightSupport = null
                peerMaxPayload = MAX_PAYLOAD
                _adbState.value = AdbConnectionState.CONNECTING
                _adbStatusDetail.value = "TV ADB(5555) 연결 시도 중..."

                try {
                    val tcpSocket = Socket().apply {
                        tcpNoDelay = true
                        soTimeout = 4_000
                        connect(InetSocketAddress(host, port), 3_500)
                    }
                    socket = tcpSocket
                    input = tcpSocket.getInputStream()
                    output = tcpSocket.getOutputStream()

                    val out = output!!
                    val inp = input!!

                    sendPacket(
                        out = out,
                        command = A_CNXN,
                        arg0 = A_VERSION,
                        arg1 = MAX_PAYLOAD,
                        data = "host::\u0000".toByteArray(Charsets.UTF_8)
                    )

                    var sentSignature = false
                    var sentPublicKey = false
                    var authenticated = false

                    while (!authenticated) {
                        val pkt = readPacket(inp)
                        when (pkt.command) {
                            A_CNXN -> {
                                tcpSocket.soTimeout = 4_500
                                if (pkt.arg1 > 0) {
                                    peerMaxPayload = pkt.arg1.coerceIn(4_096, MAX_PAYLOAD)
                                }
                                connectedHost = host
                                _adbState.value = AdbConnectionState.CONNECTED
                                _adbStatusDetail.value = "ADB 5555 연결됨"
                                authenticated = true
                            }
                            A_AUTH -> {
                                if (pkt.arg0 == ADB_AUTH_TOKEN) {
                                    if (!sentSignature) {
                                        sentSignature = true
                                        val signature = keyManager.signToken(pkt.data)
                                        sendPacket(out, A_AUTH, ADB_AUTH_SIGNATURE, 0, signature)
                                    } else if (!sentPublicKey) {
                                        sentPublicKey = true
                                        _adbState.value = AdbConnectionState.AUTHORIZING
                                        _adbStatusDetail.value = "TV 화면에서 디버깅 '허용'을 눌러주세요"
                                        // Give user up to 18s to accept the RSA prompt on the TV screen
                                        tcpSocket.soTimeout = 18_000
                                        val pubPayload = keyManager.buildAdbPublicKeyPayload()
                                        sendPacket(out, A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, pubPayload)
                                    } else {
                                        throw IllegalStateException("TV에서 ADB 인증이 거부되었습니다.")
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                    true
                } catch (t: Throwable) {
                    closeSocketOnly()
                    connectedHost = null
                    _adbState.value = AdbConnectionState.UNAVAILABLE
                    _adbStatusDetail.value = when {
                        t.message?.contains("refused", ignoreCase = true) == true ->
                            "TV 개발자 옵션 > USB/네트워크 디버깅 꺼짐 (포트 5555 닫힘)"
                        t.message?.contains("timed out", ignoreCase = true) == true ->
                            "TV ADB 응답 시간 초과 (개발자 옵션 또는 허용 팝업 확인)"
                        else -> t.message ?: "ADB 연결 실패"
                    }
                    false
                }
            }
        }
    }

    suspend fun shell(command: String, timeoutMs: Long = 4_500L): Result<String> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            testShellRunner?.let { runner ->
                if (_adbState.value != AdbConnectionState.CONNECTED) {
                    return@withLock Result.failure(IllegalStateException("ADB가 연결되어 있지 않습니다."))
                }
                return@withLock runner(command)
            }

            val out = output
            val inp = input
            val sock = socket
            if (out == null || inp == null || sock == null || sock.isClosed) {
                _adbState.value = AdbConnectionState.DISCONNECTED
                return@withLock Result.failure(IllegalStateException("ADB가 연결되어 있지 않습니다."))
            }

            try {
                sock.soTimeout = timeoutMs.coerceIn(1_000L, 120_000L).toInt()
                val localId = streamIdCounter.getAndIncrement()
                sendPacket(
                    out = out,
                    command = A_OPEN,
                    arg0 = localId,
                    arg1 = 0,
                    data = "shell:$command\u0000".toByteArray(Charsets.UTF_8)
                )

                val outputBuffer = ByteArrayOutputStream()
                var remoteId = 0
                var streamOpened = false

                while (true) {
                    val pkt = readPacket(inp)
                    when (pkt.command) {
                        A_OKAY -> {
                            if (pkt.arg1 == localId) {
                                remoteId = pkt.arg0
                                streamOpened = true
                            }
                        }
                        A_WRTE -> {
                            if (pkt.arg1 == localId) {
                                if (remoteId == 0) remoteId = pkt.arg0
                                if (pkt.data.isNotEmpty()) {
                                    outputBuffer.write(pkt.data)
                                }
                                sendPacket(out, A_OKAY, localId, remoteId, ByteArray(0))
                            }
                        }
                        A_CLSE -> {
                            if (pkt.arg1 == localId || (streamOpened && pkt.arg0 == remoteId)) {
                                if (remoteId != 0) {
                                    sendPacket(out, A_CLSE, localId, remoteId, ByteArray(0))
                                }
                                break
                            }
                        }
                        else -> Unit
                    }
                }

                Result.success(outputBuffer.toString(Charsets.UTF_8.name()).trim())
            } catch (t: Throwable) {
                closeSocketOnly()
                _adbState.value = AdbConnectionState.DISCONNECTED
                _adbStatusDetail.value = "ADB 연결이 끊어졌습니다"
                Result.failure(t)
            }
        }
    }

    /**
     * Attempts direct Xiaomi TV A Pro backlight & screen brightness writes over ADB.
     * Detects when vendor HAL blocks/ignores standard Android brightness settings.
     */
    suspend fun applyTvBrightnessPercent(percent: Int): AdbBrightnessResult {
        if (!isConnected) {
            return AdbBrightnessResult(adbConnected = false, hardwareApplied = false)
        }

        val clampedPercent = percent.coerceIn(5, 100)
        val level255 = ((clampedPercent * 255) / 100).coerceIn(10, 255)

        val script = buildString {
            append("BEFORE_D=$(dumpsys display 2>/dev/null | grep -m1 -E 'mScreenBrightness=|mCurrentBrightness='); ")
            append("ERR=$( (settings put system screen_brightness_mode 0 && settings put system screen_brightness $level255) 2>&1 ); ")
            append("settings put global mitv_backlight $clampedPercent >/dev/null 2>&1; ")
            append("settings put system backlight_level $clampedPercent >/dev/null 2>&1; ")
            append("AFTER_D=$(dumpsys display 2>/dev/null | grep -m1 -E 'mScreenBrightness=|mCurrentBrightness='); ")
            append("echo \"ERR=\$ERR|BEFORE=\$BEFORE_D|AFTER=\$AFTER_D\"")
        }

        val shellResult = shell(script)
        if (shellResult.isFailure) {
            return AdbBrightnessResult(
                adbConnected = false,
                hardwareApplied = false,
                message = shellResult.exceptionOrNull()?.message
            )
        }

        val out = shellResult.getOrDefault("")
        val errPart = out.substringAfter("ERR=", "").substringBefore("|BEFORE=").trim()
        val beforeVal = out.substringAfter("BEFORE=", "").substringBefore("|AFTER=").trim()
        val afterVal = out.substringAfter("AFTER=", "").trim()

        val hasWriteError = errPart.contains("Exception", ignoreCase = true) ||
            errPart.contains("error", ignoreCase = true) ||
            errPart.contains("Permission denied", ignoreCase = true)

        val displayNegativeOrFixed = afterVal.contains("-1") || afterVal.isBlank()
        val hardwareResponded = !hasWriteError && !displayNegativeOrFixed && (
            beforeVal != afterVal || lastVerifiedHardwareBacklightSupport == true
            )

        if (hardwareResponded) {
            lastVerifiedHardwareBacklightSupport = true
            return AdbBrightnessResult(adbConnected = true, hardwareApplied = true)
        }

        // If display float stayed -1.0 or failed permission, Xiaomi Picture HAL ignores Android screen_brightness
        val likelyVendorHalRestricted = hasWriteError || displayNegativeOrFixed || beforeVal == afterVal
        if (likelyVendorHalRestricted) {
            lastVerifiedHardwareBacklightSupport = false
        }
        return AdbBrightnessResult(
            adbConnected = true,
            hardwareApplied = !likelyVendorHalRestricted
        )
    }

    suspend fun openDisplayPictureSettings(): Boolean {
        if (!isConnected) return false
        val cmd = "(am start -a android.settings.DISPLAY_SETTINGS || " +
            "am start -n com.android.tv.settings/.MainSettings) 2>&1"
        val res = shell(cmd).getOrNull() ?: return false
        return !res.contains("Error:", ignoreCase = true) &&
            !res.contains("Exception", ignoreCase = true)
    }

    /**
     * Injects an Android KeyEvent directly at system InputManager level (`input keyevent <code >`),
     * allowing accessibility/button-remapping apps (e.g., Button Mapper) to intercept KEYCODE_ALL_APPS (284).
     */
    suspend fun sendKeyEvent(keyCode: Int): Result<Unit> {
        if (!isConnected) {
            return Result.failure(IllegalStateException("ADB 미연결"))
        }
        val cmd = buildKeyEventShellCommand(keyCode)
            ?: return Result.failure(IllegalArgumentException("잘못된 키코드: $keyCode"))
        val shellResult = shell(cmd)
        if (shellResult.isFailure) {
            return Result.failure(shellResult.exceptionOrNull() ?: IllegalStateException("ADB 키 전송 실패"))
        }
        val out = shellResult.getOrDefault("")
        if (out.contains("Error:", ignoreCase = true) || out.contains("Exception", ignoreCase = true)) {
            return Result.failure(IllegalStateException(out))
        }
        return Result.success(Unit)
    }

    /**
     * Sends text via ADB shell using raw `input text` (with `%s` space and POSIX shell escaping)
     * or returns [ADB_UNICODE_UNSUPPORTED] for non-ASCII strings.
     */
    suspend fun sendInputText(
        text: String,
        clearCount: Int = 0,
        submitWithEnter: Boolean = false
    ): Result<Unit> {
        if (!isConnected) {
            return Result.failure(IllegalStateException("ADB 미연결"))
        }

        if (text.isEmpty()) {
            return clearTextField(count = clearCount.coerceAtLeast(1), submitWithEnter = submitWithEnter)
        }

        if (isAsciiInputSupported(text)) {
            val cmd = buildInputTextShellCommand(
                text = text,
                clearCount = clearCount,
                submitWithEnter = submitWithEnter
            ) ?: return Result.failure(IllegalArgumentException("지원되지 않는 제어 문자입니다."))

            val res = shell(cmd)
            if (res.isFailure) {
                return Result.failure(res.exceptionOrNull() ?: IllegalStateException("ADB 텍스트 전송 실패"))
            }
            val out = res.getOrDefault("")
            if (out.contains("Error:", ignoreCase = true) || out.contains("Exception", ignoreCase = true)) {
                return Result.failure(IllegalStateException(out))
            }
            return Result.success(Unit)
        }

        return Result.failure(UnsupportedOperationException(ADB_UNICODE_UNSUPPORTED))
    }

    suspend fun clearTextField(count: Int = 20, submitWithEnter: Boolean = false): Result<Unit> {
        if (!isConnected) {
            return Result.failure(IllegalStateException("ADB 미연결"))
        }
        val prefix = buildClearKeyEventsPrefix(count.coerceIn(1, 30))
        val cmd = if (submitWithEnter) {
            "$prefix; input keyevent 66 2>&1"
        } else {
            "$prefix 2>&1"
        }
        val res = shell(cmd)
        if (res.isFailure) {
            return Result.failure(res.exceptionOrNull() ?: IllegalStateException("ADB 삭제 명령 실패"))
        }
        val out = res.getOrDefault("")
        if (out.contains("Error:", ignoreCase = true) || out.contains("Exception", ignoreCase = true)) {
            return Result.failure(IllegalStateException(out))
        }
        return Result.success(Unit)
    }

    suspend fun sendKeyCombination(vararg keyCodes: Int): Result<Unit> {
        if (!isConnected) {
            return Result.failure(IllegalStateException("ADB 미연결"))
        }
        val cmd = buildKeyCombinationShellCommand(*keyCodes)
            ?: return Result.failure(IllegalArgumentException("유효하지 않은 키 조합"))
        val res = shell(cmd)
        if (res.isFailure) {
            return Result.failure(res.exceptionOrNull() ?: IllegalStateException("ADB 키 조합 전송 실패"))
        }
        val out = res.getOrDefault("")
        if (out.contains("Error:", ignoreCase = true) || out.contains("Exception", ignoreCase = true)) {
            return Result.failure(IllegalStateException(out))
        }
        return Result.success(Unit)
    }

    /**
     * Executes "(1) Select All (Ctrl+A) -> (2) Delete (DEL)" via ADB `input keycombination`,
     * falling back to `MOVE_END + DEL`s on Android TV builds without `keycombination`.
     */
    suspend fun selectAllAndDelete(): Result<Unit> {
        if (!isConnected) {
            return Result.failure(IllegalStateException("ADB 미연결"))
        }
        val res = shell(buildSelectAllAndDeleteShellCommand(), timeoutMs = 6_000L)
        if (res.isFailure) {
            return Result.failure(res.exceptionOrNull() ?: IllegalStateException("전체 지우기 실패"))
        }
        val out = res.getOrDefault("")
        if (out.contains("Error:", ignoreCase = true) || out.contains("Exception", ignoreCase = true)) {
            return Result.failure(IllegalStateException(out))
        }
        return Result.success(Unit)
    }

    /**
     * Launches Google Assistant on the TV via ACTION_ASSIST intent, falling back to
     * KEYCODE_VOICE_ASSIST (231) or KEYCODE_ASSIST (219) over ADB shell.
     */
    suspend fun triggerGoogleAssistant(): Result<Unit> {
        if (!isConnected) {
            return Result.failure(IllegalStateException("ADB 미연결"))
        }
        val res = shell(buildGoogleAssistantLaunchShellCommand(), timeoutMs = 5_000L)
        if (res.isFailure) {
            return Result.failure(res.exceptionOrNull() ?: IllegalStateException("구글 어시스턴트 실행 실패"))
        }
        val out = res.getOrDefault("")
        if (out.contains("Error:", ignoreCase = true) || out.contains("Exception", ignoreCase = true)) {
            return Result.failure(IllegalStateException(out))
        }
        return Result.success(Unit)
    }

    /**
     * Submits a recognized search/assistant query to Android TV global search / Katniss
     * without requiring a focused IME field. Supports UTF-8 Korean and English queries.
     */
    suspend fun submitGoogleAssistantQuery(query: String): Result<Unit> {
        if (!isConnected) {
            return Result.failure(IllegalStateException("ADB 미연결"))
        }
        val cmd = buildGoogleAssistantQueryShellCommand(query)
            ?: return Result.failure(IllegalArgumentException("검색어가 비어 있습니다."))
        val res = shell(cmd, timeoutMs = 5_000L)
        if (res.isFailure) {
            return Result.failure(res.exceptionOrNull() ?: IllegalStateException("음성 검색 인텐트 전송 실패"))
        }
        val out = res.getOrDefault("")
        if (out.contains("Error:", ignoreCase = true) || out.contains("Exception", ignoreCase = true)) {
            return Result.failure(IllegalStateException(out))
        }
        return Result.success(Unit)
    }

    /**
     * Reads the active Android TV IME ID and Korean/English subtype language via ADB shell.
     */
    suspend fun readTvImeStatus(): Result<TvImeStatus> {
        if (!isConnected) {
            return Result.failure(IllegalStateException("ADB 미연결"))
        }
        val res = shell(buildReadTvImeStatusShellCommand(), timeoutMs = 5_500L)
        if (res.isFailure) {
            return Result.failure(res.exceptionOrNull() ?: IllegalStateException("TV IME 상태 조회 실패"))
        }
        return Result.success(parseTvImeStatus(res.getOrDefault("")))
    }

    /**
     * Launches a TV app directly via ADB shell without touching port 6466 sendAppLink,
     * preventing Xiaomi TV A Pro Remote Service disconnections.
     * Falls back to opening the Play Store detail page via ADB if the package is not installed.
     */
    suspend fun launchAppPackage(packageName: String, fallbackUri: String? = null): AdbAppLaunchResult {
        if (!isConnected) {
            return AdbAppLaunchResult(AdbAppLaunchOutcome.FAILED, "ADB 미연결")
        }
        val cmd = buildAppLaunchShellCommand(packageName, fallbackUri)
            ?: return AdbAppLaunchResult(AdbAppLaunchOutcome.FAILED, "잘못된 앱 패키지 정보")

        val shellResult = shell(cmd)
        if (shellResult.isFailure) {
            return AdbAppLaunchResult(
                outcome = AdbAppLaunchOutcome.FAILED,
                detail = shellResult.exceptionOrNull()?.message
            )
        }

        val out = shellResult.getOrDefault("")
        val status = out.substringAfter("STATUS=", "").substringBefore("|OUT=").trim()
        val detail = out.substringAfter("|OUT=", "").trim()
        val hasError = detail.contains("Error:", ignoreCase = true) ||
            detail.contains("Exception", ignoreCase = true) ||
            detail.contains("No activities found", ignoreCase = true)

        return when {
            status == "APP" && !hasError -> AdbAppLaunchResult(AdbAppLaunchOutcome.LAUNCHED_APP)
            status == "STORE" && !hasError -> AdbAppLaunchResult(AdbAppLaunchOutcome.OPENED_PLAY_STORE)
            else -> AdbAppLaunchResult(
                outcome = AdbAppLaunchOutcome.FAILED,
                detail = detail.ifBlank { "앱 실행에 실패했습니다." }
            )
        }
    }

    fun disconnect() {
        connectedHost = null
        lastVerifiedHardwareBacklightSupport = null
        peerMaxPayload = MAX_PAYLOAD
        closeSocketOnly()
        _adbState.value = AdbConnectionState.DISCONNECTED
        _adbStatusDetail.value = null
    }

    private fun closeSocketOnly() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray
    )

    private fun sendPacket(out: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(data.size)
            putInt(checksum(data))
            putInt(command.inv())
        }.array()
        out.write(header)
        if (data.isNotEmpty()) {
            out.write(data)
        }
        out.flush()
    }

    private fun readPacket(inp: InputStream): AdbPacket {
        val header = readExact(inp, 24)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buf.getInt()
        val arg0 = buf.getInt()
        val arg1 = buf.getInt()
        val length = buf.getInt()
        buf.getInt() // checksum
        val magic = buf.getInt()
        if (cmd.inv() != magic) {
            throw IllegalStateException("Invalid ADB packet header magic")
        }
        if (length < 0 || length > MAX_INCOMING_PAYLOAD) {
            throw IllegalStateException("Invalid ADB packet length: $length")
        }
        val payload = if (length > 0) readExact(inp, length) else ByteArray(0)
        return AdbPacket(cmd, arg0, arg1, payload)
    }

    private fun readExact(inp: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = inp.read(buf, offset, count - offset)
            if (read < 0) throw EOFException("ADB socket closed")
            offset += read
        }
        return buf
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) {
            sum += b.toInt() and 0xFF
        }
        return sum
    }

    companion object {
        const val DEFAULT_ADB_PORT = 5555

        private const val A_VERSION = 0x01000001
        private const val MAX_PAYLOAD = 256 * 1024
        private const val MAX_INCOMING_PAYLOAD = 1024 * 1024

        private const val A_CNXN = 0x4e584e43
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val A_AUTH = 0x48545541

        private const val ADB_AUTH_TOKEN = 1
        private const val ADB_AUTH_SIGNATURE = 2
        private const val ADB_AUTH_RSAPUBLICKEY = 3

        private val SAFE_PACKAGE_REGEX = Regex("^[A-Za-z0-9._-]+$")
        private val SAFE_URI_REGEX = Regex("^[A-Za-z0-9+._:/?=&%-]+$")
        private val METHOD_ID_REGEX = Regex("(?:mCurMethodId|mCurId)=([^\\s]+)")
        private val SUBTYPE_LOCALE_REGEX = Regex(
            "(?:mSubtypeLocale|locale|languageTag|currentLocale|activeLocale)\\s*[=:]\\s*([A-Za-z0-9_-]+)",
            RegexOption.IGNORE_CASE
        )

        fun sanitizePackageName(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || !trimmed.contains('.') || !SAFE_PACKAGE_REGEX.matches(trimmed)) {
                return null
            }
            return trimmed
        }

        const val ADB_UNICODE_UNSUPPORTED = "ADB_UNICODE_UNSUPPORTED"

        fun isAsciiInputSupported(text: String): Boolean {
            return text.all { ch -> ch.code in 0x20..0x7E }
        }

        fun escapeSingleQuotedShellArg(value: String): String = value.replace("'", "'\\''")

        fun buildReadTvImeStatusShellCommand(): String = buildString {
            append("CUR_IME=\$(settings get secure default_input_method 2>/dev/null | tr -d '\\r\\n'); ")
            append("CUR_SUB=\$(settings get secure selected_input_method_subtype 2>/dev/null | tr -d '\\r\\n'); ")
            append("DMP=\$(dumpsys input_method 2>/dev/null); ")
            append("CUR_LINE=\$(printf '%s\\n' \"\$DMP\" | grep -m6 -E 'mCurMethodId=|mCurId=|mCurrentSubtype=|mSystemLocale='); ")
            append("SUB_MATCH=\"\"; ")
            append("if [ -n \"\$CUR_SUB\" ] && [ \"\$CUR_SUB\" != \"null\" ] && [ \"\$CUR_SUB\" != \"-1\" ]; then ")
            append("SUB_MATCH=\$(printf '%s\\n' \"\$DMP\" | grep -A3 -E \"(mSubtypeId|mSubtypeHashCode|hashCode|id)=\$CUR_SUB([^0-9]|\\$)\" | head -n 8); ")
            append("fi; ")
            append("IME_SVC_DUMP=\"\"; ")
            append("if [ -n \"\$CUR_IME\" ] && [ \"\$CUR_IME\" != \"null\" ]; then ")
            append("IME_SVC_DUMP=\$(dumpsys activity service \"\$CUR_IME\" 2>/dev/null | grep -iE 'locale|language|hangul|korean|qwerty|subtype|keyboard' | head -n 10); ")
            append("fi; ")
            append("SYS_LOC=\$(getprop persist.sys.locale 2>/dev/null | tr -d '\\r\\n'); ")
            append("if [ -z \"\$SYS_LOC\" ]; then SYS_LOC=\$(settings get system system_locales 2>/dev/null | tr -d '\\r\\n'); fi; ")
            append("echo \"CUR_IME=\$CUR_IME\"; ")
            append("echo \"CUR_SUB=\$CUR_SUB\"; ")
            append("echo \"SYS_LOC=\$SYS_LOC\"; ")
            append("echo \"---CUR---\"; printf '%s\\n' \"\$CUR_LINE\"; ")
            append("echo \"---SUB---\"; printf '%s\\n' \"\$SUB_MATCH\"; ")
            append("echo \"---SVC---\"; printf '%s\\n' \"\$IME_SVC_DUMP\"")
        }

        fun parseTvImeStatus(raw: String): TvImeStatus {
            val lines = raw.lines()
            fun headerVal(prefix: String): String {
                val value = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim().orEmpty()
                return if (value.equals("null", ignoreCase = true)) "" else value
            }

            var imeId = headerVal("CUR_IME=")
            val sysLoc = headerVal("SYS_LOC=")
            val curSection = raw.substringAfter("---CUR---", "").substringBefore("---SUB---")
            val subSection = raw.substringAfter("---SUB---", "").substringBefore("---SVC---")
            val svcSection = raw.substringAfter("---SVC---", "")

            if (imeId.isBlank()) {
                imeId = METHOD_ID_REGEX.find(curSection)?.groupValues?.getOrNull(1).orEmpty()
            }
            val cleanImeId = imeId.ifBlank { null }
            val imeType = TvImeKeycodeMap.resolveImeType(cleanImeId)

            val currentSubtypeLines = curSection.lines()
                .filter { it.contains("mCurrentSubtype=", ignoreCase = true) }
                .joinToString("\n")
            val systemLocaleLines = curSection.lines()
                .filter { it.contains("mSystemLocale=", ignoreCase = true) }
                .joinToString("\n")

            val match = detectLanguageInBlock(currentSubtypeLines)
                ?: detectLanguageInBlock(subSection)
                ?: detectLanguageInBlock(svcSection)
                ?: if (imeType == TvImeType.GOOGLE_KOREAN) {
                    TvKeyboardLanguage.KOREAN to "ko_KR"
                } else {
                    null
                }

            if (match != null) {
                return TvImeStatus(
                    language = match.first,
                    imeId = cleanImeId,
                    imeType = imeType,
                    subtypeLocale = match.second,
                    isDetected = true
                )
            }

            val sysLocaleMatch = detectLocaleFromText(sysLoc) ?: detectLanguageInBlock(systemLocaleLines)
            if (sysLocaleMatch != null) {
                return TvImeStatus(
                    language = sysLocaleMatch.first,
                    imeId = cleanImeId,
                    imeType = imeType,
                    subtypeLocale = sysLocaleMatch.second,
                    isDetected = cleanImeId != null
                )
            }

            return TvImeStatus(
                language = TvKeyboardLanguage.ENGLISH,
                imeId = cleanImeId,
                imeType = imeType,
                subtypeLocale = null,
                isDetected = false
            )
        }

        private fun detectLanguageInBlock(block: String): Pair<TvKeyboardLanguage, String?>? {
            if (block.isBlank()) return null
            val localeToken = SUBTYPE_LOCALE_REGEX.find(block)?.groupValues?.getOrNull(1)
            if (!localeToken.isNullOrBlank()) {
                detectLocaleFromText(localeToken)?.let { return it }
            }
            val lower = block.lowercase()
            return when {
                lower.contains("hangul=true") ||
                    lower.contains("korean=true") ||
                    lower.contains("keyboardlayoutset=korean") ||
                    lower.contains("ko_kr") ||
                    lower.contains("ko-kr") ||
                    Regex("\\bko\\b").containsMatchIn(lower) ->
                    TvKeyboardLanguage.KOREAN to (localeToken ?: "ko_KR")
                lower.contains("hangul=false") ||
                    lower.contains("en_us") ||
                    lower.contains("en-us") ||
                    lower.contains("en_gb") ||
                    lower.contains("en-gb") ||
                    lower.contains("keyboardlayoutset=qwerty") ->
                    TvKeyboardLanguage.ENGLISH to (localeToken ?: "en_US")
                else -> null
            }
        }

        private fun detectLocaleFromText(rawLocale: String): Pair<TvKeyboardLanguage, String>? {
            val cleaned = rawLocale.trim().substringBefore(',').trim()
            if (cleaned.isBlank() || cleaned.equals("null", ignoreCase = true)) return null
            val lower = cleaned.lowercase()
            return when {
                lower == "ko" || lower.startsWith("ko_") || lower.startsWith("ko-") ->
                    TvKeyboardLanguage.KOREAN to cleaned
                lower == "en" || lower.startsWith("en_") || lower.startsWith("en-") ->
                    TvKeyboardLanguage.ENGLISH to cleaned
                else -> null
            }
        }

        fun buildKeyCombinationShellCommand(vararg keyCodes: Int): String? {
            if (keyCodes.size < 2 || keyCodes.any { it <= 0 }) return null
            val codes = keyCodes.joinToString(" ")
            return "input keycombination $codes 2>&1"
        }

        fun buildSelectAllAndDeleteShellCommand(): String = buildString {
            append("OUT=\$(input keycombination ${TvKeyCodes.CTRL_LEFT} ${TvKeyCodes.KEYCODE_A} 2>&1); ")
            append("RC=\$?; ")
            append("if [ \"\$RC\" -ne 0 ] || echo \"\$OUT\" | grep -qiE \"Error|Unknown|Usage|Invalid|Exception\"; then ")
            append("${buildClearKeyEventsPrefix(30)} 2>&1; ")
            append("else ")
            append("sleep 0.1; ")
            append("input keyevent ${TvKeyCodes.DEL} 2>&1; ")
            append("fi")
        }

        fun buildClearKeyEventsPrefix(clearCount: Int): String {
            val safeCount = clearCount.coerceIn(0, 40)
            if (safeCount <= 0) return ""
            val dels = List(safeCount) { "67" }.joinToString(" ")
            return "input keyevent 123 $dels"
        }

        fun buildInputTextShellCommand(
            text: String,
            clearCount: Int = 0,
            submitWithEnter: Boolean = false
        ): String? {
            if (!isAsciiInputSupported(text)) return null
            val clearPrefix = buildClearKeyEventsPrefix(clearCount)
            val enterSuffix = if (submitWithEnter) "; input keyevent 66 2>&1" else ""

            if (text.isEmpty()) {
                return buildString {
                    if (clearPrefix.isNotEmpty()) {
                        append(clearPrefix)
                        append(" 2>&1")
                    }
                    if (enterSuffix.isNotEmpty()) {
                        if (isNotEmpty()) append(enterSuffix)
                        else append("input keyevent 66 2>&1")
                    }
                }.ifBlank { null }
            }

            // Android `input text` treats `%s` as space; single-quoting disables all sh meta-chars
            val encodedForInput = buildString {
                for (ch in text) {
                    when (ch) {
                        ' ' -> append("%s")
                        '\'' -> append("'\\''")
                        else -> append(ch)
                    }
                }
            }

            return buildString {
                if (clearPrefix.isNotEmpty()) {
                    append(clearPrefix)
                    append("; ")
                }
                append("input text '")
                append(encodedForInput)
                append("' 2>&1")
                append(enterSuffix)
            }
        }

        fun buildKeyEventShellCommand(keyCode: Int): String? {
            if (keyCode <= 0) return null
            return "input keyevent $keyCode 2>&1"
        }

        fun buildGoogleAssistantLaunchShellCommand(): String = buildString {
            append("OUT=\$(am start -a android.intent.action.ASSIST 2>&1); ")
            append("RC=\$?; ")
            append("if [ \"\$RC\" -ne 0 ] || echo \"\$OUT\" | grep -qiE \"Error:|Exception|unable to resolve|No Activity\"; then ")
            append("input keyevent ${TvKeyCodes.VOICE_ASSIST} 2>&1 || input keyevent ${TvKeyCodes.ASSIST} 2>&1; ")
            append("fi")
        }

        fun buildGoogleAssistantQueryShellCommand(query: String): String? {
            val sanitized = query.replace('\r', ' ').replace('\n', ' ').replace("\u0000", "").trim()
            if (sanitized.isEmpty()) return null
            val escaped = escapeSingleQuotedShellArg(sanitized)
            return buildString {
                append("OUT=\$(am start -a android.search.action.GLOBAL_SEARCH --es query '$escaped' 2>&1); ")
                append("RC=\$?; ")
                append("if [ \"\$RC\" -ne 0 ] || echo \"\$OUT\" | grep -qiE \"Error:|Exception|unable to resolve|No Activity\"; then ")
                append("am start -a android.intent.action.SEARCH --es query '$escaped' 2>&1; ")
                append("fi")
            }
        }

        fun buildAppLaunchShellCommand(packageName: String, fallbackUri: String? = null): String? {
            val pkg = sanitizePackageName(packageName)
            if (pkg != null) {
                return buildString {
                    append("PKG=\"$pkg\"; ")
                    append("if pm path \"\$PKG\" >/dev/null 2>&1; then ")
                    append("ACT=\$(cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER \"\$PKG\" 2>/dev/null | tail -n 1); ")
                    append("case \"\$ACT\" in ")
                    append("*/*) OUT=\$(am start -n \"\$ACT\" 2>&1) ;; ")
                    append("*) ")
                    append("ACT=\$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER \"\$PKG\" 2>/dev/null | tail -n 1); ")
                    append("case \"\$ACT\" in ")
                    append("*/*) OUT=\$(am start -n \"\$ACT\" 2>&1) ;; ")
                    append("*) OUT=\$(monkey -p \"\$PKG\" -c android.intent.category.LEANBACK_LAUNCHER 1 2>&1 || monkey -p \"\$PKG\" -c android.intent.category.LAUNCHER 1 2>&1) ;; ")
                    append("esac ;; ")
                    append("esac; ")
                    append("case \"\$OUT\" in ")
                    append("*Error:*|*Exception*) OUT=\$(monkey -p \"\$PKG\" -c android.intent.category.LEANBACK_LAUNCHER 1 2>&1 || monkey -p \"\$PKG\" -c android.intent.category.LAUNCHER 1 2>&1) ;; ")
                    append("esac; ")
                    append("echo \"STATUS=APP|OUT=\$OUT\"; ")
                    append("else ")
                    append("OUT=\$(am start -a android.intent.action.VIEW -d \"market://details?id=\$PKG\" 2>&1); ")
                    append("echo \"STATUS=STORE|OUT=\$OUT\"; ")
                    append("fi")
                }
            }

            val cleanUri = fallbackUri?.trim().orEmpty()
            if (cleanUri.isNotEmpty() && SAFE_URI_REGEX.matches(cleanUri)) {
                return "OUT=\$(am start -a android.intent.action.VIEW -d \"$cleanUri\" 2>&1); echo \"STATUS=APP|OUT=\$OUT\""
            }
            return null
        }
    }
}
