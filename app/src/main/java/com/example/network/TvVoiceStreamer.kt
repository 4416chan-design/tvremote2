package com.example.network

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Captures 8 kHz, 16-bit signed PCM mono audio from the phone microphone and streams chunks
 * over Android TV Remote Protocol v2 (RemoteVoiceBegin / RemoteVoicePayload / RemoteVoiceEnd).
 * Pre-buffers up to ~1.5s of audio before RemoteVoiceBegin arrives from the TV so opening
 * syllables are never clipped.
 */
class TvVoiceStreamer {

    private val activeSessionId = AtomicInteger(-1)
    private val preBuffer = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var captureJob: Job? = null

    @Volatile
    private var isCapturing: Boolean = false

    @SuppressLint("MissingPermission")
    fun startCapture(
        scope: CoroutineScope,
        onChunk: suspend (sessionId: Int, chunk: ByteArray) -> Unit,
        onCaptureFailed: (Throwable) -> Unit
    ) {
        stop()
        activeSessionId.set(-1)
        preBuffer.clear()
        isCapturing = true

        captureJob = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_BYTES)
            val recordBufferSize = maxOf(minBuf * 2, CHUNK_BYTES * 2)

            var recorder: AudioRecord? = null
            try {
                recorder = createAudioRecord(recordBufferSize)
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("마이크 장치를 초기화할 수 없습니다.")
                }

                recorder.startRecording()
                val readBuf = ByteArray(CHUNK_BYTES)

                while (isActive && isCapturing) {
                    val read = recorder.read(readBuf, 0, readBuf.size)
                    if (read > 0) {
                        val copy = readBuf.copyOf(read)
                        val sid = activeSessionId.get()
                        if (sid >= 0) {
                            drainPreBuffer(sid, onChunk)
                            onChunk(sid, copy)
                        } else {
                            while (preBuffer.size >= MAX_PREBUFFER_CHUNKS) {
                                preBuffer.poll()
                            }
                            preBuffer.offer(copy)
                        }
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (t: Throwable) {
                if (isCapturing) {
                    onCaptureFailed(t)
                }
            } finally {
                isCapturing = false
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
            }
        }
    }

    fun activateSession(sessionId: Int) {
        activeSessionId.set(sessionId)
    }

    private suspend fun drainPreBuffer(
        sessionId: Int,
        onChunk: suspend (sessionId: Int, chunk: ByteArray) -> Unit
    ) {
        while (isCapturing) {
            val buffered = preBuffer.poll() ?: break
            onChunk(sessionId, buffered)
        }
    }

    fun stop() {
        isCapturing = false
        activeSessionId.set(-1)
        captureJob?.cancel()
        captureJob = null
        preBuffer.clear()
    }

    /**
     * Stops capture and waits until AudioRecord is actually released, so another recorder
     * (e.g. SpeechRecognizer fallback) can open the microphone without ERROR_AUDIO / BUSY.
     */
    suspend fun stopAndJoin() {
        val job = captureJob
        stop()
        job?.join()
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

    companion object {
        const val SAMPLE_RATE_HZ = 8000
        // 8000 Hz * 2 bytes/sample * 0.1s = 1600 bytes per 100ms chunk (well under 20 KB limit)
        const val CHUNK_BYTES = 1600
        private const val MAX_PREBUFFER_CHUNKS = 15
    }
}
