package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
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
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.data.model.PhysicalKeyAction
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
import com.example.ui.theme.OnMintGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Keyboard input bottom sheet for Android TV:
 * 1) Real-time physical KeyEvent streaming directly from phone native keyboard typing & Backspace.
 * 2) Full-field replacement submission (`전송`: replace existing text + Enter).
 * 3) Always-enabled `전체 지우기` (`Ctrl+A -> DEL`) regardless of local text field state.
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
    onRealTimeKeyEdit: (backspaceCount: Int, keyActions: List<PhysicalKeyAction>) -> Unit = { _, _ -> },
    onClearAll: () -> Unit = {},
    onSendText: (text: String, replaceExisting: Boolean, submitWithEnter: Boolean) -> Unit,
    onSendKey: (Int) -> Unit,
    onBackspace: (Int) -> Unit = { count -> repeat(count) { onSendKey(TvKeyCodes.DEL) } },
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    val haptic = LocalHapticFeedback.current
    val hostFocusManager = LocalFocusManager.current
    val hostKeyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val hostView = LocalView.current

    fun hidePhoneKeyboardAndClearFocus(extraView: android.view.View? = null) {
        hostFocusManager.clearFocus(force = true)
        hostKeyboardController?.hide()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        extraView?.windowToken?.let { token ->
            imm?.hideSoftInputFromWindow(token, 0)
        }
        hostView.windowToken?.let { token ->
            imm?.hideSoftInputFromWindow(token, 0)
        }
    }

    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialText,
                selection = TextRange(initialText.length)
            )
        )
    }
    var userEditedLocally by remember { mutableStateOf(false) }

    LaunchedEffect(initialText) {
        if (!userEditedLocally && fieldValue.text.isEmpty() && initialText.isNotEmpty()) {
            fieldValue = TextFieldValue(
                text = initialText,
                selection = TextRange(initialText.length)
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            hidePhoneKeyboardAndClearFocus()
            onDismiss()
        },
        sheetState = sheetState,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = CharcoalBg,
        contentColor = TextPrimary
    ) {
        val sheetFocusManager = LocalFocusManager.current
        val sheetKeyboardController = LocalSoftwareKeyboardController.current
        val sheetView = LocalView.current

        fun closeSheetAndKeyboard() {
            sheetFocusManager.clearFocus(force = true)
            sheetKeyboardController?.hide()
            hidePhoneKeyboardAndClearFocus(sheetView)
            onDismiss()
        }

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
                            "TV IME: $tvLangLabel (${tvImeStatus.imeType.displayName}) · 폰 키보드 실시간 키코드 전송" to MintGreen
                        isTvImeVisible ->
                            "TV 입력창 연결됨 · 폰 키보드 실시간 / 전체 전송" to MintGreen
                        else ->
                            "폰 키보드로 입력하면 키코드가 TV로 실시간 전송됩니다" to (
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
                        .clickable(onClick = ::closeSheetAndKeyboard)
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
                val textToSend = fieldValue.text
                fieldValue = fieldValue.copy(composition = null)
                onSendText(textToSend, true, true)
                closeSheetAndKeyboard()
            }

            OutlinedTextField(
                value = fieldValue,
                onValueChange = { next ->
                    val previousValue = fieldValue
                    userEditedLocally = true
                    fieldValue = next
                    val edit = TvImeKeycodeMap.computeRealTimeEditActions(
                        previousText = previousValue.text,
                        previousSelStart = previousValue.selection.min,
                        previousSelEnd = previousValue.selection.max,
                        nextText = next.text,
                        imeType = tvImeStatus.imeType
                    )
                    if (edit.backspaceCount > 0 || edit.keyActions.isNotEmpty()) {
                        if (edit.keyActions.isEmpty()) {
                            onBackspace(edit.backspaceCount)
                        } else {
                            onRealTimeKeyEdit(edit.backspaceCount, edit.keyActions)
                        }
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
                        text = "$tvName 에 실시간 입력 (또는 입력 후 전송)...",
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

            // Text Field Pill Buttons: Always-enabled 전체 지우기 (Ctrl+A -> DEL) | 전송
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        userEditedLocally = true
                        fieldValue = TextFieldValue("")
                        onClearAll()
                    },
                    enabled = true,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CharcoalSurfaceElevated,
                        contentColor = TextPrimary
                    ),
                    border = BorderStroke(1.dp, CharcoalBorder),
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .testTag("keyboard_clear_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Backspace,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "전체 지우기",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
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

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = DividerSubtle, thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            // Compact TV IME status sync & 한/영 toggle strip (no custom keyboard grid)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "TV 한/영 전환",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
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
