package com.example

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.ui.components.AdbKeyboardSetupDialog
import com.example.ui.components.KeyboardInputSheet
import com.example.ui.components.computeRealTimeBackspaceCount
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `live mode is removed, only Backspace sends in real time, and Send always replaces and submits`() {
        val sentTexts = mutableListOf<Triple<String, Boolean, Boolean>>()
        val sentKeys = mutableListOf<Int>()
        val backspaceCalls = mutableListOf<Int>()

        composeRule.setContent {
            TvRemoteTheme {
                KeyboardInputSheet(
                    tvName = "Living Room TV",
                    initialText = "",
                    isTvImeVisible = false,
                    isAdbConnected = true,
                    isAdbKeyboardInstalled = true,
                    onSendText = { text, replaceExisting, submitWithEnter ->
                        sentTexts.add(Triple(text, replaceExisting, submitWithEnter))
                    },
                    onSendKey = { key -> sentKeys.add(key) },
                    onBackspace = { count -> backspaceCalls.add(count) },
                    onDismiss = {}
                )
            }
        }

        // Live mode toggle pill must be removed
        composeRule.onNodeWithTag("keyboard_mode_live").assertDoesNotExist()
        composeRule.onNodeWithText("ADBKeyBoard 연결됨 · 백스페이스 실시간 · 전체 교체 전송").assertExists()

        // Pressing Backspace when empty sends physical Backspace immediately
        composeRule.onNodeWithTag("tv_keyboard_input_field").performClick()
        composeRule.onNodeWithTag("tv_keyboard_input_field").performKeyInput {
            pressKey(Key.Backspace)
        }
        assertEquals(listOf(1), backspaceCalls)
        assertEquals(emptyList<Triple<String, Boolean, Boolean>>(), sentTexts)

        // Pressing Send even when empty clears -> inputs nothing -> sends ENTER (replaceExisting=true, submitWithEnter=true)
        composeRule.onNodeWithTag("keyboard_send_search_button").performClick()
        assertEquals(listOf(Triple("", true, true)), sentTexts)
        assertEquals(emptyList<Int>(), sentKeys)

        // Typing Korean/ASCII does NOT stream characters or trigger Backspace
        sentTexts.clear()
        backspaceCalls.clear()
        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextInput("유튜브")
        assertEquals(emptyList<Int>(), backspaceCalls)
        assertEquals(emptyList<Triple<String, Boolean, Boolean>>(), sentTexts)

        // Deleting a character via Backspace inside the field immediately emits physical Backspace
        composeRule.onNodeWithTag("tv_keyboard_input_field").performKeyInput {
            pressKey(Key.Backspace)
        }
        assertEquals(listOf(1), backspaceCalls)

        // Modifying text and pressing Send always invokes full clear -> input -> Enter
        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextReplacement("유튜브 검색")
        composeRule.onNodeWithTag("keyboard_send_search_button").performClick()
        assertEquals(listOf(Triple("유튜브 검색", true, true)), sentTexts)

        // Even unchanged ASCII text always goes through full clear -> input -> Enter (no Enter-only shortcut)
        sentTexts.clear()
        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextReplacement("bts")
        composeRule.onNodeWithTag("keyboard_send_search_button").performClick()
        composeRule.onNodeWithTag("keyboard_send_search_button").performClick()
        assertEquals(
            listOf(
                Triple("bts", true, true),
                Triple("bts", true, true)
            ),
            sentTexts
        )

        // Clicking "전체 지우기" always sends full clear without Enter
        sentTexts.clear()
        composeRule.onNodeWithTag("keyboard_clear_button").performClick()
        assertEquals(listOf(Triple("", true, false)), sentTexts)
    }

    @Test
    fun `computeRealTimeBackspaceCount handles pure deletions and ignores Korean syllable composition`() {
        // Korean Hangul composition must NOT trigger Backspace
        assertEquals(
            0,
            computeRealTimeBackspaceCount(
                TextFieldValue("ㄱ", selection = TextRange(1), composition = TextRange(0, 1)),
                TextFieldValue("가", selection = TextRange(1), composition = TextRange(0, 1))
            )
        )
        assertEquals(
            0,
            computeRealTimeBackspaceCount(
                TextFieldValue("한", selection = TextRange(1), composition = TextRange(0, 1)),
                TextFieldValue("하나", selection = TextRange(2), composition = TextRange(1, 2))
            )
        )
        assertEquals(
            0,
            computeRealTimeBackspaceCount(
                TextFieldValue("한", selection = TextRange(1), composition = TextRange(0, 1)),
                TextFieldValue("하", selection = TextRange(1), composition = TextRange(0, 1))
            )
        )

        // Pure deletions emit exact count
        assertEquals(
            1,
            computeRealTimeBackspaceCount(
                TextFieldValue("안녕", selection = TextRange(2)),
                TextFieldValue("안", selection = TextRange(1))
            )
        )
        assertEquals(
            1,
            computeRealTimeBackspaceCount(
                TextFieldValue("a", selection = TextRange(1)),
                TextFieldValue("", selection = TextRange(0))
            )
        )
        assertEquals(
            1,
            computeRealTimeBackspaceCount(
                TextFieldValue("hello world", selection = TextRange(5)),
                TextFieldValue("hell world", selection = TextRange(4))
            )
        )
        assertEquals(
            2,
            computeRealTimeBackspaceCount(
                TextFieldValue("가나다", selection = TextRange(0, 2)),
                TextFieldValue("다", selection = TextRange(0))
            )
        )
        // Selection replacement (typing new char over 2 selected chars) is NOT Backspace
        assertEquals(
            0,
            computeRealTimeBackspaceCount(
                TextFieldValue("가나다", selection = TextRange(0, 2)),
                TextFieldValue("ㅁ다", selection = TextRange(1))
            )
        )
        // Deleting a single supplementary emoji sends 1 Backspace instead of 2 UTF-16 code units
        assertEquals(
            1,
            computeRealTimeBackspaceCount(
                TextFieldValue("a😀", selection = TextRange(3)),
                TextFieldValue("a", selection = TextRange(1))
            )
        )
    }

    @Test
    fun `missing ADBKeyBoard shows setup banner and clear sends ADB_CLEAR_TEXT`() {
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
        composeRule.onNodeWithTag("adb_kb_pick_apk_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("adb_kb_open_tv_downloads_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("adb_kb_redownload_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("adb_kb_verify_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("adb_kb_dismiss_button").performClick()

        assertEquals(1, pickClicks)
        assertEquals(1, openTvDlClicks)
        assertEquals(1, redownloadClicks)
        assertEquals(1, verifyClicks)
        assertEquals(1, dismissClicks)
    }
}
