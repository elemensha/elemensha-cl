package com.elemensha.copy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elemensha.copy.data.*
import com.elemensha.copy.update.Updater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class CopyUiState(
    val joined: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val busy: Boolean = false,

    val me: MeResponse = MeResponse(),
    val meta: CopyMeta = CopyMeta(),
    val status: CopyStatus = CopyStatus(),

    /** 거래소에서 방금 읽어온 내 계좌. [사용자 요청] */
    val account: AccountSummary = AccountSummary(),
    val accountLoading: Boolean = false,
    val positions: List<Position> = emptyList(),

    /** 내 미체결 지정가 주문. [사용자 요청] */
    val orders: List<OpenOrder> = emptyList(),
    val ordersLoading: Boolean = false,

    val events: List<Event> = emptyList(),

    /** 편집 중인 카피 설정 */
    val draft: CopyConfig = CopyConfig(),
    val draftDirty: Boolean = false,

    val balanceHistory: BalanceHistory? = null,
    val balancePeriod: String = "week",

    val updateState: Updater.State = Updater.State.Idle,
) {
    val credentialsConfigured: Boolean get() = me.credentials.configured
    val running: Boolean get() = status.running
}

class CopyViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    val api = Api(prefs)
    val updater = Updater(app, api)

    private val _state = MutableStateFlow(CopyUiState(joined = prefs.isJoined))
    val state: StateFlow<CopyUiState> = _state.asStateFlow()

    val savedServerUrl: String get() = prefs.serverUrl
    val myLabel: String get() = prefs.label

    private var streamJob: Job? = null

    init {
        if (prefs.isJoined) connect()
    }

    private fun update(block: (CopyUiState) -> CopyUiState) {
        _state.value = block(_state.value)
    }

    fun dismissMessage() = update { it.copy(error = null, notice = null) }

    private fun fail(t: Throwable) =
        update { it.copy(error = t.message ?: "오류", busy = false) }

    // ---------------------------------------------------------------- 가입

    fun join(serverUrl: String, code: String) = viewModelScope.launch {
        update { it.copy(connecting = true, error = null) }
        runCatching {
            val normalized = serverUrl.trim().let {
                if (it.startsWith("http")) it else "https://$it"
            }
            api.join(normalized, code.trim(), android.os.Build.MODEL ?: "android")
        }.onSuccess {
            update { s -> s.copy(joined = true, connecting = false) }
            connect()
        }.onFailure {
            update { s -> s.copy(connecting = false, error = it.message) }
        }
    }

    fun leave() {
        streamJob?.cancel()
        prefs.clear()
        _state.value = CopyUiState(joined = false)
    }

    // ---------------------------------------------------------------- 연결

    fun connect() {
        streamJob?.cancel()
        refreshAll()
        streamJob = viewModelScope.launch {
            while (true) {
                api.stream()
                    .catch { update { s -> s.copy(connected = false) } }
                    .collect { frame ->
                        when (frame.type) {
                            "hello", "status" -> update { s ->
                                s.copy(connected = true,
                                       status = frame.status ?: s.status)
                            }
                            "event" -> frame.event?.let { ev ->
                                update { s ->
                                    s.copy(connected = true,
                                           events = (listOf(ev) + s.events).take(300))
                                }
                            }
                        }
                    }
                update { it.copy(connected = false) }
                delay(5_000)   // 끊기면 5초 뒤 재구독
            }
        }
    }

    fun refreshAll() = viewModelScope.launch {
        runCatching { api.meta() }.onSuccess { m -> update { it.copy(meta = m) } }
        runCatching { api.me() }.onSuccess { me ->
            update { s ->
                // 서버 설정을 초안에 반영하되, 사용자가 편집 중이면 덮어쓰지 않는다
                s.copy(me = me, draft = if (s.draftDirty) s.draft else me.config)
            }
        }
        runCatching { api.status() }.onSuccess { st -> update { it.copy(status = st) } }
        runCatching { api.events(200) }.onSuccess { e -> update { it.copy(events = e.events) } }
        if (_state.value.credentialsConfigured) {
            loadAccount()
            loadOrders()
            loadBalanceHistory()
        }
    }

    // -------------------------------------------------- 내 자산 [사용자 요청]

    fun loadAccount() = viewModelScope.launch {
        update { it.copy(accountLoading = true) }
        runCatching { api.account() }
            .onSuccess { a -> update { it.copy(account = a, accountLoading = false) } }
            .onFailure { update { s -> s.copy(accountLoading = false, error = it.message) } }
        runCatching { api.positions() }
            .onSuccess { p -> update { it.copy(positions = p.positions) } }
    }

    // ----------------------------------------- 지정가 주문 상태 [사용자 요청]

    fun loadOrders() = viewModelScope.launch {
        update { it.copy(ordersLoading = true) }
        runCatching { api.orders() }
            .onSuccess { r -> update { it.copy(orders = r.orders, ordersLoading = false) } }
            .onFailure { update { s -> s.copy(ordersLoading = false, error = it.message) } }
    }

    // -------------------------------------------------------------- API 키

    fun saveCredentials(key: String, secret: String, testnet: Boolean) =
        viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            runCatching { api.setCredentials(key.trim(), secret.trim(), testnet) }
                .onSuccess { info ->
                    update {
                        it.copy(busy = false,
                                me = it.me.copy(credentials = info),
                                notice = "API 키 등록 완료 — 잔고 " +
                                         "%,.2f USDT".format(info.usdtBalance ?: 0.0))
                    }
                    loadAccount()
                    loadBalanceHistory()
                }
                .onFailure { fail(it) }
        }

    fun deleteCredentials() = viewModelScope.launch {
        update { it.copy(busy = true) }
        runCatching { api.deleteCredentials() }
            .onSuccess {
                update {
                    it.copy(busy = false,
                            me = it.me.copy(credentials = CredentialInfo()),
                            account = AccountSummary(), positions = emptyList(),
                            orders = emptyList(),
                            notice = "API 키를 삭제하고 카피를 정지했습니다.")
                }
                refreshAll()
            }
            .onFailure { fail(it) }
    }

    // ---------------------------------------------------------------- 설정

    fun editDraft(block: (CopyConfig) -> CopyConfig) {
        update { it.copy(draft = block(it.draft), draftDirty = true) }
    }

    fun resetDraft() = update { it.copy(draft = it.me.config, draftDirty = false) }

    fun saveConfig() = viewModelScope.launch {
        update { it.copy(busy = true, error = null) }
        runCatching { api.saveConfig(_state.value.draft) }
            .onSuccess {
                update { it.copy(busy = false, draftDirty = false, notice = "설정을 저장했습니다.") }
                refreshAll()
            }
            .onFailure { fail(it) }
    }

    // ------------------------------------------------------------ 시작/정지

    fun start() = action("카피 시작") { api.start() }
    fun stop() = action("카피 정지") { api.stop() }
    fun panic() = action("긴급 청산") { api.panic() }

    private fun action(label: String, block: suspend () -> Any) =
        viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            runCatching { block() }
                .onSuccess {
                    update { it.copy(busy = false, notice = "$label 완료") }
                    refreshAll()
                }
                .onFailure { fail(it) }
        }

    // ---------------------------------------------------------- 잔고 그래프

    fun loadBalanceHistory(period: String? = null) = viewModelScope.launch {
        val target = period ?: _state.value.balancePeriod
        if (period != null) update { it.copy(balancePeriod = target) }
        runCatching { api.balanceHistory(target) }
            .onSuccess { h -> update { it.copy(balanceHistory = h) } }
            .onFailure { update { s -> s.copy(error = it.message) } }
    }

    fun snapshotNow() = viewModelScope.launch {
        update { it.copy(busy = true) }
        runCatching { api.snapshotNow() }
            .onSuccess {
                update { it.copy(busy = false) }
                loadBalanceHistory()
                loadAccount()
            }
            .onFailure { fail(it) }
    }

    // ------------------------------------------------------------ 업데이트

    fun checkUpdate() = viewModelScope.launch {
        update { it.copy(updateState = Updater.State.Checking) }
        val result = updater.check()
        update { it.copy(updateState = result) }
    }

    fun downloadUpdate(info: AppVersionInfo) = viewModelScope.launch {
        update { it.copy(updateState = Updater.State.Downloading(0, info)) }
        val result = updater.download(info) { percent ->
            update { it.copy(updateState = Updater.State.Downloading(percent, info)) }
        }
        val next = if (result is Updater.State.ReadyToInstall && !updater.canInstall()) {
            Updater.State.NeedsPermission(result.file, result.info)
        } else result
        update { it.copy(updateState = next) }
    }

    fun installUpdate(state: Updater.State.ReadyToInstall) = updater.install(state.file)

    fun retryInstallAfterPermission(state: Updater.State.NeedsPermission) {
        if (updater.canInstall()) {
            update { it.copy(updateState = Updater.State.ReadyToInstall(state.file, state.info)) }
            updater.install(state.file)
        } else {
            updater.openInstallPermissionSettings()
        }
    }
}
