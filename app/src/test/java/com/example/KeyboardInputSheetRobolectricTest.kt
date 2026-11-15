package com.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
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
import com.example.data.model.TvImeStatus
import com.example.data.model.TvImeType
import com.example.data.model.TvKeyboardLanguage
import com.example.data.model.TvKeyCodes
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
    fun `text field clear triggers SelectAllDelete without backspace and Send always replaces and submits`() {
        val sentTexts = mutableListOf<Triple<String, Boolean, Boolean>>()
        val sentKeys = mutableListOf<Int>()
        val backspaceCalls = mutableListOf<Int>()
        var clearAllCalls = 0
        var resyncCalls = 0

        composeRule.setContent {
            TvRemoteTheme {
                KeyboardInputSheet(
                    tvName = "Living Room TV",
                    initialText = "",
                    isTvImeVisible = true,
                    isAdbConnected = true,
                    keyboardLanguage = TvKeyboardLanguage.KOREAN,
                    tvImeStatus = TvImeStatus(
                        language = TvKeyboardLanguage.KOREAN,
                        imeType = TvImeType.GBOARD,
                        subtypeLocale = "ko_KR",
                        isDetected = true
                    ),
                    onResyncTvIme = { resyncCalls++ },
                    onClearAll = { clearAllCalls++ },
                    onSendText = { text, replaceExisting, submitWithEnter ->
                        sentTexts.add(Triple(text, replaceExisting, submitWithEnter))
                    },
                    onSendKey = { key -> sentKeys.add(key) },
                    onBackspace = { count -> backspaceCalls.add(count) },
                    onDismiss = {}
                )
            }
        }

        // ADBKeyBoard banner must be completely removed; TV IME status is displayed instead
        composeRule.onNodeWithTag("adb_keyboard_setup_banner").assertDoesNotExist()
        composeRule.onNodeWithText("TV IME: 한글 (Gboard) · 물리키 실시간/텍스트 전송").assertExists()

        // Resync TV IME button triggers callback
        composeRule.onNodeWithTag("keyboard_resync_ime_button").performScrollTo().performClick()
        assertEquals(1, resyncCalls)

        // Pressing Backspace when empty sends physical Backspace immediately
        composeRule.onNodeWithTag("tv_keyboard_input_field").performScrollTo().performClick()
        composeRule.onNodeWithTag("tv_keyboard_input_field").performKeyInput {
            pressKey(Key.Backspace)
        }
        assertEquals(listOf(1), backspaceCalls)
        assertEquals(emptyList<Triple<String, Boolean, Boolean>>(), sentTexts)

        // Pressing Send even when empty clears -> inputs nothing -> sends ENTER (replaceExisting=true, submitWithEnter=true)
        composeRule.onNodeWithTag("keyboard_send_search_button").performScrollTo().performClick()
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

        // Modifying text and pressing Send always invokes full replacement + Enter
        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextReplacement("유튜브 검색")
        composeRule.onNodeWithTag("keyboard_send_search_button").performScrollTo().performClick()
        assertEquals(listOf(Triple("유튜브 검색", true, true)), sentTexts)

        // Clicking "전체 지우기" invokes onClearAll (Ctrl+A -> DEL) AND empties the local input box
        sentTexts.clear()
        backspaceCalls.clear()
        composeRule.onNodeWithTag("keyboard_clear_button").performScrollTo().performClick()
        assertEquals(1, clearAllCalls)
        assertEquals(emptyList<Triple<String, Boolean, Boolean>>(), sentTexts)
        assertEquals(emptyList<Int>(), backspaceCalls)

        // Subsequent Send transmits empty text (confirming local field was cleared too)
        composeRule.onNodeWithTag("keyboard_send_search_button").performScrollTo().performClick()
        assertEquals(listOf(Triple("", true, true)), sentTexts)
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
    fun `physical keypad switches between English QWERTY and Korean 2-bulsik with Shift and sends real-time keys`() {
        val physicalPresses = mutableListOf<Pair<String, Boolean>>()
        val sentKeys = mutableListOf<Int>()
        val backspaceCalls = mutableListOf<Int>()
        val sentTexts = mutableListOf<Triple<String, Boolean, Boolean>>()
        var toggleCalls = 0

        composeRule.setContent {
            var language by remember { mutableStateOf(TvKeyboardLanguage.ENGLISH) }
            TvRemoteTheme {
                KeyboardInputSheet(
                    tvName = "Living Room TV",
                    initialText = "",
                    isTvImeVisible = true,
                    isAdbConnected = true,
                    keyboardLanguage = language,
                    tvImeStatus = TvImeStatus(language = language, imeType = TvImeType.GBOARD, isDetected = true),
                    onToggleLanguage = {
                        toggleCalls++
                        language = if (language == TvKeyboardLanguage.ENGLISH) {
                            TvKeyboardLanguage.KOREAN
                        } else {
                            TvKeyboardLanguage.ENGLISH
                        }
                    },
                    onPhysicalKeyPress = { spec, shift ->
                        physicalPresses.add(spec.id to shift)
                    },
                    onSendText = { text, replaceExisting, submitWithEnter ->
                        sentTexts.add(Triple(text, replaceExisting, submitWithEnter))
                    },
                    onSendKey = { key -> sentKeys.add(key) },
                    onBackspace = { count -> backspaceCalls.add(count) },
                    onDismiss = {}
                )
            }
        }

        // English mode: Q key displays 'q', then with Shift displays 'Q' and auto-unshifts after tap
        composeRule.onNodeWithTag("phys_key_q").assertTextEquals("q")
        composeRule.onNodeWithTag("phys_key_q").performScrollTo().performClick()
        assertEquals(listOf("q" to false), physicalPresses)

        composeRule.onNodeWithTag("phys_key_shift").performScrollTo().performClick()
        composeRule.onNodeWithTag("phys_key_q").assertTextEquals("Q")
        composeRule.onNodeWithTag("phys_key_q").performScrollTo().performClick()
        composeRule.onNodeWithTag("phys_key_q").assertTextEquals("q")
        assertEquals(listOf("q" to false, "q" to true), physicalPresses)

        // Toggle to Korean via '한/영' key -> Q key changes to 'ㅂ', shifted to 'ㅃ', A key stays 'ㅁ'
        composeRule.onNodeWithTag("phys_key_lang_switch").performScrollTo().performClick()
        assertEquals(1, toggleCalls)
        composeRule.onNodeWithTag("phys_key_q").assertTextEquals("ㅂ")
        composeRule.onNodeWithTag("phys_key_q").performScrollTo().performClick()

        composeRule.onNodeWithTag("phys_key_shift").performScrollTo().performClick()
        composeRule.onNodeWithTag("phys_key_q").assertTextEquals("ㅃ")
        composeRule.onNodeWithTag("phys_key_a").assertTextEquals("ㅁ")
        composeRule.onNodeWithTag("phys_key_q").performScrollTo().performClick()

        assertEquals(
            listOf("q" to false, "q" to true, "q" to false, "q" to true),
            physicalPresses
        )

        // Backspace, Space, Enter on physical keypad
        composeRule.onNodeWithTag("phys_key_backspace").performScrollTo().performClick()
        composeRule.onNodeWithTag("phys_key_space").performScrollTo().performClick()
        composeRule.onNodeWithTag("phys_key_enter").performScrollTo().performClick()

        assertEquals(listOf(1), backspaceCalls)
        assertEquals(listOf(TvKeyCodes.SPACE, TvKeyCodes.ENTER), sentKeys)
        assertEquals(emptyList<Triple<String, Boolean, Boolean>>(), sentTexts)
    }
}
