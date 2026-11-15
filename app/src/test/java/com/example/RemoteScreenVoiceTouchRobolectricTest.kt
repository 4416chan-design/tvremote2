package com.example

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.example.data.model.TvConnectionState
import com.example.ui.RemoteScreen
import com.example.ui.theme.TvRemoteTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemoteScreenVoiceTouchRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `mic button uses Tap-to-Talk single click toggle and snackbar renders at top`() {
        var clickCount = 0
        val snackbarHostState = SnackbarHostState()
        val noticeText = "음성 입력이 없어 3초 후 마이크를 종료했습니다."

        composeRule.setContent {
            TvRemoteTheme {
                LaunchedEffect(Unit) {
                    snackbarHostState.showSnackbar(noticeText)
                }
                RemoteScreen(
                    connectionState = TvConnectionState.Connected(
                        tvName = "Living Room TV",
                        host = "192.168.0.10",
                        remotePort = 6466
                    ),
                    favoriteApps = emptyList(),
                    volumeLevel = 15,
                    maxVolume = 100,
                    isMuted = false,
                    brightnessPercent = 60,
                    hapticEnabled = false,
                    isVoiceListening = false,
                    snackbarHostState = snackbarHostState,
                    onOpenTvPicker = {},
                    onKeyPress = {},
                    onLaunchFavorite = {},
                    onOpenKeyboard = {},
                    onMicClick = { clickCount++ },
                    onOpenSettings = {},
                    onToggleMute = {},
                    onStepVolume = {},
                    onToggleDayNightBrightness = {},
                    onStepBrightness = {},
                    onOpenTvPictureSettings = {}
                )
            }
        }

        composeRule.waitForIdle()

        // Snackbar must appear at the top of the screen above the bottom action controls
        composeRule.onNodeWithText(noticeText).assertIsDisplayed()
        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        val snackbarBounds = composeRule.onNodeWithTag("top_snackbar_host").getUnclippedBoundsInRoot()
        val micBounds = composeRule.onNodeWithTag("btn_mic").getUnclippedBoundsInRoot()
        val rootMidY = (rootBounds.top + rootBounds.bottom) / 2f
        assertTrue(
            "Expected top_snackbar_host in upper half (top=${snackbarBounds.top}, bottom=${snackbarBounds.bottom}, midY=$rootMidY)",
            snackbarBounds.bottom < rootMidY
        )
        assertTrue(
            "Expected top_snackbar_host above mic button",
            snackbarBounds.bottom < micBounds.top
        )

        // 1st tap starts session; 2nd tap stops session
        composeRule.onNodeWithTag("btn_mic").performClick()
        composeRule.waitForIdle()
        assertEquals(1, clickCount)

        composeRule.onNodeWithTag("btn_mic").performClick()
        composeRule.waitForIdle()
        assertEquals(2, clickCount)
    }
}

