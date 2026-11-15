package com.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import com.example.data.model.PhysicalKeyAction
import com.example.data.model.TvImeStatus
import com.example.data.model.TvImeType
import com.example.data.model.TvKeyboardLanguage
import com.example.data.model.TvKeyCodes
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `keyboard sheet removes custom keypad grid streams real-time keycodes and keeps clear always enabled`() {
        val sentTexts = mutableListOf<Triple<String, Boolean, Boolean>>()
        val sentKeys = mutableListOf<Int>()
        val backspaceCalls = mutableListOf<Int>()
        val realTimeEdits = mutableListOf<Pair<Int, List<PhysicalKeyAction>>>()
        var clearAllCalls = 0
        var resyncCalls = 0
        var toggleCalls = 0

        composeRule.setContent {
            var language by remember { mutableStateOf(TvKeyboardLanguage.KOREAN) }
            TvRemoteTheme {
                KeyboardInputSheet(
                    tvName = "Living Room TV",
                    initialText = "",
                    isTvImeVisible = true,
                    isAdbConnected = true,
                    keyboardLanguage = language,
                    tvImeStatus = TvImeStatus(
                        language = language,
                        imeType = TvImeType.GBOARD,
                        subtypeLocale = "ko_KR",
                        isDetected = true
                    ),
                    onToggleLanguage = {
                        toggleCalls++
                        language = if (language == TvKeyboardLanguage.KOREAN) {
                            TvKeyboardLanguage.ENGLISH
                        } else {
                            TvKeyboardLanguage.KOREAN
                        }
                    },
                    onResyncTvIme = { resyncCalls++ },
                    onRealTimeKeyEdit = { deletes, actions ->
                        realTimeEdits.add(deletes to actions)
                    },
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

        // Custom 5-row keyboard grid and ADBKeyBoard banner must NOT exist
        composeRule.onNodeWithTag("adb_keyboard_setup_banner").assertDoesNotExist()
        composeRule.onNodeWithTag("physical_keypad_grid").assertDoesNotExist()
        composeRule.onNodeWithTag("phys_key_q").assertDoesNotExist()
        composeRule.onNodeWithTag("phys_key_shift").assertDoesNotExist()
        composeRule.onNodeWithTag("phys_key_space").assertDoesNotExist()

        composeRule.onNodeWithText("TV IME: 한글 (Gboard) · 폰 키보드 실시간 키코드 전송").assertExists()

        // "전체 지우기" must stay enabled even when text box is empty and on repeat clicks
        composeRule.onNodeWithTag("keyboard_clear_button").assertIsEnabled().performScrollTo().performClick()
        composeRule.onNodeWithTag("keyboard_clear_button").assertIsEnabled().performClick()
        assertEquals(2, clearAllCalls)

        // Resync & TV 한/영 toggle strip remain functional
        composeRule.onNodeWithTag("keyboard_resync_ime_button").performScrollTo().performClick()
        assertEquals(1, resyncCalls)
        composeRule.onNodeWithTag("keyboard_language_toggle").performScrollTo().performClick()
        assertEquals(1, toggleCalls)

        // Pressing Backspace when empty sends physical Backspace immediately
        composeRule.onNodeWithTag("tv_keyboard_input_field").performScrollTo().performClick()
        composeRule.onNodeWithTag("tv_keyboard_input_field").performKeyInput {
            pressKey(Key.Backspace)
        }
        assertEquals(listOf(1), backspaceCalls)
        assertEquals(emptyList<Triple<String, Boolean, Boolean>>(), sentTexts)

        // Pressing Send even when empty clears -> inputs nothing -> sends ENTER
        composeRule.onNodeWithTag("keyboard_send_search_button").performScrollTo().performClick()
        assertEquals(listOf(Triple("", true, true)), sentTexts)
        assertEquals(emptyList<Int>(), sentKeys)

        // Typing Q, W, E, R via phone keyboard streams real-time keycodes immediately
        sentTexts.clear()
        backspaceCalls.clear()
        realTimeEdits.clear()
        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextInput("q")
        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextInput("w")
        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextInput("e")
        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextInput("r")
        assertEquals(
            listOf(
                0 to listOf(PhysicalKeyAction(TvKeyCodes.KEYCODE_Q)),
                0 to listOf(PhysicalKeyAction(TvKeyCodes.KEYCODE_W)),
                0 to listOf(PhysicalKeyAction(TvKeyCodes.KEYCODE_E)),
                0 to listOf(PhysicalKeyAction(TvKeyCodes.KEYCODE_R))
            ),
            realTimeEdits
        )
        assertEquals(emptyList<Triple<String, Boolean, Boolean>>(), sentTexts)

        // Backspace after typing sends 1 physical DEL
        composeRule.onNodeWithTag("tv_keyboard_input_field").performKeyInput {
            pressKey(Key.Backspace)
        }
        assertEquals(listOf(1), backspaceCalls)

        // Clear all clears local field cleanly and triggers onClearAll
        backspaceCalls.clear()
        realTimeEdits.clear()
        composeRule.onNodeWithTag("keyboard_clear_button").assertIsEnabled().performScrollTo().performClick()
        assertEquals(3, clearAllCalls)
        assertEquals(emptyList<Int>(), backspaceCalls)
        assertEquals(emptyList<Pair<Int, List<PhysicalKeyAction>>>(), realTimeEdits)

        // Korean 2-bulsik composition steps: "ㅂ" -> "바" -> "한" -> "하"
        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextReplacement("ㅂ")
        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextReplacement("바")
        assertEquals(
            listOf(
                0 to listOf(PhysicalKeyAction(TvKeyCodes.KEYCODE_Q)),
                0 to listOf(PhysicalKeyAction(TvKeyCodes.KEYCODE_K))
            ),
            realTimeEdits
        )

        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextReplacement("한")
        backspaceCalls.clear()
        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextReplacement("하")
        assertEquals(listOf(1), backspaceCalls)

        // Send button still submits full replacement + ENTER
        sentTexts.clear()
        composeRule.onNodeWithTag("tv_keyboard_input_field").performTextReplacement("유튜브 검색")
        composeRule.onNodeWithTag("keyboard_send_search_button").performScrollTo().performClick()
        assertEquals(listOf(Triple("유튜브 검색", true, true)), sentTexts)
    }
}
