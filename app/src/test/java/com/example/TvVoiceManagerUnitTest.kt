package com.example

import com.example.network.TvVoiceManager
import com.example.proto.RemoteDirection
import com.example.proto.RemoteKeyCode
import com.example.proto.RemoteMessage
import com.example.proto.RemoteVoiceBegin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TvVoiceManagerUnitTest {

    @Test
    fun `varint32 delimited codec roundtrips single and multi-byte RemoteMessage payloads`() {
        val sampleSizes = listOf(0, 127, 128, 300, 16_384, 25_000)
        val out = ByteArrayOutputStream()
        for (size in sampleSizes) {
            val msg = TvVoiceManager.buildVoicePayloadMessage(
                sessionId = 42,
                pcmSamples = ByteArray(size) { (it and 0x7F).toByte() }
            )
            TvVoiceManager.writeDelimitedMessage(out, msg)
        }

        val input = ByteArrayInputStream(out.toByteArray())
        for (size in sampleSizes) {
            val decoded = TvVoiceManager.readDelimitedMessage(input)
            assertTrue(decoded.hasRemoteVoicePayload())
            assertEquals(42, decoded.remoteVoicePayload.sessionId)
            assertEquals(size, decoded.remoteVoicePayload.samples.size())
        }
    }

    @Test
    fun `tap to talk pre-captures PCM before RemoteVoiceBegin, waits tail delay, and executes Protocol v2 handshake`() {
        val clientOut = PipedOutputStream()
        val serverIn = PipedInputStream(clientOut, 128 * 1024)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val events = CopyOnWriteArrayList<TvVoiceManager.VoiceEvent>()
        val recorderStarted = AtomicBoolean(false)
        val recorderStopped = AtomicBoolean(false)
        val preBeginCapturedBytes = AtomicInteger(0)
        val tailPhaseStarted = AtomicBoolean(false)
        val tailCapturedBytes = AtomicInteger(0)
        val sentFramesCount = AtomicInteger(0)

        val transport = object : TvVoiceManager.RemoteMessageTransport {
            override val isConnected: Boolean = true
            override suspend fun sendMessage(message: RemoteMessage) {
                TvVoiceManager.writeDelimitedMessage(clientOut, message)
                sentFramesCount.incrementAndGet()
            }
        }

        val recorder = TvVoiceManager.VoicePcmRecorder { _, isActive, onPcmChunk ->
            recorderStarted.set(true)
            try {
                // Pre-capture 4 * 1600 = 6400 B immediately before TV sends RemoteVoiceBegin
                repeat(4) { idx ->
                    if (!isActive()) return@VoicePcmRecorder
                    val frame = ByteArray(1600) { (idx + 1).toByte() }
                    preBeginCapturedBytes.addAndGet(frame.size)
                    onPcmChunk(frame)
                }
                // Wait until RemoteVoiceBegin starts streaming, then send live 3200 B so total = 9600 B (>= 8KB)
                while (isActive() && sentFramesCount.get() < 2) {
                    delay(10)
                }
                repeat(2) {
                    if (!isActive()) return@VoicePcmRecorder
                    onPcmChunk(ByteArray(1600) { 0x33 })
                }
                // Hold until 2nd tap enters STOPPING tail-delay window, then emit trailing syllables
                var tailChunkEmitted = false
                while (isActive()) {
                    if (tailPhaseStarted.get() && !tailChunkEmitted) {
                        tailChunkEmitted = true
                        val tailFrame = ByteArray(1600) { 0x55 }
                        tailCapturedBytes.addAndGet(tailFrame.size)
                        onPcmChunk(tailFrame)
                    }
                    delay(15)
                }
            } finally {
                recorderStopped.set(true)
            }
        }

        val manager = TvVoiceManager(
            transport = transport,
            pcmRecorder = recorder,
            voiceBeginTimeoutMs = 2_000L,
            preCaptureBeforeVoiceBegin = true,
            tailDelayMs = 220L,
            noActivityTimeoutMs = 3_000L,
            coalesceChunksTo8Kb20Kb = true,
            onEvent = { events.add(it) }
        )

        try {
            // Step 1: 1st tap -> starts AudioRecord pre-capture immediately & sends KEYCODE_SEARCH(84)
            manager.toggleTapToTalk(scope)
            val keyMsg = TvVoiceManager.readDelimitedMessage(serverIn)
            assertTrue(keyMsg.hasRemoteKeyInject())
            assertEquals(RemoteKeyCode.KEYCODE_SEARCH, keyMsg.remoteKeyInject.keyCode)
            assertEquals(84, keyMsg.remoteKeyInject.keyCodeValue)
            assertEquals(RemoteDirection.SHORT, keyMsg.remoteKeyInject.direction)

            waitUntil(1_500L) { recorderStarted.get() && preBeginCapturedBytes.get() == 6400 }
            // Strictly no RemoteVoicePayload may be transmitted prior to RemoteVoiceBegin
            assertEquals(1, sentFramesCount.get())

            // Step 2: TV sends RemoteVoiceBegin(session_id = 42, package_name = "com.google.android.katniss")
            val tvBegin = RemoteMessage.newBuilder()
                .setRemoteVoiceBegin(
                    RemoteVoiceBegin.newBuilder()
                        .setSessionId(42)
                        .setPackageName("com.google.android.katniss")
                        .build()
                )
                .build()
            manager.onIncomingRemoteMessage(tvBegin)

            // Step 3: Client replies RemoteVoiceBegin(session_id = 42, voice_config = {8000, 1, 2})
            val clientBeginReply = TvVoiceManager.readDelimitedMessage(serverIn)
            assertTrue(clientBeginReply.hasRemoteVoiceBegin())
            assertEquals(42, clientBeginReply.remoteVoiceBegin.sessionId)
            assertTrue(clientBeginReply.remoteVoiceBegin.hasVoiceConfig())
            assertEquals(8000, clientBeginReply.remoteVoiceBegin.voiceConfig.sampleRate)
            assertEquals(1, clientBeginReply.remoteVoiceBegin.voiceConfig.channelConfig)
            assertEquals(2, clientBeginReply.remoteVoiceBegin.voiceConfig.audioFormat)

            // Step 4: Pre-captured + live PCM flushed as RemoteVoicePayload(42)
            val payloadMsg = TvVoiceManager.readDelimitedMessage(serverIn)
            assertTrue(payloadMsg.hasRemoteVoicePayload())
            assertEquals(42, payloadMsg.remoteVoicePayload.sessionId)
            assertEquals(9600, payloadMsg.remoteVoicePayload.samples.size())

            // Step 5: 2nd tap -> keeps recorder alive during tailDelayMs, then flushes tail + sends RemoteVoiceEnd(42)
            tailPhaseStarted.set(true)
            manager.toggleTapToTalk(scope)
            assertFalse("AudioRecord should remain active during tailDelayMs", recorderStopped.get())

            val tailPayload = TvVoiceManager.readDelimitedMessage(serverIn)
            assertTrue(tailPayload.hasRemoteVoicePayload())
            assertEquals(42, tailPayload.remoteVoicePayload.sessionId)
            assertEquals(8192, tailPayload.remoteVoicePayload.samples.size())
            assertEquals(1600, tailCapturedBytes.get())

            val endMsg = TvVoiceManager.readDelimitedMessage(serverIn)
            assertTrue(recorderStopped.get())
            assertTrue(endMsg.hasRemoteVoiceEnd())
            assertEquals(42, endMsg.remoteVoiceEnd.sessionId)

            waitUntil(1_500L) { manager.state.value == TvVoiceManager.State.IDLE }
            assertTrue(events.any { it is TvVoiceManager.VoiceEvent.SessionStarted && it.sessionId == 42 })
            assertTrue(
                events.any {
                    it is TvVoiceManager.VoiceEvent.SessionEnded &&
                        it.sessionId == 42 &&
                        !it.initiatedByTv &&
                        it.reason == TvVoiceManager.EndReason.USER_STOPPED
                }
            )
        } finally {
            manager.release()
            scope.cancel()
            clientOut.close()
            serverIn.close()
        }
    }

    @Test
    fun `inactivity timeout stops mic after silence window and sends RemoteVoiceEnd`() {
        val sentMessages = CopyOnWriteArrayList<RemoteMessage>()
        val events = CopyOnWriteArrayList<TvVoiceManager.VoiceEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val transport = object : TvVoiceManager.RemoteMessageTransport {
            override val isConnected: Boolean = true
            override suspend fun sendMessage(message: RemoteMessage) {
                sentMessages.add(message)
            }
        }

        val recorder = TvVoiceManager.VoicePcmRecorder { _, isActive, onPcmChunk ->
            while (isActive()) {
                // Silent PCM frames below SPEECH_PEAK_THRESHOLD
                onPcmChunk(ByteArray(1600))
                delay(40)
            }
        }

        val manager = TvVoiceManager(
            transport = transport,
            pcmRecorder = recorder,
            voiceBeginTimeoutMs = 1_000L,
            preCaptureBeforeVoiceBegin = true,
            tailDelayMs = 60L,
            noActivityTimeoutMs = 240L,
            coalesceChunksTo8Kb20Kb = true,
            onEvent = { events.add(it) }
        )

        try {
            manager.toggleTapToTalk(scope)
            waitUntil(1_000L) { sentMessages.any { it.hasRemoteKeyInject() } }

            manager.onIncomingRemoteMessage(
                RemoteMessage.newBuilder()
                    .setRemoteVoiceBegin(RemoteVoiceBegin.newBuilder().setSessionId(99).build())
                    .build()
            )

            waitUntil(2_000L) {
                manager.state.value == TvVoiceManager.State.IDLE &&
                    sentMessages.any { it.hasRemoteVoiceEnd() }
            }

            val endMsg = sentMessages.last { it.hasRemoteVoiceEnd() }
            assertEquals(99, endMsg.remoteVoiceEnd.sessionId)
            assertTrue(
                events.any {
                    it is TvVoiceManager.VoiceEvent.SessionEnded &&
                        it.sessionId == 99 &&
                        it.reason == TvVoiceManager.EndReason.INACTIVITY_TIMEOUT
                }
            )
        } finally {
            manager.release()
            scope.cancel()
        }
    }

    @Test
    fun `early stop or invalid negative session id never emits -1 or synthetic RemoteVoiceEnd`() {
        val sentMessages = CopyOnWriteArrayList<RemoteMessage>()
        val events = CopyOnWriteArrayList<TvVoiceManager.VoiceEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val transport = object : TvVoiceManager.RemoteMessageTransport {
            override val isConnected: Boolean = true
            override suspend fun sendMessage(message: RemoteMessage) {
                sentMessages.add(message)
            }
        }

        val manager = TvVoiceManager(
            transport = transport,
            pcmRecorder = TvVoiceManager.VoicePcmRecorder { _, isActive, _ ->
                while (isActive()) {
                    delay(10)
                }
            },
            voiceBeginTimeoutMs = 1_000L,
            onEvent = { events.add(it) }
        )

        try {
            // 1) 2nd tap before TV responds -> sends SEARCH only, never sends RemoteVoiceEnd(-1)
            manager.toggleTapToTalk(scope)
            waitUntil(1_000L) { sentMessages.isNotEmpty() }
            manager.toggleTapToTalk(scope)
            waitUntil(1_000L) { manager.state.value == TvVoiceManager.State.IDLE }
            assertEquals(1, sentMessages.size)
            assertTrue(sentMessages[0].hasRemoteKeyInject())
            assertFalse(sentMessages.any { it.hasRemoteVoiceEnd() || it.hasRemoteVoiceBegin() })

            // 2) Negative session_id (-1) from TV is rejected immediately without streaming
            sentMessages.clear()
            events.clear()
            manager.toggleTapToTalk(scope)
            waitUntil(1_000L) { sentMessages.isNotEmpty() }
            manager.onIncomingRemoteMessage(
                RemoteMessage.newBuilder()
                    .setRemoteVoiceBegin(RemoteVoiceBegin.newBuilder().setSessionId(-1).build())
                    .build()
            )
            waitUntil(1_000L) {
                manager.state.value == TvVoiceManager.State.IDLE &&
                    events.any { it is TvVoiceManager.VoiceEvent.Error }
            }
            assertFalse(sentMessages.any { it.hasRemoteVoiceBegin() || it.hasRemoteVoicePayload() || it.hasRemoteVoiceEnd() })
        } finally {
            manager.release()
            scope.cancel()
        }
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for TvVoiceManager condition", predicate())
    }
}
