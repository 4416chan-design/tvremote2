package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.PairedTvEntity
import com.example.data.model.DiscoveredTv
import com.example.data.model.TvConnectionState
import com.example.network.AdbConnectionState
import com.example.ui.theme.CharcoalBg
import com.example.ui.theme.CharcoalBorder
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CharcoalWell
import com.example.ui.theme.DividerSubtle
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.MintGreen
import com.example.ui.theme.MintGreenContainer
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.OnMintGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvConnectionSheet(
    discoveredTvs: List<DiscoveredTv>,
    pairedTvs: List<PairedTvEntity>,
    isScanning: Boolean,
    scanError: String?,
    connectionState: TvConnectionState,
    adbState: AdbConnectionState = AdbConnectionState.DISCONNECTED,
    adbStatusDetail: String? = null,
    adbKeyboardInstalled: Boolean? = null,
    onRefreshScan: () -> Unit,
    onRetryAdb: () -> Unit = {},
    onOpenAdbKeyboardSetup: () -> Unit = {},
    onConnectTv: (host: String, name: String, remotePort: Int, pairingPort: Int) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showManualIp by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = CharcoalBg,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 20.dp)
                .navigationBarsPadding()
        ) {
            // Header: "기기 선택" | Refresh Circle | Close 'X' Circle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "기기 선택",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(CharcoalSurface)
                        .border(1.dp, CharcoalBorder, CircleShape)
                        .clickable(onClick = onRefreshScan)
                        .testTag("refresh_tv_scan_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "다시 검색",
                        tint = if (isScanning) MintGreen else TextSecondary,
                        modifier = Modifier.size(20.dp)
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
                        .testTag("close_tv_sheet_button"),
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

            Spacer(modifier = Modifier.height(14.dp))

            if (isScanning) {
                LinearProgressIndicator(
                    color = TextPrimary,
                    trackColor = DividerSubtle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            } else {
                HorizontalDivider(color = DividerSubtle, thickness = 1.dp)
            }

            if (!scanError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = scanError,
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorCoral
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "사용 가능한 기기",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(10.dp))

            val connectedHost = (connectionState as? TvConnectionState.Connected)?.host
            val discoveredHosts = remember(discoveredTvs) { discoveredTvs.map { it.host }.toSet() }
            val savedOnlyTvs = remember(pairedTvs, discoveredHosts) {
                pairedTvs.filterNot { discoveredHosts.contains(it.host) }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 260.dp)
            ) {
                if (connectionState is TvConnectionState.Connected) {
                    item(key = "active_connected") {
                        TvDevicePillRow(
                            name = connectionState.tvName,
                            subtitle = connectionState.host,
                            actionText = "연결됨 · 해제",
                            isConnected = true,
                            onClick = onDisconnect
                        )
                    }
                    item(key = "active_adb_status") {
                        val (adbLabel, adbColor) = when (adbState) {
                            AdbConnectionState.CONNECTED -> "ADB 5555 연결됨 · 백라이트·즐겨찾기 실행 준비 완료" to MintGreen
                            AdbConnectionState.AUTHORIZING -> "TV 화면에서 디버깅 '허용'을 눌러주세요" to WarningAmber
                            AdbConnectionState.CONNECTING -> "ADB(5555) 연결 시도 중..." to TextSecondary
                            AdbConnectionState.UNAVAILABLE -> (adbStatusDetail ?: "TV 개발자 옵션 > 디버깅 확인") to WarningAmber
                            AdbConnectionState.DISCONNECTED -> "ADB 5555 미연결" to TextSecondary
                        }
                        Surface(
                            color = CharcoalSurface,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, CharcoalBorder, RoundedCornerShape(16.dp))
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = adbLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = adbColor,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (adbState != AdbConnectionState.CONNECTED && adbState != AdbConnectionState.CONNECTING) {
                                        TextButton(
                                            onClick = onRetryAdb,
                                            modifier = Modifier.testTag("retry_adb_button")
                                        ) {
                                            Text("ADB 연결", color = MintGreen)
                                        }
                                    }
                                }

                                val (kbLabel, kbColor, kbActionLabel) = when (adbKeyboardInstalled) {
                                    true -> Triple("ADBKeyBoard 설치됨 · 한글 전송 준비 완료", MintGreen, null)
                                    false -> Triple("ADBKeyBoard 미설치 · Download APK 설치 필요", WarningAmber, "설치")
                                    null -> Triple("ADBKeyBoard 상태 확인 및 APK 설치", TextSecondary, "설치 안내")
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = kbLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = kbColor,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (kbActionLabel != null) {
                                        TextButton(
                                            onClick = onOpenAdbKeyboardSetup,
                                            modifier = Modifier.testTag("adb_keyboard_install_button")
                                        ) {
                                            Text(kbActionLabel, color = MintGreen)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                items(
                    items = discoveredTvs.filter { it.host != connectedHost },
                    key = { "mdns_${it.host}" }
                ) { tv ->
                    TvDevicePillRow(
                        name = tv.serviceName,
                        subtitle = tv.host,
                        actionText = "연결",
                        isConnected = false,
                        onClick = { onConnectTv(tv.host, tv.serviceName, tv.port, tv.pairingPort) }
                    )
                }

                items(
                    items = savedOnlyTvs.filter { it.host != connectedHost },
                    key = { "saved_${it.host}" }
                ) { tv ->
                    TvDevicePillRow(
                        name = tv.name,
                        subtitle = tv.host,
                        actionText = "연결",
                        isConnected = false,
                        onClick = { onConnectTv(tv.host, tv.name, tv.remotePort, tv.pairingPort) }
                    )
                }

                if (discoveredTvs.isEmpty() && pairedTvs.isEmpty() && connectionState !is TvConnectionState.Connected) {
                    item {
                        Surface(
                            color = CharcoalSurface,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, CharcoalBorder, RoundedCornerShape(20.dp))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Wifi,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = if (isScanning) "같은 Wi-Fi의 Android TV를 검색 중입니다..."
                                    else "검색된 기기가 없습니다. 아래 IP 직접 연결을 이용해 주세요.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Wi-Fi를 통해 연결하려면 휴대전화와 TV가 동일한 Wi-Fi 네트워크에 연결되어 있어야 합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                color = CharcoalWell,
                shape = CircleShape,
                modifier = Modifier
                    .clip(CircleShape)
                    .border(1.dp, CharcoalBorder, CircleShape)
                    .clickable { showManualIp = !showManualIp }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = MintGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (showManualIp) "IP 직접 입력 닫기" else "IP 주소 직접 연결",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary
                    )
                }
            }

            AnimatedVisibility(visible = showManualIp) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        placeholder = { Text("192.168.0.10", fontFamily = MonoFontFamily) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFontFamily),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (manualIp.isNotBlank()) {
                                    onConnectTv(manualIp, "Android TV", 6466, 6467)
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = CharcoalWell,
                            unfocusedContainerColor = CharcoalWell,
                            focusedBorderColor = MintGreen,
                            unfocusedBorderColor = CharcoalBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_tv_ip_input")
                    )

                    Button(
                        onClick = { onConnectTv(manualIp, "Android TV", 6466, 6467) },
                        enabled = manualIp.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MintGreen,
                            contentColor = OnMintGreen
                        ),
                        shape = CircleShape,
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("manual_tv_connect_button")
                    ) {
                        Text("연결", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TvDevicePillRow(
    name: String,
    subtitle: String,
    actionText: String,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (isConnected) MintGreenContainer else CharcoalSurface,
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(CircleShape)
            .border(1.dp, if (isConnected) MintGreen.copy(alpha = 0.6f) else CharcoalBorder, CircleShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Tv,
                contentDescription = null,
                tint = if (isConnected) MintGreen else TextSecondary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFontFamily,
                    color = TextSecondary
                )
            }
            Text(
                text = actionText,
                style = MaterialTheme.typography.labelLarge,
                color = if (isConnected) MintGreen else TextSecondary
            )
        }
    }
}

@Composable
fun PairingPinDialog(
    state: TvConnectionState.PairingRequired,
    onSubmitPin: (String) -> Unit,
    onCancel: () -> Unit
) {
    var pin by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = CharcoalSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = "${state.tvName} 페어링 PIN 입력",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "TV 화면 오른쪽 아래에 표시된 6자리 코드(숫자·영문)를 입력해 주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { input ->
                        if (input.length <= 6) {
                            pin = input.uppercase()
                        }
                    },
                    placeholder = { Text("예: A1B2C3", fontFamily = MonoFontFamily) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = MonoFontFamily),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (pin.length == 6) onSubmitPin(pin)
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CharcoalWell,
                        unfocusedContainerColor = CharcoalWell,
                        focusedBorderColor = MintGreen,
                        unfocusedBorderColor = DividerSubtle,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pairing_pin_input")
                )
                if (!state.errorMessage.isNullOrBlank()) {
                    Text(
                        text = state.errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ErrorCoral
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmitPin(pin) },
                enabled = pin.length == 6,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MintGreen,
                    contentColor = OnMintGreen
                ),
                modifier = Modifier.testTag("pairing_confirm_button")
            ) {
                Text("페어링 완료", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("pairing_cancel_button")
            ) {
                Text("취소", color = TextSecondary)
            }
        }
    )
}
