package com.example.network

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.example.proto.RemoteConfigure
import com.example.proto.RemoteDeviceInfo
import com.example.proto.RemoteDirection
import com.example.proto.RemoteKeyCode
import com.example.proto.RemoteKeyInject
import com.example.proto.RemoteMessage
import com.example.proto.RemotePingResponse
import com.example.proto.RemoteSetActive
import com.example.proto.RemoteVoiceBegin
import com.example.proto.RemoteVoiceConfig
import com.example.proto.RemoteVoiceEnd
import com.example.proto.RemoteVoicePayload
import com.google.protobuf.ByteString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * Manages Android TV Remote Protocol v2 Push-to-Talk voice streaming using generated
 * `protobuf-javalite` messages (`com.example.proto.RemoteMessage`) and Varint32
 * length-prefixed framing over TLS port 6466 (with pairing on port 6467).
 *
 * Strictly executes the 5-step handshake:
 * 1. `ACTION_DOWN` -> sends `RemoteKeyInject(KEYCODE_SEARCH = 84, SHORT)`.
 * 2. Waits for TV's `RemoteVoiceBegin` and parses `session_id` (never uses arbitrary or `-1` IDs).
 * 3. Replies `RemoteVoiceBegin(session_id, RemoteVoiceConfig(sample_rate = 8000, channel_config = 1, audio_format = 2))`.
 * 4. Starts `AudioRecord` (8000 Hz, Mono, PCM 16-bit) and streams `RemoteVoicePayload(session_id, samples)`.
 * 5. `ACTION_UP` -> stops recording, flushes remaining PCM, and sends `RemoteVoiceEnd(session_id)`.
 */
