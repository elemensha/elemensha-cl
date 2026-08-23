package com.elemensha.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 서버 /api/bots/start 로 보내는 전체 파라미터. 서버 스키마와 1:1. */
@Serializable
data class BotConfig(
    val symbol: String = "BTC/USDT:USDT",

    // 거래소 설정 — 바꾸면 바이낸스에 적용되고 검증 결과가 돌아온다
    val leverage: Int = 1,
    val marginMode: String = "ISOLATED",

    // 신호
    val timeframes: List<String> = listOf("1m", "5m", "15m", "1h", "4h", "1d"),
    val useAllTimeframes: Boolean = false,
    val rsiPeriod: Int = 14,
    val rsiLower: Double = 30.0,
    val rsiUpper: Double = 70.0,
    val entryTrigger: String = "cross_up_lower",

    // 사이징
    val walletPercentage: Double = 0.001,
    val minNotionalRoundUp: Double = 10.0,
    val maxAdditionalBuys: Int? = null,          // null = 무제한

    // 청산 (익절 전용)
    val takeProfitPercent: Double = 0.01,

    val maxPositionNotional: Double? = null,
    val pollSeconds: Int = 20,
    val dryRun: Boolean = false,
)

@Serializable
data class TimeframeOption(val value: String, val default: Boolean = false)

@Serializable
data class LabeledOption(val value: String, val label: String)

@Serializable
data class MetaNotes(
    val closedCandleOnly: Boolean = true,
    val longOnly: Boolean = true,
    val stopLoss: Boolean = false,
    val rsiExit: Boolean = false,
)

@Serializable
data class Meta(
    val timeframes: List<TimeframeOption> = emptyList(),
    val allTimeframes: List<String> = emptyList(),
    val entryTriggers: List<LabeledOption> = emptyList(),
    val marginModes: List<LabeledOption> = emptyList(),
    val notes: MetaNotes = MetaNotes(),
)

@Serializable
data class SymbolInfo(
    val symbol: String,
    val id: String = "",
    val base: String = "",
    val price: Double = 0.0,
    val minNotional: Double = 0.0,
    val minQty: Double = 0.0,
    val effectiveMinNotional: Double = 0.0,
    /** true면 최소 주문액을 수량 하한이 결정한다 (BTC·BNB). */
    val qtyBound: Boolean = false,
    val maxLeverage: Int = 125,
)

@Serializable
data class SymbolsResponse(val count: Int = 0, val symbols: List<SymbolInfo> = emptyList())

/** 레버리지·마진모드 적용 결과. verified 가 바이낸스에서 되읽어 확인한 값. */
@Serializable
data class SettingResult(
    val symbol: String = "",
    val requested: JsonElement? = null,
    val actual: JsonElement? = null,
    val verified: Boolean = false,
    val message: String = "",
)

@Serializable
data class ExchangeSettingsResult(
    val leverage: SettingResult = SettingResult(),
    val marginMode: SettingResult = SettingResult(),
    val allVerified: Boolean = false,
)

@Serializable
data class ExchangeSettings(
    val symbol: String = "",
    val leverage: Int? = null,
    val marginMode: String? = null,
    val maxLeverage: Int = 125,
    val price: Double = 0.0,
    val minNotional: Double = 0.0,
    val minQty: Double = 0.0,
    val effectiveMinNotional: Double = 0.0,
    val qtyBound: Boolean = false,
)

@Serializable
data class TimeframeStatus(
    val rsi: Double? = null,
    val prevRsi: Double? = null,
    val buyCount: Int = 0,
    val maxBuys: Int? = null,
    val lastCandleTs: Long? = null,
    val error: String? = null,
)

@Serializable
data class TakeProfitStatus(
    val orderId: String? = null,
    val price: Double? = null,
    val amount: Double? = null,
)

@Serializable
data class LiveStatus(
    val balance: Double? = null,
    val price: Double? = null,
    val positionSize: Double? = null,
    val entryPrice: Double? = null,
    val unrealizedPnl: Double? = null,
    val liquidationPrice: Double? = null,
    val rsi: Map<String, Double?> = emptyMap(),
    val buyCounts: Map<String, Int> = emptyMap(),
    val tp: String? = null,
)

@Serializable
data class BotStatus(
    val symbol: String = "",
    val running: Boolean = false,
    val lastError: String? = null,
    val consecutiveErrors: Int = 0,
    val config: JsonElement? = null,
    val entryTriggerLabel: String = "",
    val timeframes: Map<String, TimeframeStatus> = emptyMap(),
    val takeProfit: TakeProfitStatus = TakeProfitStatus(),
    val realizedTrades: Int = 0,
    val startedAt: Double? = null,
    val lastTickAt: Double? = null,
    val live: LiveStatus? = null,
)

@Serializable
data class BotsResponse(val bots: List<BotStatus> = emptyList())

@Serializable
data class CredentialInfo(
    val configured: Boolean = false,
    val apiKeyMasked: String = "",
    val testnet: Boolean = false,
    val usdtBalance: Double? = null,
    val canTrade: Boolean? = null,
)

// ------------------------------------------------------------- 잔고 그래프

@Serializable
data class BalancePoint(
    val ts: Double = 0.0,
    val wallet: Double = 0.0,
    /** 지갑 + 미실현손익. 손절이 없는 전략이라 이 값이 진짜 성과다. */
    val equity: Double = 0.0,
    val unrealizedPnl: Double = 0.0,
    val positionNotional: Double = 0.0,
    val openPositions: Int = 0,
    val low: Double = 0.0,
    val high: Double = 0.0,
    val samples: Int = 0,
)

@Serializable
data class BalanceSummary(
    val startEquity: Double? = null,
    val endEquity: Double? = null,
    val change: Double? = null,
    val changePercent: Double? = null,
    val minEquity: Double? = null,
    val maxEquity: Double? = null,
    val wallet: Double? = null,
    val unrealizedPnl: Double? = null,
    val positionNotional: Double? = null,
    val openPositions: Int? = null,
)

@Serializable
data class RecordingInfo(
    val firstTs: Double? = null,
    val totalSamples: Int = 0,
    val intervalSeconds: Int = 300,
)

@Serializable
data class BalanceHistory(
    val period: String = "week",
    val label: String = "",
    val bucketSeconds: Int = 86400,
    val points: List<BalancePoint> = emptyList(),
    val summary: BalanceSummary = BalanceSummary(),
    val recording: RecordingInfo = RecordingInfo(),
)

@Serializable
data class Event(
    val id: Long = 0,
    val ts: Double = 0.0,
    val symbol: String? = null,
    val level: String = "info",
    val message: String = "",
)

@Serializable
data class EventsResponse(val events: List<Event> = emptyList())

@Serializable
data class PairResponse(val token: String = "", val serverVersion: String = "")

@Serializable
data class Health(
    val ok: Boolean = false,
    val version: String = "",
    val credentialsConfigured: Boolean = false,
    val bots: Int = 0,
    val running: Int = 0,
)

/** 인앱 업데이트용 최신 릴리스 정보. */
@Serializable
data class AppVersionInfo(
    val versionName: String = "",
    val versionCode: Int = 0,
    val apkUrl: String? = null,
    val apkSize: Long? = null,
    val notes: String = "",
    val publishedAt: String? = null,
    val source: String = "",
)

@Serializable
data class ApiError(val detail: String = "알 수 없는 오류")

/** WebSocket 으로 내려오는 프레임. */
@Serializable
data class WsFrame(
    val type: String = "",
    val event: Event? = null,
    val bots: List<BotStatus> = emptyList(),
    @SerialName("version") val serverVersion: String = "",
)
