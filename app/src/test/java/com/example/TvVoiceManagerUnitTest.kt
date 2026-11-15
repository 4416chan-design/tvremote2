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
    fun `push to talk executes strict 5-step RemoteVoiceBegin RemoteVoiceConfig RemoteVoicePayload RemoteVoiceEnd handshake`() {
        val clientOut = PipedOutputStream()
        val serverIn = PipedInputStream(clientOut, 128 * 1024)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val events = CopyOnWriteArrayList<TvVoiceManager.VoiceEvent>()
        val recorderStarted = AtomicBoolean(false)
        val recorderStopped = AtomicBoolean(false)
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
                // Emit 6 * 1600 = 9600 bytes first, then hold until ACTION_UP, then stop cleanly
                repeat(6) { idx ->
                    if (!isActive()) return@VoicePcmRecorder
                    onPcmChunk(ByteArray(1600) { (idx + 1).toByte() })
                }
                while (isActive()) {
                    delay(10)
                }
            } finally {
                recorderStopped.set(true)
            }
        }

        val manager = TvVoiceManager(
            transport = transport,
            pcmRecorder = recorder,
            voiceBeginTimeoutMs = 2_000L,
            coalesceChunksTo8Kb20Kb = true,
            onEvent = { events.add(it) }
        )

        try {
            // Step 1: ACTION_DOWN -> client sends RemoteKeyInject(KEYCODE_SEARCH = 84)
            manager.onPressDown(scope)
            val keyMsg = TvVoiceManager.readDelimitedMessage(serverIn)
            assertTrue(keyMsg.hasRemoteKeyInject())
            assertEquals(RemoteKeyCode.KEYCODE_SEARCH, keyMsg.remoteKeyInject.keyCode)
            assertEquals(84, keyMsg.remoteKeyInject.keyCodeValue)
            assertEquals(RemoteDirection.SHORT, keyMsg.remoteKeyInject.direction)
            assertFalse("AudioRecord must not start before RemoteVoiceBegin(session_id)", recorderStarted.get())

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

            // Step 4: AudioRecord starts and streams RemoteVoicePayload(session_id = 42, samples)
            val payloadMsg = TvVoiceManager.readDelimitedMessage(serverIn)
            assertTrue(recorderStarted.get())
            assertTrue(payloadMsg.hasRemoteVoicePayload())
            assertEquals(42, payloadMsg.remoteVoicePayload.sessionId)
            assertEquals(9600, payloadMsg.remoteVoicePayload.samples.size())

            // Step 5: ACTION_UP -> stop recording and send RemoteVoiceEnd(session_id = 42)
            manager.onReleaseUp()
            val endMsg = TvVoiceManager.readDelimitedMessage(serverIn)
            assertTrue(recorderStopped.get())
            assertTrue(endMsg.hasRemoteVoiceEnd())
            assertEquals(42, endMsg.remoteVoiceEnd.sessionId)

            waitUntil(1_500L) { manager.state.value == TvVoiceManager.State.IDLE }
            assertTrue(events.any { it is TvVoiceManager.VoiceEvent.SessionStarted && it.sessionId == 42 })
            assertTrue(events.any { it is TvVoiceManager.VoiceEvent.SessionEnded && it.sessionId == 42 })
        } finally {
            manager.release()
            scope.cancel()
            clientOut.close()
            serverIn.close()
        }
    }

    @Test
    fun `early release or invalid negative session id never emits -1 or synthetic RemoteVoiceEnd`() {
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
            pcmRecorder = TvVoiceManager.VoicePcmRecorder { _, _, _ -> },
            voiceBeginTimeoutMs = 1_000L,
            onEvent = { events.add(it) }
        )

        try {
            // 1) Release before TV responds -> sends SEARCH only, never sends RemoteVoiceEnd(-1)
            manager.onPressDown(scope)
            waitUntil(1_000L) { sentMessages.isNotEmpty() }
            manager.onReleaseUp()
            waitUntil(1_000L) { manager.state.value == TvVoiceManager.State.IDLE }
            assertEquals(1, sentMessages.size)
            assertTrue(sentMessages[0].hasRemoteKeyInject())
            assertFalse(sentMessages.any { it.hasRemoteVoiceEnd() || it.hasRemoteVoiceBegin() })

            // 2) Negative session_id (-1) from TV is rejected immediately without streaming
            sentMessages.clear()
            events.clear()
            manager.onPressDown(scope)
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
