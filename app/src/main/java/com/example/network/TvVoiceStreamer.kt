package com.example.network

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Buffers raw 8 kHz 16-bit PCM mono reads and emits chunks strictly sized in
 * [MIN_PAYLOAD_BYTES, MAX_PAYLOAD_BYTES] (8 KB - 20 KB), matching Android TV Remote Service's
 * payload bounds. Short tail buffers on clean session end are zero-padded to [MIN_PAYLOAD_BYTES].
 */
internal class VoiceChunkAccumulator(
    private val minChunkBytes: Int = MIN_PAYLOAD_BYTES,
    private val maxChunkBytes: Int = MAX_PAYLOAD_BYTES
) {
    private var buffer = ByteArray(maxChunkBytes * 2)
    private var size: Int = 0

    fun bufferedBytes(): Int = size

    fun append(pcmSamples: ByteArray): List<ByteArray> {
        if (pcmSamples.isEmpty()) return emptyList()
        ensureCapacity(size + pcmSamples.size)
        System.arraycopy(pcmSamples, 0, buffer, size, pcmSamples.size)
        size += pcmSamples.size
        return drainReadyChunks()
    }

    /**
     * Emits any buffered >= minChunkBytes slices first, then zero-pads any remaining partial
     * bytes (1 until minChunkBytes) up to minChunkBytes so TV voice recognition hears the tail.
     */
    fun flushPadded(): List<ByteArray> {
        if (size == 0) return emptyList()
        val result = drainReadyChunks().toMutableList()
        if (size > 0) {
            val padded = ByteArray(minChunkBytes)
            System.arraycopy(buffer, 0, padded, 0, size)
            size = 0
            result.add(padded)
        }
        return result
    }

    fun reset() {
        size = 0
    }

    private fun drainReadyChunks(): List<ByteArray> {
        if (size < minChunkBytes) return emptyList()
        val out = ArrayList<ByteArray>(2)
        var offset = 0
        while (size - offset >= minChunkBytes) {
            val available = size - offset
            val rawTake = when {
                available <= maxChunkBytes -> available
                available - maxChunkBytes >= minChunkBytes -> maxChunkBytes
                else -> available - minChunkBytes
            }
            // Preserve 16-bit PCM sample alignment (even byte count >= minChunkBytes)
            val evenTake = rawTake and 1.inv()
            if (evenTake < minChunkBytes) break
            out.add(buffer.copyOfRange(offset, offset + evenTake))
            offset += evenTake
        }
        if (offset > 0) {
            val remaining = size - offset
            if (remaining > 0) {
                System.arraycopy(buffer, offset, buffer, 0, remaining)
            }
            size = remaining
        }
        return out
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        val newCap = maxOf(buffer.size * 2, required)
        buffer = buffer.copyOf(newCap)
    }

    companion object {
        const val MIN_PAYLOAD_BYTES = 8192
        const val MAX_PAYLOAD_BYTES = 20480
    }
}

/**
 * Thread-safe voice streaming state machine coordinating pre-buffering, session activation,
 * [8192, 20480] byte chunk emission, and final padded flush before RemoteVoiceEnd.
 */
