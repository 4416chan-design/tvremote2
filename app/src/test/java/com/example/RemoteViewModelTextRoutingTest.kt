package com.example

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.TvRemoteRepository
import com.example.data.model.TvKeyCodes
import com.example.network.AdbKeyManager
import com.example.network.AndroidTvRemoteClient
import com.example.network.ClientCertStore
import com.example.network.NetworkAdbClient
import com.example.network.ProtobufWire
import com.example.network.TvDiscoveryManager
import com.example.ui.RemoteViewModel
import kotlinx.coroutines.launch
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemoteViewModelTextRoutingTest {

    @Test
    fun `sendKeyboardText sends Korean and ASCII over Protocol v2 even when TV IME flag is inactive`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = ViewModelStore()

        try {
            val factory = RemoteViewModel.Factory(
                repository = TvRemoteRepository(db),
                certStore = ClientCertStore(context),
                discoveryManager = TvDiscoveryManager(context),
                adbClient = NetworkAdbClient(AdbKeyManager(context.filesDir))
            )
            val viewModel = ViewModelProvider(store, factory)[RemoteViewModel::class.java]

            val sentChunks = CopyOnWriteArrayList<ByteArray>()
            viewModel.configureRemoteClientForTest(connected = true) { bytes ->
                sentChunks.add(bytes)
            }

            // Simulate YouTube-style app where TV never emitted RemoteImeShowRequest(22)
            assertFalse(viewModel.imePromptState.value.isTvImeVisible)

            viewModel.sendKeyboardText("bts", replaceExisting = true, submitWithEnter = true)
            waitUntil(timeoutMs = 3_000) {
                sentChunks.size >= 2 && viewModel.snackbarNotice.value != null
            }

            val firstBatch = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[0]))
            ).first { it.number == 21 }.asMessage()
            assertEquals(0L, firstBatch.first { it.number == 1 }.varint)
            assertEquals(0L, firstBatch.first { it.number == 2 }.varint)
            val firstObj = firstBatch.first { it.number == 3 }.asMessage().first { it.number == 2 }.asMessage()
            assertEquals("bts", firstObj.first { it.number == 3 }.asString())
            assertEquals(3L, firstObj.first { it.number == 1 }.varint)

            val firstEnter = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[1]))
            ).first { it.number == 10 }.asMessage()
            assertEquals(TvKeyCodes.ENTER.toLong(), firstEnter.first { it.number == 1 }.varint)
            assertEquals("TV로 입력을 전송했습니다.", viewModel.snackbarNotice.value?.message)

            sentChunks.clear()
            viewModel.clearSnackbar()

            // Korean UTF-8 text must also succeed directly via RemoteImeBatchEdit without false error
            viewModel.sendKeyboardText("유튜브 검색", replaceExisting = true, submitWithEnter = true)
            waitUntil(timeoutMs = 3_000) {
                sentChunks.size >= 2 && viewModel.snackbarNotice.value != null
            }

            val koBatch = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[0]))
            ).first { it.number == 21 }.asMessage()
            val koObj = koBatch.first { it.number == 3 }.asMessage().first { it.number == 2 }.asMessage()
            assertEquals("유튜브 검색", koObj.first { it.number == 3 }.asString())
            assertEquals(6L, koObj.first { it.number == 1 }.varint)
            assertEquals(6L, koObj.first { it.number == 2 }.varint)
            assertEquals("TV로 입력을 전송했습니다.", viewModel.snackbarNotice.value?.message)

            sentChunks.clear()
            viewModel.sendKeyboardChars("가나")
            viewModel.sendKeyboardBackspace(1)
            waitUntil(timeoutMs = 2_000) {
                sentChunks.size >= 2
            }

            val charsBatch = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[0]))
            ).first { it.number == 21 }.asMessage()
            assertEquals(
                "가나",
                charsBatch.first { it.number == 3 }.asMessage()
                    .first { it.number == 2 }.asMessage()
                    .first { it.number == 3 }.asString()
            )
            val delKey = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[1]))
            ).first { it.number == 10 }.asMessage()
            assertEquals(TvKeyCodes.DEL.toLong(), delKey.first { it.number == 1 }.varint)
            assertEquals(AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), delKey.first { it.number == 2 }.varint)
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `empty clear sends empty RemoteImeBatchEdit while empty submit sends ENTER only`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = ViewModelStore()

        try {
            val factory = RemoteViewModel.Factory(
                repository = TvRemoteRepository(db),
                certStore = ClientCertStore(context),
                discoveryManager = TvDiscoveryManager(context),
                adbClient = NetworkAdbClient(AdbKeyManager(context.filesDir))
            )
            val viewModel = ViewModelProvider(store, factory)[RemoteViewModel::class.java]
            val sentChunks = CopyOnWriteArrayList<ByteArray>()
            viewModel.configureRemoteClientForTest(connected = true) { bytes ->
                sentChunks.add(bytes)
            }

            // "전체 지우기" (submitWithEnter = false): emits empty RemoteImeBatchEdit
            viewModel.sendKeyboardText("", replaceExisting = true, submitWithEnter = false)
            waitUntil(timeoutMs = 2_000) { sentChunks.size >= 1 }
            val clearFields = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[0]))
            )
            assertFalse(clearFields.any { it.number == 20 })
            val clearObj = clearFields.first { it.number == 21 }.asMessage()
                .first { it.number == 3 }.asMessage()
                .first { it.number == 2 }.asMessage()
            assertEquals("", clearObj.first { it.number == 3 }.asString())

            sentChunks.clear()

            // Pressing "전송" on an empty box (submitWithEnter = true): emits KEYCODE_ENTER only
            viewModel.sendKeyboardText("", replaceExisting = true, submitWithEnter = true)
            waitUntil(timeoutMs = 2_000) { sentChunks.size >= 1 }
            assertEquals(1, sentChunks.size)
            val enterFields = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[0]))
            )
            assertFalse(enterFields.any { it.number == 21 })
            val keyMsg = enterFields.first { it.number == 10 }.asMessage()
            assertEquals(TvKeyCodes.ENTER.toLong(), keyMsg.first { it.number == 1 }.varint)
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `adbKeyboard installed routes Send through ADB_CLEAR_TEXT and ADB_INPUT_TEXT or B64 and Enter`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = ViewModelStore()

        try {
            val factory = RemoteViewModel.Factory(
                repository = TvRemoteRepository(db),
                certStore = ClientCertStore(context),
                discoveryManager = TvDiscoveryManager(context),
                adbClient = NetworkAdbClient(AdbKeyManager(context.filesDir))
            )
            val viewModel = ViewModelProvider(store, factory)[RemoteViewModel::class.java]

            val protocolSent = CopyOnWriteArrayList<ByteArray>()
            val adbCommands = CopyOnWriteArrayList<String>()
            viewModel.configureRemoteClientForTest(connected = true) { protocolSent.add(it) }
            viewModel.configureAdbClientForTest(connected = true) { cmd ->
                adbCommands.add(cmd)
                if (cmd.contains("pm path com.android.adbkeyboard")) {
                    Result.success("ADBKB=INSTALLED")
                } else {
                    Result.success("Broadcasting: Intent { act=... }\nBroadcast completed: result=0")
                }
            }

            // ASCII Send: uses ADB_CLEAR_TEXT + ADB_INPUT_TEXT + ENTER instead of Protocol v2
            viewModel.sendKeyboardText("bts", replaceExisting = true, submitWithEnter = true)
            waitUntil(timeoutMs = 3_000) {
                adbCommands.any { it.contains("ADB_INPUT_TEXT") } && viewModel.snackbarNotice.value != null
            }
            assertEquals(0, protocolSent.size)
            val asciiSendCmd = adbCommands.first { it.contains("ADB_INPUT_TEXT") }
            assertTrue(asciiSendCmd.contains("am broadcast -a ADB_CLEAR_TEXT 2>&1"))
            assertTrue(asciiSendCmd.contains("am broadcast -a ADB_INPUT_TEXT --es msg 'bts' 2>&1"))
            assertTrue(asciiSendCmd.contains("input keyevent 66 2>&1"))
            assertEquals("ADBKeyBoard로 입력을 전송했습니다.", viewModel.snackbarNotice.value?.message)

            adbCommands.clear()
            viewModel.clearSnackbar()

            // Korean Send: uses ADB_CLEAR_TEXT + ADB_INPUT_B64 + ENTER (per ADBKeyBoard README 1.1)
            viewModel.sendKeyboardText("유튜브 검색", replaceExisting = true, submitWithEnter = true)
            waitUntil(timeoutMs = 3_000) {
                adbCommands.any { it.contains("ADB_INPUT_B64") } && viewModel.snackbarNotice.value != null
            }
            assertEquals(0, protocolSent.size)
            val expectedB64 = AdbKeyManager.encodeBase64("유튜브 검색".toByteArray(Charsets.UTF_8))
            val koSendCmd = adbCommands.first { it.contains("ADB_INPUT_B64") }
            assertTrue(koSendCmd.contains("am broadcast -a ADB_CLEAR_TEXT 2>&1"))
            assertTrue(koSendCmd.contains("am broadcast -a ADB_INPUT_B64 --es msg '$expectedB64' 2>&1"))
            assertTrue(koSendCmd.contains("input keyevent 66 2>&1"))

            adbCommands.clear()

            // Clear button: ADB_CLEAR_TEXT only (no Enter)
            viewModel.sendKeyboardText("", replaceExisting = true, submitWithEnter = false)
            waitUntil(timeoutMs = 2_000) { adbCommands.any { it.contains("ADB_CLEAR_TEXT") } }
            val clearCmd = adbCommands.first()
            assertTrue(clearCmd.contains("am broadcast -a ADB_CLEAR_TEXT 2>&1"))
            assertFalse(clearCmd.contains("input keyevent 66"))

            // Live mode typing with space emits physical KEYCODE_SPACE over Protocol v2
            viewModel.sendKeyboardChars("ab cd")
            waitUntil(timeoutMs = 2_000) { protocolSent.size >= 3 }
            val midFrame = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(protocolSent[1]))
            ).first { it.number == 10 }.asMessage()
            assertEquals(TvKeyCodes.SPACE.toLong(), midFrame.first { it.number == 1 }.varint)
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `first connect when ADBKeyBoard missing emits v2_4_dev URL and opens setup dialog only once`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = ViewModelStore()

        try {
            val repo = TvRemoteRepository(db)
            val factory = RemoteViewModel.Factory(
                repository = repo,
                certStore = ClientCertStore(context),
                discoveryManager = TvDiscoveryManager(context),
                adbClient = NetworkAdbClient(AdbKeyManager(context.filesDir))
            )
            val viewModel = ViewModelProvider(store, factory)[RemoteViewModel::class.java]
            val openedUrls = CopyOnWriteArrayList<String>()

            val collectJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
                viewModel.openUrlRequests.collect { url -> openedUrls.add(url) }
            }

            viewModel.configureAdbClientForTest(connected = true) { cmd ->
                if (cmd.contains("pm path com.android.adbkeyboard")) {
                    Result.success("ADBKB=MISSING")
                } else {
                    Result.success("")
                }
            }

            kotlinx.coroutines.runBlocking {
                viewModel.checkAdbKeyboardOnConnectForTest()
            }

            assertEquals(false, viewModel.adbKeyboardInstalled.value)
            assertTrue(viewModel.isAdbKeyboardSetupDialogVisible.value)
            assertEquals(listOf(NetworkAdbClient.ADB_KEYBOARD_APK_URL), openedUrls)
            assertTrue(kotlinx.coroutines.runBlocking { repo.getPreferencesOnce().hasPromptedAdbKeyboardSetup })

            // Subsequent connects do not auto-open browser repeatedly once dismissed
            viewModel.dismissAdbKeyboardSetupDialog()
            kotlinx.coroutines.runBlocking {
                viewModel.checkAdbKeyboardOnConnectForTest()
            }
            assertFalse(viewModel.isAdbKeyboardSetupDialogVisible.value)
            assertEquals(1, openedUrls.size)

            collectJob.cancel()
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `room migration 2 to 3 adds hasPromptedAdbKeyboardSetup without wiping paired tvs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_2_3_test_${System.nanoTime()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val rawHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `paired_tvs` (`host` TEXT NOT NULL, `name` TEXT NOT NULL, `remotePort` INTEGER NOT NULL, `pairingPort` INTEGER NOT NULL, `lastConnectedAt` INTEGER NOT NULL, PRIMARY KEY(`host`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `favorite_apps` (`packageName` TEXT NOT NULL, `appName` TEXT NOT NULL, `launchUri` TEXT NOT NULL, `iconKey` TEXT NOT NULL, `accentHex` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`packageName`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `recent_tv_apps` (`packageName` TEXT NOT NULL, `appName` TEXT NOT NULL, `launchUri` TEXT NOT NULL, `iconKey` TEXT NOT NULL, `accentHex` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`packageName`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `custom_buttons` (`slotIndex` INTEGER NOT NULL, `label` TEXT NOT NULL, `iconKey` TEXT NOT NULL, `actionType` TEXT NOT NULL, `keyCode` INTEGER NOT NULL, `launchUri` TEXT NOT NULL, `packageName` TEXT NOT NULL, PRIMARY KEY(`slotIndex`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `app_preferences` (`id` INTEGER NOT NULL, `hapticEnabled` INTEGER NOT NULL, `autoShowKeyboard` INTEGER NOT NULL, `brightnessPercent` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("INSERT INTO `paired_tvs` VALUES ('192.168.1.55', 'Living Room TV', 6466, 6467, 1000)")
                        db.execSQL("INSERT INTO `app_preferences` VALUES (1, 1, 1, 75)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        rawHelper.writableDatabase.close()
        rawHelper.close()

        val upgradedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        try {
            kotlinx.coroutines.runBlocking {
                val repo = TvRemoteRepository(upgradedDb)
                val tv = repo.getMostRecentTv()
                assertEquals("192.168.1.55", tv?.host)
                val prefs = repo.getPreferencesOnce()
                assertEquals(75, prefs.brightnessPercent)
                assertFalse(prefs.hasPromptedAdbKeyboardSetup)
            }
        } finally {
            upgradedDb.close()
            dbFile.delete()
        }
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val mainLooperShadow = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) {
            mainLooperShadow.idleFor(25, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        mainLooperShadow.idle()
        assertTrue("Timed out waiting for async ViewModel condition", predicate())
    }
}
