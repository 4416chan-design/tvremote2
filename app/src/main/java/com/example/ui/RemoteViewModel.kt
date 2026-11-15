package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppPreferencesEntity
import com.example.data.local.FavoriteAppEntity
import com.example.data.local.PairedTvEntity
import com.example.data.local.RecentTvAppEntity
import com.example.data.local.TvRemoteRepository
import com.example.data.model.DiscoveredTv
import com.example.data.model.PhysicalKeyAction
import com.example.data.model.TvAppCatalog
import com.example.data.model.TvAppCatalogItem
import com.example.data.model.TvConnectionState
import com.example.data.model.TvImeKeycodeMap
import com.example.data.model.TvImePromptState
import com.example.data.model.TvImeStatus
import com.example.data.model.TvKeyboardLanguage
import com.example.data.model.TvKeyCodes
import com.example.network.AdbAppLaunchOutcome
import com.example.network.AdbConnectionState
import com.example.network.AndroidTvPairingClient
import com.example.network.AndroidTvRemoteClient
import com.example.network.ClientCertStore
import com.example.network.NetworkAdbClient
import com.example.network.TvDiscoveryManager
import com.example.network.TvVoiceStreamer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class SnackbarNotice(
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

class RemoteViewModel(
    private val repository: TvRemoteRepository,
    private val certStore: ClientCertStore,
    private val discoveryManager: TvDiscoveryManager,
    private val adbClient: NetworkAdbClient
) : ViewModel() {

    private val _connectionState = MutableStateFlow<TvConnectionState>(TvConnectionState.Disconnected)
    val connectionState: StateFlow<TvConnectionState> = _connectionState.asStateFlow()

    val adbState: StateFlow<AdbConnectionState> = adbClient.adbState
    val adbStatusDetail: StateFlow<String?> = adbClient.adbStatusDetail

    val discoveredTvs: StateFlow<List<DiscoveredTv>> = discoveryManager.discoveredTvs
    val isScanning: StateFlow<Boolean> = discoveryManager.isScanning
    val scanError: StateFlow<String?> = discoveryManager.scanError

    val pairedTvs: StateFlow<List<PairedTvEntity>> = repository.pairedTvs.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val favoriteApps: StateFlow<List<FavoriteAppEntity>> = repository.favoriteApps.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val recentApps: StateFlow<List<RecentTvAppEntity>> = repository.recentApps.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val preferences: StateFlow<AppPreferencesEntity> = repository.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AppPreferencesEntity()
    )

    private val _imePromptState = MutableStateFlow(TvImePromptState())
    val imePromptState: StateFlow<TvImePromptState> = _imePromptState.asStateFlow()

    private val _tvImeStatus = MutableStateFlow(TvImeStatus())
    val tvImeStatus: StateFlow<TvImeStatus> = _tvImeStatus.asStateFlow()

    private val _keyboardLanguage = MutableStateFlow(TvKeyboardLanguage.ENGLISH)
    val keyboardLanguage: StateFlow<TvKeyboardLanguage> = _keyboardLanguage.asStateFlow()

    private val _isKeyboardSheetVisible = MutableStateFlow(false)
    val isKeyboardSheetVisible: StateFlow<Boolean> = _isKeyboardSheetVisible.asStateFlow()

    private val _isTvPickerVisible = MutableStateFlow(false)
    val isTvPickerVisible: StateFlow<Boolean> = _isTvPickerVisible.asStateFlow()

    private val _isVoiceListening = MutableStateFlow(false)
    val isVoiceListening: StateFlow<Boolean> = _isVoiceListening.asStateFlow()

    private val _volumeLevel = MutableStateFlow(15)
    val volumeLevel: StateFlow<Int> = _volumeLevel.asStateFlow()

    private val _maxVolume = MutableStateFlow(100)
    val maxVolume: StateFlow<Int> = _maxVolume.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _snackbarNotice = MutableStateFlow<SnackbarNotice?>(null)
    val snackbarNotice: StateFlow<SnackbarNotice?> = _snackbarNotice.asStateFlow()

    private var hasUserManuallyToggledLanguage: Boolean = false
    private var lastAutoReconnectAttemptMs: Long = 0L
    private var lastLocalVolumeAdjustMs: Long = 0L
    private var lastTvReportedVolume: Int? = null
    private var volumeSettleJob: Job? = null
    private val volumeStepQueue = Channel<Int>(Channel.UNLIMITED)

    private sealed interface RealTimeKeyCommand {
        data class Backspace(val count: Int) : RealTimeKeyCommand
        data class KeyAction(val action: PhysicalKeyAction) : RealTimeKeyCommand
        data class SelectAllAndDelete(val host: String?) : RealTimeKeyCommand
        data class SendText(
            val host: String?,
            val text: String,
            val replaceExisting: Boolean,
            val submitWithEnter: Boolean,
            val showSuccessMessage: Boolean,
            val resultDeferred: CompletableDeferred<Boolean>? = null
        ) : RealTimeKeyCommand
    }

    private val realTimeKeyQueue = Channel<RealTimeKeyCommand>(Channel.UNLIMITED)

    private var connectJob: Job? = null
    private var adbConnectJob: Job? = null
    private var brightnessSyncJob: Job? = null
    private var voiceSessionJob: Job? = null
    private var voiceFinalizeJob: Job? = null
    private var voiceFallbackWatchdogJob: Job? = null
    private var lastPictureFallbackNoticeMs: Long = 0L
    private var lastAdbSentTextLength: Int = 0

    private val voiceStreamer = TvVoiceStreamer()
    private val voiceFallbackChannel = Channel<Unit>(Channel.BUFFERED)
    val voiceFallbackRequests: Flow<Unit> = voiceFallbackChannel.receiveAsFlow()
    private val voiceStopChannel = Channel<Unit>(Channel.BUFFERED)
    val voiceStopRequests: Flow<Unit> = voiceStopChannel.receiveAsFlow()

    @Volatile
    internal var voiceNoActivityTimeoutMs: Long = DEFAULT_VOICE_NO_ACTIVITY_TIMEOUT_MS

    @Volatile
    private var voiceBeginDeferred: CompletableDeferred<Int>? = null

    @Volatile
    private var activeVoiceSessionId: Int? = null

    @Volatile
    private var voiceEndedByTv: Boolean = false

    @Volatile
    private var lastVoiceActivityAtMs: Long = 0L

    @Volatile
    private var tvVoiceEosDetectedAtMs: Long? = null

    @Volatile
    private var isVoiceFallbackActive: Boolean = false

    @Volatile
    private var lastPartialVoiceText: String = ""

    @Volatile
    private var speechStartCountInFallback: Int = 0

    @Volatile
    private var suppressNextFallbackError: Boolean = false

    @Volatile
    private var isVoiceInteractionActive: Boolean = false

    @Volatile
    private var suppressAutoKeyboardUntilMs: Long = 0L

    private val pairingClient = AndroidTvPairingClient(certStore)
    private val remoteClient: AndroidTvRemoteClient = AndroidTvRemoteClient(
        certStore = certStore,
        onForegroundAppChanged = { packageName ->
            _connectionState.update { state ->
                if (state is TvConnectionState.Connected) {
                    state.copy(currentAppPackage = packageName)
                } else state
            }
            viewModelScope.launch {
                repository.recordRecentApp(packageName = packageName, source = "TV")
            }
        },
        onImeShowRequested = { fieldCounter, currentText ->
            _imePromptState.value = TvImePromptState(
                isTvImeVisible = true,
                currentText = currentText,
                fieldCounter = fieldCounter
            )
            if (adbClient.isConnected) {
                viewModelScope.launch {
                    refreshTvImeStatusInternal(syncLanguage = !hasUserManuallyToggledLanguage)
                }
            }
            if (preferences.value.autoShowKeyboard && !shouldSuppressAutoShowKeyboard()) {
                _isKeyboardSheetVisible.value = true
            }
        },
        onImeHidden = {
            _imePromptState.value = TvImePromptState()
        },
        onVolumeChanged = { level, max, muted ->
            val safeMax = max.coerceAtLeast(1)
            val clampedLevel = level.coerceIn(0, safeMax)
            lastTvReportedVolume = clampedLevel
            _maxVolume.value = safeMax
            _isMuted.value = muted

            val recentlyDraggedLocally = System.currentTimeMillis() - lastLocalVolumeAdjustMs < 550L
            if (!recentlyDraggedLocally) {
                _volumeLevel.value = clampedLevel
            }
            _connectionState.update { state ->
                if (state is TvConnectionState.Connected) {
                    state.copy(
                        currentVolume = if (recentlyDraggedLocally) _volumeLevel.value else clampedLevel,
                        maxVolume = safeMax,
                        isMuted = muted
                    )
                } else state
            }
        },
        onVoiceBeginRequested = { sessionId, _ ->
            activeVoiceSessionId = sessionId
            voiceEndedByTv = false
            tvVoiceEosDetectedAtMs = null
            lastVoiceActivityAtMs = System.currentTimeMillis()
            isVoiceInteractionActive = true
            refreshVoiceAutoKeyboardSuppression()
            voiceBeginDeferred?.complete(sessionId)
        },
        onVoicePayloadAck = { sessionId ->
            if (activeVoiceSessionId == null || activeVoiceSessionId == sessionId) {
                if (tvVoiceEosDetectedAtMs == null) {
                    tvVoiceEosDetectedAtMs = System.currentTimeMillis()
                }
            }
        },
        onVoiceEndRequested = { sessionId ->
            if (activeVoiceSessionId == sessionId || activeVoiceSessionId != null) {
                voiceEndedByTv = true
                refreshVoiceAutoKeyboardSuppression()
                stopVoiceSession(sendEndPacket = false)
            }
        },
        onRemoteErrorInfo = { errInfo ->
            val voiceInFlight = _isVoiceListening.value ||
                activeVoiceSessionId != null ||
                voiceFinalizeJob?.isActive == true
            if (errInfo.offendingFieldNumber in 30..32 && voiceInFlight) {
                viewModelScope.launch {
                    stopVoiceSession(sendEndPacket = false, keepVoiceSuppressionForFallback = true)
                    _isVoiceListening.value = false
                    voiceStreamer.stopAndJoin()
                    if (remoteClient.isConnected) {
                        isVoiceInteractionActive = true
                        refreshVoiceAutoKeyboardSuppression()
                        showMessage("${errInfo.detail} 휴대폰 음성 인식으로 전환합니다.")
                        voiceFallbackChannel.trySend(Unit)
                    } else {
                        isVoiceInteractionActive = false
                    }
                }
            } else if (errInfo.detail.isNotBlank()) {
                showMessage(errInfo.detail)
            }
        },
        onDisconnected = { cause ->
            stopVoiceSession(sendEndPacket = false)
            clearVoiceAutoKeyboardSuppression()
            adbConnectJob?.cancel()
            adbClient.disconnect()
            hasUserManuallyToggledLanguage = false
            _tvImeStatus.value = TvImeStatus()
            _keyboardLanguage.value = TvKeyboardLanguage.ENGLISH
            _imePromptState.value = TvImePromptState()
            _isKeyboardSheetVisible.value = false
            _connectionState.value = if (cause != null) {
                TvConnectionState.Error(cause.message ?: "TV 연결이 끊어졌습니다.")
            } else {
                TvConnectionState.Disconnected
            }
        }
    )

    init {
        viewModelScope.launch {
            adbClient.adbState.collect { state ->
                if (state == AdbConnectionState.AUTHORIZING) {
                    showMessage("TV 화면에서 네트워크 디버깅(ADB) '허용'을 선택해 주세요.")
                }
            }
        }
        viewModelScope.launch {
            for (step in volumeStepQueue) {
                if (!remoteClient.isConnected) continue
                val keyCode = if (step > 0) TvKeyCodes.VOLUME_UP else TvKeyCodes.VOLUME_DOWN
                repeat(kotlin.math.abs(step)) {
                    runCatching { remoteClient.sendKey(keyCode, AndroidTvRemoteClient.DIRECTION_SHORT) }
                    delay(28)
                }
            }
        }
        viewModelScope.launch {
            for (cmd in realTimeKeyQueue) {
                when (cmd) {
                    is RealTimeKeyCommand.Backspace -> executeBackspaceCommand(cmd.count)
                    is RealTimeKeyCommand.KeyAction -> executePhysicalKeyCommand(cmd.action)
                    is RealTimeKeyCommand.SelectAllAndDelete -> {
                        if (!executeSelectAllAndDelete(cmd.host)) {
                            showMessage("전체 지우기 전송 실패: TV 연결 상태를 확인해 주세요.")
                        }
                    }
                    is RealTimeKeyCommand.SendText -> {
                        val sent = runCatching {
                            sendTextViaStandardImeOrAdb(
                                host = cmd.host,
                                text = cmd.text,
                                replaceExisting = cmd.replaceExisting,
                                submitWithEnter = cmd.submitWithEnter,
                                showSuccessMessage = cmd.showSuccessMessage
                            )
                        }.getOrDefault(false)
                        cmd.resultDeferred?.complete(sent)
                    }
                }
            }
        }
        viewModelScope.launch {
            repository.ensureDefaultsInitialized()
            if (connectJob?.isActive == true || _connectionState.value !is TvConnectionState.Disconnected) {
                return@launch
            }
            lastAutoReconnectAttemptMs = System.currentTimeMillis()
            val lastTv = repository.getMostRecentTv()
            if (lastTv != null) {
                connectToTv(
                    host = lastTv.host,
                    tvName = lastTv.name,
                    remotePort = lastTv.remotePort,
                    pairingPort = lastTv.pairingPort,
                    silentIfOffline = true
                )
            }
        }
    }

    private fun startAdbAutoConnect(host: String) {
        adbConnectJob?.cancel()
        adbConnectJob = viewModelScope.launch {
            val connected = adbClient.connect(host, NetworkAdbClient.DEFAULT_ADB_PORT)
            if (connected) {
                refreshTvImeStatusInternal(syncLanguage = !hasUserManuallyToggledLanguage)
            } else {
                _tvImeStatus.value = TvImeStatus()
            }
        }
    }

    private suspend fun refreshTvImeStatusInternal(syncLanguage: Boolean) {
        val statusRes = adbClient.readTvImeStatus()
        if (statusRes.isSuccess) {
            val status = statusRes.getOrThrow()
            _tvImeStatus.value = status
            if (syncLanguage) {
                _keyboardLanguage.value = status.language
            }
        }
    }

    fun retryAdbConnect() {
        val connectedHost = (_connectionState.value as? TvConnectionState.Connected)?.host
        if (connectedHost.isNullOrBlank()) {
            showMessage("먼저 Wi-Fi TV에 연결해 주세요.")
            return
        }
        startAdbAutoConnect(connectedHost)
    }

    /**
     * Cleanly closes sockets with TCP FIN when leaving the app so the TV does not retain
     * a half-open zombie socket that triggers a fresh 6-digit PIN handshake on return.
     */
    fun onAppBackgrounded() {
        stopVoiceSession(sendEndPacket = false)
        clearVoiceAutoKeyboardSuppression()
        _isKeyboardSheetVisible.value = false
        val current = _connectionState.value
        if (current is TvConnectionState.PairingRequired) {
            return
        }
        connectJob?.cancel()
        adbConnectJob?.cancel()
        if (current is TvConnectionState.Connected || current is TvConnectionState.Connecting) {
            adbClient.disconnect()
            remoteClient.disconnect(notify = false)
            hasUserManuallyToggledLanguage = false
            _tvImeStatus.value = TvImeStatus()
            _keyboardLanguage.value = TvKeyboardLanguage.ENGLISH
            _imePromptState.value = TvImePromptState()
            _connectionState.value = TvConnectionState.Disconnected
        }
        lastAutoReconnectAttemptMs = 0L
    }

    fun autoReconnectIfNeeded() {
        val current = _connectionState.value
        if (current is TvConnectionState.Connected ||
            current is TvConnectionState.Connecting ||
            current is TvConnectionState.PairingRequired
        ) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastAutoReconnectAttemptMs < 1_500L) return
        lastAutoReconnectAttemptMs = now

        viewModelScope.launch {
            val lastTv = repository.getMostRecentTv() ?: return@launch
            if (connectJob?.isActive == true) return@launch
            connectToTv(
                host = lastTv.host,
                tvName = lastTv.name,
                remotePort = lastTv.remotePort,
                pairingPort = lastTv.pairingPort,
                silentIfOffline = true
            )
        }
    }

    fun openTvPicker() {
        _isTvPickerVisible.value = true
        discoveryManager.startDiscovery()
    }

    fun closeTvPicker() {
        _isTvPickerVisible.value = false
        discoveryManager.stopDiscovery()
    }

    fun refreshDiscovery() {
        discoveryManager.startDiscovery()
    }

    fun connectToTv(
        host: String,
        tvName: String,
        remotePort: Int = 6466,
        pairingPort: Int = 6467,
        silentIfOffline: Boolean = false
    ) {
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) {
            showMessage("올바른 TV IP 주소를 입력해 주세요.")
            return
        }
        val label = tvName.ifBlank { cleanHost }

        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _connectionState.value = TvConnectionState.Connecting(label, cleanHost)
            val alreadyPaired = repository.isHostPaired(cleanHost)

            // Retry once on 6466 in case TV is still releasing a previous socket after wake/background
            var remoteConnectResult = runCatching {
                remoteClient.connect(cleanHost, remotePort)
            }
            if (remoteConnectResult.isFailure && alreadyPaired) {
                delay(450)
                remoteConnectResult = runCatching {
                    remoteClient.connect(cleanHost, remotePort)
                }
            }

            if (remoteConnectResult.isSuccess) {
                repository.savePairedTv(cleanHost, label, remotePort, pairingPort)
                _connectionState.value = TvConnectionState.Connected(
                    tvName = label,
                    host = cleanHost,
                    remotePort = remotePort
                )
                _isTvPickerVisible.value = false
                discoveryManager.stopDiscovery()
                startAdbAutoConnect(cleanHost)
                return@launch
            }

            val remoteFailure = remoteConnectResult.exceptionOrNull()
            // Never ambush the user with a 6-digit PIN dialog during automatic background/resume reconnect
            if (silentIfOffline) {
                _connectionState.value = TvConnectionState.Disconnected
                return@launch
            }

            // If this TV was already paired and failed due to network/timeout (TV off or Wi-Fi glitch),
            // report the connection error instead of resetting into 6467 PIN pairing.
            if (alreadyPaired && !isCertificateRejectionError(remoteFailure)) {
                val errMsg = remoteFailure?.message
                    ?: "TV에 연결할 수 없습니다. TV 전원 및 같은 Wi-Fi를 확인해 주세요."
                _connectionState.value = TvConnectionState.Error(errMsg, cleanHost)
                showMessage(errMsg)
                return@launch
            }

            // First-time TV or explicit untrusted-certificate rejection: start pairing on port 6467
            val pairingStartResult = runCatching {
                pairingClient.startPairing(cleanHost, pairingPort, "TV 리모컨")
            }

            if (pairingStartResult.isSuccess) {
                _isTvPickerVisible.value = false
                discoveryManager.stopDiscovery()
                _connectionState.value = TvConnectionState.PairingRequired(
                    tvName = label,
                    host = cleanHost,
                    pairingPort = pairingPort,
                    remotePort = remotePort
                )
            } else {
                val errMsg = pairingStartResult.exceptionOrNull()?.message
                    ?: remoteFailure?.message
                    ?: "TV에 연결할 수 없습니다. 같은 Wi-Fi 및 TV 전원을 확인해 주세요."
                _connectionState.value = TvConnectionState.Error(errMsg, cleanHost)
                showMessage(errMsg)
            }
        }
    }

    private fun isCertificateRejectionError(error: Throwable?): Boolean {
        if (error == null) return false
        val msg = error.message.orEmpty()
        return error is javax.net.ssl.SSLHandshakeException ||
            error is javax.net.ssl.SSLException ||
            msg.contains("페어링 인증", ignoreCase = true) ||
            msg.contains("certificate", ignoreCase = true) ||
            msg.contains("bad_certificate", ignoreCase = true)
    }

    fun submitPairingPin(pin: String) {
        val current = _connectionState.value as? TvConnectionState.PairingRequired ?: return
        viewModelScope.launch {
            val pinResult = runCatching {
                pairingClient.submitPin(pin)
            }
            if (pinResult.isFailure) {
                _connectionState.value = current.copy(
                    errorMessage = pinResult.exceptionOrNull()?.message ?: "PIN 인증 실패"
                )
                return@launch
            }

            _connectionState.value = TvConnectionState.Connecting(current.tvName, current.host)
            val connectResult = runCatching {
                remoteClient.connect(current.host, current.remotePort)
            }
            if (connectResult.isSuccess) {
                repository.savePairedTv(current.host, current.tvName, current.remotePort, current.pairingPort)
                _connectionState.value = TvConnectionState.Connected(
                    tvName = current.tvName,
                    host = current.host,
                    remotePort = current.remotePort
                )
                showMessage("${current.tvName}에 연결되었습니다.")
                startAdbAutoConnect(current.host)
            } else {
                val msg = connectResult.exceptionOrNull()?.message ?: "페어링 후 연결에 실패했습니다."
                _connectionState.value = TvConnectionState.Error(msg, current.host)
                showMessage(msg)
            }
        }
    }

    fun cancelPairing() {
        pairingClient.close()
        _connectionState.value = TvConnectionState.Disconnected
    }

    fun disconnectTv() {
        stopVoiceSession(sendEndPacket = false)
        clearVoiceAutoKeyboardSuppression()
        _isKeyboardSheetVisible.value = false
        adbConnectJob?.cancel()
        adbClient.disconnect()
        remoteClient.disconnect(notify = false)
        hasUserManuallyToggledLanguage = false
        _tvImeStatus.value = TvImeStatus()
        _keyboardLanguage.value = TvKeyboardLanguage.ENGLISH
        _imePromptState.value = TvImePromptState()
        _connectionState.value = TvConnectionState.Disconnected
        showMessage("TV 연결이 해제되었습니다.")
    }

    fun forgetPairedTv(host: String) {
        viewModelScope.launch {
            val active = _connectionState.value
            if (active is TvConnectionState.Connected && active.host == host) {
                stopVoiceSession(sendEndPacket = false)
                clearVoiceAutoKeyboardSuppression()
                _isKeyboardSheetVisible.value = false
                adbConnectJob?.cancel()
                adbClient.disconnect()
                remoteClient.disconnect(notify = false)
                hasUserManuallyToggledLanguage = false
                _tvImeStatus.value = TvImeStatus()
                _keyboardLanguage.value = TvKeyboardLanguage.ENGLISH
                _imePromptState.value = TvImePromptState()
                _connectionState.value = TvConnectionState.Disconnected
            }
            repository.removePairedTv(host)
        }
    }

    fun sendKey(keyCode: Int) {
        if (!ensureConnectedOrPrompt()) return
        if (keyCode == TvKeyCodes.ALL_APPS) {
            sendAllAppsKey()
            return
        }
        viewModelScope.launch {
            runCatching {
                remoteClient.sendKey(keyCode, AndroidTvRemoteClient.DIRECTION_SHORT)
            }.onFailure { showMessage(it.message ?: "명령 전송 실패") }
        }
    }

    private fun sendAllAppsKey() {
        val connectedHost = (_connectionState.value as? TvConnectionState.Connected)?.host
        viewModelScope.launch {
            if (!adbClient.isConnected && !connectedHost.isNullOrBlank()) {
                adbClient.connect(connectedHost, NetworkAdbClient.DEFAULT_ADB_PORT)
            }
            if (adbClient.isConnected) {
                var adbResult = adbClient.sendKeyEvent(TvKeyCodes.ALL_APPS)
                if (adbResult.isFailure && !adbClient.isConnected && !connectedHost.isNullOrBlank()) {
                    if (adbClient.connect(connectedHost, NetworkAdbClient.DEFAULT_ADB_PORT)) {
                        adbResult = adbClient.sendKeyEvent(TvKeyCodes.ALL_APPS)
                    }
                }
                if (adbResult.isSuccess) {
                    return@launch
                }
            }
            // Fallback to Wi-Fi remote protocol if ADB 5555 is unavailable or not yet authorized
            runCatching {
                remoteClient.sendKey(TvKeyCodes.ALL_APPS, AndroidTvRemoteClient.DIRECTION_SHORT)
            }.onFailure { showMessage(it.message ?: "전체 앱 명령 전송 실패") }
        }
    }

    fun sendKeyLongStart(keyCode: Int) {
        if (!remoteClient.isConnected) return
        viewModelScope.launch {
            runCatching { remoteClient.sendKey(keyCode, AndroidTvRemoteClient.DIRECTION_START_LONG) }
        }
    }

    fun sendKeyLongEnd(keyCode: Int) {
        if (!remoteClient.isConnected) return
        viewModelScope.launch {
            runCatching { remoteClient.sendKey(keyCode, AndroidTvRemoteClient.DIRECTION_END_LONG) }
        }
    }

    fun launchFavorite(app: FavoriteAppEntity) {
        launchApp(
            packageName = app.packageName,
            appName = app.appName,
            launchUri = app.launchUri,
            iconKey = app.iconKey,
            accentHex = app.accentHex
        )
    }

    fun launchApp(
        packageName: String,
        appName: String,
        launchUri: String,
        iconKey: String,
        accentHex: Long
    ) {
        if (!ensureConnectedOrPrompt()) return
        val connectedHost = (_connectionState.value as? TvConnectionState.Connected)?.host
        if (connectedHost.isNullOrBlank()) return

        val safeUri = if (TvAppCatalog.isLegacyBrokenUri(launchUri)) {
            TvAppCatalog.launchUriForPackage(packageName)
        } else {
            launchUri.trim()
        }

        viewModelScope.launch {
            if (!adbClient.isConnected) {
                adbClient.connect(connectedHost, NetworkAdbClient.DEFAULT_ADB_PORT)
            }

            if (adbClient.isConnected) {
                var result = adbClient.launchAppPackage(packageName = packageName, fallbackUri = safeUri)
                // If an idle ADB socket dropped mid-command, reconnect once automatically and retry
                if (result.outcome == AdbAppLaunchOutcome.FAILED && !adbClient.isConnected) {
                    if (adbClient.connect(connectedHost, NetworkAdbClient.DEFAULT_ADB_PORT)) {
                        result = adbClient.launchAppPackage(packageName = packageName, fallbackUri = safeUri)
                    }
                }

                when (result.outcome) {
                    AdbAppLaunchOutcome.LAUNCHED_APP -> {
                        repository.recordRecentApp(
                            packageName = packageName,
                            appName = appName,
                            launchUri = safeUri,
                            iconKey = iconKey,
                            accentHex = accentHex,
                            source = "REMOTE"
                        )
                    }
                    AdbAppLaunchOutcome.OPENED_PLAY_STORE -> {
                        repository.recordRecentApp(
                            packageName = packageName,
                            appName = appName,
                            launchUri = safeUri,
                            iconKey = iconKey,
                            accentHex = accentHex,
                            source = "REMOTE"
                        )
                        showMessage("${appName} 앱이 설치되어 있지 않아 Play 스토어를 열었습니다.")
                    }
                    AdbAppLaunchOutcome.FAILED -> {
                        showMessage("${appName} 실행 실패: ${result.detail ?: "ADB 명령 오류"}")
                    }
                }
                return@launch
            }

            val msg = if (adbClient.adbState.value == AdbConnectionState.AUTHORIZING) {
                "TV 화면에서 네트워크 디버깅(ADB) '허용'을 선택한 뒤 다시 눌러주세요."
            } else {
                "즐겨찾기 실행을 위해 ADB(5555) 연결이 필요합니다. TV 개발자 옵션을 확인해 주세요."
            }
            _snackbarNotice.value = SnackbarNotice(
                message = msg,
                actionLabel = "ADB 연결",
                onAction = { retryAdbConnect() }
            )
        }
    }

    fun openKeyboardSheet() {
        if (!ensureConnectedOrPrompt()) return
        clearVoiceAutoKeyboardSuppression()
        _isKeyboardSheetVisible.value = true
        val connectedHost = (_connectionState.value as? TvConnectionState.Connected)?.host
        if (adbClient.isConnected || !connectedHost.isNullOrBlank()) {
            viewModelScope.launch {
                if (!adbClient.isConnected && !connectedHost.isNullOrBlank()) {
                    adbClient.connect(connectedHost, NetworkAdbClient.DEFAULT_ADB_PORT)
                }
                if (adbClient.isConnected) {
                    refreshTvImeStatusInternal(syncLanguage = !hasUserManuallyToggledLanguage)
                }
            }
        }
    }

    fun dismissKeyboardSheet() {
        _isKeyboardSheetVisible.value = false
        refreshVoiceAutoKeyboardSuppression(KEYBOARD_DISMISS_SUPPRESS_MS)
    }

    fun sendKeyboardText(text: String, replaceExisting: Boolean, submitWithEnter: Boolean) {
        if (!ensureConnectedOrPrompt()) return
        if (submitWithEnter) {
            _isKeyboardSheetVisible.value = false
            refreshVoiceAutoKeyboardSuppression(KEYBOARD_DISMISS_SUPPRESS_MS)
        }
        val connectedHost = (_connectionState.value as? TvConnectionState.Connected)?.host
        realTimeKeyQueue.trySend(
            RealTimeKeyCommand.SendText(
                host = connectedHost,
                text = text,
                replaceExisting = replaceExisting,
                submitWithEnter = submitWithEnter,
                showSuccessMessage = submitWithEnter
            )
        )
    }

    /**
     * Executes "(1) Select All (Ctrl+A: input keycombination 113 29) -> (2) Delete (DEL)"
     * on the focused TV text field.
     */
    fun clearKeyboardWithSelectAllDelete() {
        _imePromptState.update { it.copy(currentText = "") }
        if (!ensureConnectedOrPrompt()) return
        val connectedHost = (_connectionState.value as? TvConnectionState.Connected)?.host
        realTimeKeyQueue.trySend(RealTimeKeyCommand.SelectAllAndDelete(connectedHost))
    }

    private suspend fun executeSelectAllAndDelete(host: String?): Boolean {
        if (!adbClient.isConnected &&
            adbClient.adbState.value == AdbConnectionState.DISCONNECTED &&
            !host.isNullOrBlank()
        ) {
            adbClient.connect(host, NetworkAdbClient.DEFAULT_ADB_PORT)
        }
        if (adbClient.isConnected) {
            var adbRes = adbClient.selectAllAndDelete()
            if (adbRes.isFailure && !adbClient.isConnected && !host.isNullOrBlank()) {
                if (adbClient.connect(host, NetworkAdbClient.DEFAULT_ADB_PORT)) {
                    adbRes = adbClient.selectAllAndDelete()
                }
            }
            if (adbRes.isSuccess) {
                lastAdbSentTextLength = 0
                return true
            }
        }
        if (remoteClient.isConnected) {
            val res = runCatching { remoteClient.selectAllAndDelete() }
            if (res.isSuccess) {
                lastAdbSentTextLength = 0
                return true
            }
        }
        return false
    }

    fun sendRealTimeKeyboardEdit(backspaceCount: Int, keyActions: List<PhysicalKeyAction>) {
        if (backspaceCount <= 0 && keyActions.isEmpty()) return
        if (!ensureConnectedOrPrompt()) return
        if (backspaceCount > 0) {
            realTimeKeyQueue.trySend(RealTimeKeyCommand.Backspace(backspaceCount))
        }
        for (action in keyActions) {
            realTimeKeyQueue.trySend(RealTimeKeyCommand.KeyAction(action))
        }
    }

    fun sendPhysicalKeyCode(keyCode: Int, shift: Boolean = false, ctrl: Boolean = false) {
        if (!ensureConnectedOrPrompt()) return
        realTimeKeyQueue.trySend(
            RealTimeKeyCommand.KeyAction(PhysicalKeyAction(keyCode = keyCode, shift = shift, ctrl = ctrl))
        )
    }

    fun toggleKeyboardLanguage() {
        hasUserManuallyToggledLanguage = true
        val next = if (_keyboardLanguage.value == TvKeyboardLanguage.KOREAN) {
            TvKeyboardLanguage.ENGLISH
        } else {
            TvKeyboardLanguage.KOREAN
        }
        _keyboardLanguage.value = next
        if (!remoteClient.isConnected && !adbClient.isConnected) return
        val switchAction = TvImeKeycodeMap.resolveLanguageSwitchAction(_tvImeStatus.value.imeType)
        realTimeKeyQueue.trySend(RealTimeKeyCommand.KeyAction(switchAction))
    }

    fun resyncTvImeLanguage() {
        val connectedHost = (_connectionState.value as? TvConnectionState.Connected)?.host
        hasUserManuallyToggledLanguage = false
        viewModelScope.launch {
            if (!adbClient.isConnected && !connectedHost.isNullOrBlank()) {
                adbClient.connect(connectedHost, NetworkAdbClient.DEFAULT_ADB_PORT)
            }
            if (adbClient.isConnected) {
                refreshTvImeStatusInternal(syncLanguage = true)
                val status = _tvImeStatus.value
                val langLabel = if (status.language == TvKeyboardLanguage.KOREAN) "한글" else "영문"
                showMessage("TV IME 동기화: $langLabel (${status.imeType.displayName})")
            } else {
                showMessage("TV IME 상태를 읽으려면 ADB(5555) 연결이 필요합니다.")
            }
        }
    }

    private suspend fun executePhysicalKeyCommand(action: PhysicalKeyAction) {
        val connectedHost = (_connectionState.value as? TvConnectionState.Connected)?.host
        if (remoteClient.isConnected) {
            val res = runCatching {
                remoteClient.sendPhysicalKey(
                    keyCode = action.keyCode,
                    shift = action.shift,
                    ctrl = action.ctrl
                )
            }
            if (res.isSuccess) {
                if (!action.ctrl && action.keyCode != TvKeyCodes.ENTER) {
                    lastAdbSentTextLength = (lastAdbSentTextLength + 1).coerceAtMost(40)
                }
                return
            }
        }
        if (!adbClient.isConnected &&
            adbClient.adbState.value == AdbConnectionState.DISCONNECTED &&
            !connectedHost.isNullOrBlank()
        ) {
            adbClient.connect(connectedHost, NetworkAdbClient.DEFAULT_ADB_PORT)
        }
        if (adbClient.isConnected) {
            val adbRes = when {
                action.ctrl && action.shift ->
                    adbClient.sendKeyCombination(TvKeyCodes.CTRL_LEFT, TvKeyCodes.SHIFT_LEFT, action.keyCode)
                action.ctrl ->
                    adbClient.sendKeyCombination(TvKeyCodes.CTRL_LEFT, action.keyCode)
                action.shift ->
                    adbClient.sendKeyCombination(TvKeyCodes.SHIFT_LEFT, action.keyCode)
                else ->
                    adbClient.sendKeyEvent(action.keyCode)
            }
            if (adbRes.isSuccess) {
                if (!action.ctrl && action.keyCode != TvKeyCodes.ENTER) {
                    lastAdbSentTextLength = (lastAdbSentTextLength + 1).coerceAtMost(40)
                }
                return
            }
        }
        showMessage("물리키 전송 실패: TV 연결 상태를 확인해 주세요.")
    }

    fun sendKeyboardBackspace(count: Int = 1) {
        if (count <= 0 || !ensureConnectedOrPrompt()) return
        realTimeKeyQueue.trySend(RealTimeKeyCommand.Backspace(count))
    }

    private suspend fun executeBackspaceCommand(count: Int) {
        val connectedHost = (_connectionState.value as? TvConnectionState.Connected)?.host
        if (remoteClient.isConnected) {
            val res = runCatching { remoteClient.sendKeyboardBackspace(count) }
            if (res.isSuccess) {
                lastAdbSentTextLength = (lastAdbSentTextLength - count).coerceAtLeast(0)
                return
            }
        }
        if (!adbClient.isConnected &&
            adbClient.adbState.value == AdbConnectionState.DISCONNECTED &&
            !connectedHost.isNullOrBlank()
        ) {
            adbClient.connect(connectedHost, NetworkAdbClient.DEFAULT_ADB_PORT)
        }
        if (adbClient.isConnected) {
            var allOk = true
            repeat(count) {
                if (adbClient.sendKeyEvent(TvKeyCodes.DEL).isFailure) {
                    allOk = false
                }
            }
            if (allOk) {
                lastAdbSentTextLength = (lastAdbSentTextLength - count).coerceAtLeast(0)
                return
            }
        }
        showMessage("삭제 전송 실패: TV 연결 상태를 확인해 주세요.")
    }

    /**
     * Text-field Send routing:
     * 1) When [replaceExisting] or [text] is empty: executes Select All (`Ctrl+A`) + `DEL` first.
     * 2) Sends phone text string via Protocol v2 (`RemoteImeBatchEdit(21)` + optional `ENTER`),
     *    falling back to ADB ASCII `input text` / `ENTER` when Protocol v2 is unavailable.
     */
    private suspend fun sendTextViaStandardImeOrAdb(
        host: String?,
        text: String,
        replaceExisting: Boolean,
        submitWithEnter: Boolean,
        showSuccessMessage: Boolean
    ): Boolean {
        val shouldSelectAllDeleteFirst = replaceExisting || text.isEmpty()
        var clearedViaSelectAllDelete = false
        if (shouldSelectAllDeleteFirst) {
            clearedViaSelectAllDelete = executeSelectAllAndDelete(host)
            if (clearedViaSelectAllDelete && adbClient.isConnected) {
                delay(80)
            }
        }

        var remoteFailure: Throwable? = null
        if (remoteClient.isConnected) {
            val imeResult = runCatching {
                remoteClient.sendText(
                    text = text,
                    replaceExisting = replaceExisting,
                    submitWithEnter = submitWithEnter
                )
            }
            if (imeResult.isSuccess) {
                lastAdbSentTextLength = 0
                if (showSuccessMessage) {
                    showMessage("TV로 입력을 전송했습니다.")
                }
                return true
            }
            remoteFailure = imeResult.exceptionOrNull()
        }

        if (!adbClient.isConnected &&
            adbClient.adbState.value == AdbConnectionState.DISCONNECTED &&
            !host.isNullOrBlank()
        ) {
            adbClient.connect(host, NetworkAdbClient.DEFAULT_ADB_PORT)
        }

        if (adbClient.isConnected) {
            val clearCount = when {
                clearedViaSelectAllDelete -> 0
                text.isEmpty() || replaceExisting -> maxOf(lastAdbSentTextLength + 4, 20)
                else -> 0
            }

            if (text.isEmpty()) {
                val adbResult = if (clearedViaSelectAllDelete) {
                    if (submitWithEnter) adbClient.sendKeyEvent(TvKeyCodes.ENTER) else Result.success(Unit)
                } else {
                    adbClient.clearTextField(count = clearCount, submitWithEnter = submitWithEnter)
                }
                if (adbResult.isSuccess) {
                    lastAdbSentTextLength = 0
                    if (showSuccessMessage) {
                        showMessage("ADB로 입력을 전송했습니다.")
                    }
                    return true
                }
            } else if (NetworkAdbClient.isAsciiInputSupported(text)) {
                val adbResult = adbClient.sendInputText(
                    text = text,
                    clearCount = clearCount,
                    submitWithEnter = submitWithEnter
                )
                if (adbResult.isSuccess) {
                    lastAdbSentTextLength = if (submitWithEnter) 0 else text.length
                    if (showSuccessMessage) {
                        showMessage("ADB로 입력을 전송했습니다.")
                    }
                    return true
                }
            } else {
                showMessage("한글 전체 텍스트 전송을 위해 TV Wi-Fi 리모컨(6466) 연결을 확인해 주세요.")
                return false
            }
        }

        showMessage("텍스트 전송 실패: ${remoteFailure?.message ?: "TV 연결을 확인해 주세요."}")
        return false
    }

    private fun refreshVoiceAutoKeyboardSuppression(graceMs: Long = VOICE_AUTO_KEYBOARD_GRACE_MS) {
        val candidate = System.currentTimeMillis() + graceMs
        if (candidate > suppressAutoKeyboardUntilMs) {
            suppressAutoKeyboardUntilMs = candidate
        }
    }

    private fun clearVoiceAutoKeyboardSuppression() {
        isVoiceInteractionActive = false
        suppressAutoKeyboardUntilMs = 0L
    }

    private fun shouldSuppressAutoShowKeyboard(): Boolean {
        if (_isVoiceListening.value || isVoiceInteractionActive) return true
        return System.currentTimeMillis() < suppressAutoKeyboardUntilMs
    }

    fun setVoiceListening(listening: Boolean) {
        _isVoiceListening.value = listening
        if (listening) {
            isVoiceInteractionActive = true
            refreshVoiceAutoKeyboardSuppression()
        }
    }

    fun onVoiceFallbackStarted() {
        voiceFallbackWatchdogJob?.cancel()
        suppressNextFallbackError = false
        isVoiceFallbackActive = true
        lastPartialVoiceText = ""
        speechStartCountInFallback = 0
        _isVoiceListening.value = true
        isVoiceInteractionActive = true
        refreshVoiceAutoKeyboardSuppression(VOICE_AUTO_KEYBOARD_GRACE_MS)
        armVoiceFallbackWatchdog()
    }

    fun reportVoiceSpeechStarted() {
        if (!isVoiceFallbackActive) return
        if (speechStartCountInFallback == 0) {
            speechStartCountInFallback = 1
            armVoiceFallbackWatchdog()
        }
    }

    fun reportVoiceTextActivity(partialText: String) {
        if (!isVoiceFallbackActive) return
        val normalized = partialText.trim()
        if (normalized.isEmpty() || normalized == lastPartialVoiceText) return
        lastPartialVoiceText = normalized
        refreshVoiceAutoKeyboardSuppression(VOICE_AUTO_KEYBOARD_GRACE_MS)
        armVoiceFallbackWatchdog()
    }

    private fun armVoiceFallbackWatchdog() {
        voiceFallbackWatchdogJob?.cancel()
        val timeoutMs = voiceNoActivityTimeoutMs
        voiceFallbackWatchdogJob = viewModelScope.launch {
            delay(timeoutMs)
            onVoiceFallbackTimedOut()
        }
    }

    internal fun onVoiceFallbackTimedOut() {
        if (!isVoiceFallbackActive) return
        isVoiceFallbackActive = false
        voiceFallbackWatchdogJob?.cancel()
        voiceFallbackWatchdogJob = null
        suppressNextFallbackError = true
        _isVoiceListening.value = false
        isVoiceInteractionActive = false
        refreshVoiceAutoKeyboardSuppression(VOICE_AUTO_KEYBOARD_GRACE_MS)
        voiceStopChannel.trySend(Unit)
        showMessage("음성 텍스트 입력이 3초간 작동하지 않아 마이크를 종료했습니다.")
    }

    private suspend fun triggerTvGoogleAssistant(): Boolean {
        val connectedHost = (_connectionState.value as? TvConnectionState.Connected)?.host
        if (remoteClient.isConnected) {
            val v2Sent = runCatching {
                remoteClient.sendKey(TvKeyCodes.VOICE_ASSIST, AndroidTvRemoteClient.DIRECTION_SHORT)
            }.isSuccess
            if (v2Sent) return true
        }
        if (!adbClient.isConnected &&
            adbClient.adbState.value == AdbConnectionState.DISCONNECTED &&
            !connectedHost.isNullOrBlank()
        ) {
            adbClient.connect(connectedHost, NetworkAdbClient.DEFAULT_ADB_PORT)
        }
        if (adbClient.isConnected) {
            return adbClient.triggerGoogleAssistant().isSuccess
        }
        return false
    }

    /**
     * Initiates Android TV Google Assistant + voice input:
     * 1) Dismisses mobile keyboard sheet & suppresses autoShowKeyboard popups triggered by Katniss/Assistant
     * 2) Launches TV Google Assistant even when `FEATURE_VOICE` is omitted (`tvFeatures = 615`), then starts SpeechRecognizer fallback
     * 3) Otherwise pre-captures 8 kHz 16-bit mono PCM & sends `KEYCODE_SEARCH` (and `VOICE_ASSIST` fallback if needed)
     * 4) Upon TV's `RemoteVoiceBegin(sessionId)`, echoes `RemoteVoiceBegin` & streams `[8192, 20480]` B chunks
     * 5) Automatically stops mic after 3 seconds of no voice/text activity or TV EOS grace expiry
     */
    fun startVoiceSession() {
        if (!ensureConnectedOrPrompt()) return
        if (_isVoiceListening.value || isVoiceFallbackActive) {
            stopVoiceSession(sendEndPacket = true)
            return
        }

        _isKeyboardSheetVisible.value = false
        voiceSessionJob?.cancel()
        voiceSessionJob = null
        voiceFinalizeJob?.cancel()
        voiceFinalizeJob = null
        voiceFallbackWatchdogJob?.cancel()
        voiceFallbackWatchdogJob = null
        isVoiceFallbackActive = false
        suppressNextFallbackError = false
        voiceStreamer.stop()
        activeVoiceSessionId = null
        voiceEndedByTv = false
        tvVoiceEosDetectedAtMs = null
        lastVoiceActivityAtMs = System.currentTimeMillis()
        isVoiceInteractionActive = true
        refreshVoiceAutoKeyboardSuppression()

        if (remoteClient.isVoiceSupportedByTv == false) {
            voiceBeginDeferred = null
            _isVoiceListening.value = false
            voiceSessionJob = viewModelScope.launch {
                val assistantOpened = triggerTvGoogleAssistant()
                refreshVoiceAutoKeyboardSuppression(VOICE_AUTO_KEYBOARD_GRACE_MS)
                if (assistantOpened) {
                    showMessage("TV 구글 어시스턴트 실행 — 휴대폰 마이크로 말씀하세요.")
                } else {
                    showMessage("TV 직접 마이크 미지원 — 휴대폰 음성 인식으로 검색합니다.")
                }
                voiceFallbackChannel.trySend(Unit)
            }
            return
        }

        val beginSignal = CompletableDeferred<Int>()
        voiceBeginDeferred = beginSignal
        _isVoiceListening.value = true

        voiceSessionJob = viewModelScope.launch {
            try {
                lastVoiceActivityAtMs = System.currentTimeMillis()
                tvVoiceEosDetectedAtMs = null
                voiceStreamer.startCapture(
                    scope = viewModelScope,
                    onChunk = { sid, chunk ->
                        if (remoteClient.isConnected) {
                            runCatching { remoteClient.sendVoicePayload(sid, chunk) }
                        }
                    },
                    onCaptureFailed = { err ->
                        viewModelScope.launch {
                            showMessage("마이크 시작 실패: ${err.message ?: "권한 또는 오디오 장치 오류"}")
                            stopVoiceSession(sendEndPacket = false)
                        }
                    },
                    onSpeechDetected = {
                        lastVoiceActivityAtMs = System.currentTimeMillis()
                    }
                )

                // Protocol v2 spec: send KEYCODE_SEARCH (84) first and await RemoteVoiceBegin(30).
                // If TV does not open a voice session, also trigger TV Google Assistant directly.
                runCatching {
                    remoteClient.sendKey(TvKeyCodes.SEARCH, AndroidTvRemoteClient.DIRECTION_SHORT)
                }

                var sessionId = withTimeoutOrNull(VOICE_SEARCH_TRIGGER_WAIT_MS) { beginSignal.await() }
                if (sessionId == null && remoteClient.isConnected && _isVoiceListening.value) {
                    triggerTvGoogleAssistant()
                    sessionId = withTimeoutOrNull(VOICE_ASSIST_TRIGGER_WAIT_MS) { beginSignal.await() }
                }

                if (sessionId == null) {
                    // TV did not open a Protocol v2 RemoteVoiceBegin stream; release mic & fall back to SpeechRecognizer
                    voiceBeginDeferred = null
                    voiceStreamer.stopAndJoin()
                    _isVoiceListening.value = false
                    if (remoteClient.isConnected) {
                        isVoiceInteractionActive = true
                        refreshVoiceAutoKeyboardSuppression()
                        showMessage("TV 구글 어시스턴트 실행 — 휴대폰 음성 인식으로 전환합니다.")
                        voiceFallbackChannel.trySend(Unit)
                    } else {
                        isVoiceInteractionActive = false
                    }
                    return@launch
                }

                if (!_isVoiceListening.value || voiceEndedByTv || !remoteClient.isConnected) {
                    return@launch
                }

                activeVoiceSessionId = sessionId
                refreshVoiceAutoKeyboardSuppression(VOICE_STREAM_MAX_MS + VOICE_AUTO_KEYBOARD_GRACE_MS)
                remoteClient.sendVoiceBegin(sessionId)
                voiceStreamer.activateSession(sessionId)
                showMessage("TV 구글 어시스턴트 음성 전송 중 — 말씀 없으면 3초 후 자동 종료됩니다.")

                val streamStartMs = System.currentTimeMillis()
                if (lastVoiceActivityAtMs < streamStartMs) {
                    lastVoiceActivityAtMs = streamStartMs
                }
                var timedOutForInactivity = false
                while (isActive && _isVoiceListening.value && !voiceEndedByTv && remoteClient.isConnected) {
                    val now = System.currentTimeMillis()
                    val idleMs = now - lastVoiceActivityAtMs
                    val totalMs = now - streamStartMs
                    val eosElapsedMs = tvVoiceEosDetectedAtMs?.let { now - it }
                    val noActivityLimit = voiceNoActivityTimeoutMs
                    if (idleMs >= noActivityLimit) {
                        timedOutForInactivity = true
                        break
                    }
                    if (eosElapsedMs != null && eosElapsedMs >= TV_EOS_GRACE_MS) {
                        break
                    }
                    if (totalMs >= VOICE_STREAM_MAX_MS) {
                        break
                    }
                    val sleepMs = minOf(
                        100L,
                        (noActivityLimit - idleMs).coerceAtLeast(10L),
                        (VOICE_STREAM_MAX_MS - totalMs).coerceAtLeast(10L),
                        eosElapsedMs?.let { (TV_EOS_GRACE_MS - it).coerceAtLeast(10L) } ?: Long.MAX_VALUE
                    )
                    delay(sleepMs)
                }
                if (timedOutForInactivity && _isVoiceListening.value && !voiceEndedByTv) {
                    showMessage("음성 입력이 없어 3초 후 마이크를 종료했습니다.")
                }
                stopVoiceSession(sendEndPacket = true)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Handled by stopVoiceSession
            } catch (t: Throwable) {
                showMessage("음성 전송 오류: ${t.message}")
                stopVoiceSession(sendEndPacket = true)
            }
        }
    }

    fun stopVoiceSession(
        sendEndPacket: Boolean = true,
        keepVoiceSuppressionForFallback: Boolean = false
    ) {
        val sid = activeVoiceSessionId
        val canSendEnd = sid != null && sendEndPacket && !voiceEndedByTv && remoteClient.isConnected
        val wasFallbackActive = isVoiceFallbackActive
        activeVoiceSessionId = null
        tvVoiceEosDetectedAtMs = null
        voiceBeginDeferred?.cancel()
        voiceBeginDeferred = null
        voiceSessionJob?.cancel()
        voiceSessionJob = null
        isVoiceFallbackActive = false
        voiceFallbackWatchdogJob?.cancel()
        voiceFallbackWatchdogJob = null
        if (wasFallbackActive) {
            suppressNextFallbackError = true
            voiceStopChannel.trySend(Unit)
        }
        _isVoiceListening.value = false
        refreshVoiceAutoKeyboardSuppression()

        if (canSendEnd) {
            val endSessionId = checkNotNull(sid)
            isVoiceInteractionActive = true
            voiceFinalizeJob?.cancel()
            voiceFinalizeJob = viewModelScope.launch {
                try {
                    voiceStreamer.finishAndFlushAndJoin(endSessionId)
                    if (remoteClient.isConnected) {
                        runCatching { remoteClient.sendVoiceEnd(endSessionId) }
                    }
                } finally {
                    if (!keepVoiceSuppressionForFallback) {
                        isVoiceInteractionActive = false
                    }
                    refreshVoiceAutoKeyboardSuppression()
                }
            }
        } else {
            voiceFinalizeJob?.cancel()
            voiceFinalizeJob = null
            voiceStreamer.stop()
            if (!keepVoiceSuppressionForFallback) {
                isVoiceInteractionActive = false
            }
        }
    }

    fun onVoiceFallbackFinished(errorMessage: String? = null) {
        val suppressed = suppressNextFallbackError
        suppressNextFallbackError = false
        isVoiceFallbackActive = false
        voiceFallbackWatchdogJob?.cancel()
        voiceFallbackWatchdogJob = null
        _isVoiceListening.value = false
        isVoiceInteractionActive = false
        refreshVoiceAutoKeyboardSuppression()
        if (!suppressed && !errorMessage.isNullOrBlank()) {
            showMessage(errorMessage)
        }
    }

    fun triggerTvVoiceAssistWithoutMic() {
        if (!ensureConnectedOrPrompt()) return
        _isKeyboardSheetVisible.value = false
        refreshVoiceAutoKeyboardSuppression()
        showMessage("마이크 권한이 거부되어 TV 구글 어시스턴트 키만 전송합니다.")
        viewModelScope.launch {
            triggerTvGoogleAssistant()
        }
    }

    fun onVoiceSearchRecognized(spokenText: String) {
        isVoiceFallbackActive = false
        voiceFallbackWatchdogJob?.cancel()
        voiceFallbackWatchdogJob = null
        suppressNextFallbackError = false
        _isVoiceListening.value = false
        if (spokenText.isBlank()) {
            isVoiceInteractionActive = false
            refreshVoiceAutoKeyboardSuppression()
            return
        }
        if (!ensureConnectedOrPrompt()) {
            isVoiceInteractionActive = false
            refreshVoiceAutoKeyboardSuppression()
            return
        }
        val connectedHost = (_connectionState.value as? TvConnectionState.Connected)?.host
        isVoiceInteractionActive = true
        refreshVoiceAutoKeyboardSuppression()
        viewModelScope.launch {
            try {
                var imeActive = remoteClient.isImeFieldActive || _imePromptState.value.isTvImeVisible
                if (!imeActive) {
                    // Open TV search and wait until the TV actually reports a focused text field
                    runCatching {
                        remoteClient.sendKey(TvKeyCodes.SEARCH, AndroidTvRemoteClient.DIRECTION_SHORT)
                    }
                    imeActive = awaitTvImeField(VOICE_IME_WAIT_MS) ||
                        remoteClient.isImeFieldActive ||
                        _imePromptState.value.isTvImeVisible
                }
                if (!imeActive && adbClient.isConnected) {
                    val assistantSearch = adbClient.submitGoogleAssistantQuery(spokenText)
                    if (assistantSearch.isSuccess) {
                        showMessage("\"$spokenText\" 어시스턴트 음성 검색 실행")
                        return@launch
                    }
                }
                val done = CompletableDeferred<Boolean>()
                val queued = realTimeKeyQueue.trySend(
                    RealTimeKeyCommand.SendText(
                        host = connectedHost,
                        text = spokenText,
                        replaceExisting = true,
                        submitWithEnter = true,
                        showSuccessMessage = false,
                        resultDeferred = done
                    )
                )
                val sent = if (queued.isSuccess) done.await() else false
                if (sent) {
                    showMessage("\"$spokenText\" 음성 검색 전송")
                }
            } catch (t: Throwable) {
                showMessage("음성 검색 전송 실패: ${t.message}")
            } finally {
                refreshVoiceAutoKeyboardSuppression()
                isVoiceInteractionActive = false
            }
        }
    }

    private suspend fun awaitTvImeField(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            _imePromptState.first { it.isTvImeVisible }
        } != null

    companion object {
        const val DEFAULT_VOICE_NO_ACTIVITY_TIMEOUT_MS = 3_000L
        internal const val VOICE_SEARCH_TRIGGER_WAIT_MS = 1_100L
        internal const val VOICE_ASSIST_TRIGGER_WAIT_MS = 1_100L
        internal const val VOICE_STREAM_MAX_MS = 10_000L
        internal const val VOICE_AUTO_KEYBOARD_GRACE_MS = 5_000L
        internal const val VOICE_IME_WAIT_MS = 1_500L
        internal const val TV_EOS_GRACE_MS = 1_200L
        internal const val KEYBOARD_DISMISS_SUPPRESS_MS = 2_000L
    }

    fun toggleFavorite(app: TvAppCatalogItem, isCurrentlyFavorite: Boolean) {
        viewModelScope.launch {
            repository.toggleFavorite(app, isCurrentlyFavorite)
        }
    }

    fun addFavoriteFromRecent(recent: RecentTvAppEntity) {
        viewModelScope.launch {
            repository.addFavoriteFromRecent(recent)
        }
    }

    fun addCustomFavoriteApp(appName: String, launchUri: String, packageName: String) {
        viewModelScope.launch {
            repository.addCustomFavoriteApp(appName.trim(), launchUri.trim(), packageName.trim())
        }
    }

    fun removeFavorite(packageName: String) {
        viewModelScope.launch {
            repository.removeFavorite(packageName)
        }
    }

    fun moveFavorite(index: Int, delta: Int) {
        val current = favoriteApps.value
        viewModelScope.launch {
            repository.moveFavorite(current, index, delta)
        }
    }

    fun stepVolume(delta: Int) {
        if (delta == 0) return
        val max = _maxVolume.value.coerceAtLeast(1)
        val current = _volumeLevel.value
        val next = (current + delta).coerceIn(0, max)
        if (next == current && ((delta > 0 && current >= max) || (delta < 0 && current <= 0))) {
            return
        }
        if (!ensureConnectedOrPrompt()) return

        lastLocalVolumeAdjustMs = System.currentTimeMillis()
        _volumeLevel.value = next
        if (_isMuted.value && delta > 0) {
            _isMuted.value = false
        }

        val effectiveSteps = (next - current).coerceIn(-5, 5)
        if (effectiveSteps != 0) {
            volumeStepQueue.trySend(effectiveSteps)
        }

        volumeSettleJob?.cancel()
        volumeSettleJob = viewModelScope.launch {
            delay(650)
            lastTvReportedVolume?.let { tvLevel ->
                _volumeLevel.value = tvLevel.coerceIn(0, _maxVolume.value.coerceAtLeast(1))
            }
        }
    }

    fun toggleMute() {
        if (!ensureConnectedOrPrompt()) return
        _isMuted.value = !_isMuted.value
        sendKey(TvKeyCodes.MUTE)
    }

    fun stepBrightness(delta: Int) {
        if (delta == 0) return
        if (!ensureConnectedOrPrompt()) return
        val current = preferences.value.brightnessPercent
        val next = (current + delta).coerceIn(5, 100)
        if (next == current) return
        setBrightnessPercent(next)

        brightnessSyncJob?.cancel()
        brightnessSyncJob = viewModelScope.launch {
            delay(220)
            syncBrightnessWithFallback(next, allowOpenPictureSettings = true)
        }
    }

    fun toggleDayNightBrightness() {
        if (!ensureConnectedOrPrompt()) return
        val current = preferences.value.brightnessPercent
        val target = if (current <= 35) 60 else 30
        setBrightnessPercent(target)

        brightnessSyncJob?.cancel()
        brightnessSyncJob = viewModelScope.launch {
            syncBrightnessWithFallback(target, allowOpenPictureSettings = true)
        }
    }

    private suspend fun syncBrightnessWithFallback(targetPercent: Int, allowOpenPictureSettings: Boolean) {
        val result = adbClient.applyTvBrightnessPercent(targetPercent)
        if (result.adbConnected && result.hardwareApplied) {
            return
        }

        // Also transmit standard brightness keycode in case firmware responds to HID key
        val keyCode = if (targetPercent >= 50) TvKeyCodes.BRIGHTNESS_UP else TvKeyCodes.BRIGHTNESS_DOWN
        runCatching { remoteClient.sendKey(keyCode, AndroidTvRemoteClient.DIRECTION_SHORT) }

        if (allowOpenPictureSettings) {
            val now = System.currentTimeMillis()
            if (now - lastPictureFallbackNoticeMs > 4_000L) {
                lastPictureFallbackNoticeMs = now
                openTvPictureSettingsInternal()
                val msg = when {
                    adbClient.adbState.value == AdbConnectionState.AUTHORIZING ->
                        "TV에서 ADB 디버깅 '허용' 후 직접 제어가 가능합니다. 사진 설정을 열었습니다."
                    result.adbConnected ->
                        "샤오미 TV 백라이트 직접 변경 미지원 — TV 사진 설정을 열었습니다."
                    else ->
                        "ADB(5555) 미연결 — TV 사진/디스플레이 설정을 열었습니다."
                }
                _snackbarNotice.value = SnackbarNotice(
                    message = msg,
                    actionLabel = "ADB 재연결",
                    onAction = { retryAdbConnect() }
                )
            }
        }
    }

    fun openTvPictureSettings() {
        if (!ensureConnectedOrPrompt()) return
        viewModelScope.launch {
            openTvPictureSettingsInternal()
        }
    }

    private suspend fun openTvPictureSettingsInternal() {
        val openedViaAdb = adbClient.openDisplayPictureSettings()
        if (!openedViaAdb) {
            runCatching { remoteClient.sendKey(TvKeyCodes.SETTINGS, AndroidTvRemoteClient.DIRECTION_SHORT) }
        }
    }

    private fun setBrightnessPercent(percent: Int) {
        val clamped = percent.coerceIn(5, 100)
        viewModelScope.launch {
            repository.updatePreferences(
                hapticEnabled = preferences.value.hapticEnabled,
                autoShowKeyboard = preferences.value.autoShowKeyboard,
                brightnessPercent = clamped
            )
        }
    }

    fun setHapticEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updatePreferences(
                hapticEnabled = enabled,
                autoShowKeyboard = preferences.value.autoShowKeyboard,
                brightnessPercent = preferences.value.brightnessPercent
            )
        }
    }

    fun setAutoShowKeyboard(enabled: Boolean) {
        viewModelScope.launch {
            repository.updatePreferences(
                hapticEnabled = preferences.value.hapticEnabled,
                autoShowKeyboard = enabled,
                brightnessPercent = preferences.value.brightnessPercent
            )
        }
    }

    fun clearSnackbar() {
        _snackbarNotice.value = null
    }

    fun showMessage(msg: String) {
        _snackbarNotice.value = SnackbarNotice(message = msg)
    }

    internal fun configureRemoteClientForTest(
        connected: Boolean,
        sink: ((ByteArray) -> Unit)?
    ) {
        remoteClient.testRawBytesSink = sink
        remoteClient.setConnectedForTest(connected)
    }

    internal fun configureAdbClientForTest(
        connected: Boolean,
        shellRunner: (suspend (String) -> Result<String>)?
    ) {
        adbClient.configureForTest(connected, shellRunner)
        if (!connected) {
            _tvImeStatus.value = TvImeStatus()
        }
    }

    internal fun configureVoiceCaptureForTest(
        backend: (suspend (frameBytes: Int, isRunning: () -> Boolean, onFrame: suspend (ByteArray) -> Unit) -> Unit)?
    ) {
        voiceStreamer.captureBackendForTest = backend?.let { fn ->
            com.example.network.VoiceAudioCaptureBackend { frameBytes, isRunning, onFrame ->
                fn(frameBytes, isRunning, onFrame)
            }
        }
    }

    internal suspend fun applyRemoteFieldsForTest(fields: List<com.example.network.ProtobufWire.Field>) {
        remoteClient.applyIncomingFieldsForTest(fields)
    }

    internal val tvVoiceSupportedForTest: Boolean?
        get() = remoteClient.isVoiceSupportedByTv

    internal suspend fun refreshTvImeStatusForTest(syncLanguage: Boolean = !hasUserManuallyToggledLanguage) {
        refreshTvImeStatusInternal(syncLanguage = syncLanguage)
    }

    private fun ensureConnectedOrPrompt(): Boolean {
        if (remoteClient.isConnected) return true
        showMessage("먼저 상단 TV 선택 버튼에서 Wi-Fi TV를 연결해 주세요.")
        openTvPicker()
        return false
    }

    override fun onCleared() {
        super.onCleared()
        stopVoiceSession(sendEndPacket = false)
        clearVoiceAutoKeyboardSuppression()
        connectJob?.cancel()
        adbConnectJob?.cancel()
        brightnessSyncJob?.cancel()
        adbClient.disconnect()
        discoveryManager.stopDiscovery()
        pairingClient.close()
        remoteClient.disconnect(notify = false)
    }

    class Factory(
        private val repository: TvRemoteRepository,
        private val certStore: ClientCertStore,
        private val discoveryManager: TvDiscoveryManager,
        private val adbClient: NetworkAdbClient
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RemoteViewModel(repository, certStore, discoveryManager, adbClient) as T
        }
    }
}
