package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.model.TvConnectionState
import com.example.ui.RemoteScreen
import com.example.ui.RemoteViewModel
import com.example.ui.SettingsScreen
import com.example.ui.components.AdbKeyboardSetupDialog
import com.example.ui.components.KeyboardInputSheet
import com.example.ui.components.PairingPinDialog
import com.example.ui.components.TvConnectionSheet
import com.example.ui.theme.TvRemoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ROUTE_REMOTE = "remote"
const val ROUTE_SETTINGS = "settings"

class MainActivity : ComponentActivity() {

    private val viewModel: RemoteViewModel by viewModels {
        val app = application as TvRemoteApplication
        RemoteViewModel.Factory(app.repository, app.certStore, app.discoveryManager, app.adbClient)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TvRemoteTheme {
                val context = LocalContext.current
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }
                val ioScope = rememberCoroutineScope()

                val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
                val discoveredTvs by viewModel.discoveredTvs.collectAsStateWithLifecycle()
                val pairedTvs by viewModel.pairedTvs.collectAsStateWithLifecycle()
                val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
                val scanError by viewModel.scanError.collectAsStateWithLifecycle()
                val favoriteApps by viewModel.favoriteApps.collectAsStateWithLifecycle()
                val recentApps by viewModel.recentApps.collectAsStateWithLifecycle()
                val preferences by viewModel.preferences.collectAsStateWithLifecycle()
                val volumeLevel by viewModel.volumeLevel.collectAsStateWithLifecycle()
                val maxVolume by viewModel.maxVolume.collectAsStateWithLifecycle()
                val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
                val imePromptState by viewModel.imePromptState.collectAsStateWithLifecycle()
                val isKeyboardSheetVisible by viewModel.isKeyboardSheetVisible.collectAsStateWithLifecycle()
                val isTvPickerVisible by viewModel.isTvPickerVisible.collectAsStateWithLifecycle()
                val isVoiceListening by viewModel.isVoiceListening.collectAsStateWithLifecycle()
                val snackbarNotice by viewModel.snackbarNotice.collectAsStateWithLifecycle()
                val adbState by viewModel.adbState.collectAsStateWithLifecycle()
                val adbStatusDetail by viewModel.adbStatusDetail.collectAsStateWithLifecycle()
                val adbKeyboardInstalled by viewModel.adbKeyboardInstalled.collectAsStateWithLifecycle()
                val isAdbKeyboardSetupDialogVisible by viewModel.isAdbKeyboardSetupDialogVisible.collectAsStateWithLifecycle()
                val adbKeyboardSetupStatus by viewModel.adbKeyboardSetupStatus.collectAsStateWithLifecycle()
                val isInstallingAdbKeyboard by viewModel.isInstallingAdbKeyboard.collectAsStateWithLifecycle()

                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    viewModel.autoReconnectIfNeeded()
                }

                LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                    val activity = context as? android.app.Activity
                    if (activity?.isChangingConfigurations != true) {
                        viewModel.onAppBackgrounded()
                    }
                }

                LaunchedEffect(snackbarNotice) {
                    val notice = snackbarNotice ?: return@LaunchedEffect
                    val result = snackbarHostState.showSnackbar(
                        message = notice.message,
                        actionLabel = notice.actionLabel,
                        duration = if (notice.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        notice.onAction?.invoke()
                    }
                    viewModel.clearSnackbar()
                }

                LaunchedEffect(viewModel) {
                    viewModel.openUrlRequests.collect { url ->
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val launched = runCatching { context.startActivity(intent) }.isSuccess
                        if (!launched) {
                            viewModel.showMessage("인터넷 브라우저를 열 수 없습니다. 직접 다운로드해 주세요.")
                        }
                    }
                }

                val apkPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    ioScope.launch {
                        val bytes = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            }.getOrNull()
                        }
                        if (bytes == null || bytes.isEmpty()) {
                            viewModel.showMessage("선택한 APK 파일을 읽을 수 없습니다.")
                        } else {
                            viewModel.installAdbKeyboardFromLocalApkBytes(bytes)
                        }
                    }
                }

                // On-device SpeechRecognizer fallback (used only when TV does not open RemoteVoiceBegin)
                val speechRecognizer = remember(context) {
                    if (SpeechRecognizer.isRecognitionAvailable(context)) {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    } else null
                }

                fun launchSpeechRecognizerFallback() {
                    if (speechRecognizer == null) {
                        viewModel.setVoiceListening(false)
                        viewModel.showMessage("TV 음성 스트리밍 응답이 없고 기기 음성 인식기도 사용할 수 없습니다.")
                        return
                    }
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    }
                    viewModel.setVoiceListening(true)
                    runCatching { speechRecognizer.startListening(intent) }
                        .onFailure {
                            viewModel.setVoiceListening(false)
                            viewModel.showMessage("음성 인식을 시작할 수 없습니다: ${it.message}")
                        }
                }

                LaunchedEffect(viewModel) {
                    viewModel.voiceFallbackRequests.collect {
                        launchSpeechRecognizerFallback()
                    }
                }

                DisposableEffect(speechRecognizer) {
                    speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            viewModel.setVoiceListening(true)
                        }
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() {
                            viewModel.setVoiceListening(false)
                        }
                        override fun onError(error: Int) {
                            viewModel.setVoiceListening(false)
                            val msg = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH,
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성을 인식하지 못했습니다. 다시 시도해 주세요."
                                SpeechRecognizer.ERROR_AUDIO,
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한 또는 오디오 입력을 확인해 주세요."
                                SpeechRecognizer.ERROR_NETWORK,
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                                SpeechRecognizer.ERROR_SERVER -> "음성 인식 서버 연결 오류입니다."
                                else -> "음성 인식 실패 (코드: $error)"
                            }
                            viewModel.showMessage(msg)
                        }
                        override fun onResults(results: Bundle?) {
                            viewModel.setVoiceListening(false)
                            val spoken = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                .orEmpty()
                            viewModel.onVoiceSearchRecognized(spoken)
                        }
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })
                    onDispose {
                        speechRecognizer?.destroy()
                    }
                }

                val micPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        viewModel.startVoiceSession()
                    } else {
                        viewModel.showMessage("마이크 권한이 거부되어 TV 음성 어시스턴트 키만 전송합니다.")
                        viewModel.sendKey(com.example.data.model.TvKeyCodes.VOICE_ASSIST)
                    }
                }

                fun handleMicClick() {
                    if (connectionState !is TvConnectionState.Connected) {
                        viewModel.showMessage("먼저 상단 TV 선택 버튼에서 Wi-Fi TV를 연결해 주세요.")
                        viewModel.openTvPicker()
                        return
                    }
                    val hasAudioPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasAudioPermission) {
                        if (isVoiceListening) {
                            viewModel.stopVoiceSession(sendEndPacket = true)
                            speechRecognizer?.stopListening()
                        } else {
                            viewModel.startVoiceSession()
                        }
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_REMOTE
                ) {
                    composable(ROUTE_REMOTE) {
                        RemoteScreen(
                            connectionState = connectionState,
                            favoriteApps = favoriteApps,
                            volumeLevel = volumeLevel,
                            maxVolume = maxVolume,
                            isMuted = isMuted,
                            brightnessPercent = preferences.brightnessPercent,
                            hapticEnabled = preferences.hapticEnabled,
                            isVoiceListening = isVoiceListening,
                            snackbarHostState = snackbarHostState,
                            onOpenTvPicker = viewModel::openTvPicker,
                            onKeyPress = viewModel::sendKey,
                            onLaunchFavorite = viewModel::launchFavorite,
                            onOpenKeyboard = viewModel::openKeyboardSheet,
                            onMicClick = ::handleMicClick,
                            onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                            onToggleMute = viewModel::toggleMute,
                            onStepVolume = viewModel::stepVolume,
                            onToggleDayNightBrightness = viewModel::toggleDayNightBrightness,
                            onStepBrightness = viewModel::stepBrightness,
                            onOpenTvPictureSettings = viewModel::openTvPictureSettings
                        )
                    }

                    composable(ROUTE_SETTINGS) {
                        SettingsScreen(
                            favoriteApps = favoriteApps,
                            recentApps = recentApps,
                            pairedTvs = pairedTvs,
                            preferences = preferences,
                            onNavigateBack = { navController.popBackStack() },
                            onToggleCatalogFavorite = viewModel::toggleFavorite,
                            onAddFavoriteFromRecent = viewModel::addFavoriteFromRecent,
                            onAddCustomFavorite = viewModel::addCustomFavoriteApp,
                            onRemoveFavorite = viewModel::removeFavorite,
                            onMoveFavorite = viewModel::moveFavorite,
                            onConnectSavedTv = { tv ->
                                viewModel.connectToTv(tv.host, tv.name, tv.remotePort, tv.pairingPort)
                                navController.popBackStack()
                            },
                            onForgetTv = viewModel::forgetPairedTv,
                            onToggleHaptic = viewModel::setHapticEnabled,
                            onToggleAutoKeyboard = viewModel::setAutoShowKeyboard
                        )
                    }
                }

                if (isTvPickerVisible) {
                    TvConnectionSheet(
                        discoveredTvs = discoveredTvs,
                        pairedTvs = pairedTvs,
                        isScanning = isScanning,
                        scanError = scanError,
                        connectionState = connectionState,
                        adbState = adbState,
                        adbStatusDetail = adbStatusDetail,
                        adbKeyboardInstalled = adbKeyboardInstalled,
                        onRefreshScan = viewModel::refreshDiscovery,
                        onRetryAdb = viewModel::retryAdbConnect,
                        onOpenAdbKeyboardSetup = viewModel::openAdbKeyboardSetupDialog,
                        onConnectTv = { host, name, rPort, pPort ->
                            viewModel.connectToTv(host, name, rPort, pPort)
                        },
                        onDisconnect = {
                            viewModel.disconnectTv()
                            viewModel.closeTvPicker()
                        },
                        onDismiss = viewModel::closeTvPicker
                    )
                }

                val pairingState = connectionState as? TvConnectionState.PairingRequired
                if (pairingState != null) {
                    PairingPinDialog(
                        state = pairingState,
                        onSubmitPin = viewModel::submitPairingPin,
                        onCancel = viewModel::cancelPairing
                    )
                }

                if (isKeyboardSheetVisible) {
                    val tvName = (connectionState as? TvConnectionState.Connected)?.tvName ?: "Android TV"
                    KeyboardInputSheet(
                        tvName = tvName,
                        initialText = imePromptState.currentText,
                        isTvImeVisible = imePromptState.isTvImeVisible,
                        isAdbConnected = adbState == com.example.network.AdbConnectionState.CONNECTED,
                        isAdbKeyboardInstalled = adbKeyboardInstalled,
                        onOpenAdbKeyboardSetup = viewModel::openAdbKeyboardSetupDialog,
                        onSendText = viewModel::sendKeyboardText,
                        onInsertChars = viewModel::sendKeyboardChars,
                        onBackspace = viewModel::sendKeyboardBackspace,
                        onSendKey = viewModel::sendKey,
                        onDismiss = viewModel::dismissKeyboardSheet
                    )
                }

                if (isAdbKeyboardSetupDialogVisible) {
                    AdbKeyboardSetupDialog(
                        isInstalled = adbKeyboardInstalled,
                        statusMessage = adbKeyboardSetupStatus,
                        isInstalling = isInstallingAdbKeyboard,
                        onPickApkFromDownloads = {
                            apkPickerLauncher.launch(
                                arrayOf(
                                    "application/vnd.android.package-archive",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        },
                        onOpenTvDownloads = viewModel::openTvDownloadFolderForApk,
                        onRedownloadInBrowser = viewModel::redownloadAdbKeyboardApk,
                        onVerifyInstalled = viewModel::verifyAdbKeyboardInstallation,
                        onDismiss = viewModel::dismissAdbKeyboardSetupDialog
                    )
                }
            }
        }
    }
}
