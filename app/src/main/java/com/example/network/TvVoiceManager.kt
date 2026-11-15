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

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay

/**
 * Manages Android TV Remote Protocol v2 voice streaming using generated
 * `protobuf-javalite` messages (`com.example.proto.RemoteMessage`) and Varint32
 * length-prefixed framing over TLS port 6466 (with pairing on port 6467).
 *
 * Supports Tap-to-Talk with pre-capture buffering and tail capture delay while strictly preserving
 * the Protocol v2 wire handshake:
 * 1. Sends `RemoteKeyInject(KEYCODE_SEARCH = 84, SHORT)` and immediately pre-captures PCM (8 kHz, Mono, 16-bit).
 * 2. Waits for TV's `RemoteVoiceBegin` and parses `session_id` (never uses arbitrary or `-1` IDs).
 * 3. Replies `RemoteVoiceBegin(session_id, RemoteVoiceConfig(sample_rate = 8000, channel_config = 1, audio_format = 2))`.
 * 4. Flushes pre-captured PCM and continues streaming `RemoteVoicePayload(session_id, samples)`.
 * 5. On stop (2nd tap / 3s silence / max duration), waits `tailDelayMs`, stops `AudioRecord`,
 *    flushes remaining PCM, and sends `RemoteVoiceEnd(session_id)`.
 */
