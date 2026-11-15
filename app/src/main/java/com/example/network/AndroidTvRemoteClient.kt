package com.example.network

import android.os.Build
import com.example.data.model.TvKeyCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * Maintains the mTLS remote control session on TCP port 6466,
 * handling configure/active handshake, ping heartbeats, key injection,
 * app link launches, and Korean/English IME text batch edits.
 */
class AndroidTvRemoteClient(
    private val certStore: ClientCertStore?,
    private val onForegroundAppChanged: (String) -> Unit,
    private val onImeShowRequested: (fieldCounter: Int, text: String) -> Unit,
    private val onImeHidden: () -> Unit = {},
    private val onVolumeChanged: (level: Int, max: Int, muted: Boolean) -> Unit,
    private val onVoiceBeginRequested: (sessionId: Int, packageName: String?) -> Unit = { _, _ -> },
    private val onVoiceEndRequested: (sessionId: Int) -> Unit = { _ -> },
    private val onRemoteError: (code: Int, detail: String?) -> Unit = { _, _ -> },
    private val onDisconnected: (Throwable?) -> Unit
) {

    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    private var readJob: Job? = null
    private var sessionScope: CoroutineScope? = null
    private val writeMutex = Mutex()
    private val imeMutex = Mutex()

    @Volatile
    private var imeCounter: Int = 0

    @Volatile
    private var fieldCounter: Int = 0

    @Volatile
    private var hasKeyInjectImeCounter: Boolean = false

    @Volatile
    private var hasKeyInjectFieldCounter: Boolean = false

    @Volatile
    private var syncedTextInt5: Int = -1

    @Volatile
    private var syncedTextLabel: String = ""

    @Volatile
    private var trackedTvText: String = ""

    @Volatile
    private var trackedSelStart: Int = 0

    @Volatile
    private var trackedSelEnd: Int = 0

    @Volatile
    private var lastImeAppPackage: String? = null

    @Volatile
    private var currentForegroundPackage: String? = null

    @Volatile
    var isImeFieldActive: Boolean = false
        private set

    @Volatile
    var isConnected: Boolean = false
        private set

    internal val debugImeCounter: Int
        get() = imeCounter

    internal val debugFieldCounter: Int
        get() = fieldCounter

    internal val debugHasKeyInjectImeCounter: Boolean
        get() = hasKeyInjectImeCounter

    internal val debugHasKeyInjectFieldCounter: Boolean
        get() = hasKeyInjectFieldCounter

    internal val debugSyncedTextInt5: Int
        get() = syncedTextInt5

    internal val debugSyncedTextLabel: String
        get() = syncedTextLabel

    internal val debugForegroundPackage: String?
        get() = currentForegroundPackage

    internal val debugTrackedText: String
        get() = trackedTvText

    internal var testRawBytesSink: ((ByteArray) -> Unit)? = null

    suspend fun connect(host: String, remotePort: Int) = withContext(Dispatchers.IO) {
        disconnect(notify = false)
        resetImeState()

        val store = requireNotNull(certStore) { "ClientCertStore is required for TLS connection" }
        val sslContext = store.createSslContext()
        val sslSocket = (sslContext.socketFactory.createSocket() as SSLSocket).apply {
            soTimeout = 30_000
            connect(InetSocketAddress(host, remotePort), 8_000)
            startHandshake()
            soTimeout = 0 // non-blocking reader waits on ping/events
        }

        socket = sslSocket
        output = sslSocket.outputStream
        val input = sslSocket.inputStream

        // Perform initial RemoteConfigure handshake (include PING|KEY|IME|POWER|VOLUME|APP_LINK)
        val serverConfigFrame = ProtobufWire.readFramedMessage(input)
        val serverFields = ProtobufWire.parseFields(serverConfigFrame)
        if (serverFields.none { it.number == 1 }) {
            throw IllegalStateException("TV에서 페어링 인증이 필요합니다.")
        }

        val configurePayload = ProtobufWire.encode {
            writeMessageField(1) { // remote_configure
                writeVarintField(1, REMOTE_FEATURES)
                writeMessageField(2) { // device_info
                    writeStringField(1, Build.MODEL ?: "Android")
                    writeStringField(2, Build.MANUFACTURER ?: "Google")
                    writeVarintField(3, 1)
                    writeStringField(4, "1")
                    writeStringField(5, "com.aistudio.tvremote")
                    writeStringField(6, "1.0")
                }
            }
        }
        sendRaw(configurePayload)

        // Wait for remote_set_active (or initial remote messages) from TV and acknowledge
        val activeFrame = ProtobufWire.readFramedMessage(input)
        val activeFields = ProtobufWire.parseFields(activeFrame)
        handleIncomingFields(activeFields)

        isConnected = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessionScope = scope
        readJob = scope.launch {
            readLoop(input)
        }
    }

    private suspend fun readLoop(input: InputStream) {
        var disconnectCause: Throwable? = null
        try {
            while (isConnected) {
                val frame = ProtobufWire.readFramedMessage(input)
                val fields = ProtobufWire.parseFields(frame)
                handleIncomingFields(fields)
            }
        } catch (t: Throwable) {
            if (isConnected) {
                disconnectCause = if (t is SSLException) {
                    IllegalStateException("페어링 인증서가 만료되었거나 재페어링이 필요합니다.", t)
                } else t
            }
        } finally {
            val wasConnected = isConnected
            isConnected = false
            if (wasConnected) {
                onDisconnected(disconnectCause)
            }
        }
    }

    private suspend fun handleIncomingFields(fields: List<ProtobufWire.Field>) {
        for (field in fields) {
            when (field.number) {
                2 -> { // remote_set_active
                    val activeReply = ProtobufWire.encode {
                        writeMessageField(2) {
                            writeVarintField(1, REMOTE_FEATURES)
                        }
                    }
                    sendRaw(activeReply)
                }
                3 -> { // remote_error
                    val errFields = field.asMessage()
                    val code = errFields.firstOrNull { it.number == 1 }?.varint?.toInt() ?: -1
                    val detail = errFields.firstOrNull { it.number == 2 }?.bytes?.let {
                        runCatching {
                            val nested = ProtobufWire.parseFields(it)
                            nested.firstOrNull { f -> f.number == 1 || f.number == 2 }?.asString()
                        }.getOrNull() ?: it.toString(Charsets.UTF_8)
                    }
                    onRemoteError(code, detail)
                }
                8 -> { // remote_ping_request
                    val pingFields = field.asMessage()
                    val val1 = pingFields.firstOrNull { it.number == 1 }?.varint?.toInt() ?: 0
                    val pong = ProtobufWire.encode {
                        writeMessageField(9) {
                            writeVarintField(1, val1)
                        }
                    }
                    sendRaw(pong)
                }
                20 -> { // remote_ime_key_inject (reports active app, app_info.counter & IME status)
                    val imeFields = field.asMessage()
                    val appInfo = imeFields.firstOrNull { it.number == 1 && it.wireType == 2 }?.asMessage()
                    if (appInfo != null) {
                        val incomingImeCounter = appInfo.firstOrNull { it.number == 1 && it.wireType == 0 }?.varint?.toInt() ?: 0
                        imeCounter = incomingImeCounter
                        hasKeyInjectImeCounter = incomingImeCounter != 0
                    }

                    // RemoteAppInfo.app_package is field 12 (string); field 3 is int3 (varint)
                    val pkg = appInfo?.firstOrNull { it.number == 12 && it.wireType == 2 }?.asString()
                    if (!pkg.isNullOrBlank()) {
                        val previousPkg = currentForegroundPackage
                        currentForegroundPackage = pkg
                        if (isImeFieldActive && previousPkg != null && previousPkg != pkg && lastImeAppPackage != pkg) {
                            isImeFieldActive = false
                            lastImeAppPackage = null
                            onImeHidden()
                        }
                        onForegroundAppChanged(pkg)
                    }
                    val textStatus = imeFields.firstOrNull { it.number == 2 && it.wireType == 2 }?.asMessage()
                    if (textStatus != null) {
                        val wasActive = isImeFieldActive
                        val prevFieldCounter = fieldCounter
                        syncImeTextStatus(textStatus, isShowRequest = false)
                        notifyImeActivationIfNeeded(wasActive, prevFieldCounter)
                    } else {
                        fieldCounter = 0
                        hasKeyInjectFieldCounter = false
                        trackedTvText = ""
                        trackedSelStart = 0
                        trackedSelEnd = 0
                    }
                }
                21 -> { // remote_ime_batch_edit (fallback counters only when key_inject/show_request has none)
                    val batchFields = field.asMessage()
                    val wasActive = isImeFieldActive
                    val prevFieldCounter = fieldCounter
                    syncIncomingImeBatchEdit(batchFields)
                    notifyImeActivationIfNeeded(wasActive, prevFieldCounter)
                }
                22 -> { // remote_ime_show_request
                    val showFields = field.asMessage()
                    val textStatus = (
                        showFields.firstOrNull { it.number == 2 && it.wireType == 2 }
                            ?: showFields.firstOrNull { it.number == 1 && it.wireType == 2 }
                        )?.asMessage()
                    isImeFieldActive = true
                    lastImeAppPackage = currentForegroundPackage
                    if (textStatus != null) {
                        syncImeTextStatus(textStatus, isShowRequest = true)
                    } else {
                        fieldCounter = 0
                        hasKeyInjectFieldCounter = true
                        trackedTvText = ""
                        trackedSelStart = 0
                        trackedSelEnd = 0
                    }
                    onImeShowRequested(resolvedImeFieldCounter(), trackedTvText)
                }
                30 -> { // remote_voice_begin (TV requests phone mic stream with session_id)
                    val (sessionId, pkg) = parseVoiceBegin(field.asMessage())
                    onVoiceBeginRequested(sessionId, pkg)
                }
                32 -> { // remote_voice_end (TV signals end of voice session)
                    val sessionId = field.asMessage().firstOrNull { it.number == 1 }?.varint?.toInt() ?: 0
                    onVoiceEndRequested(sessionId)
                }
                50 -> { // remote_set_volume_level (6=volume_max, 7=volume_level, 8=volume_muted)
                    val (level, max, muted) = parseVolumeLevel(field.asMessage())
                    onVolumeChanged(level, max, muted)
                }
            }
        }
    }

    internal suspend fun applyIncomingFieldsForTest(fields: List<ProtobufWire.Field>) {
        handleIncomingFields(fields)
    }

    internal fun setConnectedForTest(connected: Boolean) {
        isConnected = connected
    }

    /**
     * Some TVs/apps signal text-field focus only via RemoteImeKeyInject(20) or RemoteImeBatchEdit(21)
     * instead of RemoteImeShowRequest(22). Surface those activations to the UI as well.
     */
    private fun notifyImeActivationIfNeeded(wasActive: Boolean, prevFieldCounter: Int) {
        if (isImeFieldActive && (!wasActive || fieldCounter != prevFieldCounter)) {
            lastImeAppPackage = currentForegroundPackage
            onImeShowRequested(resolvedImeFieldCounter(), trackedTvText)
        }
    }

    private fun resetImeState() {
        imeCounter = 0
        fieldCounter = 0
        hasKeyInjectImeCounter = false
        hasKeyInjectFieldCounter = false
        syncedTextInt5 = -1
        syncedTextLabel = ""
        trackedTvText = ""
        trackedSelStart = 0
        trackedSelEnd = 0
        isImeFieldActive = false
        lastImeAppPackage = null
        currentForegroundPackage = null
    }

    internal fun resolvedImeFieldCounter(): Int = fieldCounter

    private fun syncIncomingImeBatchEdit(batchFields: List<ProtobufWire.Field>) {
        val tvImeCounter = batchFields.firstOrNull { it.number == 1 }?.varint?.toInt() ?: 0
        val tvFieldCounter = batchFields.firstOrNull { it.number == 2 }?.varint?.toInt() ?: 0
        if (!hasKeyInjectImeCounter) {
            imeCounter = tvImeCounter
        }
        if (!hasKeyInjectFieldCounter) {
            fieldCounter = tvFieldCounter
            if (tvFieldCounter > 0) {
                isImeFieldActive = true
            }
        }
        val edits = batchFields.filter { it.number == 3 }
        for (editField in edits) {
            val editMsg = editField.asMessage()
            val imeObj = editMsg.firstOrNull { it.number == 2 }?.asMessage() ?: continue
            // Same semantics as outgoing edits: value is the full field text snapshot.
            val value = imeObj.firstOrNull { it.number == 3 }?.asString() ?: ""
            trackedTvText = value
            trackedSelStart = value.length
            trackedSelEnd = value.length
        }
    }

    private fun syncImeTextStatus(textStatus: List<ProtobufWire.Field>, isShowRequest: Boolean) {
        val incomingFc = textStatus.firstOrNull { it.number == 1 }?.varint?.toInt() ?: 0
        val incomingValue = textStatus.firstOrNull { it.number == 2 }?.asString() ?: ""
        val incomingStart = textStatus.firstOrNull { it.number == 3 }?.varint?.toInt()
        val incomingEnd = textStatus.firstOrNull { it.number == 4 }?.varint?.toInt()
        val incomingInt5 = textStatus.firstOrNull { it.number == 5 }?.varint?.toInt()
        val incomingLabel = textStatus.firstOrNull { it.number == 6 }?.asString()
        // NOTE: RemoteTextFieldStatus field 5 (int5) is TV EditorInfo attribute/inputType flags, NOT ime_counter!

        // Accept fieldCounter = 0 (NVIDIA Shield & YouTube use 0)
        fieldCounter = incomingFc
        hasKeyInjectFieldCounter = isShowRequest || incomingFc != 0
        if (incomingInt5 != null) {
            syncedTextInt5 = incomingInt5
        }
        if (incomingLabel != null) {
            syncedTextLabel = incomingLabel
        }
        if (isShowRequest || incomingFc > 0 || incomingValue.isNotEmpty() || (incomingInt5 != null && incomingInt5 >= 0)) {
            isImeFieldActive = true
        }

        trackedTvText = incomingValue
        val safeLen = trackedTvText.length
        val selStart = (incomingStart ?: if (trackedTvText.isEmpty()) 0 else safeLen).coerceIn(0, safeLen)
        val selEnd = (incomingEnd ?: selStart).coerceIn(selStart, safeLen)
        trackedSelStart = selStart
        trackedSelEnd = selEnd
    }

    suspend fun sendKey(keyCode: Int, direction: Int = DIRECTION_SHORT) {
        val payload = ProtobufWire.encode {
            writeMessageField(10) { // remote_key_inject
                writeVarintField(1, keyCode)
                writeVarintField(2, direction)
            }
        }
        sendRaw(payload)
    }

    suspend fun sendPressAndReleaseKey(keyCode: Int) {
        sendKey(keyCode, DIRECTION_START_LONG)
        kotlinx.coroutines.delay(65)
        sendKey(keyCode, DIRECTION_END_LONG)
    }

    suspend fun sendAppLink(appLinkUri: String) {
        val payload = ProtobufWire.encode {
            writeMessageField(90) { // remote_app_link_launch_request
                writeStringField(1, appLinkUri)
            }
        }
        sendRaw(payload)
    }

    suspend fun sendVoiceBegin(sessionId: Int) {
        sendRaw(buildVoiceBeginPayload(sessionId))
    }

    suspend fun sendVoicePayload(sessionId: Int, pcmSamples: ByteArray) {
        if (pcmSamples.isEmpty()) return
        sendRaw(buildVoicePayload(sessionId, pcmSamples))
    }

    suspend fun sendVoiceEnd(sessionId: Int) {
        sendRaw(buildVoiceEndPayload(sessionId))
    }

    /**
     * Sends a single RemoteImeBatchEdit(21) frame using the TV-authoritative imeCounter & fieldCounter.
     * Client must never transmit RemoteImeKeyInject(20) (TV -> remote only) or bump imeCounter locally.
     */
    private suspend fun sendBatchEditLocked(text: String) {
        val payload = buildImeBatchEditPayload(
            imeCounter = imeCounter,
            fieldCounter = resolvedImeFieldCounter(),
            text = text,
            start = text.length,
            end = text.length,
            insert = 1
        )
        sendRaw(payload)
    }

    suspend fun sendText(text: String, replaceExisting: Boolean = false, submitWithEnter: Boolean = false) {
        imeMutex.withLock {
            if (text.isNotEmpty() || replaceExisting || !submitWithEnter) {
                sendBatchEditLocked(text)
                trackedTvText = text
                trackedSelStart = text.length
                trackedSelEnd = text.length
            }

            if (submitWithEnter) {
                if (text.isNotEmpty() || replaceExisting) {
                    kotlinx.coroutines.delay(150)
                }
                sendKey(TvKeyCodes.ENTER, DIRECTION_SHORT)
            }
        }
    }

    /**
     * Transmits inserted characters/text live via RemoteImeBatchEdit(21),
     * sending spaces as physical KEYCODE_SPACE (62) key events matching TV remote apps.
     */
    suspend fun sendKeyboardChars(text: String) {
        if (text.isEmpty()) return
        imeMutex.withLock {
            val nonSpaceChunk = StringBuilder()
            suspend fun flushChunk() {
                if (nonSpaceChunk.isNotEmpty()) {
                    val chunk = nonSpaceChunk.toString()
                    nonSpaceChunk.clear()
                    sendBatchEditLocked(chunk)
                    trackedTvText += chunk
                    trackedSelStart = trackedTvText.length
                    trackedSelEnd = trackedTvText.length
                }
            }
            for (ch in text) {
                if (ch == ' ') {
                    flushChunk()
                    sendKey(TvKeyCodes.SPACE, DIRECTION_SHORT)
                    trackedTvText += ' '
                    trackedSelStart = trackedTvText.length
                    trackedSelEnd = trackedTvText.length
                } else {
                    nonSpaceChunk.append(ch)
                }
            }
            flushChunk()
        }
    }

    suspend fun sendKeyboardBackspace(count: Int = 1) {
        if (count <= 0) return
        imeMutex.withLock {
            repeat(count) {
                sendKey(TvKeyCodes.DEL, DIRECTION_SHORT)
                if (trackedTvText.isNotEmpty()) {
                    trackedTvText = trackedTvText.dropLast(1)
                    trackedSelStart = trackedTvText.length
                    trackedSelEnd = trackedTvText.length
                }
            }
        }
    }

    /**
     * Transmits a real-time physical key event (optionally with Ctrl / Shift held) under [imeMutex]
     * so modifier down -> target key -> modifier up frames never interleave with subsequent key taps.
     */
    suspend fun sendPhysicalKey(keyCode: Int, shift: Boolean = false, ctrl: Boolean = false) {
        imeMutex.withLock {
            sendKeyWithModifiersLocked(keyCode = keyCode, shift = shift, ctrl = ctrl)
        }
    }

    /**
     * Executes "(1) Select All (Ctrl+A) -> (2) Delete (DEL)" using physical key injects.
     */
    suspend fun selectAllAndDelete() {
        imeMutex.withLock {
            sendKeyWithModifiersLocked(keyCode = TvKeyCodes.KEYCODE_A, ctrl = true)
            kotlinx.coroutines.delay(60)
            sendKey(TvKeyCodes.DEL, DIRECTION_SHORT)
            trackedTvText = ""
            trackedSelStart = 0
            trackedSelEnd = 0
        }
    }

    private suspend fun sendKeyWithModifiersLocked(
        keyCode: Int,
        shift: Boolean = false,
        ctrl: Boolean = false
    ) {
        if (!shift && !ctrl) {
            sendKey(keyCode, DIRECTION_SHORT)
            return
        }
        val heldModifiers = mutableListOf<Int>()
        if (ctrl) heldModifiers.add(TvKeyCodes.CTRL_LEFT)
        if (shift) heldModifiers.add(TvKeyCodes.SHIFT_LEFT)
        for (mod in heldModifiers) {
            sendKey(mod, DIRECTION_START_LONG)
        }
        kotlinx.coroutines.delay(25)
        sendKey(keyCode, DIRECTION_SHORT)
        kotlinx.coroutines.delay(25)
        for (mod in heldModifiers.asReversed()) {
            sendKey(mod, DIRECTION_END_LONG)
        }
    }

    private suspend fun sendRaw(payload: ByteArray) = sendFramedBytes(ProtobufWire.frameMessage(payload))

    private suspend fun sendFramedBytes(framedBytes: ByteArray) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val sink = testRawBytesSink
            if (sink != null && output == null) {
                sink(framedBytes)
                return@withLock
            }
            val out = output ?: throw IllegalStateException("TV에 연결되어 있지 않습니다.")
            out.write(framedBytes)
            out.flush()
        }
    }

    fun disconnect(notify: Boolean = false) {
        val wasConnected = isConnected
        isConnected = false
        resetImeState()
        readJob?.cancel()
        sessionScope?.cancel()
        readJob = null
        sessionScope = null
        runCatching { socket?.close() }
        socket = null
        output = null
        if (notify && wasConnected) {
            onDisconnected(null)
        }
    }

    companion object {
        const val DIRECTION_START_LONG = 1
        const val DIRECTION_END_LONG = 2
        const val DIRECTION_SHORT = 3
        const val REMOTE_FEATURES = 639 // PING(1)|KEY(2)|IME(4)|POWER(8)|VOICE(16)|VOLUME(32)|64|APP_LINK(512)

        fun parseVolumeLevel(volFields: List<ProtobufWire.Field>): Triple<Int, Int, Boolean> {
            val max = (volFields.firstOrNull { it.number == 6 }?.varint?.toInt() ?: 100).coerceAtLeast(1)
            val level = (volFields.firstOrNull { it.number == 7 }?.varint?.toInt() ?: 0).coerceIn(0, max)
            val muted = (volFields.firstOrNull { it.number == 8 }?.varint ?: 0L) != 0L
            return Triple(level, max, muted)
        }

        fun parseVoiceBegin(fields: List<ProtobufWire.Field>): Pair<Int, String?> {
            val sessionId = fields.firstOrNull { it.number == 1 }?.varint?.toInt() ?: 0
            val packageName = fields.firstOrNull { it.number == 2 }?.asString()
            return sessionId to packageName
        }

        fun buildVoiceBeginPayload(sessionId: Int): ByteArray = ProtobufWire.encode {
            writeMessageField(30) { // remote_voice_begin
                writeVarintField(1, sessionId)
            }
        }

        fun buildVoicePayload(sessionId: Int, pcmSamples: ByteArray): ByteArray = ProtobufWire.encode {
            writeMessageField(31) { // remote_voice_payload
                writeVarintField(1, sessionId)
                writeBytesField(2, pcmSamples)
            }
        }

        fun buildVoiceEndPayload(sessionId: Int): ByteArray = ProtobufWire.encode {
            writeMessageField(32) { // remote_voice_end
                writeVarintField(1, sessionId)
            }
        }

        fun buildImeBatchEditPayload(
            imeCounter: Int,
            fieldCounter: Int,
            text: String,
            cursor: Int = text.length,
            start: Int = cursor,
            end: Int = cursor,
            insert: Int = 1
        ): ByteArray = ProtobufWire.encode {
            writeMessageField(21) { // remote_ime_batch_edit
                writeVarintField(1, imeCounter)
                writeVarintField(2, fieldCounter)
                writeMessageField(3) { // edit_info (RemoteEditInfo)
                    writeVarintField(1, insert)
                    writeMessageField(2) { // text_field_status (RemoteImeObject)
                        writeVarintField(1, start)
                        writeVarintField(2, end)
                        writeStringField(3, text)
                    }
                }
            }
        }

        fun applyTextEdit(existing: String, start: Int?, end: Int?, replacement: String): String {
            if (start == null || end == null || start < 0 || end < start || start > existing.length) {
                return replacement
            }
            val safeEnd = end.coerceIn(start, existing.length)
            return existing.substring(0, start) + replacement + existing.substring(safeEnd)
        }
    }
}
