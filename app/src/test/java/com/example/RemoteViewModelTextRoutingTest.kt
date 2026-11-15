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
    fun `sendKeyboardText clears with Ctrl+A DEL before sending Korean and ASCII over Protocol v2`() {
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

            fun keyAndDirAt(i: Int): Pair<Long, Long> {
                val msg = ProtobufWire.parseFields(
                    ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[i]))
                ).first { it.number == 10 }.asMessage()
                return msg.first { it.number == 1 }.varint to msg.first { it.number == 2 }.varint
            }

            // Simulate YouTube-style app where TV never emitted RemoteImeShowRequest(22)
            assertFalse(viewModel.imePromptState.value.isTvImeVisible)

            // With ADB disconnected, Send executes Ctrl+A -> DEL over Protocol v2 (5 frames) -> BatchEdit -> ENTER (7 frames)
            viewModel.sendKeyboardText("bts", replaceExisting = true, submitWithEnter = true)
            waitUntil(timeoutMs = 3_000) {
                sentChunks.size >= 7 && viewModel.snackbarNotice.value != null
            }

            assertEquals(7, sentChunks.size)
            assertEquals(TvKeyCodes.CTRL_LEFT.toLong() to AndroidTvRemoteClient.DIRECTION_START_LONG.toLong(), keyAndDirAt(0))
            assertEquals(TvKeyCodes.KEYCODE_A.toLong() to AndroidTvRemoteClient.DIRECTION_START_LONG.toLong(), keyAndDirAt(1))
            assertEquals(TvKeyCodes.KEYCODE_A.toLong() to AndroidTvRemoteClient.DIRECTION_END_LONG.toLong(), keyAndDirAt(2))
            assertEquals(TvKeyCodes.CTRL_LEFT.toLong() to AndroidTvRemoteClient.DIRECTION_END_LONG.toLong(), keyAndDirAt(3))
            assertEquals(TvKeyCodes.DEL.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyAndDirAt(4))

            val firstBatch = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[5]))
            ).first { it.number == 21 }.asMessage()
            assertEquals(0L, firstBatch.first { it.number == 1 }.varint)
            assertEquals(0L, firstBatch.first { it.number == 2 }.varint)
            val firstObj = firstBatch.first { it.number == 3 }.asMessage().first { it.number == 2 }.asMessage()
            assertEquals("bts", firstObj.first { it.number == 3 }.asString())
            assertEquals(3L, firstObj.first { it.number == 1 }.varint)

            assertEquals(TvKeyCodes.ENTER.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyAndDirAt(6))
            assertEquals("TV로 입력을 전송했습니다.", viewModel.snackbarNotice.value?.message)

            sentChunks.clear()
            viewModel.clearSnackbar()

            // Korean UTF-8 text must also clear first via Ctrl+A -> DEL then send RemoteImeBatchEdit + ENTER
            viewModel.sendKeyboardText("유튜브 검색", replaceExisting = true, submitWithEnter = true)
            waitUntil(timeoutMs = 3_000) {
                sentChunks.size >= 7 && viewModel.snackbarNotice.value != null
            }

            assertEquals(7, sentChunks.size)
            assertEquals(TvKeyCodes.DEL.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyAndDirAt(4))
            val koBatch = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[5]))
            ).first { it.number == 21 }.asMessage()
            val koObj = koBatch.first { it.number == 3 }.asMessage().first { it.number == 2 }.asMessage()
            assertEquals("유튜브 검색", koObj.first { it.number == 3 }.asString())
            assertEquals(6L, koObj.first { it.number == 1 }.varint)
            assertEquals(6L, koObj.first { it.number == 2 }.varint)
            assertEquals(TvKeyCodes.ENTER.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyAndDirAt(6))
            assertEquals("TV로 입력을 전송했습니다.", viewModel.snackbarNotice.value?.message)

            sentChunks.clear()
            viewModel.sendKeyboardBackspace(1)
            waitUntil(timeoutMs = 2_000) {
                sentChunks.size >= 1
            }

            assertEquals(TvKeyCodes.DEL.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyAndDirAt(0))
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `sendKeyboardText with ADB connected executes ADB keycombination 113 29 + DEL strictly before field text in FIFO order`() {
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
            val timeline = CopyOnWriteArrayList<String>()
            val protocolSent = CopyOnWriteArrayList<ByteArray>()
            val adbCommands = CopyOnWriteArrayList<String>()

            viewModel.configureRemoteClientForTest(connected = true) { bytes ->
                protocolSent.add(bytes)
                val fields = ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(bytes)))
                when {
                    fields.any { it.number == 10 } -> {
                        val kc = fields.first { it.number == 10 }.asMessage().first { it.number == 1 }.varint
                        timeline.add("V2_KEY:$kc")
                    }
                    fields.any { it.number == 21 } -> {
                        val txt = fields.first { it.number == 21 }.asMessage()
                            .first { it.number == 3 }.asMessage()
                            .first { it.number == 2 }.asMessage()
                            .first { it.number == 3 }.asString()
                        timeline.add("V2_BATCH:$txt")
                    }
                }
            }
            viewModel.configureAdbClientForTest(connected = true) { cmd ->
                adbCommands.add(cmd)
                timeline.add("ADB:$cmd")
                Result.success("")
            }

            // Enqueue real-time typing immediately followed by Send: must execute strictly FIFO:
            // real-time Q, W -> ADB (113 29 -> 67) -> Protocol v2 Batch("유튜브") -> ENTER
            val editQ = TvImeKeycodeMap.computeRealTimeEditActions("", 0, 0, "q", TvImeType.GBOARD)
            val editW = TvImeKeycodeMap.computeRealTimeEditActions("q", 1, 1, "qw", TvImeType.GBOARD)
            viewModel.sendRealTimeKeyboardEdit(editQ.backspaceCount, editQ.keyActions)
            viewModel.sendRealTimeKeyboardEdit(editW.backspaceCount, editW.keyActions)
            viewModel.sendKeyboardText("유튜브", replaceExisting = true, submitWithEnter = true)

            waitUntil(timeoutMs = 3_000) {
                timeline.size >= 5 && viewModel.snackbarNotice.value != null
            }

            assertEquals(1, adbCommands.size)
            assertTrue(adbCommands[0].contains("input keycombination ${TvKeyCodes.CTRL_LEFT} ${TvKeyCodes.KEYCODE_A}"))
            assertTrue(adbCommands[0].contains("input keyevent ${TvKeyCodes.DEL} 2>&1"))
            assertEquals("V2_KEY:${TvKeyCodes.KEYCODE_Q}", timeline[0])
            assertEquals("V2_KEY:${TvKeyCodes.KEYCODE_W}", timeline[1])
            assertTrue(timeline[2].startsWith("ADB:"))
            assertEquals("V2_BATCH:유튜브", timeline[3])
            assertEquals("V2_KEY:${TvKeyCodes.ENTER}", timeline[4])
            assertEquals(4, protocolSent.size)
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
            waitUntil(timeoutMs = 2_000) { sentChunks.size >= 6 }
            assertEquals(6, sentChunks.size)
            val clearFields = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[5]))
            )
            assertFalse(clearFields.any { it.number == 20 })
            val clearObj = clearFields.first { it.number == 21 }.asMessage()
                .first { it.number == 3 }.asMessage()
                .first { it.number == 2 }.asMessage()
            assertEquals("", clearObj.first { it.number == 3 }.asString())

            sentChunks.clear()

            // Pressing "전송" on an empty box (submitWithEnter = true): Ctrl+A -> DEL, empty BatchEdit, then ENTER
            viewModel.sendKeyboardText("", replaceExisting = true, submitWithEnter = true)
            waitUntil(timeoutMs = 2_000) { sentChunks.size >= 7 }
            assertEquals(7, sentChunks.size)
            val delKey = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[4]))
            ).first { it.number == 10 }.asMessage()
            assertEquals(TvKeyCodes.DEL.toLong(), delKey.first { it.number == 1 }.varint)

            val emptyBatch = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[5]))
            ).first { it.number == 21 }.asMessage()
            assertEquals(
                "",
                emptyBatch.first { it.number == 3 }.asMessage()
                    .first { it.number == 2 }.asMessage()
                    .first { it.number == 3 }.asString()
            )
            val enterFields = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[6]))
            )
            val keyMsg = enterFields.first { it.number == 10 }.asMessage()
            assertEquals(TvKeyCodes.ENTER.toLong(), keyMsg.first { it.number == 1 }.varint)
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `raw ADB fallback runs keycombination 113 29 DEL before input text and Enter and routes Backspace to DEL`() {
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

            // Empty Send in ADB fallback: Ctrl+A -> DEL first, then ENTER
            viewModel.sendKeyboardText("", replaceExisting = true, submitWithEnter = true)
            waitUntil(timeoutMs = 3_000) {
                adbCommands.size >= 2
            }
            assertTrue(adbCommands[0].contains("input keycombination ${TvKeyCodes.CTRL_LEFT} ${TvKeyCodes.KEYCODE_A}"))
            assertTrue(adbCommands[0].contains("input keyevent ${TvKeyCodes.DEL} 2>&1"))
            assertEquals("input keyevent ${TvKeyCodes.ENTER} 2>&1", adbCommands[1])

            // Non-empty ASCII Send in ADB fallback: Ctrl+A -> DEL first, then input text + ENTER without redundant 20x DEL
            adbCommands.clear()
            viewModel.sendKeyboardText("hello tv", replaceExisting = true, submitWithEnter = true)
            waitUntil(timeoutMs = 3_000) {
                adbCommands.size >= 2
            }
            assertTrue(adbCommands[0].contains("input keycombination ${TvKeyCodes.CTRL_LEFT} ${TvKeyCodes.KEYCODE_A}"))
            assertTrue(adbCommands[0].contains("input keyevent ${TvKeyCodes.DEL} 2>&1"))
            assertEquals("input text 'hello%stv' 2>&1; input keyevent ${TvKeyCodes.ENTER} 2>&1", adbCommands[1])

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
    fun `tv IME detection syncs initial language and real-time edits plus toggleLanguage emit FIFO keycodes`() {
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

            // Typing Q, W, E, R + Backspace + Korean 바 -> strictly FIFO KeyEvent packets
            fun keyAndDirAt(i: Int): Pair<Long, Long> {
                val msg = ProtobufWire.parseFields(
                    ProtobufWire.readFramedMessage(ByteArrayInputStream(protocolSent[i]))
                ).first { it.number == 10 }.asMessage()
                return msg.first { it.number == 1 }.varint to msg.first { it.number == 2 }.varint
            }

            val qwerEdit = TvImeKeycodeMap.computeRealTimeEditActions("", 0, 0, "qwer", TvImeType.GBOARD)
            viewModel.sendRealTimeKeyboardEdit(qwerEdit.backspaceCount, qwerEdit.keyActions)
            viewModel.sendKeyboardBackspace(1)
            val koEdit = TvImeKeycodeMap.computeRealTimeEditActions("ㅂ", 1, 1, "바", TvImeType.GBOARD)
            viewModel.sendRealTimeKeyboardEdit(koEdit.backspaceCount, koEdit.keyActions)
            waitUntil(timeoutMs = 2_000) { protocolSent.size >= 6 }

            assertEquals(TvKeyCodes.KEYCODE_Q.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyAndDirAt(0))
            assertEquals(TvKeyCodes.KEYCODE_W.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyAndDirAt(1))
            assertEquals(TvKeyCodes.KEYCODE_E.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyAndDirAt(2))
            assertEquals(TvKeyCodes.KEYCODE_R.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyAndDirAt(3))
            assertEquals(TvKeyCodes.DEL.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyAndDirAt(4))
            assertEquals(TvKeyCodes.KEYCODE_K.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyAndDirAt(5))

            // Toggling language on Gboard switches to ENGLISH and sends KEYCODE_LANGUAGE_SWITCH (204)
            protocolSent.clear()
            viewModel.toggleKeyboardLanguage()
            assertEquals(TvKeyboardLanguage.ENGLISH, viewModel.keyboardLanguage.value)
            waitUntil(timeoutMs = 2_000) { protocolSent.size >= 1 }
            assertEquals(
                TvKeyCodes.LANGUAGE_SWITCH.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(),
                keyAndDirAt(0)
            )

            // Shifted uppercase Q sends SHIFT_LEFT START -> Q START -> Q END -> SHIFT_LEFT END
            protocolSent.clear()
            val shiftQ = TvImeKeycodeMap.computeRealTimeEditActions("", 0, 0, "Q", TvImeType.GBOARD)
            viewModel.sendRealTimeKeyboardEdit(shiftQ.backspaceCount, shiftQ.keyActions)
            waitUntil(timeoutMs = 2_000) { protocolSent.size >= 4 }
            assertEquals(TvKeyCodes.SHIFT_LEFT.toLong() to AndroidTvRemoteClient.DIRECTION_START_LONG.toLong(), keyAndDirAt(0))
            assertEquals(TvKeyCodes.KEYCODE_Q.toLong() to AndroidTvRemoteClient.DIRECTION_START_LONG.toLong(), keyAndDirAt(1))
            assertEquals(TvKeyCodes.KEYCODE_Q.toLong() to AndroidTvRemoteClient.DIRECTION_END_LONG.toLong(), keyAndDirAt(2))
            assertEquals(TvKeyCodes.SHIFT_LEFT.toLong() to AndroidTvRemoteClient.DIRECTION_END_LONG.toLong(), keyAndDirAt(3))

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
            waitUntil(timeoutMs = 2_000) { protocolSent.size >= 4 }
            assertEquals(TvKeyCodes.SHIFT_LEFT.toLong() to AndroidTvRemoteClient.DIRECTION_START_LONG.toLong(), keyAndDirAt(0))
            assertEquals(TvKeyCodes.SPACE.toLong() to AndroidTvRemoteClient.DIRECTION_START_LONG.toLong(), keyAndDirAt(1))
            assertEquals(TvKeyCodes.SPACE.toLong() to AndroidTvRemoteClient.DIRECTION_END_LONG.toLong(), keyAndDirAt(2))
            assertEquals(TvKeyCodes.SHIFT_LEFT.toLong() to AndroidTvRemoteClient.DIRECTION_END_LONG.toLong(), keyAndDirAt(3))
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `clearKeyboardWithSelectAllDelete prefers ADB keycombination first and falls back to Protocol v2`() {
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
                Result.success("")
            }

            // When both ADB and Protocol v2 are connected, ADB runs FIRST so TV receives true CTRL+A
            viewModel.clearKeyboardWithSelectAllDelete()
            waitUntil(timeoutMs = 3_000) {
                adbCommands.any { it.contains("input keycombination ${TvKeyCodes.CTRL_LEFT} ${TvKeyCodes.KEYCODE_A}") }
            }
            val cmd = adbCommands.first { it.contains("input keycombination") }
            assertTrue(cmd.contains("input keyevent ${TvKeyCodes.DEL} 2>&1"))
            assertEquals(0, protocolSent.size)

            // When ADB is unavailable or fails, fall back to Protocol v2 5-frame Ctrl+A -> DEL
            viewModel.configureAdbClientForTest(connected = false, shellRunner = null)
            viewModel.clearKeyboardWithSelectAllDelete()
            waitUntil(timeoutMs = 2_000) { protocolSent.size >= 5 }

            fun keyAndDirAt(i: Int): Pair<Long, Long> {
                val msg = ProtobufWire.parseFields(
                    ProtobufWire.readFramedMessage(ByteArrayInputStream(protocolSent[i]))
                ).first { it.number == 10 }.asMessage()
                return msg.first { it.number == 1 }.varint to msg.first { it.number == 2 }.varint
            }
            assertEquals(TvKeyCodes.CTRL_LEFT.toLong() to AndroidTvRemoteClient.DIRECTION_START_LONG.toLong(), keyAndDirAt(0))
            assertEquals(TvKeyCodes.KEYCODE_A.toLong() to AndroidTvRemoteClient.DIRECTION_START_LONG.toLong(), keyAndDirAt(1))
            assertEquals(TvKeyCodes.KEYCODE_A.toLong() to AndroidTvRemoteClient.DIRECTION_END_LONG.toLong(), keyAndDirAt(2))
            assertEquals(TvKeyCodes.CTRL_LEFT.toLong() to AndroidTvRemoteClient.DIRECTION_END_LONG.toLong(), keyAndDirAt(3))
            assertEquals(TvKeyCodes.DEL.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyAndDirAt(4))
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

    @Test
    fun `startVoiceSession sends single SEARCH suppresses Katniss popup and streams 8KB-20KB PCM with padded tail`() {
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
            viewModel.setAutoShowKeyboard(true)
            waitUntil(timeoutMs = 2_000) { viewModel.preferences.value.autoShowKeyboard }

            val protocolSent = CopyOnWriteArrayList<ByteArray>()
            val pushedFrames = java.util.concurrent.atomic.AtomicInteger(0)
            val sendTailNow = java.util.concurrent.atomic.AtomicBoolean(false)
            val tailPushed = java.util.concurrent.atomic.AtomicBoolean(false)
            viewModel.configureRemoteClientForTest(connected = true) { protocolSent.add(it) }
            viewModel.configureVoiceCaptureForTest { _, isRunning, onFrame ->
                // Emit 6 * 1600 = 9600 bytes for initial pre-buffer
                repeat(6) { idx ->
                    if (!isRunning()) return@configureVoiceCaptureForTest
                    onFrame(ByteArray(1600) { (idx + 1).toByte() })
                    pushedFrames.incrementAndGet()
                }
                while (isRunning() && !sendTailNow.get()) {
                    kotlinx.coroutines.delay(10)
                }
                if (isRunning() && sendTailNow.get()) {
                    onFrame(ByteArray(1600) { 0x5A })
                    tailPushed.set(true)
                }
                while (isRunning()) {
                    kotlinx.coroutines.delay(10)
                }
            }

            kotlinx.coroutines.runBlocking {
                val configBytes = ProtobufWire.encode {
                    writeMessageField(1) { writeVarintField(1, 623) }
                }
                viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(configBytes))
            }
            assertEquals(true, viewModel.tvVoiceSupportedForTest)

            viewModel.startVoiceSession()
            waitUntil(timeoutMs = 3_000) {
                protocolSent.isNotEmpty() && pushedFrames.get() >= 6
            }

            // First key must be single KEYCODE_SEARCH (84) and never VOICE_ASSIST (231)
            val firstFields = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(protocolSent[0]))
            )
            assertEquals(
                TvKeyCodes.SEARCH.toLong(),
                firstFields.first { it.number == 10 }.asMessage().first { it.number == 1 }.varint
            )

            // Simulate Xiaomi / Google TV Katniss IME show request while waiting for RemoteVoiceBegin
            kotlinx.coroutines.runBlocking {
                val katnissShow = ProtobufWire.encode {
                    writeMessageField(20) {
                        writeMessageField(1) {
                            writeVarintField(1, 5)
                            writeStringField(12, "com.google.android.katniss")
                        }
                        writeMessageField(2) {
                            writeVarintField(1, 68)
                            writeVarintField(5, 1)
                        }
                    }
                    writeMessageField(22) {
                        writeMessageField(2) {
                            writeVarintField(1, 68)
                            writeVarintField(5, 1)
                        }
                    }
                }
                viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(katnissShow))
            }
            assertTrue(viewModel.imePromptState.value.isTvImeVisible)
            assertFalse(viewModel.isKeyboardSheetVisible.value)

            // TV opens RemoteVoiceBegin(sessionId = 42)
            kotlinx.coroutines.runBlocking {
                val voiceBegin = ProtobufWire.encode {
                    writeMessageField(30) {
                        writeVarintField(1, 42)
                        writeStringField(2, "com.google.android.katniss")
                    }
                }
                viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(voiceBegin))
            }

            waitUntil(timeoutMs = 3_000) {
                protocolSent.any { bytes ->
                    ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(bytes)))
                        .any { it.number == 31 }
                }
            }
            assertFalse(viewModel.isKeyboardSheetVisible.value)

            // Feed 1600-byte tail frame while session active, then stop cleanly -> zero-padded 8192 B chunk -> RemoteVoiceEnd(42)
            sendTailNow.set(true)
            waitUntil(timeoutMs = 2_000) { tailPushed.get() }
            viewModel.stopVoiceSession(sendEndPacket = true)
            waitUntil(timeoutMs = 3_000) {
                protocolSent.any { bytes ->
                    ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(bytes)))
                        .any { it.number == 32 }
                }
            }

            val decoded = protocolSent.map { bytes ->
                ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(bytes)))
            }
            assertFalse(
                decoded.any { fields ->
                    fields.any { it.number == 10 } &&
                        fields.first { it.number == 10 }.asMessage().first { it.number == 1 }.varint == TvKeyCodes.VOICE_ASSIST.toLong()
                }
            )
            val payloads = decoded.filter { f -> f.any { it.number == 31 } }
                .map { f -> f.first { it.number == 31 }.asMessage() }
            assertEquals(2, payloads.size)
            assertEquals(42L, payloads[0].first { it.number == 1 }.varint)
            assertEquals(9600, payloads[0].first { it.number == 2 }.bytes.size)

            val tailSamples = payloads[1].first { it.number == 2 }.bytes
            assertEquals(42L, payloads[1].first { it.number == 1 }.varint)
            assertEquals(8192, tailSamples.size)
            assertTrue(tailSamples.copyOfRange(0, 1600).all { it == 0x5A.toByte() })
            assertTrue(tailSamples.copyOfRange(1600, 8192).all { it == 0.toByte() })
            assertTrue(payloads.all { it.first { f -> f.number == 2 }.bytes.size in 8192..20480 })

            val endIdx = decoded.indexOfFirst { f -> f.any { it.number == 32 } }
            val lastPayloadIdx = decoded.indexOfLast { f -> f.any { it.number == 31 } }
            assertTrue(endIdx > lastPayloadIdx)
            assertEquals(42L, decoded[endIdx].first { it.number == 32 }.asMessage().first { it.number == 1 }.varint)

            // Trailing Katniss show request within grace window remains suppressed; manual open clears suppression
            kotlinx.coroutines.runBlocking {
                val trailingShow = ProtobufWire.encode {
                    writeMessageField(22) {
                        writeMessageField(2) { writeVarintField(1, 69) }
                    }
                }
                viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(trailingShow))
            }
            assertFalse(viewModel.isKeyboardSheetVisible.value)

            viewModel.openKeyboardSheet()
            assertTrue(viewModel.isKeyboardSheetVisible.value)
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `voice fallback triggers immediately when FEATURE_VOICE omitted and keeps mobile keyboard hidden across search injection`() {
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
            viewModel.setAutoShowKeyboard(true)
            waitUntil(timeoutMs = 2_000) { viewModel.preferences.value.autoShowKeyboard }

            val protocolSent = CopyOnWriteArrayList<ByteArray>()
            viewModel.configureRemoteClientForTest(connected = true) { protocolSent.add(it) }

            val fallbackCount = java.util.concurrent.atomic.AtomicInteger(0)
            val collectScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
            )
            val collectorJob = collectScope.launch {
                viewModel.voiceFallbackRequests.collect {
                    fallbackCount.incrementAndGet()
                }
            }

            try {
                // TV feature flags = 615 (bit 8 FEATURE_VOICE missing)
                kotlinx.coroutines.runBlocking {
                    val cfg = ProtobufWire.encode {
                        writeMessageField(1) { writeVarintField(1, 615) }
                    }
                    viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(cfg))
                }
                assertEquals(false, viewModel.tvVoiceSupportedForTest)

                viewModel.startVoiceSession()
                waitUntil(timeoutMs = 1_500) {
                    fallbackCount.get() == 1 && protocolSent.isNotEmpty()
                }
                assertFalse(viewModel.isVoiceListening.value)
                assertFalse(viewModel.isKeyboardSheetVisible.value)
                // Even when FEATURE_VOICE is missing (615), Google Assistant key must be transmitted to wake TV Assistant
                assertEquals(
                    TvKeyCodes.VOICE_ASSIST.toLong(),
                    ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(protocolSent[0])))
                        .first { it.number == 10 }.asMessage().first { it.number == 1 }.varint
                )

                protocolSent.clear()
                // SpeechRecognizer delivers recognized query -> viewModel sends SEARCH, awaits IME without popping sheet, sends BatchEdit + ENTER
                viewModel.onVoiceSearchRecognized("오늘 날씨")
                waitUntil(timeoutMs = 2_000) { protocolSent.size >= 1 }
                assertEquals(
                    TvKeyCodes.SEARCH.toLong(),
                    ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(protocolSent[0])))
                        .first { it.number == 10 }.asMessage().first { it.number == 1 }.varint
                )

                kotlinx.coroutines.runBlocking {
                    val searchShow = ProtobufWire.encode {
                        writeMessageField(22) {
                            writeMessageField(2) {
                                writeVarintField(1, 12)
                                writeVarintField(5, 1)
                            }
                        }
                    }
                    viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(searchShow))
                }

                waitUntil(timeoutMs = 3_000) {
                    protocolSent.any { bytes ->
                        val f = ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(bytes)))
                        f.any { it.number == 10 } &&
                            f.first { it.number == 10 }.asMessage().first { it.number == 1 }.varint == TvKeyCodes.ENTER.toLong()
                    }
                }
                assertFalse(viewModel.isKeyboardSheetVisible.value)
                assertEquals("\"오늘 날씨\" 음성 검색 전송", viewModel.snackbarNotice.value?.message)
            } finally {
                collectorJob.cancel()
            }
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `remote voice error aborts active stream into fallback without opening mobile keyboard`() {
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
            viewModel.setAutoShowKeyboard(true)
            waitUntil(timeoutMs = 2_000) { viewModel.preferences.value.autoShowKeyboard }

            val protocolSent = CopyOnWriteArrayList<ByteArray>()
            val captureStopped = java.util.concurrent.atomic.AtomicBoolean(false)
            viewModel.configureRemoteClientForTest(connected = true) { protocolSent.add(it) }
            viewModel.configureVoiceCaptureForTest { _, isRunning, _ ->
                try {
                    while (isRunning()) {
                        kotlinx.coroutines.delay(15)
                    }
                } finally {
                    captureStopped.set(true)
                }
            }

            val fallbackCount = java.util.concurrent.atomic.AtomicInteger(0)
            val collectScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
            )
            val collectorJob = collectScope.launch {
                viewModel.voiceFallbackRequests.collect {
                    fallbackCount.incrementAndGet()
                }
            }

            try {
                kotlinx.coroutines.runBlocking {
                    val cfg = ProtobufWire.encode {
                        writeMessageField(1) { writeVarintField(1, 623) }
                    }
                    viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(cfg))
                }
                viewModel.startVoiceSession()
                waitUntil(timeoutMs = 2_000) { protocolSent.isNotEmpty() }

                kotlinx.coroutines.runBlocking {
                    val voiceBegin = ProtobufWire.encode {
                        writeMessageField(30) {
                            writeVarintField(1, 7)
                            writeStringField(2, "com.google.android.katniss")
                        }
                    }
                    viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(voiceBegin))
                }
                waitUntil(timeoutMs = 2_000) {
                    protocolSent.any { bytes ->
                        ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(bytes)))
                            .any { it.number == 30 }
                    }
                }

                // TV rejects voice payload with RemoteError(3) wrapping remote_voice_payload(31)
                kotlinx.coroutines.runBlocking {
                    val voiceErr = ProtobufWire.encode {
                        writeMessageField(3) {
                            writeBoolField(1, true)
                            writeMessageField(2) {
                                writeMessageField(31) {
                                    writeVarintField(1, 7)
                                }
                            }
                        }
                    }
                    viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(voiceErr))
                }

                waitUntil(timeoutMs = 3_000) {
                    fallbackCount.get() == 1 && captureStopped.get()
                }
                assertFalse(viewModel.isVoiceListening.value)
                assertTrue(viewModel.snackbarNotice.value?.message.orEmpty().contains("휴대폰 음성 인식"))

                kotlinx.coroutines.runBlocking {
                    val showReq = ProtobufWire.encode {
                        writeMessageField(22) { writeMessageField(2) { writeVarintField(1, 1) } }
                    }
                    viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(showReq))
                }
                assertFalse(viewModel.isKeyboardSheetVisible.value)
            } finally {
                collectorJob.cancel()
            }
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `sendKeyboardText submitWithEnter and dismissKeyboardSheet close popup and suppress trailing IME echoes`() {
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
            viewModel.setAutoShowKeyboard(true)
            waitUntil(timeoutMs = 2_000) { viewModel.preferences.value.autoShowKeyboard }
            viewModel.configureRemoteClientForTest(connected = true) {}

            kotlinx.coroutines.runBlocking {
                val showReq = ProtobufWire.encode {
                    writeMessageField(22) { writeMessageField(2) { writeVarintField(1, 1) } }
                }
                viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(showReq))
            }
            assertTrue(viewModel.isKeyboardSheetVisible.value)

            // Sending with submitWithEnter closes sheet immediately & suppresses trailing TV echo
            viewModel.sendKeyboardText("유튜브", replaceExisting = true, submitWithEnter = true)
            assertFalse(viewModel.isKeyboardSheetVisible.value)

            kotlinx.coroutines.runBlocking {
                val trailingEcho = ProtobufWire.encode {
                    writeMessageField(22) { writeMessageField(2) { writeVarintField(1, 2) } }
                }
                viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(trailingEcho))
            }
            assertFalse(viewModel.isKeyboardSheetVisible.value)

            // Explicit open re-shows sheet, dismiss closes + suppresses immediate echo
            viewModel.openKeyboardSheet()
            assertTrue(viewModel.isKeyboardSheetVisible.value)
            viewModel.dismissKeyboardSheet()
            assertFalse(viewModel.isKeyboardSheetVisible.value)
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `voice fallback submits ADB GLOBAL_SEARCH when TV Assistant opens without EditText`() {
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
                Result.success("Starting: Intent")
            }

            // Do not emit RemoteImeShowRequest(22); Katniss overlay has no focused IME field
            viewModel.onVoiceSearchRecognized("유튜브 틀어줘")
            waitUntil(timeoutMs = 3_500) {
                adbCommands.any { it.contains("android.search.action.GLOBAL_SEARCH") } &&
                    viewModel.snackbarNotice.value != null
            }

            val searchIntentCmd = adbCommands.first { it.contains("android.search.action.GLOBAL_SEARCH") }
            assertTrue(searchIntentCmd.contains("--es query '유튜브 틀어줘'"))
            assertEquals("\"유튜브 틀어줘\" 어시스턴트 음성 검색 실행", viewModel.snackbarNotice.value?.message)
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `voice default inactivity timeout is 3 seconds and stops quiet PCM and fallback STT cleanly`() {
        assertEquals(3_000L, RemoteViewModel.DEFAULT_VOICE_NO_ACTIVITY_TIMEOUT_MS)

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
            viewModel.voiceNoActivityTimeoutMs = 260L

            val protocolSent = CopyOnWriteArrayList<ByteArray>()
            val stopSignals = java.util.concurrent.atomic.AtomicInteger(0)
            val emitSpeech = java.util.concurrent.atomic.AtomicBoolean(true)

            val collectScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
            )
            val stopCollector = collectScope.launch {
                viewModel.voiceStopRequests.collect {
                    stopSignals.incrementAndGet()
                }
            }

            try {
                viewModel.configureRemoteClientForTest(connected = true) { protocolSent.add(it) }
                viewModel.configureVoiceCaptureForTest { _, isRunning, onFrame ->
                    // Emit loud frames (> 600 peak) briefly, then switch to near-silence (0x0101 = 257 < 600)
                    while (isRunning()) {
                        val sampleByte: Byte = if (emitSpeech.get()) 0x5A else 0x01
                        onFrame(ByteArray(1600) { sampleByte })
                        kotlinx.coroutines.delay(50)
                    }
                }

                kotlinx.coroutines.runBlocking {
                    val cfg = ProtobufWire.encode { writeMessageField(1) { writeVarintField(1, 623) } }
                    viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(cfg))
                }

                // 1) PCM stream stays open while speech is active past 260ms, then stops automatically after 260ms silence
                viewModel.startVoiceSession()
                waitUntil(timeoutMs = 2_000) { protocolSent.isNotEmpty() }
                kotlinx.coroutines.runBlocking {
                    val begin = ProtobufWire.encode {
                        writeMessageField(30) {
                            writeVarintField(1, 91)
                            writeStringField(2, "com.google.android.katniss")
                        }
                    }
                    viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(begin))
                }
                waitUntil(timeoutMs = 2_000) {
                    protocolSent.any { bytes ->
                        ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(bytes)))
                            .any { it.number == 30 }
                    }
                }
                shadowOf(Looper.getMainLooper()).idleFor(320, TimeUnit.MILLISECONDS)
                Thread.sleep(320)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue("Mic must stay open during active speech frames", viewModel.isVoiceListening.value)

                emitSpeech.set(false)
                waitUntil(timeoutMs = 2_500) {
                    !viewModel.isVoiceListening.value && protocolSent.any { bytes ->
                        ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(bytes)))
                            .any { it.number == 32 }
                    }
                }
                assertEquals("음성 입력이 없어 3초 후 마이크를 종료했습니다.", viewModel.snackbarNotice.value?.message)

                // 2) Fallback SpeechRecognizer extends on new partial voice-text, stops on subsequent inactivity, and ignores post-cancel ERROR_CLIENT
                viewModel.clearSnackbar()
                viewModel.onVoiceFallbackStarted()
                assertTrue(viewModel.isVoiceListening.value)

                Thread.sleep(150)
                shadowOf(Looper.getMainLooper()).idleFor(150, TimeUnit.MILLISECONDS)
                viewModel.reportVoiceTextActivity("오늘")
                assertTrue("Partial text must extend listening past original window", viewModel.isVoiceListening.value)

                // Sending unchanged duplicate partial text must NOT extend timeout indefinitely
                Thread.sleep(150)
                shadowOf(Looper.getMainLooper()).idleFor(150, TimeUnit.MILLISECONDS)
                viewModel.reportVoiceTextActivity("오늘")

                waitUntil(timeoutMs = 2_000) {
                    stopSignals.get() >= 1 && !viewModel.isVoiceListening.value
                }
                assertEquals("음성 텍스트 입력이 3초간 작동하지 않아 마이크를 종료했습니다.", viewModel.snackbarNotice.value?.message)

                // Spurious async cancel callback must NOT overwrite timeout notice
                viewModel.onVoiceFallbackFinished("음성 인식기를 사용할 수 없습니다.")
                assertEquals("음성 텍스트 입력이 3초간 작동하지 않아 마이크를 종료했습니다.", viewModel.snackbarNotice.value?.message)
            } finally {
                stopCollector.cancel()
            }
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun `when tvFeatures supports voice 623 but ignores SEARCH startVoiceSession wakes Google Assistant before fallback`() {
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
            val fallbackCount = java.util.concurrent.atomic.AtomicInteger(0)
            viewModel.configureRemoteClientForTest(connected = true) { protocolSent.add(it) }
            viewModel.configureVoiceCaptureForTest { _, isRunning, _ ->
                while (isRunning()) {
                    kotlinx.coroutines.delay(20)
                }
            }

            val collectScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
            )
            val collector = collectScope.launch {
                viewModel.voiceFallbackRequests.collect { fallbackCount.incrementAndGet() }
            }

            try {
                kotlinx.coroutines.runBlocking {
                    val cfg = ProtobufWire.encode { writeMessageField(1) { writeVarintField(1, 623) } }
                    viewModel.applyRemoteFieldsForTest(ProtobufWire.parseFields(cfg))
                }

                viewModel.startVoiceSession()
                // Waits 1.1s after SEARCH then sends VOICE_ASSIST, waits 1.1s then triggers SpeechRecognizer fallback
                waitUntil(timeoutMs = 4_000) {
                    fallbackCount.get() == 1 && protocolSent.size >= 2
                }

                val sentKeys = protocolSent.map { bytes ->
                    ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(bytes)))
                        .first { it.number == 10 }.asMessage()
                        .first { it.number == 1 }.varint
                }
                assertEquals(listOf(TvKeyCodes.SEARCH.toLong(), TvKeyCodes.VOICE_ASSIST.toLong()), sentKeys)
                assertFalse(viewModel.isVoiceListening.value)
            } finally {
                collector.cancel()
            }
        } finally {
            store.clear()
            db.close()
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
