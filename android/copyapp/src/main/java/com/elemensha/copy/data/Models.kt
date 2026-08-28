package com.elemensha.copy.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ------------------------------------------------------------------ 가입/연결

@Serializable
data class JoinResponse(
    val token: String = "",
    val followerId: Int = 0,
    val label: String = "",
)

@Serializable
data class Health(
    val ok: Boolean = false,
    val version: String = "",
)

// ------------------------------------------------------------------ 설정

/** 서버 /api/copy/config 와 1:1. 주문 크기 방식이 이 화면의 핵심이다. */
@Serializable
data class CopyConfig(
    /** equity(자산 비례) | multiplier(고정 배수) | fixed(고정 금액) */
    val sizingMode: String = "equity",
    val equityScale: Double = 1.0,
    val multiplier: Double = 1.0,
    val fixedNotional: Double = 20.0,

    val maxRatio: Double? = null,
    val maxPositionNotional: Double? = null,
    /** skip(건너뛰기) | round_up(최소금액으로 올림) */
    val belowMinimum: String = "skip",
    val minNotionalRoundUp: Double = 0.0,

    val leverage: Int = 1,
    val marginMode: String = "ISOLATED",

    /** 빈 목록 = 리더가 돌리는 전 종목을 따라간다 */
    val symbols: List<String> = emptyList(),
    /** null = 리더의 익절률을 그대로 따라간다 */
    val takeProfitPercent: Double? = null,
    val pollSeconds: Int = 15,
    val dryRun: Boolean = false,
)

@Serializable
data class SizingModeOption(
    val value: String = "",
    val label: String = "",
    val description: String = "",
)

@Serializable
data class LabeledOption(val value: String, val label: String)

@Serializable
data class PeriodOption(
    val value: String = "",
    val label: String = "",
    val windowSeconds: Long = 0,
    val bucketSeconds: Int = 0,
)

@Serializable
data class CopyNotes(
    val entriesMirrored: Boolean = true,
    val exitIndependent: Boolean = true,
    val stopLoss: Boolean = false,
    val forceCloseOnLeaderExit: Boolean = false,
)

@Serializable
data class CopyMeta(
    val sizingModes: List<SizingModeOption> = emptyList(),
    val belowMinimumModes: List<LabeledOption> = emptyList(),
    val marginModes: List<LabeledOption> = emptyList(),
    val balancePeriods: List<PeriodOption> = emptyList(),
    val notes: CopyNotes = CopyNotes(),
)

// ------------------------------------------------------------------ 내 계정

@Serializable
data class CredentialInfo(
    val configured: Boolean = false,
    val apiKeyMasked: String = "",
    val testnet: Boolean = false,
    val usdtBalance: Double? = null,
    val canTrade: Boolean? = null,
)

@Serializable
data class MeResponse(
    val followerId: Int = 0,
    val label: String = "",
    val enabled: Boolean = false,
    val running: Boolean = false,
    val createdAt: Double? = null,
    val credentials: CredentialInfo = CredentialInfo(),
    val config: CopyConfig = CopyConfig(),
)

/** [사용자 요청] 카피 앱도 본인 계정 자산을 알 수 있어야 한다. */
@Serializable
data class AccountSummary(
    val wallet: Double? = null,
    /** 지갑 + 미실현손익. 손절이 없는 전략이라 이 값이 진짜 성과다. */
    val equity: Double? = null,
    val unrealizedPnl: Double? = null,
    val available: Double? = null,
    val positionNotional: Double? = null,
    val openPositions: Int? = null,
)

@Serializable
data class TakeProfitInfo(
    val orderId: String? = null,
    val price: Double? = null,
    val amount: Double? = null,
)

@Serializable
data class SymbolCopyStatus(
    val symbol: String = "",
    val leaderRunning: Boolean = false,
    val leaderHasPosition: Boolean = false,
    val mirroredBuys: Int = 0,
    val skippedBuys: Int = 0,
    val lastSkipReason: String? = null,
    val lastRatio: Double? = null,
    val positionSize: Double = 0.0,
    val takeProfitPercent: Double = 0.01,
    val takeProfit: TakeProfitInfo = TakeProfitInfo(),
    val realizedTrades: Int = 0,
    val settingsVerified: Boolean = false,
    val error: String? = null,
    /** 리더는 이미 청산됐는데 내 지정가는 아직 안 팔린 상태. 정상이지만 알려야 한다. */
    val waitingAlone: Boolean = false,
)

@Serializable
data class LeaderSymbol(
    val symbol: String = "",
    val running: Boolean = false,
    val hasPosition: Boolean = false,
    val takeProfitPercent: Double? = null,
    val entryTrigger: String = "",
    val timeframes: List<String> = emptyList(),
)

@Serializable
data class CopyStatus(
    val followerId: Int = 0,
    val label: String = "",
    val running: Boolean = false,
    val lastError: String? = null,
    val consecutiveErrors: Int = 0,
    val startedAt: Double? = null,
    val lastTickAt: Double? = null,
    val sizingLabel: String = "",
    val config: JsonElement? = null,
    val account: AccountSummary = AccountSummary(),
    val symbols: List<SymbolCopyStatus> = emptyList(),
    val leaderSymbols: List<LeaderSymbol> = emptyList(),
    val credentialsConfigured: Boolean = false,
)

// ------------------------------------------------------- 지정가 주문 / 포지션

/** [사용자 요청] 지정가 주문 상태. 거래소에 직접 물어본 값이다. */
@Serializable
data class OpenOrder(
    val symbol: String = "",
    val orderId: String = "",
    val side: String? = null,
    val type: String? = null,
    val status: String? = null,
    val price: Double = 0.0,
    val amount: Double = 0.0,
    val filled: Double = 0.0,
    val remaining: Double = 0.0,
    val notional: Double = 0.0,
    val reduceOnly: Boolean = false,
    val createdAt: Long? = null,
    val markPrice: Double? = null,
    /** 현재가에서 익절가까지 남은 거리(%). 양수면 아직 덜 올랐다는 뜻. */
    val distancePercent: Double? = null,
    val error: String? = null,
)

@Serializable
data class OrdersResponse(val count: Int = 0, val orders: List<OpenOrder> = emptyList())

@Serializable
data class Position(
    val symbol: String = "",
    val size: Double = 0.0,
    val entryPrice: Double = 0.0,
    val markPrice: Double = 0.0,
    val notional: Double = 0.0,
    val unrealizedPnl: Double = 0.0,
    val liquidationPrice: Double = 0.0,
    val leverage: Int? = null,
    val marginMode: String? = null,
    val takeProfitPrice: Double? = null,
    val takeProfitAmount: Double? = null,
    val error: String? = null,
)

@Serializable
data class PositionsResponse(val positions: List<Position> = emptyList())

// ------------------------------------------------------------- 잔고 그래프

@Serializable
data class BalancePoint(
    val ts: Double = 0.0,
    val wallet: Double = 0.0,
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

// ------------------------------------------------------------------ 로그

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

// ------------------------------------------------------------- 인앱 업데이트

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

/** WebSocket 프레임. 팔로워 스트림은 자기 상태와 자기 로그만 싣는다. */
@Serializable
data class WsFrame(
    val type: String = "",
    val event: Event? = null,
    @SerialName("status") val status: CopyStatus? = null,
)
