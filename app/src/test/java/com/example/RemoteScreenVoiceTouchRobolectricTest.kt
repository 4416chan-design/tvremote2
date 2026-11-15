package com.example

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.example.data.model.TvConnectionState
import com.example.ui.RemoteScreen
import com.example.ui.theme.TvRemoteTheme
import org.junit.Assert.assertEquals
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
    fun `mic button dispatches touch down on ACTION_DOWN and touch up only on ACTION_UP`() {
        val events = mutableListOf<String>()

        composeRule.setContent {
            TvRemoteTheme {
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
                    snackbarHostState = SnackbarHostState(),
                    onOpenTvPicker = {},
                    onKeyPress = {},
                    onLaunchFavorite = {},
                    onOpenKeyboard = {},
                    onMicTouchDown = { events.add("ACTION_DOWN") },
                    onMicTouchUp = { events.add("ACTION_UP") },
                    onOpenSettings = {},
                    onToggleMute = {},
                    onStepVolume = {},
                    onToggleDayNightBrightness = {},
                    onStepBrightness = {},
                    onOpenTvPictureSettings = {}
                )
            }
        }

        // Hold finger down: ACTION_DOWN fires immediately, ACTION_UP must NOT fire yet
        composeRule.onNodeWithTag("btn_mic").performTouchInput {
            down(center)
        }
        composeRule.waitForIdle()
        assertEquals(listOf("ACTION_DOWN"), events)

        // Move slightly inside button while still pressed: must not retrigger DOWN or UP
        composeRule.onNodeWithTag("btn_mic").performTouchInput {
            moveBy(androidx.compose.ui.geometry.Offset(2f, 2f))
        }
        composeRule.waitForIdle()
        assertEquals(listOf("ACTION_DOWN"), events)

        // Release finger: ACTION_UP fires once
        composeRule.onNodeWithTag("btn_mic").performTouchInput {
            up()
        }
        composeRule.waitForIdle()
        assertEquals(listOf("ACTION_DOWN", "ACTION_UP"), events)

        // Verify MotionEvent ACTION_CANCEL also releases cleanly
        composeRule.onNodeWithTag("btn_mic").performTouchInput {
            down(center)
            cancel()
        }
        composeRule.waitForIdle()
        assertEquals(listOf("ACTION_DOWN", "ACTION_UP", "ACTION_DOWN", "ACTION_UP"), events)
    }
}

