package com.elemensha.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elemensha.app.data.*
import com.elemensha.app.update.Updater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class UiState(
    val paired: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val serverVersion: String = "",
    val error: String? = null,
    val notice: String? = null,

    val credentials: CredentialInfo = CredentialInfo(),
    val meta: Meta = Meta(),
    val symbols: List<SymbolInfo> = emptyList(),
    val symbolsLoading: Boolean = false,

    val bots: List<BotStatus> = emptyList(),
    val events: List<Event> = emptyList(),

    /** 편집 중인 봇 설정 */
    val draft: BotConfig = BotConfig(),
    val draftSymbolInfo: SymbolInfo? = null,
    val exchangeSettings: ExchangeSettings? = null,
    /** 레버리지/마진 적용 결과 피드백 [요구사항 1] */
    val settingsFeedback: ExchangeSettingsResult? = null,
    val applyingSettings: Boolean = false,
    val busy: Boolean = false,

    val updateState: Updater.State = Updater.State.Idle,

    /** 잔고 그래프 */
    val balanceHistory: BalanceHistory? = null,
    val balancePeriod: String = "week",
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    val api = Api(prefs)
    val updater = Updater(app, api)

    private val _state = MutableStateFlow(UiState(paired = prefs.isPaired))
    val state: StateFlow<UiState> = _state.asStateFlow()

    val savedServerUrl: String get() = prefs.serverUrl

    private var streamJob: Job? = null

    init {
        if (prefs.isPaired) {
            restoreDraft()
            connect()
        }
    }

    private fun update(block: (UiState) -> UiState) = _state.update(block)

    private fun MutableStateFlow<UiState>.update(block: (UiState) -> UiState) {
        value = block(value)
    }

    fun dismissError() = update { it.copy(error = null, notice = null) }

    private fun fail(t: Throwable) = update { it.copy(error = t.message ?: "오류", busy = false) }

    // --------------------------------------------------------------- 페어링

    fun pair(serverUrl: String, code: String) = viewModelScope.launch {
        update { it.copy(connecting = true, error = null) }
        runCatching {
            val normalized = serverUrl.trim().let {
                if (it.startsWith("http")) it else "https://$it"
            }
            api.pair(normalized, code.trim(), android.os.Build.MODEL ?: "android")
        }.onSuccess { result ->
            update {
                it.copy(paired = true, connecting = false, serverVersion = result.serverVersion)
            }
            connect()
        }.onFailure {
            update { s -> s.copy(connecting = false, error = it.message) }
        }
    }

    fun unpair() {
        streamJob?.cancel()
        prefs.clear()
        _state.value = UiState(paired = false)
    }

    // --------------------------------------------------------------- 연결

    fun connect() {
        streamJob?.cancel()
        refreshAll()
        streamJob = viewModelScope.launch {
            while (true) {
                api.stream()
                    .catch { update { s -> s.copy(connected = false) } }
                    .collect { frame ->
                        when (frame.type) {
                            "hello" -> update {
                                it.copy(connected = true, bots = frame.bots,
                                        serverVersion = frame.serverVersion)
                            }
                            "status" -> update { it.copy(connected = true, bots = frame.bots) }
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
        runCatching { api.credentials() }.onSuccess { c -> update { it.copy(credentials = c) } }
        runCatching { api.bots() }.onSuccess { b -> update { it.copy(bots = b.bots) } }
        runCatching { api.events(200) }.onSuccess { e -> update { it.copy(events = e.events) } }
        if (_state.value.credentials.configured) {
            loadSymbols()
            loadBalanceHistory()
        }
    }

    // ---------------------------------------------------------- API 키 [7]

    fun saveCredentials(key: String, secret: String, testnet: Boolean) =
        viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            runCatching { api.setCredentials(key.trim(), secret.trim(), testnet) }
                .onSuccess { info ->
                    update {
                        it.copy(busy = false, credentials = info,
                                notice = "API 키 등록 완료 — 잔고 " +
                                         "%,.2f USDT".format(info.usdtBalance ?: 0.0))
                    }
                    loadSymbols()
                }
                .onFailure { fail(it) }
        }

    fun deleteCredentials() = viewModelScope.launch {
        update { it.copy(busy = true) }
        runCatching { api.deleteCredentials() }
            .onSuccess {
                update {
                    it.copy(busy = false, credentials = CredentialInfo(),
                            symbols = emptyList(), notice = "API 키를 삭제하고 봇을 정지했습니다.")
                }
            }
            .onFailure { fail(it) }
    }

    // ----------------------------------------------------------- 심볼 [3]

    fun loadSymbols(refresh: Boolean = false) = viewModelScope.launch {
        update { it.copy(symbolsLoading = true) }
        runCatching { api.symbols(refresh) }
            .onSuccess { r ->
                update { s ->
                    s.copy(symbolsLoading = false, symbols = r.symbols,
                           draftSymbolInfo = r.symbols.find { it.symbol == s.draft.symbol })
                }
            }
            .onFailure { update { s -> s.copy(symbolsLoading = false, error = it.message) } }
    }

    // -------------------------------------------------------------- 설정 편집

    fun editDraft(block: (BotConfig) -> BotConfig) {
        update { s ->
            val next = block(s.draft)
            prefs.lastConfigJson = api.json.encodeToString(BotConfig.serializer(), next)
            s.copy(draft = next,
                   draftSymbolInfo = s.symbols.find { it.symbol == next.symbol })
        }
    }

    private fun restoreDraft() {
        val saved = prefs.lastConfigJson
        if (saved.isBlank()) return
        runCatching { api.json.decodeFromString(BotConfig.serializer(), saved) }
            .onSuccess { cfg -> update { it.copy(draft = cfg) } }
    }

    fun selectSymbol(symbol: String) {
        editDraft { it.copy(symbol = symbol) }
        loadExchangeSettings(symbol)
    }

    /** 전체 봉 적용 토글 [사용자 요청] */
    fun toggleAllTimeframes(enabled: Boolean) = editDraft { draft ->
        val all = _state.value.meta.allTimeframes
        if (enabled) draft.copy(useAllTimeframes = true,
                                timeframes = all.ifEmpty { draft.timeframes })
        else draft.copy(useAllTimeframes = false)
    }

    fun toggleTimeframe(tf: String) = editDraft { draft ->
        val next = if (tf in draft.timeframes) draft.timeframes - tf
                   else (draft.timeframes + tf)
        val ordered = _state.value.meta.allTimeframes
            .filter { it in next }
            .ifEmpty { next }
        draft.copy(timeframes = ordered, useAllTimeframes = false)
    }

    // ------------------------------------------- 레버리지/마진 적용 [요구사항 1]

    fun loadExchangeSettings(symbol: String) = viewModelScope.launch {
        runCatching { api.exchangeSettings(symbol) }
            .onSuccess { e -> update { it.copy(exchangeSettings = e) } }
    }

    fun applyExchangeSettings() = viewModelScope.launch {
        val draft = _state.value.draft
        update { it.copy(applyingSettings = true, settingsFeedback = null, error = null) }
        runCatching {
            api.applyExchangeSettings(draft.symbol, draft.leverage, draft.marginMode)
        }.onSuccess { result ->
            update { it.copy(applyingSettings = false, settingsFeedback = result) }
            loadExchangeSettings(draft.symbol)
        }.onFailure {
            update { s -> s.copy(applyingSettings = false, error = it.message) }
        }
    }

    // ---------------------------------------------------------------- 봇

    fun startBot() = viewModelScope.launch {
        update { it.copy(busy = true, error = null) }
        runCatching { api.startBot(_state.value.draft) }
            .onSuccess {
                update { s -> s.copy(busy = false, notice = "${s.draft.symbol} 봇을 시작했습니다.") }
                runCatching { api.bots() }.onSuccess { b -> update { it.copy(bots = b.bots) } }
            }
            .onFailure { fail(it) }
    }

    fun stopBot(symbol: String) = botAction(symbol, "정지") { api.stopBot(symbol) }
    fun panicBot(symbol: String) = botAction(symbol, "긴급 청산") { api.panicBot(symbol) }
    fun deleteBot(symbol: String) = botAction(symbol, "삭제") { api.deleteBot(symbol) }
    fun panicAll() = botAction("전체", "긴급 청산") { api.panicAll() }

    private fun botAction(symbol: String, label: String, block: suspend () -> Any) =
        viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            runCatching { block() }
                .onSuccess {
                    update { it.copy(busy = false, notice = "$symbol $label 완료") }
                    runCatching { api.bots() }.onSuccess { b -> update { it.copy(bots = b.bots) } }
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

    /** 지금 즉시 한 점 기록하고 그래프를 다시 불러온다. */
    fun snapshotNow() = viewModelScope.launch {
        update { it.copy(busy = true) }
        runCatching { api.snapshotNow() }
            .onSuccess { update { it.copy(busy = false) }; loadBalanceHistory() }
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
        // 다운로드가 끝나면 설치 권한 유무에 따라 분기
        val next = if (result is Updater.State.ReadyToInstall && !updater.canInstall()) {
            Updater.State.NeedsPermission(result.file, result.info)
        } else result
        update { it.copy(updateState = next) }
    }

    fun installUpdate(state: Updater.State.ReadyToInstall) = updater.install(state.file)

    fun requestInstallPermission() = updater.openInstallPermissionSettings()

    fun retryInstallAfterPermission(state: Updater.State.NeedsPermission) {
        if (updater.canInstall()) {
            update { it.copy(updateState = Updater.State.ReadyToInstall(state.file, state.info)) }
            updater.install(state.file)
        } else {
            updater.openInstallPermissionSettings()
        }
    }
}
