package com.example.ui

import android.view.MotionEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.FavoriteAppEntity
import com.example.data.model.TvConnectionState
import com.example.data.model.TvKeyCodes
import com.example.ui.components.CircularDpadController
import com.example.ui.components.FullBullnoseDialSlider
import com.example.ui.components.TvAppBadgeIcon
import com.example.ui.theme.CharcoalBg
import com.example.ui.theme.CharcoalBorder
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CharcoalWell
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.MintGreen
import com.example.ui.theme.MintGreenContainer
import com.example.ui.theme.OnMintGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

@Composable
fun RemoteScreen(
    connectionState: TvConnectionState,
    favoriteApps: List<FavoriteAppEntity>,
    volumeLevel: Int,
    maxVolume: Int,
    isMuted: Boolean,
    brightnessPercent: Int,
    hapticEnabled: Boolean,
    isVoiceListening: Boolean,
    snackbarHostState: SnackbarHostState,
    onOpenTvPicker: () -> Unit,
    onKeyPress: (Int) -> Unit,
    onLaunchFavorite: (FavoriteAppEntity) -> Unit,
    onOpenKeyboard: () -> Unit,
    onMicTouchDown: () -> Unit,
    onMicTouchUp: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleMute: () -> Unit,
    onStepVolume: (Int) -> Unit,
    onToggleDayNightBrightness: () -> Unit,
    onStepBrightness: (Int) -> Unit,
    onOpenTvPictureSettings: () -> Unit
) {
    val hapticHelper = rememberHapticHelper()
    fun triggerHaptic() {
        if (hapticEnabled) {
            hapticHelper.vibrateClick()
        }
    }

    Scaffold(
        containerColor = CharcoalBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding()
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            val compactHeight = maxHeight < 710.dp
            val dpadDiameter = (maxHeight * 0.28f).coerceIn(184.dp, 240.dp)
            val bigCircleSize = if (compactHeight) 56.dp else 64.dp
            val rowGap = if (compactHeight) 10.dp else 14.dp
            val sliderHeight = if (compactHeight) 46.dp else 52.dp

            val scrollModifier = if (maxHeight < 640.dp) {
                Modifier.verticalScroll(rememberScrollState())
            } else {
                Modifier
            }

            Column(
                modifier = Modifier
                    .widthIn(max = 500.dp)
                    .fillMaxSize()
                    .then(scrollModifier)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Top Header: Star Favorites Edit Circle | TV Status Pill | Power Circle
                TopRemoteHeader(
                    connectionState = connectionState,
                    onFavoritesEditClick = {
                        triggerHaptic()
                        onOpenSettings()
                    },
                    onTvSelectorClick = {
                        triggerHaptic()
                        onOpenTvPicker()
                    },
                    onPowerClick = {
                        triggerHaptic()
                        onKeyPress(TvKeyCodes.POWER)
                    }
                )

                // 2. Top Sliders Block: Volume + TV Brightness (Full Bullnose Pill Sliders)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FullBullnoseDialSlider(
                        icon = if (isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        iconContentDescription = stringResource(R.string.cd_mute),
                        iconActive = isMuted,
                        value = volumeLevel,
                        minValue = 0,
                        maxValue = maxVolume.coerceAtLeast(1),
                        valueText = if (isMuted) "음소거" else volumeLevel.toString(),
                        hapticEnabled = hapticEnabled,
                        height = sliderHeight,
                        accentColor = MintGreen,
                        dimmed = isMuted,
                        iconTestTag = "btn_mute_toggle",
                        sliderTestTag = "volume_dial_slider",
                        valueTestTag = "volume_value_text",
                        onIconClick = onToggleMute,
                        onStep = onStepVolume
                    )

                    val isNightMode = brightnessPercent <= 35
                    FullBullnoseDialSlider(
                        icon = Icons.Rounded.WbSunny,
                        iconContentDescription = "TV 밝기 전환 (탭: 야간 30% / 낮 60%, 길게: TV 화질 설정)",
                        iconActive = !isNightMode,
                        value = brightnessPercent,
                        minValue = 5,
                        maxValue = 100,
                        valueText = "$brightnessPercent%",
                        hapticEnabled = hapticEnabled,
                        height = sliderHeight,
                        accentColor = WarningAmber,
                        dimmed = false,
                        iconTestTag = "btn_brightness_toggle",
                        sliderTestTag = "brightness_dial_slider",
                        valueTestTag = "brightness_value_text",
                        onIconClick = onToggleDayNightBrightness,
                        onIconLongClick = onOpenTvPictureSettings,
                        onStep = onStepBrightness
                    )
                }

                // 3. Favorites Chip Bar (App-colored Full Bullnose Pills + Trailing Mint '+' Circle)
                FavoritesChipBar(
                    favorites = favoriteApps,
                    onFavoriteClick = { app ->
                        triggerHaptic()
                        onLaunchFavorite(app)
                    },
                    onAddClick = {
                        triggerHaptic()
                        onOpenSettings()
                    }
                )

                // 4. Center Circular D-Pad
                CircularDpadController(
                    diameter = dpadDiameter,
                    hapticEnabled = hapticEnabled,
                    onKeyPress = { code ->
                        triggerHaptic()
                        onKeyPress(code)
                    },
                    onRepeatKeyPress = onKeyPress
                )

                // 5. Bottom 2x3 Circular Action Grid
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(rowGap),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Row 1: 키보드 | 홈 | 뒤로가기
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircleIconRemoteButton(
                            icon = Icons.Rounded.Keyboard,
                            contentDescription = stringResource(R.string.btn_keyboard),
                            size = bigCircleSize,
                            iconSize = 26.dp,
                            testTag = "btn_keyboard",
                            onClick = {
                                triggerHaptic()
                                onOpenKeyboard()
                            }
                        )

                        CircleIconRemoteButton(
                            icon = Icons.Rounded.Home,
                            contentDescription = stringResource(R.string.btn_home),
                            size = bigCircleSize,
                            iconSize = 26.dp,
                            testTag = "btn_home",
                            onClick = {
                                triggerHaptic()
                                onKeyPress(TvKeyCodes.HOME)
                            }
                        )

                        CircleIconRemoteButton(
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back),
                            size = bigCircleSize,
                            iconSize = 26.dp,
                            testTag = "btn_back",
                            onClick = {
                                triggerHaptic()
                                onKeyPress(TvKeyCodes.BACK)
                            }
                        )
                    }

                    // Row 2: 마이크 | Apps | TV 설정
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val micBg by animateColorAsState(
                            targetValue = if (isVoiceListening) MintGreen else CharcoalSurface,
                            label = "mic_bg"
                        )
                        val micTint by animateColorAsState(
                            targetValue = if (isVoiceListening) OnMintGreen else TextPrimary,
                            label = "mic_tint"
                        )
                        PushToTalkMicButton(
                            contentDescription = stringResource(R.string.btn_mic),
                            size = bigCircleSize,
                            iconSize = 26.dp,
                            containerColor = micBg,
                            iconTint = micTint,
                            borderColor = if (isVoiceListening) MintGreen else CharcoalBorder,
                            testTag = "btn_mic",
                            onTouchDown = {
                                triggerHaptic()
                                onMicTouchDown()
                            },
                            onTouchUp = onMicTouchUp
                        )

                        CircleIconRemoteButton(
                            icon = Icons.Rounded.Apps,
                            contentDescription = stringResource(R.string.btn_apps),
                            size = bigCircleSize,
                            iconSize = 26.dp,
                            testTag = "btn_apps",
                            onClick = {
                                triggerHaptic()
                                onKeyPress(TvKeyCodes.ALL_APPS)
                            }
                        )

                        CircleIconRemoteButton(
                            icon = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.btn_settings),
                            size = bigCircleSize,
                            iconSize = 26.dp,
                            testTag = "btn_settings",
                            onClick = {
                                triggerHaptic()
                                onKeyPress(TvKeyCodes.SETTINGS)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopRemoteHeader(
    connectionState: TvConnectionState,
    onFavoritesEditClick: () -> Unit,
    onTvSelectorClick: () -> Unit,
    onPowerClick: () -> Unit
) {
    val isConnected = connectionState is TvConnectionState.Connected
    val isConnecting = connectionState is TvConnectionState.Connecting

    val pillColor by animateColorAsState(
        targetValue = when (connectionState) {
            is TvConnectionState.Connected -> MintGreen
            is TvConnectionState.Connecting -> MintGreenContainer
            else -> CharcoalSurface
        },
        label = "tv_pill_color"
    )
    val pillBorderColor by animateColorAsState(
        targetValue = when (connectionState) {
            is TvConnectionState.Connected -> MintGreen
            is TvConnectionState.Connecting -> MintGreen.copy(alpha = 0.55f)
            is TvConnectionState.Error -> ErrorCoral.copy(alpha = 0.65f)
            else -> CharcoalBorder
        },
        label = "tv_pill_border"
    )
    val pillContentColor = when (connectionState) {
        is TvConnectionState.Connected -> OnMintGreen
        is TvConnectionState.Connecting -> MintGreen
        is TvConnectionState.Error -> ErrorCoral
        else -> TextPrimary
    }
    val pillIconTint = when (connectionState) {
        is TvConnectionState.Connected -> OnMintGreen
        is TvConnectionState.Connecting -> MintGreen
        is TvConnectionState.Error -> ErrorCoral
        else -> TextSecondary
    }
    val pillLabel = when (connectionState) {
        is TvConnectionState.Connected -> connectionState.tvName
        is TvConnectionState.Connecting -> "${connectionState.tvName} 연결 중..."
        is TvConnectionState.PairingRequired -> "${connectionState.tvName} PIN 대기"
        is TvConnectionState.Error -> stringResource(R.string.tv_select_needed)
        TvConnectionState.Disconnected -> stringResource(R.string.tv_select_needed)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        CircleIconRemoteButton(
            icon = Icons.Rounded.Star,
            contentDescription = stringResource(R.string.cd_favorites_edit),
            size = 48.dp,
            iconSize = 22.dp,
            containerColor = CharcoalSurface,
            iconTint = TextPrimary,
            testTag = "btn_favorites_edit",
            onClick = onFavoritesEditClick
        )

        // Full Bullnose TV Selector Pill
        Surface(
            color = pillColor,
            shape = CircleShape,
            modifier = Modifier
                .height(48.dp)
                .widthIn(min = 148.dp, max = 216.dp)
                .clip(CircleShape)
                .border(1.dp, pillBorderColor, CircleShape)
                .clickable(onClick = onTvSelectorClick)
                .testTag("btn_tv_selector_pill")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        color = MintGreen,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Tv,
                        contentDescription = null,
                        tint = pillIconTint,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = pillLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = pillContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "TV 목록 열기",
                    tint = if (isConnected) OnMintGreen else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        CircleIconRemoteButton(
            icon = Icons.Rounded.PowerSettingsNew,
            contentDescription = stringResource(R.string.cd_power),
            size = 48.dp,
            iconSize = 22.dp,
            containerColor = CharcoalSurface,
            iconTint = TextPrimary,
            testTag = "btn_power",
            onClick = onPowerClick
        )
    }
}

@Composable
private fun FavoritesChipBar(
    favorites: List<FavoriteAppEntity>,
    onFavoriteClick: (FavoriteAppEntity) -> Unit,
    onAddClick: () -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(favorites, key = { it.packageName }) { app ->
            val accentColor = Color(app.accentHex)
            Surface(
                color = CharcoalWell,
                shape = CircleShape,
                modifier = Modifier
                    .height(48.dp)
                    .clip(CircleShape)
                    .border(1.dp, accentColor.copy(alpha = 0.65f), CircleShape)
                    .clickable { onFavoriteClick(app) }
                    .testTag("favorite_chip_${app.iconKey}")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp, end = 14.dp)
                ) {
                    TvAppBadgeIcon(
                        iconKey = app.iconKey,
                        accentHex = app.accentHex,
                        appName = app.appName,
                        size = 28.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CharcoalWell)
                    .border(1.dp, MintGreen.copy(alpha = 0.72f), CircleShape)
                    .clickable(
                        onClickLabel = stringResource(R.string.add_favorite_chip),
                        onClick = onAddClick
                    )
                    .testTag("favorite_chip_add"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.add_favorite_chip),
                    tint = MintGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun CircleIconRemoteButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    iconSize: Dp,
    testTag: String,
    modifier: Modifier = Modifier,
    containerColor: Color = CharcoalSurface,
    iconTint: Color = TextPrimary,
    borderColor: Color = CharcoalBorder,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.91f else 1f,
        animationSpec = spring(stiffness = 700f),
        label = "circle_btn_scale"
    )

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(containerColor)
            .border(1.dp, borderColor, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true, color = MintGreen),
                onClickLabel = contentDescription,
                onClick = onClick
            )
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(iconSize)
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PushToTalkMicButton(
    contentDescription: String,
    size: Dp,
    iconSize: Dp,
    containerColor: Color,
    iconTint: Color,
    borderColor: Color,
    testTag: String,
    onTouchDown: () -> Unit,
    onTouchUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pressed by remember { mutableStateOf(false) }
    val latestOnTouchDown by rememberUpdatedState(onTouchDown)
    val latestOnTouchUp by rememberUpdatedState(onTouchUp)
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.91f else 1f,
        animationSpec = spring(stiffness = 700f),
        label = "mic_btn_scale"
    )

    DisposableEffect(Unit) {
        onDispose {
            if (pressed) {
                pressed = false
                latestOnTouchUp()
            }
        }
    }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(containerColor)
            .border(1.dp, borderColor, CircleShape)
            .pointerInteropFilter { motionEvent ->
                when (motionEvent.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!pressed) {
                            pressed = true
                            latestOnTouchDown()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        if (pressed) {
                            pressed = false
                            latestOnTouchUp()
                        }
                        true
                    }
                    else -> true
                }
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(iconSize)
        )
    }
}
