package com.example

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.TvRemoteRepository
import com.example.network.AdbKeyManager
import com.example.network.ClientCertStore
import com.example.network.NetworkAdbClient
import com.example.network.ProtobufWire
import com.example.network.TvDiscoveryManager
import com.example.network.TvVoiceManager
import com.example.proto.RemoteDirection
import com.example.proto.RemoteKeyCode
import com.example.proto.RemoteMessage
import com.example.proto.RemoteVoiceBegin
import com.example.ui.RemoteViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemoteViewModelVoiceRobolectricTest {

    @Test
    fun `RemoteViewModel tap-to-talk pre-captures, responds RemoteVoiceBegin with config, and captures tail before RemoteVoiceEnd`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = ViewModelStore()

        try {
            val repository = TvRemoteRepository(db)
            runBlocking { repository.ensureDefaultsInitialized() }
            val factory = RemoteViewModel.Factory(
                repository = repository,
                certStore = ClientCertStore(context),
                discoveryManager = TvDiscoveryManager(context),
                adbClient = NetworkAdbClient(AdbKeyManager(context.filesDir))
            )
            val viewModel = ViewModelProvider(store, factory)[RemoteViewModel::class.java]
            shadowOf(Looper.getMainLooper()).idle()

            val sentFrames = CopyOnWriteArrayList<ByteArray>()
            val captureStopped = AtomicBoolean(false)
            val preCaptureDelivered = AtomicBoolean(false)

            viewModel.configureRemoteClientForTest(connected = true) { bytes ->
                sentFrames.add(bytes)
            }
            viewModel.configureVoiceCaptureForTest { _, isRunning, onFrame ->
                try {
                    // Emit 6 * 1600 = 9600 B during pre-capture before TV RemoteVoiceBegin arrives
                    repeat(6) {
                        if (!isRunning()) return@configureVoiceCaptureForTest
                        onFrame(ByteArray(1600) { 0x2A })
                    }
                    preCaptureDelivered.set(true)
                    while (isRunning()) {
                        delay(15)
                    }
                } finally {
                    captureStopped.set(true)
                }
            }

            // 1. 1st tap starts pre-capture immediately and sends KEYCODE_SEARCH(84)
            viewModel.toggleTapToTalkVoiceSession()
            waitUntil(2_000L) { sentFrames.isNotEmpty() && preCaptureDelivered.get() }
            val firstMsg = TvVoiceManager.readDelimitedMessage(ByteArrayInputStream(sentFrames[0]))
            assertTrue(firstMsg.hasRemoteKeyInject())
            assertEquals(RemoteKeyCode.KEYCODE_SEARCH, firstMsg.remoteKeyInject.keyCode)
            assertEquals(RemoteDirection.SHORT, firstMsg.remoteKeyInject.direction)
            assertEquals(1, sentFrames.size)

            // 2. TV replies RemoteVoiceBegin(session_id = 77)
            runBlocking {
                val tvBegin = RemoteMessage.newBuilder()
                    .setRemoteVoiceBegin(
                        RemoteVoiceBegin.newBuilder()
                            .setSessionId(77)
                            .setPackageName("com.google.android.katniss")
                            .build()
                    )
                    .build()
                    .toByteArray()
                viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(tvBegin))
            }

            // 3 & 4. Client echoes RemoteVoiceBegin(77, config={8000,1,2}) and flushes pre-captured RemoteVoicePayload(77)
            waitUntil(2_500L) {
                sentFrames.any { bytes ->
                    TvVoiceManager.readDelimitedMessage(ByteArrayInputStream(bytes)).hasRemoteVoicePayload()
                }
            }
            val parsedMessages = sentFrames.map {
                TvVoiceManager.readDelimitedMessage(ByteArrayInputStream(it))
            }
            val replyBegin = parsedMessages.first { it.hasRemoteVoiceBegin() }.remoteVoiceBegin
            assertEquals(77, replyBegin.sessionId)
            assertEquals(8000, replyBegin.voiceConfig.sampleRate)
            assertEquals(1, replyBegin.voiceConfig.channelConfig)
            assertEquals(2, replyBegin.voiceConfig.audioFormat)

            val payload = parsedMessages.first { it.hasRemoteVoicePayload() }.remoteVoicePayload
            assertEquals(77, payload.sessionId)
            assertEquals(9600, payload.samples.size())

            // 5. 2nd tap enters STOPPING tail delay, stops recorder, and sends RemoteVoiceEnd(77)
            viewModel.toggleTapToTalkVoiceSession()
            waitUntil(2_500L) {
                captureStopped.get() && sentFrames.any { bytes ->
                    TvVoiceManager.readDelimitedMessage(ByteArrayInputStream(bytes)).hasRemoteVoiceEnd()
                }
            }
            val endMsg = TvVoiceManager.readDelimitedMessage(ByteArrayInputStream(sentFrames.last()))
            assertTrue(endMsg.hasRemoteVoiceEnd())
            assertEquals(77, endMsg.remoteVoiceEnd.sessionId)
            assertFalse(viewModel.isVoiceListening.value)
        } finally {
            store.clear()
            shadowOf(Looper.getMainLooper()).idle()
            db.close()
        }
    }

    @Test
    fun `toggleTapToTalkVoiceSession auto-stops after inactivity timeout when no user speech occurs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = ViewModelStore()

        try {
            val repository = TvRemoteRepository(db)
            runBlocking { repository.ensureDefaultsInitialized() }
            val factory = RemoteViewModel.Factory(
                repository = repository,
                certStore = ClientCertStore(context),
                discoveryManager = TvDiscoveryManager(context),
                adbClient = NetworkAdbClient(AdbKeyManager(context.filesDir))
            )
            val viewModel = ViewModelProvider(store, factory)[RemoteViewModel::class.java]
            viewModel.voiceNoActivityTimeoutMs = 260L
            shadowOf(Looper.getMainLooper()).idle()

            val sentFrames = CopyOnWriteArrayList<ByteArray>()
            val captureStopped = AtomicBoolean(false)

            viewModel.configureRemoteClientForTest(connected = true) { bytes ->
                sentFrames.add(bytes)
            }
            viewModel.configureVoiceCaptureForTest { _, isRunning, onFrame ->
                try {
                    while (isRunning()) {
                        // Emit quiet room noise (0x0202 = 514 < SPEECH_PEAK_THRESHOLD = 2000)
                        onFrame(ByteArray(1600) { 0x02 })
                        delay(40)
                    }
                } finally {
                    captureStopped.set(true)
                }
            }

            viewModel.toggleTapToTalkVoiceSession()
            waitUntil(1_500L) { sentFrames.isNotEmpty() && viewModel.isVoiceListening.value }

            runBlocking {
                val tvBegin = RemoteMessage.newBuilder()
                    .setRemoteVoiceBegin(
                        RemoteVoiceBegin.newBuilder()
                            .setSessionId(88)
                            .setPackageName("com.google.android.katniss")
                            .build()
                    )
                    .build()
                    .toByteArray()
                viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(tvBegin))
            }

            // Without a 2nd tap, microphone must automatically deactivate after inactivity timeout
            waitUntil(2_500L) {
                !viewModel.isVoiceListening.value &&
                    captureStopped.get() &&
                    sentFrames.any { bytes ->
                        TvVoiceManager.readDelimitedMessage(ByteArrayInputStream(bytes)).hasRemoteVoiceEnd()
                    }
            }
            assertEquals("음성 입력이 없어 3초 후 마이크를 종료했습니다.", viewModel.snackbarNotice.value?.message)
        } finally {
            store.clear()
            shadowOf(Looper.getMainLooper()).idle()
            db.close()
        }
    }

    @Test
    fun `Xiaomi TV A Pro Katniss fake IME status does not block ADB GLOBAL_SEARCH and editable field skips SEARCH`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = ViewModelStore()

        try {
            val repository = TvRemoteRepository(db)
            runBlocking { repository.ensureDefaultsInitialized() }
            val factory = RemoteViewModel.Factory(
                repository = repository,
                certStore = ClientCertStore(context),
                discoveryManager = TvDiscoveryManager(context),
                adbClient = NetworkAdbClient(AdbKeyManager(context.filesDir))
            )
            val viewModel = ViewModelProvider(store, factory)[RemoteViewModel::class.java]
            viewModel.voiceNoActivityTimeoutMs = 260L
            shadowOf(Looper.getMainLooper()).idle()

            val sentFrames = CopyOnWriteArrayList<ByteArray>()
            val adbCommands = CopyOnWriteArrayList<String>()
            viewModel.configureRemoteClientForTest(connected = true) { bytes ->
                sentFrames.add(bytes)
            }
            viewModel.configureAdbClientForTest(connected = true) { cmd ->
                adbCommands.add(cmd)
                Result.success("Starting: Intent")
            }

            // 1. Xiaomi TV A Pro Katniss reports RemoteImeKeyInject(20) + RemoteImeShowRequest(22) for com.google.android.katniss
            runBlocking {
                val katnissIme = ProtobufWire.encode {
                    writeMessageField(20) {
                        writeMessageField(1) {
                            writeVarintField(1, 3)
                            writeStringField(12, "com.google.android.katniss")
                        }
                        writeMessageField(2) {
                            writeVarintField(1, 10)
                            writeVarintField(5, 1)
                        }
                    }
                    writeMessageField(22) {
                        writeMessageField(2) {
                            writeVarintField(1, 10)
                            writeVarintField(5, 1)
                        }
                    }
                }
                viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(katnissIme))
            }

            // Simulate SpeechRecognizer partial text -> endOfSpeech -> delayed final result past 260ms
            viewModel.onVoiceFallbackStarted()
            viewModel.reportVoiceSpeechStarted()
            viewModel.reportVoiceTextActivity("넷플릭스 오징어게임")
            viewModel.reportVoiceEndOfSpeech()
            shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
            Thread.sleep(350)
            shadowOf(Looper.getMainLooper()).idle()

            // Now deliver final SpeechRecognizer result: must NOT treat Katniss as an editable EditText,
            // and must execute ADB GLOBAL_SEARCH with the spoken query!
            viewModel.onVoiceSearchRecognized("넷플릭스 오징어게임")
            waitUntil(3_500L) {
                adbCommands.any { it.contains("android.search.action.GLOBAL_SEARCH") } &&
                    viewModel.snackbarNotice.value != null
            }
            val globalSearchCmd = adbCommands.first { it.contains("android.search.action.GLOBAL_SEARCH") }
            assertTrue(globalSearchCmd.contains("--es query '넷플릭스 오징어게임'"))
            assertEquals("\"넷플릭스 오징어게임\" 어시스턴트 음성 검색 실행", viewModel.snackbarNotice.value?.message)

            // 2. When a real editable app search field (e.g. YouTube) is active, tapping Mic starts phone STT directly
            //    without sending KEYCODE_SEARCH or KEYCODE_VOICE_ASSIST (preserving focus in the search box)
            sentFrames.clear()
            adbCommands.clear()
            runBlocking {
                val youtubeIme = ProtobufWire.encode {
                    writeMessageField(20) {
                        writeMessageField(1) {
                            writeVarintField(1, 4)
                            writeStringField(12, "com.google.android.youtube.tv")
                        }
                        writeMessageField(2) {
                            writeVarintField(1, 11)
                            writeVarintField(5, 1)
                        }
                    }
                    writeMessageField(22) {
                        writeMessageField(2) {
                            writeVarintField(1, 11)
                            writeVarintField(5, 1)
                        }
                    }
                }
                viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(youtubeIme))
            }

            viewModel.toggleTapToTalkVoiceSession()
            shadowOf(Looper.getMainLooper()).idle()
            // Must NOT send KEYCODE_SEARCH(84) or KEYCODE_VOICE_ASSIST(231) when already inside an editable field
            assertTrue(sentFrames.isEmpty())

            viewModel.onVoiceSearchRecognized("아이유 콘서트")
            waitUntil(3_000L) {
                sentFrames.any { bytes ->
                    val fields = ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(bytes)))
                    fields.any { it.number == 21 }
                }
            }
            val batchFrame = sentFrames.map {
                ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(it)))
            }.first { f -> f.any { it.number == 21 } }
            val textSent = batchFrame.first { it.number == 21 }.asMessage()
                .first { it.number == 3 }.asMessage()
                .first { it.number == 2 }.asMessage()
                .first { it.number == 3 }.asString()
            assertEquals("아이유 콘서트", textSent)
        } finally {
            store.clear()
            shadowOf(Looper.getMainLooper()).idle()
            db.close()
        }
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val mainLooperShadow = shadowOf(Looper.getMainLooper())
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!predicate() && SystemClock.uptimeMillis() < deadline) {
            mainLooperShadow.idleFor(25, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        mainLooperShadow.idle()
        assertTrue("Timed out waiting for Tap-to-Talk condition", predicate())
    }
}
