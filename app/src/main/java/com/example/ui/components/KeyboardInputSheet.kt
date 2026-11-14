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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CharcoalBg
import com.example.ui.theme.CharcoalBorder
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CharcoalSurfaceElevated
import com.example.ui.theme.CharcoalWell
import com.example.ui.theme.MintGreen
import com.example.ui.theme.OnMintGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

/**
 * Reference-matched Korean + English keyboard input sheet for Android TV IME input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardInputSheet(
    tvName: String,
    initialText: String,
    isTvImeVisible: Boolean = false,
    isAdbConnected: Boolean = false,
    isAdbKeyboardInstalled: Boolean? = null,
    onOpenAdbKeyboardSetup: () -> Unit = {},
    onSendText: (text: String, replaceExisting: Boolean, submitWithEnter: Boolean) -> Unit,
    onSendKey: (Int) -> Unit,
    onBackspace: (Int) -> Unit = { count -> repeat(count) { onSendKey(com.example.data.model.TvKeyCodes.DEL) } },
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }

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
                .padding(horizontal = 22.dp, vertical = 20.dp)
                .navigationBarsPadding()
        ) {
            // Header: Keyboard Icon + "키보드 입력" | Close 'X' Circle
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
                    val (statusText, statusColor) = when {
                        isAdbConnected && isAdbKeyboardInstalled == true ->
                            "ADBKeyBoard 연결됨 · 백스페이스 실시간 · 전체 교체 전송" to MintGreen
                        isAdbConnected && isAdbKeyboardInstalled == false ->
                            "ADBKeyBoard 미설치 · Download APK 설치 필요" to WarningAmber
                        isTvImeVisible ->
                            "TV 입력창 연결됨 (한글/영문 지원)" to MintGreen
                        else ->
                            "TV 검색창/키보드를 연 상태에서 입력 후 전송하세요" to (
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
                        .size(42.dp)
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

            if (isAdbKeyboardInstalled == false) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = CharcoalSurface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, WarningAmber.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                        .clickable(onClick = onOpenAdbKeyboardSetup)
                        .testTag("adb_keyboard_setup_banner")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ADBKeyBoard 미설치 · Download APK 설치 안내 열기",
                            style = MaterialTheme.typography.labelMedium,
                            color = WarningAmber,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

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
                    .height(136.dp)
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
                maxLines = 5,
                shape = RoundedCornerShape(18.dp),
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

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom Full Bullnose Pill Buttons: 전체 지우기 | 전송
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = CharcoalSurface,
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(CircleShape)
                        .border(1.dp, CharcoalBorder, CircleShape)
                        .clickable {
                            userEditedLocally = true
                            fieldValue = TextFieldValue("")
                            onSendText("", true, false)
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
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
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
                        .height(52.dp)
                        .testTag("keyboard_send_search_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "전송",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
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
