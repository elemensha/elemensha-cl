"""
RSI 과매도 이탈 DCA 전략 엔진.

설계 방침:
    롱 전용. 진입은 RSI 조건, 청산은 익절 하나뿐.
    손절 없음 / 숏 없음 / RSI 상단 청산 없음 - 의도된 설계다.
    진입 판정은 항상 '봉 완성(확정봉)' 기준.

앱 기본 셋팅(= 원본 코드와 동일):
    6개 타임프레임 각각에서 RSI(14)가 하한선 30을 아래->위로 돌파하면
    시장가 매수, 포지션 평단 +1%에 지정가 전량 청산.

원본 대비 고친 것:
  [요구사항 5] 평단/수량이 실제로 바뀐 경우에만 TP 취소·재등록.
               변화가 없으면 쓰기 호출 0회 -> 익절 주문이 비는 순간이 사라진다.
  [요구사항 6] try/except를 타임프레임 루프 '안'으로 이동.
               한 TF가 실패해도 나머지 TF의 RSI 추적이 끊기지 않는다.
  + 봉마감 기준 판정: 미완성 봉 대신 확정된 봉으로 판정해 리페인팅 제거.
  + 같은 봉에서의 중복 매수 방지(캔들 타임스탬프 기반 중복 실행 차단).
  + RSI 직접 구현: pandas/numpy/pandas_ta 전부 제거. 500MB RAM 무료 서버에서
    돌리기 위해 순수 파이썬으로 대체(결과는 소수 12자리까지 동일).
  + 매수 횟수 상한 제거(무제한). 총액 상한만 선택적으로 걸 수 있다.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field, asdict
from typing import Any, Callable

from .exchange import BinanceFutures, ExchangeError

# 바이낸스 선물이 기본 제공하는 캔들 간격 전체 (15종)
TIMEFRAME_SECONDS = {
    "1m": 60, "3m": 180, "5m": 300, "15m": 900, "30m": 1800,
    "1h": 3600, "2h": 7200, "4h": 14400, "6h": 21600, "8h": 28800,
    "12h": 43200, "1d": 86400, "3d": 259200, "1w": 604800,
    "1M": 2592000,
}
ALL_TIMEFRAMES = list(TIMEFRAME_SECONDS)

# 앱 기본 선택값 (원본 코드와 동일한 6종)
DEFAULT_TIMEFRAMES = ["1m", "5m", "15m", "1h", "4h", "1d"]


class EntryTrigger:
    """진입 조건. 전부 '확정된 봉' 기준으로 판정한다."""

    CROSS_UP_LOWER = "cross_up_lower"       # 하한선 아래->위 돌파 (원본 기본값)
    CROSS_DOWN_LOWER = "cross_down_lower"   # 하한선 위->아래 돌파
    BELOW_LOWER = "below_lower"             # 하한선 아래에 머무는 동안 매 봉
    CROSS_UP_UPPER = "cross_up_upper"       # 상한선 아래->위 돌파
    CROSS_DOWN_UPPER = "cross_down_upper"   # 상한선 위->아래 돌파
    ABOVE_UPPER = "above_upper"             # 상한선 위에 머무는 동안 매 봉

    ALL = (CROSS_UP_LOWER, CROSS_DOWN_LOWER, BELOW_LOWER,
           CROSS_UP_UPPER, CROSS_DOWN_UPPER, ABOVE_UPPER)

    LABELS = {
        CROSS_UP_LOWER: "하한선 상향 돌파",
        CROSS_DOWN_LOWER: "하한선 하향 돌파",
        BELOW_LOWER: "하한선 아래 위치",
        CROSS_UP_UPPER: "상한선 상향 돌파",
        CROSS_DOWN_UPPER: "상한선 하향 돌파",
        ABOVE_UPPER: "상한선 위 위치",
    }


@dataclass
class StrategyConfig:
    """앱에서 전부 조절 가능한 파라미터. 기본값 = 원본 코드 그대로."""

    symbol: str = "BTC/USDT:USDT"

    # --- 거래소 설정 [요구사항 1]
    leverage: int = 1
    margin_mode: str = "ISOLATED"

    # --- 신호 (전부 앱에서 수정 가능)
    timeframes: list[str] = field(default_factory=lambda: list(DEFAULT_TIMEFRAMES))
    rsi_period: int = 14
    rsi_lower: float = 30.0                        # RSI 하한선
    rsi_upper: float = 70.0                        # RSI 상한선
    entry_trigger: str = EntryTrigger.CROSS_UP_LOWER

    # --- 사이징 [요구사항 2]
    wallet_percentage: float = 0.001
    min_notional_round_up: float = 10.0
    # None = 무제한. 원본의 399회 상한은 제거됨(앱에서 원하면 다시 걸 수 있다).
    max_additional_buys: int | None = None

    # --- 청산: 익절 하나뿐. 손절 없음, 숏 없음, RSI 상단 청산 없음.
    take_profit_percent: float = 0.01

    # --- 선택형 총액 상한 (기본 꺼짐)
    max_position_notional: float | None = None

    # --- 실행
    poll_seconds: int = 20
    dry_run: bool = False

    def validate(self) -> None:
        if self.rsi_period < 2:
            raise ValueError("RSI 기간은 2 이상이어야 합니다.")
        if not 0 <= self.rsi_lower < 100:
            raise ValueError("RSI 하한선은 0~100 사이여야 합니다.")
        if not 0 < self.rsi_upper <= 100:
            raise ValueError("RSI 상한선은 0~100 사이여야 합니다.")
        if self.rsi_lower >= self.rsi_upper:
            raise ValueError("RSI 하한선은 상한선보다 작아야 합니다.")
        if self.entry_trigger not in EntryTrigger.ALL:
            raise ValueError(f"알 수 없는 진입 조건: {self.entry_trigger}")
        if not 0 < self.wallet_percentage <= 1:
            raise ValueError("지갑 비율은 0 초과 1 이하여야 합니다.")
        if self.take_profit_percent <= 0:
            raise ValueError("익절률은 0보다 커야 합니다.")
        if self.max_additional_buys is not None and self.max_additional_buys < 1:
            raise ValueError("최대 매수 횟수는 1 이상이거나 무제한(비움)이어야 합니다.")
        bad = [tf for tf in self.timeframes if tf not in TIMEFRAME_SECONDS]
        if bad:
            raise ValueError(f"지원하지 않는 타임프레임: {', '.join(bad)}")
        if not self.timeframes:
            raise ValueError("타임프레임을 최소 1개 선택해야 합니다.")
        if self.margin_mode.upper() not in ("ISOLATED", "CROSS", "CROSSED"):
            raise ValueError("마진 모드는 ISOLATED 또는 CROSS만 가능합니다.")

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class TimeframeState:
    last_rsi: float | None = None
    prev_rsi: float | None = None
    last_candle_ts: int | None = None   # 마지막으로 '판정한' 확정봉
    buy_count: int = 0
    last_error: str | None = None


class StrategyEngine:
    """심볼 1개를 담당. 상태를 들고 tick()을 반복 호출당한다."""

    def __init__(
        self,
        exchange: BinanceFutures,
        config: StrategyConfig,
        emit: Callable[[str, str, dict[str, Any]], None] | None = None,
        state: dict[str, Any] | None = None,
    ):
        config.validate()
        self.ex = exchange
        self.cfg = config
        self.symbol = exchange.normalize(config.symbol)
        self.cfg.symbol = self.symbol
        self._emit = emit or (lambda level, msg, data: None)

        self.tf_state: dict[str, TimeframeState] = {
            tf: TimeframeState() for tf in config.timeframes
        }
        # 현재 걸려 있다고 믿는 TP 주문 [요구사항 5의 비교 기준]
        self.tp_order_id: str | None = None
        self.tp_price: float | None = None
        self.tp_amount: float | None = None

        self.last_position_size: float = 0.0
        self.realized_trades: int = 0
        self.started_at: float = time.time()
        self.last_tick_at: float | None = None

        if state:
            self.restore(state)

    # ------------------------------------------------------------- 상태 저장

    def snapshot(self) -> dict[str, Any]:
        return {
            "tf_state": {tf: asdict(s) for tf, s in self.tf_state.items()},
            "tp_order_id": self.tp_order_id,
            "tp_price": self.tp_price,
            "tp_amount": self.tp_amount,
            "last_position_size": self.last_position_size,
            "realized_trades": self.realized_trades,
            "started_at": self.started_at,
        }

    def restore(self, state: dict[str, Any]) -> None:
        for tf, raw in (state.get("tf_state") or {}).items():
            if tf in self.tf_state:
                self.tf_state[tf] = TimeframeState(**raw)
        self.tp_order_id = state.get("tp_order_id")
        self.tp_price = state.get("tp_price")
        self.tp_amount = state.get("tp_amount")
        self.last_position_size = state.get("last_position_size", 0.0)
        self.realized_trades = state.get("realized_trades", 0)
        self.started_at = state.get("started_at", time.time())

    def log(self, level: str, message: str, **data: Any) -> None:
        self._emit(level, message, data)

    # --------------------------------------------------- 거래소 설정 [요구사항 1]

    def sync_exchange_settings(self) -> dict[str, Any]:
        """엔진 시작 시 반드시 호출. 원본은 이걸 호출하지 않아 1x가 거짓이었다."""
        lev = self.ex.apply_leverage(self.symbol, self.cfg.leverage)
        mar = self.ex.apply_margin_mode(self.symbol, self.cfg.margin_mode)
        for result in (lev, mar):
            self.log("info" if result["verified"] else "error", result["message"],
                     **result)
        return {"leverage": lev, "marginMode": mar}

    # --------------------------------------------------------------- RSI 계산

    def _rsi_series(self, closes: list[float]) -> list[float | None]:
        """
        Wilder RSI — 순수 파이썬 구현.

        시드는 TradingView 의 ta.rsi 와 동일하게 '첫 period 개 변화량의 단순평균'
        을 쓴다. 바이낸스 차트에 보이는 RSI 가 바로 이 방식으로 계산되므로,
        캔들 개수와 무관하게 화면 값과 일치한다.
        (첫 값부터 재귀를 시작하는 pandas ewm 방식은 캔들이 300개 미만이면
         0.04~0.6 만큼 어긋난다 — 원본 코드가 64개만 받아서 겪던 문제다.)

        pandas/numpy 를 쓰지 않는 이유: 이 봇은 RAM 500MB 급 무료 인스턴스에서
        돌아간다. pandas+numpy 는 import 만으로 ~150MB 를 먹는데 실제로 쓰는
        기능은 이 재귀식 하나뿐이라 값어치가 없다.
        """
        period = self.cfg.rsi_period
        n = len(closes)
        out: list[float | None] = [None] * n
        if n < period + 1:
            return out

        # 시드: 첫 period 개 변화량의 단순평균
        gain_sum = loss_sum = 0.0
        for i in range(1, period + 1):
            delta = closes[i] - closes[i - 1]
            if delta > 0:
                gain_sum += delta
            else:
                loss_sum -= delta
        avg_gain = gain_sum / period
        avg_loss = loss_sum / period
        out[period] = (100.0 if avg_loss == 0.0
                       else 100.0 - 100.0 / (1.0 + avg_gain / avg_loss))

        # 이후는 Wilder 평활(RMA)
        alpha = 1.0 / period
        for i in range(period + 1, n):
            delta = closes[i] - closes[i - 1]
            gain = delta if delta > 0 else 0.0
            loss = -delta if delta < 0 else 0.0
            avg_gain += alpha * (gain - avg_gain)
            avg_loss += alpha * (loss - avg_loss)
            out[i] = (100.0 if avg_loss == 0.0
                      else 100.0 - 100.0 / (1.0 + avg_gain / avg_loss))
        return out

    def read_rsi(self, timeframe: str) -> tuple[float, float, int]:
        """
        (직전 확정봉 RSI, 최신 확정봉 RSI, 최신 확정봉 타임스탬프) 반환.

        진입 판정은 항상 '봉 완성 기준'이다. 진행 중인 마지막 봉은 버린다.
        미완성 봉으로 판정하면 값이 계속 변해(리페인팅) 같은 자리에서
        신호가 생겼다 사라졌다 하고, 중복 매수가 난다.
        """
        # 수렴 여유를 넉넉히: Wilder RSI는 초기값 영향이 오래 남는다
        limit = min(1000, max(300, self.cfg.rsi_period * 12))
        raw = self.ex.ohlcv(self.symbol, timeframe, limit)
        if len(raw) < self.cfg.rsi_period + 3:
            raise ExchangeError(f"{timeframe} 캔들이 부족합니다 ({len(raw)}개).")

        rows = raw[:-1]                        # 진행 중인 봉 제거
        closes = [float(row[4]) for row in rows]

        values = [v for v in self._rsi_series(closes) if v is not None]
        if len(values) < 2:
            raise ExchangeError(f"{timeframe} RSI 계산 실패.")
        return values[-2], values[-1], int(rows[-1][0])

    def signal_fires(self, prev_rsi: float, curr_rsi: float) -> bool:
        """선택된 진입 조건에 최신 확정봉이 부합하는지."""
        lower, upper = self.cfg.rsi_lower, self.cfg.rsi_upper
        trigger = self.cfg.entry_trigger

        if trigger == EntryTrigger.CROSS_UP_LOWER:
            return prev_rsi < lower <= curr_rsi
        if trigger == EntryTrigger.CROSS_DOWN_LOWER:
            return prev_rsi >= lower > curr_rsi
        if trigger == EntryTrigger.BELOW_LOWER:
            return curr_rsi < lower
        if trigger == EntryTrigger.CROSS_UP_UPPER:
            return prev_rsi < upper <= curr_rsi
        if trigger == EntryTrigger.CROSS_DOWN_UPPER:
            return prev_rsi >= upper > curr_rsi
        if trigger == EntryTrigger.ABOVE_UPPER:
            return curr_rsi > upper
        return False

    # ------------------------------------------------------------------ 매수

    def _buy_notional(self, balance: float) -> float:
        return balance * self.cfg.wallet_percentage

    def _position_notional(self, size: float, price: float) -> float:
        return abs(size) * price

    def try_buy(self, timeframe: str, price: float, balance: float,
                position_size: float) -> dict[str, Any] | None:
        state = self.tf_state[timeframe]

        limit = self.cfg.max_additional_buys
        if limit is not None and state.buy_count >= limit:
            return None

        cap = self.cfg.max_position_notional
        if cap and self._position_notional(position_size, price) >= cap:
            self.log("warn", f"포지션 상한 ${cap:,.0f} 도달 - {timeframe} 매수 건너뜀")
            return None

        amount, notional = self.ex.order_amount(
            self.symbol, self._buy_notional(balance), price,
            self.cfg.min_notional_round_up,
        )

        if self.cfg.dry_run:
            state.buy_count += 1
            self.log("trade", f"[모의] {timeframe} 매수 {amount} @ ~{price}",
                     timeframe=timeframe, amount=amount, notional=notional,
                     price=price, dry_run=True)
            return {"dry_run": True, "amount": amount, "notional": notional}

        order = self.ex.market_buy(self.symbol, amount)
        state.buy_count += 1
        cap = "무제한" if limit is None else str(limit)
        self.log("trade",
                 f"{timeframe} 매수 체결 {amount} (${notional:,.2f}) "
                 f"- 누적 {state.buy_count}/{cap}",
                 timeframe=timeframe, amount=amount, notional=notional,
                 price=price, order_id=order.get("id"),
                 buy_count=state.buy_count)
        return order

    # -------------------------------------------------- 익절 관리 [요구사항 5]

    def _find_live_tp(self) -> dict[str, Any] | None:
        """거래소에 실제로 살아있는 reduceOnly 매도 주문."""
        for order in self.ex.open_orders(self.symbol):
            if order.get("side") != "sell":
                continue
            info = order.get("info", {})
            reduce_only = (order.get("reduceOnly")
                           or str(info.get("reduceOnly", "")).lower() == "true")
            if reduce_only or order.get("type") == "limit":
                return order
        return None

    def reconcile_take_profit(self, position_size: float,
                              entry_price: float) -> str:
        """
        요구사항 5의 핵심.
        목표 익절가/수량이 거래소의 현재 주문과 같으면 아무것도 하지 않는다.
        (원본은 무조건 취소 후 재등록 -> 하루 1,440회 낭비 + 무방비 구간 발생)
        """
        live = self._find_live_tp()

        if position_size <= 0:
            if live:
                self.ex.cancel(live["id"], self.symbol)
                self.log("info", "포지션 없음 - 잔여 청산주문 취소")
            self.tp_order_id = self.tp_price = self.tp_amount = None
            return "cleared"

        raw_price = entry_price * (1 + self.cfg.take_profit_percent)
        want_price = float(self.ex.client.price_to_precision(self.symbol, raw_price))
        want_amount = float(
            self.ex.client.amount_to_precision(self.symbol, position_size)
        )

        if live:
            have_price = float(live.get("price") or 0.0)
            have_amount = float(live.get("amount") or 0.0)
            tick = self.ex.spec(self.symbol).price_tick or 1e-9
            step = self.ex.spec(self.symbol).qty_step or 1e-12
            same = (abs(have_price - want_price) < tick / 2
                    and abs(have_amount - want_amount) < step / 2)
            if same:
                self.tp_order_id = live["id"]
                self.tp_price, self.tp_amount = have_price, have_amount
                return "unchanged"          # <- API 쓰기 호출 0회
            self.ex.cancel(live["id"], self.symbol)
            self.log("info",
                     f"익절 주문 갱신: {have_price} x{have_amount} "
                     f"-> {want_price} x{want_amount}")

        if self.cfg.dry_run:
            self.tp_order_id, self.tp_price, self.tp_amount = (
                "dry-run", want_price, want_amount
            )
            return "placed"

        order = self.ex.limit_sell_reduce_only(self.symbol, want_amount, want_price)
        self.tp_order_id = order.get("id")
        self.tp_price, self.tp_amount = want_price, want_amount
        self.log("info",
                 f"익절 지정가 등록 {want_price} x{want_amount} "
                 f"(평단 {entry_price} +{self.cfg.take_profit_percent:.2%})",
                 price=want_price, amount=want_amount, order_id=self.tp_order_id)
        return "placed"

    # ------------------------------------------------------------------ 틱

    def tick(self) -> dict[str, Any]:
        """1회 실행. 예외는 TF 단위로 격리된다 [요구사항 6]."""
        self.last_tick_at = time.time()
        result: dict[str, Any] = {"signals": [], "errors": {}, "tp": None}

        balance = self.ex.usdt_balance()
        price = self.ex.last_price(self.symbol)
        pos = self.ex.position(self.symbol)
        size, entry = pos["size"], pos["entryPrice"]

        # 익절 체결 감지
        if self.last_position_size > 0 and size == 0:
            self.realized_trades += 1
            self.log("trade",
                     f"익절 청산 완료 (누적 {self.realized_trades}회)",
                     realized_trades=self.realized_trades)
            self.tp_order_id = self.tp_price = self.tp_amount = None

        # ---- 타임프레임별 신호 판정: 각 TF를 독립적으로 보호 [요구사항 6]
        for tf in self.cfg.timeframes:
            state = self.tf_state[tf]
            try:
                prev_rsi, curr_rsi, candle_ts = self.read_rsi(tf)
                state.last_error = None

                # 이미 판정한 봉이면 신호 재발생 없음 (같은 봉 중복 매수 차단)
                is_new_candle = state.last_candle_ts != candle_ts
                state.prev_rsi, state.last_rsi = prev_rsi, curr_rsi

                if is_new_candle and self.signal_fires(prev_rsi, curr_rsi):
                    order = self.try_buy(tf, price, balance, size)
                    if order:
                        result["signals"].append({
                            "timeframe": tf, "prevRsi": prev_rsi,
                            "rsi": curr_rsi, "candleTs": candle_ts,
                        })
                        pos = self.ex.position(self.symbol)
                        size, entry = pos["size"], pos["entryPrice"]

                state.last_candle_ts = candle_ts

            except Exception as exc:  # noqa: BLE001 - TF 단위 격리가 목적
                state.last_error = str(exc)
                result["errors"][tf] = str(exc)
                self.log("error", f"{tf} 처리 실패: {exc}", timeframe=tf)
                # 다른 TF는 그대로 진행된다

        # ---- 익절 주문 정합 [요구사항 5]
        try:
            result["tp"] = self.reconcile_take_profit(size, entry)
        except Exception as exc:  # noqa: BLE001
            result["errors"]["take_profit"] = str(exc)
            self.log("error", f"익절 주문 처리 실패: {exc}")

        self.last_position_size = size
        result.update({
            "balance": balance, "price": price, "positionSize": size,
            "entryPrice": entry, "unrealizedPnl": pos["unrealizedPnl"],
            "liquidationPrice": pos["liquidationPrice"],
            "rsi": {tf: s.last_rsi for tf, s in self.tf_state.items()},
            "buyCounts": {tf: s.buy_count for tf, s in self.tf_state.items()},
        })
        return result

    # ------------------------------------------------------------------ 조회

    def status(self) -> dict[str, Any]:
        return {
            "symbol": self.symbol,
            "config": self.cfg.to_dict(),
            "entryTriggerLabel": EntryTrigger.LABELS.get(self.cfg.entry_trigger, ""),
            "timeframes": {
                tf: {
                    "rsi": s.last_rsi,
                    "prevRsi": s.prev_rsi,
                    "buyCount": s.buy_count,
                    "maxBuys": self.cfg.max_additional_buys,   # None = 무제한
                    "lastCandleTs": s.last_candle_ts,
                    "error": s.last_error,
                }
                for tf, s in self.tf_state.items()
            },
            "takeProfit": {
                "orderId": self.tp_order_id,
                "price": self.tp_price,
                "amount": self.tp_amount,
            },
            "realizedTrades": self.realized_trades,
            "startedAt": self.started_at,
            "lastTickAt": self.last_tick_at,
        }