class TvVoiceManager(
    transport: RemoteMessageTransport? = null,
    private val pcmRecorder: VoicePcmRecorder = HardwareAudioRecordRecorder(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val voiceBeginTimeoutMs: Long = DEFAULT_VOICE_BEGIN_TIMEOUT_MS,
    private val coalesceChunksTo8Kb20Kb: Boolean = true,
    private val onEvent: (VoiceEvent) -> Unit = {},
    private val onSpeechDetected: () -> Unit = {}
) {

    enum class State {
        IDLE,
        WAITING_FOR_SESSION,
        STREAMING,
        STOPPING
    }

    sealed interface VoiceEvent {
        data class StateChanged(val state: State) : VoiceEvent
        data class SessionStarted(val sessionId: Int) : VoiceEvent
        data class SessionEnded(val sessionId: Int, val initiatedByTv: Boolean) : VoiceEvent
        data class VoiceBeginTimeout(val detail: String) : VoiceEvent
        data class Error(
            val message: String,
            val cause: Throwable? = null,
            val recoverableWithFallback: Boolean = false
        ) : VoiceEvent
    }

    interface RemoteMessageTransport {
        val isConnected: Boolean
        suspend fun sendMessage(message: RemoteMessage)
        fun close() {}
    }

    fun interface VoicePcmRecorder {
        suspend fun record(
            bufferSizeBytes: Int,
            isActive: () -> Boolean,
            onPcmChunk: suspend (ByteArray) -> Unit
        )
    }

    /**
     * Shares an existing [AndroidTvRemoteClient] mTLS port 6466 connection so opening a push-to-talk
     * stream does not create a conflicting second control socket to the TV.
     */
    class SharedRemoteClientTransport(
        private val remoteClient: AndroidTvRemoteClient
    ) : RemoteMessageTransport {
        override val isConnected: Boolean
            get() = remoteClient.isConnected

        override suspend fun sendMessage(message: RemoteMessage) {
            remoteClient.sendDelimitedFrame(encodeDelimited(message))
        }
    }

    /**
     * Standalone Android TV Remote Protocol v2 TLS transport (control port 6466; pairing port 6467)
     * encoding/decoding every [RemoteMessage] with a Varint32 length prefix.
     */
    class TlsRemoteMessageTransport(
        private val sslContext: SSLContext,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        private val onIncomingMessage: (RemoteMessage) -> Unit = {},
        private val onDisconnected: (Throwable?) -> Unit = {}
    ) : RemoteMessageTransport {
        private var socket: SSLSocket? = null
        private var output: OutputStream? = null
        private var readJob: Job? = null
        private var readerScope: CoroutineScope? = null
        private val writeMutex = Mutex()

        @Volatile
        override var isConnected: Boolean = false
            private set

        suspend fun connect(
            host: String,
            remotePort: Int = REMOTE_CONTROL_PORT,
            connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS
        ) = withContext(ioDispatcher) {
            close()
            val sslSocket = (sslContext.socketFactory.createSocket() as SSLSocket).apply {
                soTimeout = 30_000
                connect(InetSocketAddress(host, remotePort), connectTimeoutMs)
                startHandshake()
                soTimeout = 0
            }
            socket = sslSocket
            output = sslSocket.outputStream
            val input = sslSocket.inputStream

            val serverConfig = readDelimitedMessage(input)
            if (!serverConfig.hasRemoteConfigure()) {
                throw IllegalStateException("TV에서 페어링 인증(포트 $PAIRING_PORT)이 필요합니다.")
            }
            val tvFeatures = serverConfig.remoteConfigure.code1
            val negotiatedFeatures = AndroidTvRemoteClient.computeNegotiatedFeatures(tvFeatures)

            val clientConfig = RemoteMessage.newBuilder()
                .setRemoteConfigure(
                    RemoteConfigure.newBuilder()
                        .setCode1(negotiatedFeatures)
                        .setDeviceInfo(
                            RemoteDeviceInfo.newBuilder()
                                .setModel(Build.MODEL ?: "Android")
                                .setVendor(Build.MANUFACTURER ?: "Google")
                                .setUnknown1(1)
                                .setUnknown2("1")
                                .setPackageName("com.aistudio.tvremote")
                                .setAppVersion("1.0")
                                .build()
                        )
                        .build()
                )
                .build()
            writeMessageInternal(clientConfig)

            val initialMsg = readDelimitedMessage(input)
            handleControlMessage(initialMsg, negotiatedFeatures)

            isConnected = true
            val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
            readerScope = scope
            readJob = scope.launch {
                readLoop(input, negotiatedFeatures)
            }
        }

        private suspend fun readLoop(input: InputStream, negotiatedFeatures: Int) {
            var disconnectError: Throwable? = null
            try {
                while (isConnected) {
                    val msg = readDelimitedMessage(input)
                    handleControlMessage(msg, negotiatedFeatures)
                }
            } catch (t: Throwable) {
                if (isConnected && t !is CancellationException) {
                    disconnectError = if (t is SSLException) {
                        IllegalStateException("TLS 인증서 오류로 재페어링(포트 $PAIRING_PORT)이 필요합니다.", t)
                    } else t
                }
            } finally {
                val wasConnected = isConnected
                isConnected = false
                if (wasConnected) {
                    onDisconnected(disconnectError)
                }
            }
        }

        private suspend fun handleControlMessage(msg: RemoteMessage, negotiatedFeatures: Int) {
            if (msg.hasRemoteSetActive()) {
                val activeReply = RemoteMessage.newBuilder()
                    .setRemoteSetActive(
                        RemoteSetActive.newBuilder().setActive(negotiatedFeatures).build()
                    )
                    .build()
                writeMessageInternal(activeReply)
            }
            if (msg.hasRemotePingRequest()) {
                val pong = RemoteMessage.newBuilder()
                    .setRemotePingResponse(
                        RemotePingResponse.newBuilder().setVal1(msg.remotePingRequest.val1).build()
                    )
                    .build()
                writeMessageInternal(pong)
            }
            onIncomingMessage(msg)
        }

        override suspend fun sendMessage(message: RemoteMessage) = withContext(ioDispatcher) {
            writeMessageInternal(message)
        }

        private suspend fun writeMessageInternal(message: RemoteMessage) {
            val framed = encodeDelimited(message)
            writeMutex.withLock {
                val out = output ?: throw IllegalStateException("TV 원격 소켓(포트 $REMOTE_CONTROL_PORT)이 연결되어 있지 않습니다.")
                out.write(framed)
                out.flush()
            }
        }

        override fun close() {
            isConnected = false
            readJob?.cancel()
            readerScope?.cancel()
            readJob = null
            readerScope = null
            runCatching { socket?.close() }
            socket = null
            output = null
        }
    }

    class HardwareAudioRecordRecorder : VoicePcmRecorder {
        @SuppressLint("MissingPermission")
        override suspend fun record(
            bufferSizeBytes: Int,
            isActive: () -> Boolean,
            onPcmChunk: suspend (ByteArray) -> Unit
        ) {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                throw IllegalStateException("AudioRecord.getMinBufferSize 실패 (code=$minBuffer)")
            }
            val safeBufferSize = maxOf(minBuffer * 2, bufferSizeBytes * 4, READ_FRAME_BYTES * 4)

            var recorder: AudioRecord? = null
            try {
                recorder = createAudioRecord(safeBufferSize)
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord 초기화에 실패했습니다 (8000Hz Mono PCM 16-bit).")
                }

                recorder.startRecording()
                val readBuffer = ByteArray(bufferSizeBytes.coerceAtLeast(READ_FRAME_BYTES))
                val ctx = currentCoroutineContext()
                while (ctx.isActive && isActive()) {
                    val read = recorder.read(readBuffer, 0, readBuffer.size)
                    when {
                        read > 0 -> onPcmChunk(readBuffer.copyOf(read))
                        read == 0 -> continue
                        else -> throw IOException("AudioRecord.read 오류 (code=$read)")
                    }
                }
            } finally {
                runCatching {
                    if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                }
                runCatching { recorder?.release() }
            }
        }

        @SuppressLint("MissingPermission")
        private fun createAudioRecord(bufferSize: Int): AudioRecord {
            val voiceRecognition = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull()

            if (voiceRecognition != null && voiceRecognition.state == AudioRecord.STATE_INITIALIZED) {
                return voiceRecognition
            }
            runCatching { voiceRecognition?.release() }

            return AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }
    }

    @Volatile
    private var activeTransport: RemoteMessageTransport? = transport

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lifecycleMutex = Mutex()
    private val sendMutex = Mutex()

    @Volatile
    private var sessionJob: Job? = null

    @Volatile
    private var finalizeJob: Job? = null

    @Volatile
    private var captureActive: Boolean = false

    @Volatile
    private var releaseRequested: Boolean = false

    @Volatile
    private var activeSessionId: Int? = null

    @Volatile
    private var tvEndedSession: Boolean = false

    @Volatile
    private var sessionIdDeferred: CompletableDeferred<Int>? = null

    @Volatile
    private var activeScope: CoroutineScope? = null

    private fun updateState(next: State) {
        if (_state.value != next) {
            _state.value = next
            onEvent(VoiceEvent.StateChanged(next))
        }
    }

    fun attachTransport(transport: RemoteMessageTransport) {
        activeTransport = transport
    }

    /**
     * Connects a standalone TLS 6466 socket transport to the given TV host using [certStore]
     * (paired via port 6467). Incoming messages are routed straight into [onIncomingRemoteMessage].
     */
    suspend fun connectStandalone(
        host: String,
        remotePort: Int = REMOTE_CONTROL_PORT,
        certStore: ClientCertStore,
        onDisconnected: (Throwable?) -> Unit = {}
    ) {
        val tlsTransport = TlsRemoteMessageTransport(
            sslContext = certStore.createSslContext(),
            ioDispatcher = ioDispatcher,
            onIncomingMessage = { msg -> onIncomingRemoteMessage(msg) },
            onDisconnected = { err ->
                cancel()
                onDisconnected(err)
            }
        )
        tlsTransport.connect(host = host, remotePort = remotePort)
        activeTransport?.takeIf { it !== tlsTransport }?.close()
        activeTransport = tlsTransport
    }

    /**
     * Step 1..4 of Push-to-Talk when user presses the mic button (`MotionEvent.ACTION_DOWN`):
     * Sends `RemoteKeyInject(KEYCODE_SEARCH = 84)`, awaits TV `RemoteVoiceBegin(session_id)`,
     * replies `RemoteVoiceBegin(session_id, RemoteVoiceConfig(8000, 1, 2))`,
     * and starts `AudioRecord` streaming `RemoteVoicePayload`.
     */
    fun onPressDown(scope: CoroutineScope) {
        activeScope = scope
        val transport = activeTransport
        if (transport == null || !transport.isConnected) {
            onEvent(VoiceEvent.Error("TV와 TLS 6466 소켓이 연결되어 있지 않습니다."))
            return
        }

        val staleJob = sessionJob
        val staleFinalize = finalizeJob
        sessionJob = null
        finalizeJob = null
        captureActive = false
        releaseRequested = false
        tvEndedSession = false
        activeSessionId = null
        sessionIdDeferred?.cancel()

        val newBeginSignal = CompletableDeferred<Int>()
        sessionIdDeferred = newBeginSignal
        updateState(State.WAITING_FOR_SESSION)

        sessionJob = scope.launch(ioDispatcher) {
            staleFinalize?.cancel()
            staleJob?.cancel()
            runCatching { staleJob?.join() }

            val accumulator = if (coalesceChunksTo8Kb20Kb) VoiceChunkAccumulator() else null
            var confirmedSessionId: Int? = null

            try {
                if (releaseRequested) {
                    return@launch
                }

                // Step 1: Send RemoteKeyInject(KEYCODE_SEARCH = 84, SHORT)
                sendFrame(transport, buildKeyInjectSearchMessage())

                // Step 2: Wait for TV RemoteVoiceBegin and parse valid session_id (never -1 or arbitrary)
                val parsedSessionId = withTimeoutOrNull(voiceBeginTimeoutMs) {
                    newBeginSignal.await()
                }

                if (parsedSessionId == null) {
                    if (!releaseRequested && _state.value == State.WAITING_FOR_SESSION) {
                        val msg = "TV가 RemoteVoiceBegin 세션을 열지 않았습니다."
                        updateState(State.IDLE)
                        onEvent(VoiceEvent.VoiceBeginTimeout(msg))
                    }
                    return@launch
                }

                if (parsedSessionId < 0) {
                    throw IllegalStateException("TV에서 잘못된 음성 session_id($parsedSessionId)를 반환했습니다.")
                }

                if (releaseRequested || tvEndedSession || !transport.isConnected) {
                    return@launch
                }

                confirmedSessionId = parsedSessionId
                activeSessionId = parsedSessionId
                sessionIdDeferred = null

                // Step 3: Reply RemoteVoiceBegin with parsed session_id + RemoteVoiceConfig(8000, 1, 2)
                sendFrame(transport, buildVoiceBeginReplyMessage(parsedSessionId))

                if (releaseRequested || tvEndedSession || !transport.isConnected) {
                    return@launch
                }

                // Step 4: Start AudioRecord (8000 Hz, Mono, PCM 16-bit) and stream RemoteVoicePayload
                captureActive = true
                updateState(State.STREAMING)
                onEvent(VoiceEvent.SessionStarted(parsedSessionId))

                pcmRecorder.record(
                    bufferSizeBytes = READ_FRAME_BYTES,
                    isActive = { captureActive && !releaseRequested && !tvEndedSession && transport.isConnected }
                ) { pcmBytes ->
                    if (!captureActive || pcmBytes.isEmpty()) return@record
                    if (TvVoiceStreamer.computePcm16PeakAmplitude(pcmBytes) >= TvVoiceStreamer.SPEECH_PEAK_THRESHOLD) {
                        onSpeechDetected()
                    }
                    if (accumulator != null) {
                        for (chunk in accumulator.append(pcmBytes)) {
                            if (captureActive && transport.isConnected && !tvEndedSession) {
                                sendFrame(transport, buildVoicePayloadMessage(parsedSessionId, chunk))
                            }
                        }
                    } else if (transport.isConnected && !tvEndedSession) {
                        sendFrame(transport, buildVoicePayloadMessage(parsedSessionId, pcmBytes))
                    }
                }
            } catch (_: CancellationException) {
                // Cancellation can originate either from onReleaseUp() or scope shutdown
            } catch (sec: SecurityException) {
                onEvent(VoiceEvent.Error("RECORD_AUDIO 권한이 필요합니다.", sec))
            } catch (t: Throwable) {
                onEvent(
                    VoiceEvent.Error(
                        message = t.message ?: "음성 스트리밍 중 오류가 발생했습니다.",
                        cause = t,
                        recoverableWithFallback = t !is IllegalStateException
                    )
                )
            } finally {
                captureActive = false
                val sid = confirmedSessionId
                if (sid != null && !tvEndedSession && transport.isConnected) {
                    updateState(State.STOPPING)
                    if (accumulator != null) {
                        for (tailChunk in accumulator.flushPadded()) {
                            runCatching {
                                sendFrame(transport, buildVoicePayloadMessage(sid, tailChunk))
                            }
                        }
                    }
                    // Step 5: Send RemoteVoiceEnd(session_id) after recording stops & flushes
                    runCatching {
                        sendFrame(transport, buildVoiceEndMessage(sid))
                    }
                    onEvent(VoiceEvent.SessionEnded(sid, initiatedByTv = false))
                }
                if (activeSessionId == sid) {
                    activeSessionId = null
                }
                if (sessionIdDeferred === newBeginSignal) {
                    sessionIdDeferred = null
                }
                updateState(State.IDLE)
            }
        }
    }

    /**
     * Step 5 of Push-to-Talk when user lifts their finger (`MotionEvent.ACTION_UP` / `ACTION_CANCEL`):
     * Stops `AudioRecord` and triggers clean flush + `RemoteVoiceEnd(session_id)` transmission.
     * If TV never delivered a valid `RemoteVoiceBegin`, aborts waiting without sending `-1`.
     */
    fun onReleaseUp() {
        releaseRequested = true
        captureActive = false

        val pendingDeferred = sessionIdDeferred
        if (pendingDeferred != null && !pendingDeferred.isCompleted) {
            pendingDeferred.cancel()
            sessionIdDeferred = null
        }

        if (_state.value == State.WAITING_FOR_SESSION && activeSessionId == null) {
            sessionJob?.cancel()
            updateState(State.IDLE)
            return
        }

        // When STREAMING, setting captureActive=false & releaseRequested=true lets AudioRecord.record()
        // exit cleanly on the next read and run its finally block (flush + RemoteVoiceEnd).
        // Launch a safety timeout guard in case a hardware read blocks briefly.
        val runningJob = sessionJob
        val scope = activeScope
        if (runningJob != null && runningJob.isActive && scope != null) {
            updateState(State.STOPPING)
            finalizeJob?.cancel()
            finalizeJob = scope.launch(ioDispatcher) {
                val finished = withTimeoutOrNull(RELEASE_FINISH_GRACE_MS) {
                    runningJob.join()
                    true
                } ?: false
                if (!finished) {
                    runningJob.cancel()
                    runningJob.join()
                }
            }
        }
    }

    /**
     * Routes decoded [RemoteMessage] frames received on the TLS 6466 socket into the manager.
     */
    fun onIncomingRemoteMessage(message: RemoteMessage) {
        if (message.hasRemoteVoiceBegin()) {
            onTvVoiceBegin(message.remoteVoiceBegin.sessionId)
        }
        if (message.hasRemoteVoiceEnd()) {
            onTvVoiceEnd(message.remoteVoiceEnd.sessionId)
        }
        if (message.hasRemoteError()) {
            val err = message.remoteError
            val nested = err.message
            val isVoiceError = nested.hasRemoteVoiceBegin() ||
                nested.hasRemoteVoicePayload() ||
                nested.hasRemoteVoiceEnd()
            if (isVoiceError) {
                onTvVoiceError("TV가 음성 패킷을 거부했습니다.", recoverableWithFallback = true)
            }
        }
    }

    fun onTvVoiceBegin(sessionId: Int) {
        if (sessionId < 0) {
            sessionIdDeferred?.completeExceptionally(
                IllegalArgumentException("TV에서 유효하지 않은 session_id($sessionId)를 보냈습니다.")
            )
            return
        }
        sessionIdDeferred?.complete(sessionId)
    }

    fun onTvVoiceEnd(sessionId: Int) {
        val sid = activeSessionId
        if (sid != null && (sessionId == sid || sessionId == 0)) {
            tvEndedSession = true
            captureActive = false
            sessionJob?.cancel()
            activeSessionId = null
            updateState(State.IDLE)
            onEvent(VoiceEvent.SessionEnded(sid, initiatedByTv = true))
        }
    }

    fun onTvVoiceError(detail: String, recoverableWithFallback: Boolean = true) {
        val sid = activeSessionId
        val inFlight = _state.value != State.IDLE || sid != null
        if (!inFlight) return
        tvEndedSession = true
        captureActive = false
        sessionIdDeferred?.cancel()
        sessionIdDeferred = null
        sessionJob?.cancel()
        activeSessionId = null
        updateState(State.IDLE)
        onEvent(VoiceEvent.Error(detail, recoverableWithFallback = recoverableWithFallback))
    }

    fun cancel() {
        releaseRequested = true
        captureActive = false
        tvEndedSession = true
        sessionIdDeferred?.cancel()
        sessionIdDeferred = null
        finalizeJob?.cancel()
        finalizeJob = null
        sessionJob?.cancel()
        sessionJob = null
        activeSessionId = null
        updateState(State.IDLE)
    }

    fun release() {
        cancel()
        activeTransport?.close()
    }

    private suspend fun sendFrame(transport: RemoteMessageTransport, message: RemoteMessage) {
        sendMutex.withLock {
            transport.sendMessage(message)
        }
    }

    companion object {
        const val REMOTE_CONTROL_PORT = 6466
        const val PAIRING_PORT = 6467
        const val SAMPLE_RATE_HZ = 8000
        const val VOICE_CHANNEL_CONFIG_MONO = 1
        const val VOICE_AUDIO_FORMAT_PCM_16BIT = 2
        const val READ_FRAME_BYTES = 1600
        const val DEFAULT_VOICE_BEGIN_TIMEOUT_MS = 1_500L
        const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000
        const val MAX_FRAME_BYTES = 1024 * 1024
        private const val RELEASE_FINISH_GRACE_MS = 450L

        fun buildKeyInjectSearchMessage(): RemoteMessage =
            RemoteMessage.newBuilder()
                .setRemoteKeyInject(
                    RemoteKeyInject.newBuilder()
                        .setKeyCode(RemoteKeyCode.KEYCODE_SEARCH) // 84
                        .setDirection(RemoteDirection.SHORT) // 3
                        .build()
                )
                .build()

        fun buildVoiceBeginReplyMessage(sessionId: Int): RemoteMessage {
            require(sessionId >= 0) { "session_id must be non-negative (was $sessionId)" }
            return RemoteMessage.newBuilder()
                .setRemoteVoiceBegin(
                    RemoteVoiceBegin.newBuilder()
                        .setSessionId(sessionId)
                        .setVoiceConfig(
                            RemoteVoiceConfig.newBuilder()
                                .setSampleRate(SAMPLE_RATE_HZ)
                                .setChannelConfig(VOICE_CHANNEL_CONFIG_MONO)
                                .setAudioFormat(VOICE_AUDIO_FORMAT_PCM_16BIT)
                                .build()
                        )
                        .build()
                )
                .build()
        }

        fun buildVoicePayloadMessage(sessionId: Int, pcmSamples: ByteArray): RemoteMessage {
            require(sessionId >= 0) { "session_id must be non-negative (was $sessionId)" }
            return RemoteMessage.newBuilder()
                .setRemoteVoicePayload(
                    RemoteVoicePayload.newBuilder()
                        .setSessionId(sessionId)
                        .setSamples(ByteString.copyFrom(pcmSamples))
                        .build()
                )
                .build()
        }

        fun buildVoiceEndMessage(sessionId: Int): RemoteMessage {
            require(sessionId >= 0) { "session_id must be non-negative (was $sessionId)" }
            return RemoteMessage.newBuilder()
                .setRemoteVoiceEnd(
                    RemoteVoiceEnd.newBuilder()
                        .setSessionId(sessionId)
                        .build()
                )
                .build()
        }

        /**
         * Encodes [value] as an unsigned base-128 Varint32 into [out].
         */
        fun writeVarint32(out: OutputStream, value: Int) {
            var v = value
            while ((v and 0x7F.inv()) != 0) {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v and 0x7F)
        }

        /**
         * Reads an unsigned base-128 Varint32 from [input], throwing [EOFException] on closed stream.
         */
        fun readVarint32(input: InputStream): Int {
            var result = 0
            var shift = 0
            while (shift < 32) {
                val b = input.read()
                if (b < 0) throw EOFException("Stream closed while reading Varint32 length prefix")
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            // Consume high continuation bytes if a malformed/oversized 64-bit varint is sent
            for (ignored in 0 until 5) {
                val b = input.read()
                if (b < 0) throw EOFException("Stream closed while reading Varint32")
                if ((b and 0x80) == 0) {
                    throw IllegalStateException("Malformed Varint32 length prefix")
                }
            }
            throw IllegalStateException("Malformed Varint32 length prefix")
        }

        /**
         * Serializes [message] with a leading Varint32 byte-length prefix.
         */
        fun encodeDelimited(message: RemoteMessage): ByteArray {
            val payload = message.toByteArray()
            val out = ByteArrayOutputStream(payload.size + 5)
            writeVarint32(out, payload.size)
            out.write(payload)
            return out.toByteArray()
        }

        fun writeDelimitedMessage(out: OutputStream, message: RemoteMessage) {
            out.write(encodeDelimited(message))
            out.flush()
        }

        /**
         * Reads a Varint32 length prefix from [input], reads exact payload bytes, and parses [RemoteMessage].
         */
        fun readDelimitedMessage(input: InputStream): RemoteMessage {
            val length = readVarint32(input)
            if (length < 0 || length > MAX_FRAME_BYTES) {
                throw IllegalStateException("Invalid RemoteMessage Varint32 frame length: $length")
            }
            val payload = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(payload, offset, length - offset)
                if (read < 0) throw EOFException("Stream closed before reading full RemoteMessage ($offset/$length)")
                offset += read
            }
            return RemoteMessage.parseFrom(payload)
        }
    }
}
