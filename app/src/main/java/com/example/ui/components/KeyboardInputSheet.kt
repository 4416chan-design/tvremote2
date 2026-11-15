package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardCapslock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PhysicalKeySpec
import com.example.data.model.TvImeKeycodeMap
import com.example.data.model.TvImeStatus
import com.example.data.model.TvKeyboardLanguage
import com.example.data.model.TvKeyCodes
import com.example.ui.theme.CharcoalBg
import com.example.ui.theme.CharcoalBorder
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CharcoalSurfaceElevated
import com.example.ui.theme.CharcoalWell
import com.example.ui.theme.DividerSubtle
import com.example.ui.theme.MintGreen
import com.example.ui.theme.MintGreenContainer
import com.example.ui.theme.OnMintGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Dual-mode Korean + English keyboard sheet for Android TV:
 * 1) Full text-field submission (`전체 교체 전송` + real-time Backspace).
 * 2) Direct physical-key keypad (`QWERTY / 두벌식 한·영 전환`) mapped per TV IME without phone IME composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardInputSheet(
    tvName: String,
    initialText: String,
    isTvImeVisible: Boolean = false,
    isAdbConnected: Boolean = false,
    keyboardLanguage: TvKeyboardLanguage = TvKeyboardLanguage.ENGLISH,
    tvImeStatus: TvImeStatus = TvImeStatus(),
    onToggleLanguage: () -> Unit = {},
    onResyncTvIme: () -> Unit = {},
    onPhysicalKeyPress: (PhysicalKeySpec, Boolean) -> Unit = { _, _ -> },
    onClearAll: () -> Unit = {},
    onSendText: (text: String, replaceExisting: Boolean, submitWithEnter: Boolean) -> Unit,
    onSendKey: (Int) -> Unit,
    onBackspace: (Int) -> Unit = { count -> repeat(count) { onSendKey(TvKeyCodes.DEL) } },
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    val haptic = LocalHapticFeedback.current

    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialText,
                selection = TextRange(initialText.length)
            )
        )
    }
    var userEditedLocally by remember { mutableStateOf(false) }
    var isShiftActive by remember { mutableStateOf(false) }

    LaunchedEffect(initialText) {
        if (!userEditedLocally && fieldValue.text.isEmpty() && initialText.isNotEmpty()) {
            fieldValue = TextFieldValue(
                text = initialText,
                selection = TextRange(initialText.length)
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = CharcoalBg,
        contentColor = TextPrimary
    ) {
        LaunchedEffect(focusRequester) {
            kotlinx.coroutines.yield()
            runCatching { focusRequester.requestFocus() }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .navigationBarsPadding()
        ) {
            // Header: Keyboard Icon + Title & TV IME status | Close 'X' Circle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Rounded.Keyboard,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "키보드 입력",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    val tvLangLabel = if (tvImeStatus.language == TvKeyboardLanguage.KOREAN) "한글" else "영문"
                    val (statusText, statusColor) = when {
                        tvImeStatus.isDetected ->
                            "TV IME: $tvLangLabel (${tvImeStatus.imeType.displayName}) · 물리키 실시간/텍스트 전송" to MintGreen
                        isTvImeVisible ->
                            "TV 입력창 연결됨 · 물리키 실시간 / 텍스트 전송" to MintGreen
                        else ->
                            "물리키 자판으로 바로 입력하거나 텍스트 입력 후 전송하세요" to (
                                if (isAdbConnected) MintGreen else TextSecondary
                                )
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(CharcoalSurface)
                        .border(1.dp, CharcoalBorder, CircleShape)
                        .clickable(onClick = onDismiss)
                        .testTag("keyboard_close_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "닫기",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            fun submitCurrentText() {
                fieldValue = fieldValue.copy(composition = null)
                onSendText(fieldValue.text, true, true)
            }

            OutlinedTextField(
                value = fieldValue,
                onValueChange = { next ->
                    val previousValue = fieldValue
                    userEditedLocally = true
                    fieldValue = next
                    val deletedCount = computeRealTimeBackspaceCount(previousValue, next)
                    if (deletedCount > 0) {
                        onBackspace(deletedCount)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 76.dp, max = 96.dp)
                    .onPreviewKeyEvent { keyEvent ->
                        if (fieldValue.text.isEmpty() &&
                            keyEvent.type == KeyEventType.KeyDown &&
                            keyEvent.key == Key.Backspace
                        ) {
                            onBackspace(1)
                            true
                        } else {
                            false
                        }
                    }
                    .focusRequester(focusRequester)
                    .testTag("tv_keyboard_input_field"),
                placeholder = {
                    Text(
                        text = "$tvName 에 전송할 텍스트를 입력하세요...",
                        color = TextSecondary
                    )
                },
                maxLines = 3,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CharcoalWell,
                    unfocusedContainerColor = CharcoalWell,
                    focusedBorderColor = CharcoalBorder,
                    unfocusedBorderColor = CharcoalBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = MintGreen
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { submitCurrentText() }
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Text Field Pill Buttons: 전체 지우기 (Ctrl+A -> DEL) | 전송
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = CharcoalSurface,
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(CircleShape)
                        .border(1.dp, CharcoalBorder, CircleShape)
                        .clickable {
                            userEditedLocally = true
                            fieldValue = TextFieldValue("")
                            onClearAll()
                        }
                        .testTag("keyboard_clear_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Backspace,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "전체 지우기",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextSecondary
                        )
                    }
                }

                val hasText = fieldValue.text.isNotEmpty()
                Button(
                    onClick = { submitCurrentText() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasText) MintGreen else CharcoalSurfaceElevated,
                        contentColor = if (hasText) OnMintGreen else TextPrimary
                    ),
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .testTag("keyboard_send_search_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "전송",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = DividerSubtle, thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            // Real-time Physical Keypad Header Bar: Mode label + Resync + 영/한 Switcher
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "물리키 실시간 입력",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary
                )
                if (isAdbConnected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = CharcoalSurface,
                        shape = CircleShape,
                        modifier = Modifier
                            .clip(CircleShape)
                            .border(1.dp, CharcoalBorder, CircleShape)
                            .clickable(onClick = onResyncTvIme)
                            .testTag("keyboard_resync_ime_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "TV IME 한/영 다시 읽기",
                                tint = MintGreen,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "IME 읽기",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))

                Surface(
                    color = CharcoalWell,
                    shape = CircleShape,
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(1.dp, CharcoalBorder, CircleShape)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isShiftActive = false
                            onToggleLanguage()
                        }
                        .testTag("keyboard_language_toggle")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(2.dp)
                    ) {
                        val isKorean = keyboardLanguage == TvKeyboardLanguage.KOREAN
                        LanguageChipSegment(label = "영", selected = !isKorean)
                        LanguageChipSegment(label = "한", selected = isKorean)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            fun pressKeySpec(spec: PhysicalKeySpec) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                val shiftNow = isShiftActive
                onPhysicalKeyPress(spec, shiftNow)
                if (shiftNow && spec.supportsShift) {
                    isShiftActive = false
                }
            }

            // Row 1: Numbers 1..0
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("physical_keypad_grid")
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TvImeKeycodeMap.numberRow.forEach { spec ->
                        PhysicalKeyCap(
                            label = spec.labelFor(keyboardLanguage, shifted = false),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("phys_key_${spec.id}"),
                            onClick = { pressKeySpec(spec) }
                        )
                    }
                }

                // Row 2: QWERTY / ㅂ..ㅔ
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TvImeKeycodeMap.topRow.forEach { spec ->
                        PhysicalKeyCap(
                            label = spec.labelFor(keyboardLanguage, shifted = isShiftActive),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("phys_key_${spec.id}"),
                            onClick = { pressKeySpec(spec) }
                        )
                    }
                }

                // Row 3: ASDF..L / ㅁ..ㅣ
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Spacer(modifier = Modifier.weight(0.45f))
                    TvImeKeycodeMap.homeRow.forEach { spec ->
                        PhysicalKeyCap(
                            label = spec.labelFor(keyboardLanguage, shifted = isShiftActive),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("phys_key_${spec.id}"),
                            onClick = { pressKeySpec(spec) }
                        )
                    }
                    Spacer(modifier = Modifier.weight(0.45f))
                }

                // Row 4: Shift + Z..M / ㅋ..ㅡ + Backspace
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PhysicalIconKeyCap(
                        icon = Icons.Rounded.KeyboardCapslock,
                        contentDescription = "Shift",
                        containerColor = if (isShiftActive) MintGreen else CharcoalSurfaceElevated,
                        contentColor = if (isShiftActive) OnMintGreen else TextPrimary,
                        modifier = Modifier
                            .weight(1.45f)
                            .testTag("phys_key_shift"),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isShiftActive = !isShiftActive
                        }
                    )
                    TvImeKeycodeMap.bottomRow.forEach { spec ->
                        PhysicalKeyCap(
                            label = spec.labelFor(keyboardLanguage, shifted = isShiftActive),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("phys_key_${spec.id}"),
                            onClick = { pressKeySpec(spec) }
                        )
                    }
                    PhysicalIconKeyCap(
                        icon = Icons.AutoMirrored.Rounded.Backspace,
                        contentDescription = "지우기",
                        containerColor = CharcoalSurfaceElevated,
                        contentColor = TextPrimary,
                        modifier = Modifier
                            .weight(1.45f)
                            .testTag("phys_key_backspace"),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onBackspace(1)
                        }
                    )
                }

                // Row 5: 한/영 | , | Space | . | Enter
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val isKorean = keyboardLanguage == TvKeyboardLanguage.KOREAN
                    PhysicalKeyCap(
                        label = "한/영",
                        containerColor = if (isKorean) MintGreenContainer else CharcoalSurfaceElevated,
                        contentColor = if (isKorean) MintGreen else TextPrimary,
                        modifier = Modifier
                            .weight(1.8f)
                            .testTag("phys_key_lang_switch"),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isShiftActive = false
                            onToggleLanguage()
                        }
                    )
                    PhysicalKeyCap(
                        label = ",",
                        modifier = Modifier
                            .weight(0.9f)
                            .testTag("phys_key_comma"),
                        onClick = { pressKeySpec(TvImeKeycodeMap.commaSpec) }
                    )
                    PhysicalKeyCap(
                        label = "Space",
                        containerColor = CharcoalSurface,
                        contentColor = TextSecondary,
                        modifier = Modifier
                            .weight(3.8f)
                            .testTag("phys_key_space"),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSendKey(TvKeyCodes.SPACE)
                        }
                    )
                    PhysicalKeyCap(
                        label = ".",
                        modifier = Modifier
                            .weight(0.9f)
                            .testTag("phys_key_period"),
                        onClick = { pressKeySpec(TvImeKeycodeMap.periodSpec) }
                    )
                    PhysicalIconKeyCap(
                        icon = Icons.AutoMirrored.Rounded.KeyboardReturn,
                        contentDescription = "Enter",
                        containerColor = MintGreen,
                        contentColor = OnMintGreen,
                        modifier = Modifier
                            .weight(1.8f)
                            .testTag("phys_key_enter"),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSendKey(TvKeyCodes.ENTER)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageChipSegment(
    label: String,
    selected: Boolean
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) MintGreen else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) OnMintGreen else TextSecondary
        )
    }
}

