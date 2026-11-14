package com.example.network

import android.util.Base64
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
 * shell command execution, Xiaomi TV A Pro backlight probes/writes, and TV Picture Settings fallback.
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
    private var lastVerifiedHardwareBacklightSupport: Boolean? = null

    @Volatile
    private var adbKeyboardInstalled: Boolean? = null

    val isConnected: Boolean
        get() = _adbState.value == AdbConnectionState.CONNECTED && socket?.isConnected == true && socket?.isClosed == false

    suspend fun connect(host: String, port: Int = DEFAULT_ADB_PORT): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (connectedHost == host && isConnected) {
                return@withLock true
            }
            closeSocketOnly()
            lastVerifiedHardwareBacklightSupport = null
            adbKeyboardInstalled = null
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

                while (true) {
                    val pkt = readPacket(inp)
                    when (pkt.command) {
                        A_CNXN -> {
                            tcpSocket.soTimeout = 4_500
                            connectedHost = host
                            _adbState.value = AdbConnectionState.CONNECTED
                            _adbStatusDetail.value = "ADB 5555 연결됨"
                            return@withLock true
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
                return@withLock false
            }
        }
        false
    }

    suspend fun shell(command: String): Result<String> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val out = output
            val inp = input
            val sock = socket
            if (out == null || inp == null || sock == null || sock.isClosed) {
                _adbState.value = AdbConnectionState.DISCONNECTED
                return@withLock Result.failure(IllegalStateException("ADB가 연결되어 있지 않습니다."))
            }

            try {
                sock.soTimeout = 4_500
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
     * Sends text via ADB shell:
     * - For ASCII text: uses `input text` (with proper `%s` space and POSIX shell escaping)
     * - For Unicode/Korean text: uses ADBKeyboard Base64 broadcast if installed on the TV,
     *   otherwise returns failure with [ADB_UNICODE_UNSUPPORTED] so caller can bridge UTF-8 IME + ADB Enter.
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

        // Non-ASCII (e.g. Korean Hangul): check if ADBKeyboard is installed on TV
        if (hasAdbKeyboardIme()) {
            val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val clearPrefix = buildClearKeyEventsPrefix(clearCount)
            val enterSuffix = if (submitWithEnter) "; input keyevent 66 2>&1" else ""
            val cmd = buildString {
                if (clearPrefix.isNotEmpty()) {
                    append(clearPrefix)
                    append("; ")
                }
                append("PREV_IME=\$(settings get secure default_input_method 2>/dev/null); ")
                append("ime enable com.android.adbkeyboard/.AdbIME >/dev/null 2>&1; ")
                append("ime set com.android.adbkeyboard/.AdbIME >/dev/null 2>&1; ")
                append("sleep 0.2; ")
                append("am broadcast -a ADB_INPUT_B64 --es msg '$b64' >/dev/null 2>&1; ")
                append("if [ -n \"\$PREV_IME\" ] && [ \"\$PREV_IME\" != \"null\" ]; then ime set \"\$PREV_IME\" >/dev/null 2>&1; fi")
                append(enterSuffix)
            }
            val res = shell(cmd)
            if (res.isSuccess) return Result.success(Unit)
            return Result.failure(res.exceptionOrNull() ?: IllegalStateException("ADBKeyboard 전송 실패"))
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

    suspend fun hasAdbKeyboardIme(): Boolean {
        if (adbKeyboardInstalled == true) return true
        val res = shell("ime list -a -s 2>/dev/null").getOrNull().orEmpty()
        val installed = res.contains("com.android.adbkeyboard/.AdbIME", ignoreCase = true)
        if (installed) {
            adbKeyboardInstalled = true
        }
        return installed
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
        adbKeyboardInstalled = null
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
        if (length < 0 || length > MAX_PAYLOAD) {
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

        fun buildClearKeyEventsPrefix(clearCount: Int): String {
            val safeCount = clearCount.coerceIn(0, 30)
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