internal class VoiceStreamPipeline(
    private val maxPreBufferBytes: Int = MAX_PREBUFFER_BYTES
) {
    private val emitMutex = Mutex()
    private val stateLock = Any()
    private val preBuffer = ArrayDeque<ByteArray>()
    private var preBufferBytes = 0
    private val accumulator = VoiceChunkAccumulator()
    private var activeSessionId: Int = -1
    private var minAcceptedGeneration: Int = 0
    private var activeGeneration: Int = 0

    fun prepareCaptureSync(generation: Int) {
        synchronized(stateLock) {
            if (generation < minAcceptedGeneration) return
            activeGeneration = generation
            activeSessionId = -1
            preBuffer.clear()
            preBufferBytes = 0
            accumulator.reset()
        }
    }

    suspend fun beginCapture(generation: Int) {
        prepareCaptureSync(generation)
    }

    suspend fun activateSession(
        generation: Int,
        sessionId: Int,
        onChunk: suspend (sessionId: Int, chunk: ByteArray) -> Unit
    ) {
        if (sessionId < 0) return
        emitMutex.withLock {
            val readyChunks = synchronized(stateLock) {
                if (generation != activeGeneration || generation < minAcceptedGeneration) {
                    return@synchronized emptyList()
                }
                activeSessionId = sessionId
                val collected = ArrayList<ByteArray>()
                while (preBuffer.isNotEmpty()) {
                    val frame = preBuffer.removeFirst()
                    preBufferBytes = (preBufferBytes - frame.size).coerceAtLeast(0)
                    collected.addAll(accumulator.append(frame))
                }
                collected
            }
            for (chunk in readyChunks) {
                val valid = synchronized(stateLock) {
                    generation == activeGeneration &&
                        generation >= minAcceptedGeneration &&
                        activeSessionId == sessionId
                }
                if (!valid) break
                onChunk(sessionId, chunk)
            }
        }
    }

    suspend fun pushFrame(
        generation: Int,
        frame: ByteArray,
        onChunk: suspend (sessionId: Int, chunk: ByteArray) -> Unit
    ) {
        if (frame.isEmpty()) return
        emitMutex.withLock {
            var emitSessionId = -1
            val readyChunks = synchronized(stateLock) {
                if (generation != activeGeneration || generation < minAcceptedGeneration) {
                    return@synchronized emptyList()
                }
                val sid = activeSessionId
                if (sid >= 0) {
                    emitSessionId = sid
                    val list = ArrayList<ByteArray>()
                    while (preBuffer.isNotEmpty()) {
                        val buffered = preBuffer.removeFirst()
                        preBufferBytes = (preBufferBytes - buffered.size).coerceAtLeast(0)
                        list.addAll(accumulator.append(buffered))
                    }
                    list.addAll(accumulator.append(frame))
                    list
                } else {
                    preBuffer.addLast(frame)
                    preBufferBytes += frame.size
                    while (preBufferBytes > maxPreBufferBytes && preBuffer.isNotEmpty()) {
                        val evicted = preBuffer.removeFirst()
                        preBufferBytes = (preBufferBytes - evicted.size).coerceAtLeast(0)
                    }
                    emptyList()
                }
            }
            if (emitSessionId >= 0) {
                for (chunk in readyChunks) {
                    val valid = synchronized(stateLock) {
                        generation == activeGeneration &&
                            generation >= minAcceptedGeneration &&
                            activeSessionId == emitSessionId
                    }
                    if (!valid) break
                    onChunk(emitSessionId, chunk)
                }
            }
        }
    }

    suspend fun finishAndFlush(
        sessionId: Int,
        onChunk: suspend (sessionId: Int, chunk: ByteArray) -> Unit
    ) {
        emitMutex.withLock {
            var capturedGen = -1
            val finalChunks = synchronized(stateLock) {
                if (activeSessionId != sessionId) return@synchronized emptyList()
                capturedGen = activeGeneration
                val collected = ArrayList<ByteArray>()
                while (preBuffer.isNotEmpty()) {
                    val frame = preBuffer.removeFirst()
                    preBufferBytes = (preBufferBytes - frame.size).coerceAtLeast(0)
                    collected.addAll(accumulator.append(frame))
                }
                collected.addAll(accumulator.flushPadded())
                activeSessionId = -1
                preBuffer.clear()
                preBufferBytes = 0
                collected
            }
            for (chunk in finalChunks) {
                val valid = synchronized(stateLock) {
                    activeGeneration == capturedGen && activeGeneration >= minAcceptedGeneration
                }
                if (!valid) break
                onChunk(sessionId, chunk)
            }
        }
    }

    suspend fun reset() {
        emitMutex.withLock {
            synchronized(stateLock) {
                minAcceptedGeneration = maxOf(minAcceptedGeneration + 1, activeGeneration + 1)
                activeSessionId = -1
                preBuffer.clear()
                preBufferBytes = 0
                accumulator.reset()
            }
        }
    }

    fun resetSync(nextValidGeneration: Int) {
        synchronized(stateLock) {
            minAcceptedGeneration = maxOf(minAcceptedGeneration, nextValidGeneration)
            activeSessionId = -1
            preBuffer.clear()
            preBufferBytes = 0
            accumulator.reset()
        }
    }

    companion object {
        // 8000 Hz * 2 B * 1.5s = 24,000 B
        const val MAX_PREBUFFER_BYTES = 24_000
    }
}

