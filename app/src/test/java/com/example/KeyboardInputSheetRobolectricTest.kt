package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.data.model.TvKeyCodes
import com.example.ui.components.AdbKeyboardSetupDialog
import com.example.ui.components.KeyboardInputSheet
import com.example.ui.theme.TvRemoteTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeyboardInputSheetRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `default mode sends text with enter on submit and live sync sends enter only for unchanged ASCII`() {
        val sentTexts = mutableListOf<Triple<String, Boolean, Boolean>>()
        val insertedChars = mutableListOf<String>()
        val sentKeys = mutableListOf<Int>()

        composeRule.setContent {
            TvRemoteTheme {
                KeyboardInputSheet(
                    tvName = "Living Room TV",
                    initialText = "",
                    isTvImeVisible = false,
                    isAdbConnected = false,
                    onSendText = { text, replaceExisting, submitWithEnter ->
                        sentTexts.add(Triple(text, replaceExisting, submitWithEnter))
                    },
                    onSendKey = { key -> sentKeys.add(key) },
                    onInsertChars = { chars -> insertedChars.add(chars) },
                    onBackspace = {},
                    onDismiss = {}
                )
            }
        }

        // Status subtitle should guide the user accurately regardless of IME visibility
        composeRule.onNodeWithText("TV 검색창/키보드를 연 상태에서 입력 후 전송하세요").assertExists()

        // Default mode (실시간 OFF): typing and pressing "전송" sends full text + ENTER
        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextInput("유튜브 검색")
        composeRule.onNodeWithTag("keyboard_send_search_button").performClick()

        assertEquals(listOf(Triple("유튜브 검색", true, true)), sentTexts)
        assertEquals(emptyList<Int>(), sentKeys)

        // Pressing "전체 지우기" sends empty string clear without ENTER
        sentTexts.clear()
        composeRule.onNodeWithTag("keyboard_clear_button").performClick()
        assertEquals(listOf(Triple("", true, false)), sentTexts)

        // Turn on live sync mode, type English "hello world", then press "전송":
        // unchanged live ASCII only sends ENTER (no duplication).
        sentTexts.clear()
        insertedChars.clear()
        sentKeys.clear()
        composeRule.onNodeWithTag("keyboard_mode_live").performClick()
        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextInput("hello world")
        assertEquals(listOf("hello world"), insertedChars)

        composeRule.onNodeWithTag("keyboard_send_search_button").performClick()
        assertEquals(emptyList<Triple<String, Boolean, Boolean>>(), sentTexts)
        assertEquals(listOf(TvKeyCodes.ENTER), sentKeys)

        // Clear and type Korean while live mode is still ON: pressing "전송" clears and replaces full Korean text
        composeRule.onNodeWithTag("keyboard_clear_button").performClick()
        sentTexts.clear()
        sentKeys.clear()
        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextInput("고양이")
        composeRule.onNodeWithTag("keyboard_send_search_button").performClick()
        assertEquals(listOf(Triple("고양이", true, true)), sentTexts)
        assertEquals(emptyList<Int>(), sentKeys)
    }

    @Test
    fun `missing ADBKeyBoard shows setup banner and live clear with installed ADBKeyBoard uses ADB_CLEAR_TEXT`() {
        var openSetupCalls = 0
        val sentTexts = mutableListOf<Triple<String, Boolean, Boolean>>()
        val backspaceCalls = mutableListOf<Int>()

        composeRule.setContent {
            TvRemoteTheme {
                KeyboardInputSheet(
                    tvName = "Xiaomi TV",
                    initialText = "abc",
                    isTvImeVisible = true,
                    isAdbConnected = true,
                    isAdbKeyboardInstalled = false,
                    onOpenAdbKeyboardSetup = { openSetupCalls++ },
                    onSendText = { text, replaceExisting, submitWithEnter ->
                        sentTexts.add(Triple(text, replaceExisting, submitWithEnter))
                    },
                    onSendKey = {},
                    onInsertChars = {},
                    onBackspace = { count -> backspaceCalls.add(count) },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("adb_keyboard_setup_banner").performClick()
        assertEquals(1, openSetupCalls)

        composeRule.onNodeWithTag("keyboard_clear_button").performClick()
        assertEquals(listOf(Triple("", true, false)), sentTexts)
        assertEquals(emptyList<Int>(), backspaceCalls)
    }

    @Test
    fun `live sync clear uses ADB_CLEAR_TEXT when ADBKeyBoard is installed and setup dialog actions work`() {
        val sentTexts = mutableListOf<Triple<String, Boolean, Boolean>>()
        val backspaceCalls = mutableListOf<Int>()

        composeRule.setContent {
            TvRemoteTheme {
                KeyboardInputSheet(
                    tvName = "Xiaomi TV",
                    initialText = "hello",
                    isTvImeVisible = true,
                    isAdbConnected = true,
                    isAdbKeyboardInstalled = true,
                    onOpenAdbKeyboardSetup = {},
                    onSendText = { text, replaceExisting, submitWithEnter ->
                        sentTexts.add(Triple(text, replaceExisting, submitWithEnter))
                    },
                    onSendKey = {},
                    onInsertChars = {},
                    onBackspace = { count -> backspaceCalls.add(count) },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("keyboard_mode_live").performClick()
        composeRule.onNodeWithTag("keyboard_clear_button").performClick()
        assertEquals(listOf(Triple("", true, false)), sentTexts)
        assertEquals(emptyList<Int>(), backspaceCalls)
    }

    @Test
    fun `adb keyboard setup dialog guides Download folder APK install and invokes actions`() {
        var pickClicks = 0
        var openTvDlClicks = 0
        var redownloadClicks = 0
        var verifyClicks = 0
        var dismissClicks = 0

        composeRule.setContent {
            TvRemoteTheme {
                AdbKeyboardSetupDialog(
                    isInstalled = false,
                    statusMessage = "인터넷 창에서 다운로드를 시작했습니다.",
                    isInstalling = false,
                    onPickApkFromDownloads = { pickClicks++ },
                    onOpenTvDownloads = { openTvDlClicks++ },
                    onRedownloadInBrowser = { redownloadClicks++ },
                    onVerifyInstalled = { verifyClicks++ },
                    onDismiss = { dismissClicks++ }
                )
            }
        }

        composeRule.onNodeWithText("Download/keyboardservice-debug.apk").assertExists()
        composeRule.onNodeWithTag("adb_kb_pick_apk_button").performClick()
        composeRule.onNodeWithTag("adb_kb_open_tv_downloads_button").performClick()
        composeRule.onNodeWithTag("adb_kb_redownload_button").performClick()
        composeRule.onNodeWithTag("adb_kb_verify_button").performClick()
        composeRule.onNodeWithTag("adb_kb_dismiss_button").performClick()

        assertEquals(1, pickClicks)
        assertEquals(1, openTvDlClicks)
        assertEquals(1, redownloadClicks)
        assertEquals(1, verifyClicks)
        assertEquals(1, dismissClicks)
    }
}