@Composable
private fun PhysicalKeyCap(
    label: String,
    modifier: Modifier = Modifier,
    containerColor: Color = CharcoalSurface,
    contentColor: Color = TextPrimary,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(9.dp)
    Surface(
        color = containerColor,
        shape = shape,
        modifier = modifier
            .height(38.dp)
            .clip(shape)
            .border(1.dp, CharcoalBorder, shape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun PhysicalIconKeyCap(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(9.dp)
    Surface(
        color = containerColor,
        shape = shape,
        modifier = modifier
            .height(38.dp)
            .clip(shape)
            .border(1.dp, CharcoalBorder, shape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Returns the number of characters removed by a pure Backspace/deletion edit,
 * returning 0 for text insertions or Korean Hangul IME syllable composition transitions.
 */
internal fun computeRealTimeBackspaceCount(
    previous: TextFieldValue,
    next: TextFieldValue
): Int {
    val prevText = previous.text
    val nextText = next.text
    if (nextText.length >= prevText.length) return 0

    if (!previous.selection.collapsed) {
        val start = previous.selection.min.coerceIn(0, prevText.length)
        val end = previous.selection.max.coerceIn(start, prevText.length)
        val afterDelete = prevText.removeRange(start, end)
        return if (afterDelete == nextText) {
            Character.codePointCount(prevText, start, end).coerceAtLeast(0)
        } else {
            0
        }
    }

    val prefixLen = prevText.commonPrefixWith(nextText).length
    val prevRemaining = prevText.substring(prefixLen)
    val nextRemaining = nextText.substring(prefixLen)
    val suffixLen = prevRemaining.commonSuffixWith(nextRemaining).length
    return if (prefixLen + suffixLen == nextText.length) {
        val deleteEnd = (prevText.length - suffixLen).coerceAtLeast(prefixLen)
        Character.codePointCount(prevText, prefixLen, deleteEnd).coerceAtLeast(0)
    } else {
        0
    }
}