internal fun interface VoiceAudioCaptureBackend {
    suspend fun captureFrames(
        frameBytes: Int,
        isRunning: () -> Boolean,
        onFrame: suspend (ByteArray) -> Unit
    )
}

/**
 * Captures 8 kHz, 16-bit signed PCM mono audio from the phone microphone and streams chunks
 * over Android TV Remote Protocol v2 (RemoteVoiceBegin / RemoteVoicePayload / RemoteVoiceEnd).
 * Pre-buffers up to ~1.5s of audio before RemoteVoiceBegin arrives from the TV, and coalesces all
 * outgoing RemoteVoicePayload chunks into [8192, 20480] bytes as required by Android TV.
 */
class TvVoiceStreamer internal constructor(
    private val defaultBackend: VoiceAudioCaptureBackend
) {
    constructor() : this(createHardwareCaptureBackend())

    private val pipeline = VoiceStreamPipeline()
    private val generationCounter = AtomicInteger(0)

    @Volatile
    private var captureJob: Job? = null

    @Volatile
    private var cancelingJob: Job? = null

    @Volatile
    private var isCapturing: Boolean = false

    @Volatile
    private var activeChunkCallback: (suspend (sessionId: Int, chunk: ByteArray) -> Unit)? = null

    @Volatile
    internal var captureBackendForTest: VoiceAudioCaptureBackend? = null

    fun startCapture(
        scope: CoroutineScope,
        onChunk: suspend (sessionId: Int, chunk: ByteArray) -> Unit,
        onCaptureFailed: (Throwable) -> Unit,
        onSpeechDetected: () -> Unit = {}
    ) {
        stop()
        val prevReleasingJob = cancelingJob
        val generation = generationCounter.incrementAndGet()
        activeChunkCallback = onChunk
        pipeline.prepareCaptureSync(generation)
        isCapturing = true

        captureJob = scope.launch(Dispatchers.IO) {
            if (prevReleasingJob != null && !prevReleasingJob.isCompleted) {
                withTimeoutOrNull(FINISH_JOIN_TIMEOUT_MS) {
                    prevReleasingJob.join()
                }
            }
            if (!isCapturing || generationCounter.get() != generation) {
                return@launch
            }
            try {
                val backend = captureBackendForTest ?: defaultBackend
                backend.captureFrames(
                    frameBytes = CHUNK_BYTES,
                    isRunning = { isCapturing }
                ) { frame ->
                    if (!isCapturing) return@captureFrames
                    if (computePcm16PeakAmplitude(frame) >= SPEECH_PEAK_THRESHOLD) {
                        onSpeechDetected()
                    }
                    pipeline.pushFrame(generation, frame, onChunk)
                }
            } catch (t: Throwable) {
                if (isCapturing && t !is kotlinx.coroutines.CancellationException) {
                    onCaptureFailed(t)
                }
            } finally {
                isCapturing = false
            }
        }
    }

    suspend fun activateSession(sessionId: Int) {
        val cb = activeChunkCallback ?: return
        pipeline.activateSession(generationCounter.get(), sessionId, cb)
    }

    /**
     * Gracefully stops microphone reads, flushes any remaining PCM bytes (zero-padded to 8192 B),
     * and releases AudioRecord before the caller transmits RemoteVoiceEnd.
     */
    suspend fun finishAndFlushAndJoin(sessionId: Int) {
        val job = captureJob
        val cb = activeChunkCallback
        isCapturing = false
        if (job != null) {
            val finishedCleanly = withTimeoutOrNull(FINISH_JOIN_TIMEOUT_MS) {
                job.join()
                true
            } ?: false
            if (!finishedCleanly) {
                job.cancel()
                job.join()
            }
        }
        captureJob = null
        cancelingJob = null
        if (cb != null) {
            pipeline.finishAndFlush(sessionId, cb)
        }
        pipeline.reset()
        activeChunkCallback = null
    }

    fun stop() {
        isCapturing = false
        val nextGen = generationCounter.incrementAndGet()
        val running = captureJob
        if (running != null && !running.isCompleted) {
            cancelingJob = running
            running.invokeOnCompletion {
                if (cancelingJob === running) {
                    cancelingJob = null
                }
            }
            running.cancel()
        } else {
            running?.cancel()
        }
        captureJob = null
        activeChunkCallback = null
        pipeline.resetSync(nextGen)
    }

    /**
     * Aborts capture without flushing remaining bytes and waits until AudioRecord is released,
     * so another recorder (e.g. SpeechRecognizer fallback) can open the mic without BUSY error.
     */
    suspend fun stopAndJoin() {
        val job = captureJob ?: cancelingJob
        isCapturing = false
        generationCounter.incrementAndGet()
        job?.cancel()
        job?.join()
        captureJob = null
        cancelingJob = null
        activeChunkCallback = null
        pipeline.reset()
    }

    companion object {
        const val SAMPLE_RATE_HZ = 8000
        // 8000 Hz * 2 bytes/sample * 0.1s = 1600 bytes per 100ms read frame (batched into 8KB-20KB packets)
        const val CHUNK_BYTES = 1600
        const val MIN_PAYLOAD_BYTES = VoiceChunkAccumulator.MIN_PAYLOAD_BYTES
        const val MAX_PAYLOAD_BYTES = VoiceChunkAccumulator.MAX_PAYLOAD_BYTES
        internal const val SPEECH_PEAK_THRESHOLD = 2_000
        private const val FINISH_JOIN_TIMEOUT_MS = 450L

        internal fun computePcm16PeakAmplitude(pcmLeBytes: ByteArray): Int {
            var peak = 0
            var i = 0
            val limit = pcmLeBytes.size and 1.inv()
            while (i < limit) {
                val lo = pcmLeBytes[i].toInt() and 0xFF
                val hi = pcmLeBytes[i + 1].toInt()
                val sample = (hi shl 8) or lo
                val abs = kotlin.math.abs(sample)
                if (abs > peak) {
                    peak = abs
                }
                i += 2
            }
            return peak
        }

        private fun createHardwareCaptureBackend(): VoiceAudioCaptureBackend =
            VoiceAudioCaptureBackend { frameBytes, isRunning, onFrame ->
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(frameBytes)
                val recordBufferSize = maxOf(minBuf * 2, frameBytes * 4)

                var recorder: AudioRecord? = null
                try {
                    recorder = createAudioRecord(recordBufferSize)
                    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                        throw IllegalStateException("마이크 장치를 초기화할 수 없습니다.")
                    }

                    recorder.startRecording()
                    val readBuf = ByteArray(frameBytes)
                    val ctx = currentCoroutineContext()
                    while (ctx.isActive && isRunning()) {
                        val read = recorder.read(readBuf, 0, readBuf.size)
                        if (read > 0) {
                            onFrame(readBuf.copyOf(read))
                        } else if (read < 0) {
                            break
                        }
                    }
                } finally {
                    runCatching { recorder?.stop() }
                    runCatching { recorder?.release() }
                }
            }

        @SuppressLint("MissingPermission")
        private fun createAudioRecord(bufferSize: Int): AudioRecord {
            val voiceRec = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull()

            if (voiceRec != null && voiceRec.state == AudioRecord.STATE_INITIALIZED) {
                return voiceRec
            }
            runCatching { voiceRec?.release() }

            return AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }
    }
}
