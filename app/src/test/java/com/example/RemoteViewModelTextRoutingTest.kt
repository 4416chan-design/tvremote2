package com.example

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.TvRemoteRepository
import com.example.data.model.TvImeKeycodeMap
import com.example.data.model.TvImeType
import com.example.data.model.TvKeyboardLanguage
import com.example.data.model.TvKeyCodes
import com.example.network.AdbKeyManager
import com.example.network.AndroidTvRemoteClient
import com.example.network.ClientCertStore
import com.example.network.NetworkAdbClient
import com.example.network.ProtobufWire
import com.example.network.TvDiscoveryManager
import com.example.ui.RemoteViewModel
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
            viewModel.sendKeyboardBackspace(1)
            waitUntil(timeoutMs = 2_000) {
                sentChunks.size >= 1
            }

            val delKey = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[0]))
            ).first { it.number == 10 }.asMessage()
            assertEquals(TvKeyCodes.DEL.toLong(), delKey.first { it.number == 1 }.varint)
            assertEquals(AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), delKey.first { it.number == 2 }.varint)
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `empty clear sends empty RemoteImeBatchEdit and empty submit also clears before ENTER`() {
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

            // Pressing "전송" on an empty box (submitWithEnter = true): clears first then emits KEYCODE_ENTER
            viewModel.sendKeyboardText("", replaceExisting = true, submitWithEnter = true)
            waitUntil(timeoutMs = 2_000) { sentChunks.size >= 2 }
            assertEquals(2, sentChunks.size)
            val emptyBatch = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[0]))
            ).first { it.number == 21 }.asMessage()
            assertEquals(
                "",
                emptyBatch.first { it.number == 3 }.asMessage()
                    .first { it.number == 2 }.asMessage()
                    .first { it.number == 3 }.asString()
            )
            val enterFields = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[1]))
            )
            val keyMsg = enterFields.first { it.number == 10 }.asMessage()
            assertEquals(TvKeyCodes.ENTER.toLong(), keyMsg.first { it.number == 1 }.varint)
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `raw ADB fallback still clears before Enter on empty Send and routes Backspace to DEL`() {
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
            val adbCommands = CopyOnWriteArrayList<String>()
            viewModel.configureRemoteClientForTest(connected = true) {
                throw java.io.IOException("simulated Protocol v2 write drop")
            }
            viewModel.configureAdbClientForTest(connected = true) { cmd ->
                adbCommands.add(cmd)
                Result.success("")
            }

            viewModel.sendKeyboardText("", replaceExisting = true, submitWithEnter = true)
            waitUntil(timeoutMs = 3_000) {
                adbCommands.any { it.contains("input keyevent 123") }
            }
            val clearEnterCmd = adbCommands.first { it.contains("input keyevent 123") }
            assertTrue(clearEnterCmd.contains("67"))
            assertTrue(clearEnterCmd.contains("input keyevent 66 2>&1"))

            adbCommands.clear()
            viewModel.sendKeyboardBackspace(1)
            waitUntil(timeoutMs = 2_000) {
                adbCommands.any { it.contains("input keyevent 67") }
            }
            assertTrue(adbCommands.first { it.contains("input keyevent 67") }.contains("input keyevent 67 2>&1"))
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `tv IME detection syncs initial language and physical keys plus toggleLanguage emit correct keycodes`() {
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
            viewModel.configureRemoteClientForTest(connected = true) { protocolSent.add(it) }

            var imeDump = """
                CUR_IME=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
                CUR_SUB=777
                SYS_LOC=ko-KR
                ---CUR---
                mCurrentSubtype=InputMethodSubtype@1 mSubtypeLocale=ko_KR
                ---SUB---
                ---SVC---
            """.trimIndent()
            viewModel.configureAdbClientForTest(connected = true) {
                Result.success(imeDump)
            }

            kotlinx.coroutines.runBlocking {
                viewModel.refreshTvImeStatusForTest()
            }

            assertEquals(TvKeyboardLanguage.KOREAN, viewModel.keyboardLanguage.value)
            assertEquals(TvImeType.GBOARD, viewModel.tvImeStatus.value.imeType)
            assertTrue(viewModel.tvImeStatus.value.isDetected)

            // Pressing ㅂ (id="q") sends KEYCODE_Q (45) without Shift
            viewModel.sendPhysicalKey(TvImeKeycodeMap.findSpecById("q")!!, shift = false)
            waitUntil(timeoutMs = 2_000) { protocolSent.size >= 1 }
            val keyMsg = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(protocolSent[0]))
            ).first { it.number == 10 }.asMessage()
            assertEquals(TvKeyCodes.KEYCODE_Q.toLong(), keyMsg.first { it.number == 1 }.varint)
            assertEquals(AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyMsg.first { it.number == 2 }.varint)

            // Toggling language on Gboard switches to ENGLISH and sends KEYCODE_LANGUAGE_SWITCH (204)
            protocolSent.clear()
            viewModel.toggleKeyboardLanguage()
            assertEquals(TvKeyboardLanguage.ENGLISH, viewModel.keyboardLanguage.value)
            waitUntil(timeoutMs = 2_000) { protocolSent.size >= 1 }
            val switchMsg = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(protocolSent[0]))
            ).first { it.number == 10 }.asMessage()
            assertEquals(TvKeyCodes.LANGUAGE_SWITCH.toLong(), switchMsg.first { it.number == 1 }.varint)

            // Shifted Q sends SHIFT_LEFT START -> Q SHORT -> SHIFT_LEFT END
            protocolSent.clear()
            viewModel.sendPhysicalKey(TvImeKeycodeMap.findSpecById("q")!!, shift = true)
            waitUntil(timeoutMs = 2_000) { protocolSent.size >= 3 }
            fun codeAt(i: Int): Long = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(protocolSent[i]))
            ).first { it.number == 10 }.asMessage().first { it.number == 1 }.varint
            assertEquals(TvKeyCodes.SHIFT_LEFT.toLong(), codeAt(0))
            assertEquals(TvKeyCodes.KEYCODE_Q.toLong(), codeAt(1))
            assertEquals(TvKeyCodes.SHIFT_LEFT.toLong(), codeAt(2))

            // Resync onto Google Korean IME -> switching language uses SHIFT_LEFT + SPACE
            imeDump = """
                CUR_IME=com.google.android.inputmethod.korean/.KoreanIME
                CUR_SUB=
                SYS_LOC=ko-KR
                ---CUR---
                ---SUB---
                ---SVC---
                mHangul=true
            """.trimIndent()
            viewModel.resyncTvImeLanguage()
            waitUntil(timeoutMs = 2_000) {
                viewModel.tvImeStatus.value.imeType == TvImeType.GOOGLE_KOREAN &&
                    viewModel.keyboardLanguage.value == TvKeyboardLanguage.KOREAN
            }

            protocolSent.clear()
            viewModel.toggleKeyboardLanguage()
            assertEquals(TvKeyboardLanguage.ENGLISH, viewModel.keyboardLanguage.value)
            waitUntil(timeoutMs = 2_000) { protocolSent.size >= 3 }
            assertEquals(TvKeyCodes.SHIFT_LEFT.toLong(), codeAt(0))
            assertEquals(TvKeyCodes.SPACE.toLong(), codeAt(1))
            assertEquals(TvKeyCodes.SHIFT_LEFT.toLong(), codeAt(2))
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `clearKeyboardWithSelectAllDelete sends Ctrl+A then DEL over Protocol v2 and falls back to ADB`() {
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
            viewModel.configureRemoteClientForTest(connected = true) { protocolSent.add(it) }

            viewModel.clearKeyboardWithSelectAllDelete()
            waitUntil(timeoutMs = 2_000) { protocolSent.size >= 4 }

            fun keyAndDirAt(i: Int): Pair<Long, Long> {
                val msg = ProtobufWire.parseFields(
                    ProtobufWire.readFramedMessage(ByteArrayInputStream(protocolSent[i]))
                ).first { it.number == 10 }.asMessage()
                return msg.first { it.number == 1 }.varint to msg.first { it.number == 2 }.varint
            }
            assertEquals(TvKeyCodes.CTRL_LEFT.toLong() to AndroidTvRemoteClient.DIRECTION_START_LONG.toLong(), keyAndDirAt(0))
            assertEquals(TvKeyCodes.KEYCODE_A.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyAndDirAt(1))
            assertEquals(TvKeyCodes.CTRL_LEFT.toLong() to AndroidTvRemoteClient.DIRECTION_END_LONG.toLong(), keyAndDirAt(2))
            assertEquals(TvKeyCodes.DEL.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyAndDirAt(3))

            // ADB fallback when Protocol v2 write drops
            val adbCommands = CopyOnWriteArrayList<String>()
            viewModel.configureRemoteClientForTest(connected = true) {
                throw java.io.IOException("simulated Protocol v2 write drop")
            }
            viewModel.configureAdbClientForTest(connected = true) { cmd ->
                adbCommands.add(cmd)
                Result.success("")
            }
            viewModel.clearKeyboardWithSelectAllDelete()
            waitUntil(timeoutMs = 3_000) {
                adbCommands.any { it.contains("input keycombination ${TvKeyCodes.CTRL_LEFT} ${TvKeyCodes.KEYCODE_A}") }
            }
            val cmd = adbCommands.first { it.contains("input keycombination") }
            assertTrue(cmd.contains("input keyevent ${TvKeyCodes.DEL}"))
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `room migration 2 to 4 removes hasPromptedAdbKeyboardSetup while preserving paired tvs and prefs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_2_4_test_${System.nanoTime()}.db"
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
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        try {
            kotlinx.coroutines.runBlocking {
                val repo = TvRemoteRepository(upgradedDb)
                val tv = repo.getMostRecentTv()
                assertEquals("192.168.1.55", tv?.host)
                val prefs = repo.getPreferencesOnce()
                assertEquals(75, prefs.brightnessPercent)
                assertTrue(prefs.hapticEnabled)
                assertTrue(prefs.autoShowKeyboard)
            }
            val columns = mutableListOf<String>()
            upgradedDb.openHelper.readableDatabase.query("PRAGMA table_info(`app_preferences`)").use { cursor ->
                val nameCol = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameCol))
                }
            }
            assertFalse(columns.contains("hasPromptedAdbKeyboardSetup"))
            assertTrue(columns.containsAll(listOf("id", "hapticEnabled", "autoShowKeyboard", "brightnessPercent")))
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