class TvVoiceManager(
    transport: RemoteMessageTransport? = null,
    private val pcmRecorder: VoicePcmRecorder = HardwareAudioRecordRecorder(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val voiceBeginTimeoutMs: Long = DEFAULT_VOICE_BEGIN_TIMEOUT_MS,
    private val preCaptureBeforeVoiceBegin: Boolean = true,
    private val tailDelayMs: Long = DEFAULT_TAIL_DELAY_MS,
    @Volatile var noActivityTimeoutMs: Long? = DEFAULT_NO_ACTIVITY_TIMEOUT_MS,
    private val maxStreamDurationMs: Long? = DEFAULT_MAX_STREAM_DURATION_MS,
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

    enum class EndReason {
        USER_STOPPED,
        TV_ENDED,
        INACTIVITY_TIMEOUT,
        MAX_DURATION
    }

    sealed interface VoiceEvent {
        data class StateChanged(val state: State) : VoiceEvent
        data class SessionStarted(val sessionId: Int) : VoiceEvent
        data class SessionEnded(
            val sessionId: Int,
            val initiatedByTv: Boolean,
            val reason: EndReason = if (initiatedByTv) EndReason.TV_ENDED else EndReason.USER_STOPPED
        ) : VoiceEvent
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

    private val sendMutex = Mutex()
    private val streamSendMutex = Mutex()
    private val preCaptureLock = Any()

    @Volatile
    private var sessionJob: Job? = null

    @Volatile
    private var captureJob: Job? = null

    @Volatile
    private var captureActive: Boolean = false

    @Volatile
    private var stopRequested: Boolean = false

    @Volatile
    private var immediateCancelRequested: Boolean = false

    @Volatile
    private var activeSessionId: Int? = null

    @Volatile
    private var tvEndedSession: Boolean = false

    @Volatile
    private var sessionIdDeferred: CompletableDeferred<Int>? = null

    @Volatile
    private var stopSignal: CompletableDeferred<Unit>? = null

    @Volatile
    private var lastSpeechActivityAtMs: Long = 0L

    @Volatile
    private var tvVoiceEosDetectedAtMs: Long? = null

    @Volatile
    private var pendingTvVoiceError: Pair<String, Boolean>? = null

    @Volatile
    private var captureFailure: Throwable? = null

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
     * Tap-to-Talk toggle: starts listening when idle, or ends the active stream with [tailDelayMs]
     * tail capture when already waiting or streaming.
     */
    fun toggleTapToTalk(scope: CoroutineScope) {
        if (_state.value != State.IDLE) {
            stopWithTailDelay()
        } else {
            startTapToTalk(scope)
        }
    }

    /**
     * Alias for Push-to-Talk callers; delegates to [startTapToTalk].
     */
    fun onPressDown(scope: CoroutineScope) {
        startTapToTalk(scope)
    }

    /**
     * Alias for Push-to-Talk release callers; delegates to [stopWithTailDelay].
     */
    fun onReleaseUp() {
        stopWithTailDelay()
    }

    /**
     * Starts a Tap-to-Talk voice session:
     * 1. Pre-captures 8 kHz Mono PCM 16-bit immediately (when [preCaptureBeforeVoiceBegin] is true)
     *    while sending `RemoteKeyInject(KEYCODE_SEARCH = 84, SHORT)`.
     * 2. Waits for TV's `RemoteVoiceBegin(session_id)` (rejects `< 0` / never synthesizes `-1`).
     * 3. Replies `RemoteVoiceBegin(session_id, RemoteVoiceConfig(8000, 1, 2))` and flushes pre-captured PCM.
     * 4. Streams live `RemoteVoicePayload` chunks and monitors silence/duration/stop requests.
     * 5. On local stop, captures [tailDelayMs] trailing audio, flushes remaining PCM, and sends `RemoteVoiceEnd(session_id)`.
     */
    fun startTapToTalk(scope: CoroutineScope) {
        val transport = activeTransport
        if (transport == null || !transport.isConnected) {
            onEvent(VoiceEvent.Error("TV와 TLS 6466 소켓이 연결되어 있지 않습니다."))
            return
        }

        val staleJob = sessionJob
        val staleCaptureJob = captureJob
        sessionJob = null
        captureJob = null
        captureActive = true
        stopRequested = false
        immediateCancelRequested = false
        tvEndedSession = false
        activeSessionId = null
        tvVoiceEosDetectedAtMs = null
        pendingTvVoiceError = null
        captureFailure = null
        sessionIdDeferred?.cancel()
        stopSignal?.cancel()

        val newBeginSignal = CompletableDeferred<Int>()
        val newStopSignal = CompletableDeferred<Unit>()
        sessionIdDeferred = newBeginSignal
        stopSignal = newStopSignal
        lastSpeechActivityAtMs = System.currentTimeMillis()
        updateState(State.WAITING_FOR_SESSION)

        sessionJob = scope.launch(ioDispatcher) {
            staleCaptureJob?.cancel()
            runCatching { staleCaptureJob?.join() }
            staleJob?.cancel()
            runCatching { staleJob?.join() }

            val accumulator = if (coalesceChunksTo8Kb20Kb) VoiceChunkAccumulator() else null
            val preCaptureQueue = ArrayDeque<ByteArray>()
            var preCaptureBytes = 0
            var confirmedSessionId: Int? = null
            var handshakeReady = false
            var endReason = EndReason.USER_STOPPED

            suspend fun drainPendingSamplesLocked(sid: Int, extraFrame: ByteArray?) {
                val pending = ArrayList<ByteArray>()
                synchronized(preCaptureLock) {
                    while (preCaptureQueue.isNotEmpty()) {
                        val queued = preCaptureQueue.removeFirst()
                        preCaptureBytes = (preCaptureBytes - queued.size).coerceAtLeast(0)
                        pending.add(queued)
                    }
                }
                if (extraFrame != null && extraFrame.isNotEmpty()) {
                    pending.add(extraFrame)
                }
                for (pcm in pending) {
                    if (tvEndedSession || !transport.isConnected) break
                    if (accumulator != null) {
                        for (chunk in accumulator.append(pcm)) {
                            if (!tvEndedSession && transport.isConnected) {
                                sendFrame(transport, buildVoicePayloadMessage(sid, chunk))
                            }
                        }
                    } else {
                        sendFrame(transport, buildVoicePayloadMessage(sid, pcm))
                    }
                }
            }

            fun launchRecorderJob(): Job = scope.launch(ioDispatcher) {
                try {
                    pcmRecorder.record(
                        bufferSizeBytes = READ_FRAME_BYTES,
                        isActive = { captureActive && !immediateCancelRequested && !tvEndedSession && transport.isConnected }
                    ) { pcmBytes ->
                        if (!captureActive || immediateCancelRequested || pcmBytes.isEmpty()) return@record
                        if (TvVoiceStreamer.computePcm16PeakAmplitude(pcmBytes) >= TvVoiceStreamer.SPEECH_PEAK_THRESHOLD) {
                            lastSpeechActivityAtMs = System.currentTimeMillis()
                            onSpeechDetected()
                        }

                        var readySid: Int? = null
                        synchronized(preCaptureLock) {
                            if (!handshakeReady || confirmedSessionId == null) {
                                preCaptureQueue.addLast(pcmBytes)
                                preCaptureBytes += pcmBytes.size
                                while (preCaptureBytes > MAX_PRECAPTURE_BYTES && preCaptureQueue.isNotEmpty()) {
                                    val evicted = preCaptureQueue.removeFirst()
                                    preCaptureBytes = (preCaptureBytes - evicted.size).coerceAtLeast(0)
                                }
                            } else {
                                readySid = confirmedSessionId
                            }
                        }

                        val sid = readySid
                        if (sid != null && !tvEndedSession && !immediateCancelRequested && transport.isConnected) {
                            streamSendMutex.withLock {
                                if (!tvEndedSession && !immediateCancelRequested && transport.isConnected) {
                                    drainPendingSamplesLocked(sid, pcmBytes)
                                }
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    // Normal capture cancellation
                } catch (t: Throwable) {
                    captureFailure = t
                    newBeginSignal.completeExceptionally(t)
                    newStopSignal.complete(Unit)
                }
            }

            var activeCaptureJob: Job? = null

            suspend fun stopAndJoinRecorderNow() {
                captureActive = false
                val job = activeCaptureJob
                if (job != null) {
                    withContext(NonCancellable) {
                        val stoppedCleanly = withTimeoutOrNull(RELEASE_FINISH_GRACE_MS) {
                            job.join()
                            true
                        } ?: false
                        if (!stoppedCleanly) {
                            job.cancel()
                            runCatching { job.join() }
                        }
                    }
                    if (captureJob === job) {
                        captureJob = null
                    }
                    activeCaptureJob = null
                }
            }

            try {
                if (stopRequested || immediateCancelRequested) {
                    return@launch
                }

                // Start AudioRecord immediately (pre-capture) so first syllables are buffered during TV handshake
                if (preCaptureBeforeVoiceBegin) {
                    activeCaptureJob = launchRecorderJob()
                    captureJob = activeCaptureJob
                }

                // Step 1: Send RemoteKeyInject(KEYCODE_SEARCH = 84, SHORT)
                sendFrame(transport, buildKeyInjectSearchMessage())

                // Step 2: Wait for TV RemoteVoiceBegin and parse valid session_id (never -1 or arbitrary)
                val parsedSessionId = withTimeoutOrNull(voiceBeginTimeoutMs) {
                    newBeginSignal.await()
                }

                captureFailure?.let { throw it }

                if (parsedSessionId == null) {
                    if (!stopRequested && !immediateCancelRequested && _state.value == State.WAITING_FOR_SESSION) {
                        val msg = "TV가 RemoteVoiceBegin 세션을 열지 않았습니다."
                        stopAndJoinRecorderNow()
                        updateState(State.IDLE)
                        onEvent(VoiceEvent.VoiceBeginTimeout(msg))
                    }
                    return@launch
                }

                if (parsedSessionId < 0) {
                    throw IllegalStateException("TV에서 잘못된 음성 session_id($parsedSessionId)를 반환했습니다.")
                }

                if (stopRequested || immediateCancelRequested || tvEndedSession || !transport.isConnected) {
                    return@launch
                }

                confirmedSessionId = parsedSessionId
                activeSessionId = parsedSessionId
                sessionIdDeferred = null

                // Step 3: Reply RemoteVoiceBegin with parsed session_id + RemoteVoiceConfig(8000, 1, 2)
                sendFrame(transport, buildVoiceBeginReplyMessage(parsedSessionId))

                if (stopRequested || immediateCancelRequested || tvEndedSession || !transport.isConnected) {
                    return@launch
                }

                // Step 4: Flush pre-captured PCM and stream live RemoteVoicePayload
                updateState(State.STREAMING)
                onEvent(VoiceEvent.SessionStarted(parsedSessionId))

                if (activeCaptureJob == null) {
                    activeCaptureJob = launchRecorderJob()
                    captureJob = activeCaptureJob
                }

                streamSendMutex.withLock {
                    synchronized(preCaptureLock) {
                        handshakeReady = true
                    }
                    drainPendingSamplesLocked(parsedSessionId, extraFrame = null)
                }

                val streamStartMs = System.currentTimeMillis()
                if (lastSpeechActivityAtMs < streamStartMs) {
                    lastSpeechActivityAtMs = streamStartMs
                }

                while (!stopRequested && !immediateCancelRequested && !tvEndedSession && transport.isConnected) {
                    captureFailure?.let { throw it }
                    val now = System.currentTimeMillis()
                    val idleMs = now - lastSpeechActivityAtMs
                    val totalMs = now - streamStartMs
                    val eosElapsedMs = tvVoiceEosDetectedAtMs?.let { now - it }

                    val noAct = noActivityTimeoutMs
                    if (noAct != null && idleMs >= noAct) {
                        endReason = EndReason.INACTIVITY_TIMEOUT
                        break
                    }
                    if (eosElapsedMs != null && eosElapsedMs >= DEFAULT_TV_EOS_GRACE_MS) {
                        endReason = EndReason.TV_ENDED
                        break
                    }
                    val maxDur = maxStreamDurationMs
                    if (maxDur != null && totalMs >= maxDur) {
                        endReason = EndReason.MAX_DURATION
                        break
                    }
                    val sleepMs = minOf(
                        100L,
                        noAct?.let { (it - idleMs).coerceAtLeast(10L) } ?: Long.MAX_VALUE,
                        eosElapsedMs?.let { (DEFAULT_TV_EOS_GRACE_MS - it).coerceAtLeast(10L) } ?: Long.MAX_VALUE,
                        maxDur?.let { (it - totalMs).coerceAtLeast(10L) } ?: Long.MAX_VALUE
                    )
                    withTimeoutOrNull(sleepMs) {
                        newStopSignal.await()
                    }
                }
            } catch (_: CancellationException) {
                // Cancellation from cancel()/onCleared() or TV RemoteVoiceEnd
            } catch (sec: SecurityException) {
                stopAndJoinRecorderNow()
                updateState(State.IDLE)
                onEvent(VoiceEvent.Error("RECORD_AUDIO 권한이 필요합니다.", sec))
            } catch (t: Throwable) {
                stopAndJoinRecorderNow()
                updateState(State.IDLE)
                onEvent(
                    VoiceEvent.Error(
                        message = t.message ?: "음성 스트리밍 중 오류가 발생했습니다.",
                        cause = t,
                        recoverableWithFallback = t !is IllegalStateException
                    )
                )
            } finally {
                val sid = confirmedSessionId
                val shouldSendEnd = sid != null && !tvEndedSession && !immediateCancelRequested && transport.isConnected
                if (shouldSendEnd) {
                    updateState(State.STOPPING)
                    // Tail capture delay: keep recording briefly so trailing syllables aren't clipped
                    if (tailDelayMs > 0L && captureActive) {
                        val tailDeadline = System.currentTimeMillis() + tailDelayMs
                        while (!tvEndedSession && !immediateCancelRequested && transport.isConnected) {
                            val remaining = tailDeadline - System.currentTimeMillis()
                            if (remaining <= 0L) break
                            delay(minOf(remaining, 40L))
                        }
                    }
                }

                stopAndJoinRecorderNow()

                if (sid != null && shouldSendEnd && !tvEndedSession && !immediateCancelRequested && transport.isConnected) {
                    withContext(NonCancellable) {
                        streamSendMutex.withLock {
                            runCatching { drainPendingSamplesLocked(sid, extraFrame = null) }
                            if (accumulator != null) {
                                for (tailChunk in accumulator.flushPadded()) {
                                    runCatching {
                                        sendFrame(transport, buildVoicePayloadMessage(sid, tailChunk))
                                    }
                                }
                            }
                            // Step 5: Send RemoteVoiceEnd(session_id) after tail delay + recording stop + flush
                            runCatching {
                                sendFrame(transport, buildVoiceEndMessage(sid))
                            }
                        }
                    }
                    onEvent(VoiceEvent.SessionEnded(sid, initiatedByTv = false, reason = endReason))
                }

                if (activeSessionId == sid) {
                    activeSessionId = null
                }
                if (sessionIdDeferred === newBeginSignal) {
                    sessionIdDeferred = null
                }
                if (stopSignal === newStopSignal) {
                    stopSignal = null
                }
                updateState(State.IDLE)
                val deferredError = pendingTvVoiceError
                if (deferredError != null) {
                    pendingTvVoiceError = null
                    onEvent(VoiceEvent.Error(deferredError.first, recoverableWithFallback = deferredError.second))
                }
            }
        }
    }

    /**
     * Requests a graceful stop with [tailDelayMs] trailing capture, remaining PCM flush,
     * and `RemoteVoiceEnd(session_id)`. If TV never delivered a valid `RemoteVoiceBegin`,
     * aborts waiting immediately without sending `-1`.
     */
    fun stopWithTailDelay() {
        stopRequested = true
        stopSignal?.complete(Unit)

        val pendingDeferred = sessionIdDeferred
        if (pendingDeferred != null && !pendingDeferred.isCompleted) {
            captureActive = false
            pendingDeferred.cancel()
            sessionIdDeferred = null
        }

        if (_state.value == State.WAITING_FOR_SESSION && activeSessionId == null) {
            captureActive = false
            captureJob?.cancel()
            sessionJob?.cancel()
            updateState(State.IDLE)
            return
        }

        if (_state.value == State.STREAMING) {
            updateState(State.STOPPING)
        }
    }

    /**
     * Routes decoded [RemoteMessage] frames received on the TLS 6466 socket into the manager.
     */
    fun onIncomingRemoteMessage(message: RemoteMessage) {
        if (message.hasRemoteVoiceBegin()) {
            onTvVoiceBegin(message.remoteVoiceBegin.sessionId)
        }
        if (message.hasRemoteVoicePayload()) {
            onTvVoicePayloadAck(message.remoteVoicePayload.sessionId)
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

    fun onTvVoicePayloadAck(sessionId: Int) {
        val sid = activeSessionId
        if (sid != null && (sessionId == sid || sessionId == 0)) {
            if (tvVoiceEosDetectedAtMs == null) {
                tvVoiceEosDetectedAtMs = System.currentTimeMillis()
            }
        }
    }

    fun onTvVoiceEnd(sessionId: Int) {
        val sid = activeSessionId
        if (sid != null && (sessionId == sid || sessionId == 0)) {
            tvEndedSession = true
            captureActive = false
            stopSignal?.complete(Unit)
            captureJob?.cancel()
            sessionJob?.cancel()
            activeSessionId = null
            updateState(State.IDLE)
            onEvent(VoiceEvent.SessionEnded(sid, initiatedByTv = true, reason = EndReason.TV_ENDED))
        }
    }

    fun onTvVoiceError(detail: String, recoverableWithFallback: Boolean = true) {
        val sid = activeSessionId
        val inFlight = _state.value != State.IDLE || sid != null
        if (!inFlight) return
        tvEndedSession = true
        immediateCancelRequested = true
        captureActive = false
        activeSessionId = null
        val activeSessionJob = sessionJob
        if (activeSessionJob != null && activeSessionJob.isActive) {
            pendingTvVoiceError = detail to recoverableWithFallback
            stopSignal?.complete(Unit)
            sessionIdDeferred?.cancel()
            sessionIdDeferred = null
        } else {
            stopSignal?.complete(Unit)
            sessionIdDeferred?.cancel()
            sessionIdDeferred = null
            captureJob?.cancel()
            sessionJob?.cancel()
            updateState(State.IDLE)
            onEvent(VoiceEvent.Error(detail, recoverableWithFallback = recoverableWithFallback))
        }
    }

    fun cancel() {
        stopRequested = true
        immediateCancelRequested = true
        captureActive = false
        tvEndedSession = true
        tvVoiceEosDetectedAtMs = null
        pendingTvVoiceError = null
        stopSignal?.complete(Unit)
        stopSignal = null
        sessionIdDeferred?.cancel()
        sessionIdDeferred = null
        captureJob?.cancel()
        captureJob = null
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
        const val MAX_PRECAPTURE_BYTES = 24_000
        const val DEFAULT_VOICE_BEGIN_TIMEOUT_MS = 1_500L
        const val DEFAULT_TAIL_DELAY_MS = 300L
        const val DEFAULT_NO_ACTIVITY_TIMEOUT_MS = 3_000L
        const val DEFAULT_TV_EOS_GRACE_MS = 1_200L
        const val DEFAULT_MAX_STREAM_DURATION_MS = 10_000L
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
