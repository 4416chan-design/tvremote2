package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CharcoalBorder
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CharcoalSurfaceElevated
import com.example.ui.theme.CharcoalWell
import com.example.ui.theme.MintGreen
import com.example.ui.theme.MintGreenContainer
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.OnMintGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

/**
 * First-launch & on-demand guide dialog for installing ADBKeyBoard (`keyboardservice-debug.apk`)
 * onto the connected Android TV so Korean input and full-text replace (`ADB_CLEAR_TEXT`) work reliably.
 */
@Composable
fun AdbKeyboardSetupDialog(
    isInstalled: Boolean?,
    statusMessage: String?,
    isInstalling: Boolean,
    onPickApkFromDownloads: () -> Unit,
    onOpenTvDownloads: () -> Unit,
    onRedownloadInBrowser: () -> Unit,
    onVerifyInstalled: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CharcoalSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = "TV용 ADBKeyBoard 설치 안내",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "TV에서 한글 전송과 전체 텍스트 교체 전송을 사용하려면 TV에 ADBKeyBoard 앱이 설치되어 있어야 합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Surface(
                    color = CharcoalWell,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CharcoalBorder, RoundedCornerShape(14.dp))
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = "설치 방법",
                            style = MaterialTheme.typography.labelMedium,
                            color = MintGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "1. 인터넷 창에서 다운로드된 파일(keyboardservice-debug.apk)을 확인하세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "2. Download 폴더의 keyboardservice-debug.apk 파일을 TV에서 열어 설치하거나, 아래 버튼으로 다운로드한 APK를 선택해 TV에 바로 설치하세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Download/keyboardservice-debug.apk",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = MonoFontFamily,
                            color = TextSecondary
                        )
                    }
                }

                if (isInstalling) {
                    LinearProgressIndicator(
                        color = MintGreen,
                        trackColor = CharcoalWell,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!statusMessage.isNullOrBlank()) {
                    val statusColor = if (isInstalled == true) MintGreen else WarningAmber
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isInstalled == true) MintGreenContainer else CharcoalWell,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Button(
                    onClick = onPickApkFromDownloads,
                    enabled = !isInstalling,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MintGreen,
                        contentColor = OnMintGreen
                    ),
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("adb_kb_pick_apk_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.InstallMobile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Download APK 선택 → TV 설치", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onOpenTvDownloads,
                    enabled = !isInstalling,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = CharcoalSurfaceElevated,
                        contentColor = TextPrimary
                    ),
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("adb_kb_open_tv_downloads_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("TV Download 폴더 / APK 열기")
                }

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        onClick = onRedownloadInBrowser,
                        enabled = !isInstalling,
                        modifier = Modifier.testTag("adb_kb_redownload_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("브라우저 다운로드", color = TextSecondary)
                    }

                    TextButton(
                        onClick = onVerifyInstalled,
                        enabled = !isInstalling,
                        modifier = Modifier.testTag("adb_kb_verify_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MintGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("설치 확인", color = MintGreen, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("adb_kb_dismiss_button")
            ) {
                Text("닫기", color = TextSecondary)
            }
        }
    )
}
