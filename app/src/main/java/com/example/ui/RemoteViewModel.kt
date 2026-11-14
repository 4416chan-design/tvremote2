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
import com.example.data.model.TvAppCatalog
import com.example.data.model.TvAppCatalogItem
import com.example.data.model.TvConnectionState
import com.example.data.model.TvImePromptState
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

    private var lastAutoReconnectAttemptMs: Long = 0L
    private var lastLocalVolumeAdjustMs: Long = 0L
    private var lastTvReportedVolume: Int? = null
    private var volumeSettleJob: Job? = null
    private val volumeStepQueue = Channel<Int>(Channel.UNLIMITED)

    private var connectJob: Job? = null
    private var adbConnectJob: Job? = null
    private var brightnessSyncJob: Job? = null
    private var voiceSessionJob: Job? = null
    private var lastPictureFallbackNoticeMs: Long = 0L
    private var lastAdbSentTextLength: Int = 0

    private val voiceStreamer = TvVoiceStreamer()
    private val voiceFallbackChannel = Channel<Unit>(Channel.BUFFERED)
    val voiceFallbackRequests: Flow<Unit> = voiceFallbackChannel.receiveAsFlow()

    @Volatile
    private var voiceBeginDeferred: CompletableDeferred<Int>? = null

    @Volatile
    private var activeVoiceSessionId: Int? = null

    @Volatile
    private var voiceEndedByTv: Boolean = false

    private val pairingClient = AndroidTvPairingClient(certStore)
    private val remoteClient = AndroidTvRemoteClient(
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
            if (preferences.value.autoShowKeyboard) {
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
            voiceBeginDeferred?.complete(sessionId)
        },
        onVoiceEndRequested = { sessionId ->
            if (activeVoiceSessionId == sessionId || activeVoiceSessionId != null) {
                voiceEndedByTv = true
                stopVoiceSession(sendEndPacket = false)
            }
        },
        onRemoteError = { _, detail ->
            if (!detail.isNullOrBlank()) {
                showMessage(detail)
            }
        },
        onDisconnected = { cause ->
            stopVoiceSession(sendEndPacket = false)
            adbConnectJob?.cancel()
            adbClient.disconnect()
            _imePromptState.value = TvImePromptState()
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
            adbClient.connect(host, NetworkAdbClient.DEFAULT_ADB_PORT)
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
        stopVoiceSession(sendEndPacket = true)
        val current = _connectionState.value
        if (current is TvConnectionState.PairingRequired) {
            return
        }
        connectJob?.cancel()
        adbConnectJob?.cancel()
        if (current is TvConnectionState.Connected || current is TvConnectionState.Connecting) {
            adbClient.disconnect()
            remoteClient.disconnect(notify = false)
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
        adbConnectJob?.cancel()
        adbClient.disconnect()
        remoteClient.disconnect(notify = false)
        _connectionState.value = TvConnectionState.Disconnected
        showMessage("TV 연결이 해제되었습니다.")
    }

    fun forgetPairedTv(host: String) {
        viewModelScope.launch {
            val active = _connectionState.value
            if (active is TvConnectionState.Connected && active.host == host) {
                adbConnectJob?.cancel()
                adbClient.disconnect()
                remoteClient.disconnect(notify = false)
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
        _isKeyboardSheetVisible.value = true
    }

    fun dismissKeyboardSheet() {
        _isKeyboardSheetVisible.value = false
    }

    fun sendKeyboardText(text: String, replaceExisting: Boolean, submitWithEnter: Boolean) {
        if (!ensureConnectedOrPrompt()) return
        val connectedHost = (_connectionState.value as? TvConnectionState.Connected)?.host
        viewModelScope.launch {
            sendTextViaStandardImeOrAdb(
                host = connectedHost,
                text = text,
                replaceExisting = replaceExisting,
                submitWithEnter = submitWithEnter,
                showSuccessMessage = submitWithEnter
            )
        }
    }

    /**
     * Standard Android TV Remote v2 keyboard transmission:
     * 1) When the TV has an active IME input field (RemoteImeShowRequest / RemoteImeKeyInject),
     *    send all UTF-8 text (Korean Hangul, English, symbols) via RemoteImeBatchEdit + ENTER.
     * 2) When the TV app uses a custom keyboard without native Android IME focus (e.g. YouTube UI),
     *    fall back to ADB `input text` (ASCII) or ADBKeyboard broadcast (if installed).
     */
    private suspend fun sendTextViaStandardImeOrAdb(
        host: String?,
        text: String,
        replaceExisting: Boolean,
        submitWithEnter: Boolean,
        showSuccessMessage: Boolean
    ): Boolean {
        val imeActive = remoteClient.isImeFieldActive || _imePromptState.value.isTvImeVisible
        if (remoteClient.isConnected && imeActive) {
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
        }

        // TV IME is not currently active: try ADB (ASCII input text or ADBKeyboard Base64 Hangul)
        if (!adbClient.isConnected && !host.isNullOrBlank()) {
            adbClient.connect(host, NetworkAdbClient.DEFAULT_ADB_PORT)
        }

        if (adbClient.isConnected) {
            val clearCount = when {
                text.isEmpty() -> maxOf(lastAdbSentTextLength + 4, 20)
                replaceExisting && lastAdbSentTextLength > 0 -> (lastAdbSentTextLength + 2).coerceAtMost(30)
                else -> 0
            }

            if (text.isEmpty() || NetworkAdbClient.isAsciiInputSupported(text) || adbClient.hasAdbKeyboardIme()) {
                var adbResult = adbClient.sendInputText(
                    text = text,
                    clearCount = clearCount,
                    submitWithEnter = submitWithEnter
                )
                if (adbResult.isFailure && !adbClient.isConnected && !host.isNullOrBlank()) {
                    if (adbClient.connect(host, NetworkAdbClient.DEFAULT_ADB_PORT)) {
                        adbResult = adbClient.sendInputText(
                            text = text,
                            clearCount = clearCount,
                            submitWithEnter = submitWithEnter
                        )
                    }
                }
                if (adbResult.isSuccess) {
                    lastAdbSentTextLength = if (submitWithEnter || text.isEmpty()) 0 else text.length
                    if (showSuccessMessage) {
                        showMessage("ADB로 입력을 전송했습니다.")
                    }
                    return true
                }
            }
        }

        // Last-resort attempt via RemoteImeBatchEdit even if TV didn't emit RemoteImeShowRequest
        val fallbackResult = runCatching {
            remoteClient.sendText(
                text = text,
                replaceExisting = replaceExisting,
                submitWithEnter = submitWithEnter
            )
        }
        return if (fallbackResult.isSuccess) {
            if (!imeActive && !NetworkAdbClient.isAsciiInputSupported(text) && text.isNotEmpty()) {
                showMessage("TV 검색창(키보드)이 열려 있는지 확인해 주세요. (자체 키보드 앱은 영문 ADB 입력 지원)")
                false
            } else {
                if (showSuccessMessage) {
                    showMessage("TV로 입력을 전송했습니다.")
                }
                true
            }
        } else {
            showMessage("텍스트 전송 실패: ${fallbackResult.exceptionOrNull()?.message}")
            false
        }
    }

    fun setVoiceListening(listening: Boolean) {
        _isVoiceListening.value = listening
    }

    /**
     * Initiates Android TV Remote v2 PCM voice streaming:
     * 1) Pre-captures 8 kHz 16-bit mono PCM immediately so opening words aren't lost
     * 2) Sends KEYCODE_VOICE_ASSIST (then KEYCODE_SEARCH fallback if needed)
     * 3) Upon TV's RemoteVoiceBegin(sessionId), echoes RemoteVoiceBegin and streams RemoteVoicePayload
     * 4) Falls back to phone SpeechRecognizer -> ADB text if TV firmware does not open RemoteVoiceBegin
     */
    fun startVoiceSession() {
        if (!ensureConnectedOrPrompt()) return
        if (_isVoiceListening.value) {
            stopVoiceSession(sendEndPacket = true)
            return
        }

        voiceSessionJob?.cancel()
        voiceStreamer.stop()
        activeVoiceSessionId = null
        voiceEndedByTv = false
        val beginSignal = CompletableDeferred<Int>()
        voiceBeginDeferred = beginSignal
        _isVoiceListening.value = true

        voiceSessionJob = viewModelScope.launch {
            try {
                voiceStreamer.startCapture(
                    scope = this,
                    onChunk = { sid, chunk ->
                        if (remoteClient.isConnected) {
                            runCatching { remoteClient.sendVoicePayload(sid, chunk) }
                        }
                    },
                    onCaptureFailed = { err ->
                        showMessage("마이크 시작 실패: ${err.message ?: "권한 또는 오디오 장치 오류"}")
                        stopVoiceSession(sendEndPacket = false)
                    }
                )

                // Trigger TV search/voice assistant and wait for RemoteVoiceBegin(30)
                runCatching {
                    remoteClient.sendKey(TvKeyCodes.SEARCH, AndroidTvRemoteClient.DIRECTION_SHORT)
                }

                var sessionId = withTimeoutOrNull(1_150L) { beginSignal.await() }
                if (sessionId == null && remoteClient.isConnected && _isVoiceListening.value) {
                    runCatching {
                        remoteClient.sendKey(TvKeyCodes.VOICE_ASSIST, AndroidTvRemoteClient.DIRECTION_SHORT)
                    }
                    sessionId = withTimeoutOrNull(1_150L) { beginSignal.await() }
                }

                if (sessionId == null) {
                    // TV did not open a Protocol v2 RemoteVoiceBegin stream; release mic & fall back to SpeechRecognizer
                    voiceBeginDeferred = null
                    // Wait for AudioRecord release before SpeechRecognizer opens the mic
                    voiceStreamer.stopAndJoin()
                    _isVoiceListening.value = false
                    if (remoteClient.isConnected) {
                        voiceFallbackChannel.trySend(Unit)
                    }
                    return@launch
                }

                activeVoiceSessionId = sessionId
                remoteClient.sendVoiceBegin(sessionId)
                voiceStreamer.activateSession(sessionId)
                showMessage("TV로 음성을 전송 중입니다. 말씀하신 뒤 마이크를 다시 누르세요.")

                // Auto-stop after 10 seconds if TV VAD hasn't ended the stream yet
                delay(10_000L)
                stopVoiceSession(sendEndPacket = true)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Handled by stopVoiceSession
            } catch (t: Throwable) {
                showMessage("음성 전송 오류: ${t.message}")
                stopVoiceSession(sendEndPacket = true)
            }
        }
    }

    fun stopVoiceSession(sendEndPacket: Boolean = true) {
        val sid = activeVoiceSessionId
        val shouldSendEnd = sendEndPacket && sid != null && !voiceEndedByTv && remoteClient.isConnected
        activeVoiceSessionId = null
        voiceBeginDeferred?.cancel()
        voiceBeginDeferred = null
        voiceSessionJob?.cancel()
        voiceSessionJob = null
        voiceStreamer.stop()
        _isVoiceListening.value = false

        if (shouldSendEnd) {
            viewModelScope.launch {
                runCatching { remoteClient.sendVoiceEnd(sid!!) }
            }
        }
    }

    fun onVoiceSearchRecognized(spokenText: String) {
        _isVoiceListening.value = false
        if (spokenText.isBlank()) return
        if (!ensureConnectedOrPrompt()) return
        val connectedHost = (_connectionState.value as? TvConnectionState.Connected)?.host
        viewModelScope.launch {
            runCatching {
                val imeAlreadyActive = remoteClient.isImeFieldActive || _imePromptState.value.isTvImeVisible
                if (!imeAlreadyActive) {
                    // Open TV search and wait until the TV actually reports a focused text field
                    // (fixed 350 ms delay was too short, so text was sent before the field existed).
                    remoteClient.sendKey(TvKeyCodes.SEARCH, AndroidTvRemoteClient.DIRECTION_SHORT)
                    awaitTvImeField(VOICE_IME_WAIT_MS)
                }
                val sent = sendTextViaStandardImeOrAdb(
                    host = connectedHost,
                    text = spokenText,
                    replaceExisting = true,
                    submitWithEnter = true,
                    showSuccessMessage = false
                )
                if (sent) {
                    showMessage("\"$spokenText\" 음성 검색 전송")
                }
            }.onFailure {
                showMessage("음성 검색 전송 실패: ${it.message}")
            }
        }
    }

    private suspend fun awaitTvImeField(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            _imePromptState.first { it.isTvImeVisible }
        } != null

    private companion object {
        const val VOICE_IME_WAIT_MS = 3_000L
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

    private fun ensureConnectedOrPrompt(): Boolean {
        if (remoteClient.isConnected) return true
        showMessage("먼저 상단 TV 선택 버튼에서 Wi-Fi TV를 연결해 주세요.")
        openTvPicker()
        return false
    }

    override fun onCleared() {
        super.onCleared()
        stopVoiceSession(sendEndPacket = true)
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
