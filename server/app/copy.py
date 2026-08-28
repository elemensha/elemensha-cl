"""
카피 트레이딩 엔진.

리더 봇이 매수하면 팔로워 계정들이 각자의 배율로 같은 종목을 따라 산다.
팔로워는 리더 서버에 세들어 사는 별개 테넌트다 — API 키도, 잔고 기록도,
로그도 리더의 것과 절대 섞이지 않는다.

설계 방침:

  진입은 따라가고, 청산은 각자 한다.
      리더가 매수하면 팔로워도 매수한다. 하지만 익절은 팔로워 '자기' 평단
      기준으로 자기 지정가 주문을 건다. 체결가가 미세하게 달라 평단이
      어긋나므로, 리더의 익절가를 그대로 베끼면 팔로워는 목표보다 낮은
      값에 팔게 된다. 리더가 익절되어 포지션이 비어도 팔로워의 지정가는
      그대로 살려둔다 — 강제로 시장가 청산하면 손실이 확정된다.

  못 따라간 신호는 조용히 넘기지 않는다.
      최소 주문금액에 미달해 건너뛴 신호는 상태와 로그에 남는다.
      팔로워 자산이 작으면 이게 일상적으로 일어나므로 반드시 보여야 한다.

  리더의 금액은 팔로워에게 보여주지 않는다.
      배율 계산에는 쓰지만, 앱으로 내려보내는 건 배율과 심볼까지다.
"""
from __future__ import annotations

import asyncio
import time
from dataclasses import asdict, dataclass, field
from typing import Any, Callable

from .exchange import BinanceFutures, ExchangeError
from .store import LEADER_OWNER, Store, describe_owner
from .strategy import sync_take_profit


class SizingMode:
    """팔로워 주문 크기를 정하는 방식. 앱에서 고른다."""

    EQUITY = "equity"          # 자산 비례 — 내 순자산 / 리더 잔고
    MULTIPLIER = "multiplier"  # 고정 배수 — 리더 수량 x N
    FIXED = "fixed"            # 고정 금액 — 리더가 사면 나는 항상 $N

    ALL = (EQUITY, MULTIPLIER, FIXED)

    LABELS = {
        EQUITY: "자산 비례",
        MULTIPLIER: "고정 배수",
        FIXED: "고정 금액",
    }

    DESCRIPTIONS = {
        EQUITY: "내 순자산 ÷ 리더 잔고 만큼의 비율로 따라 산다. "
                "자산이 리더의 1/10이면 주문도 1/10.",
        MULTIPLIER: "리더가 산 수량에 배수를 곱한다. 0.5 = 절반, 2 = 두 배.",
        FIXED: "리더가 몇 번을 사든 나는 매번 정해진 금액만큼 산다.",
    }


class BelowMinimum:
    """주문액이 거래소 최소 금액에 못 미칠 때의 처리."""

    SKIP = "skip"          # 건너뛰고 로그에 남긴다 (기본)
    ROUND_UP = "round_up"  # 최소 금액까지 올려서 산다

    ALL = (SKIP, ROUND_UP)

    LABELS = {
        SKIP: "건너뛰기 (안전)",
        ROUND_UP: "최소 금액으로 올려 매수",
    }


@dataclass
class FollowerConfig:
    """팔로워 한 명의 카피 설정. 전부 앱에서 조절한다."""

    # --- 주문 크기 [사용자 요청: 앱에서 방식 선택]
    sizing_mode: str = SizingMode.EQUITY
    equity_scale: float = 1.0        # 자산 비례 모드의 추가 보정 (1.0 = 그대로)
    multiplier: float = 1.0          # 고정 배수 모드
    fixed_notional: float = 20.0     # 고정 금액 모드 (USDT)

    # --- 안전장치
    max_ratio: float | None = None            # 리더 대비 배율 상한
    max_position_notional: float | None = None  # 종목별 포지션 총액 상한
    below_minimum: str = BelowMinimum.SKIP
    min_notional_round_up: float = 0.0        # 0 = 올림 안 함

    # --- 거래소 설정
    leverage: int = 1
    margin_mode: str = "ISOLATED"

    # --- 대상
    symbols: list[str] = field(default_factory=list)   # 빈 값 = 리더 전 종목

    # --- 청산: None 이면 리더의 익절률을 그대로 따라간다
    take_profit_percent: float | None = None

    poll_seconds: int = 15
    dry_run: bool = False

    def validate(self) -> None:
        if self.sizing_mode not in SizingMode.ALL:
            raise ValueError(f"알 수 없는 주문 크기 방식: {self.sizing_mode}")
        if self.below_minimum not in BelowMinimum.ALL:
            raise ValueError(f"알 수 없는 최소금액 처리 방식: {self.below_minimum}")
        if self.equity_scale <= 0:
            raise ValueError("자산 비례 보정값은 0보다 커야 합니다.")
        if self.multiplier <= 0:
            raise ValueError("배수는 0보다 커야 합니다.")
        if self.fixed_notional <= 0:
            raise ValueError("고정 금액은 0보다 커야 합니다.")
        if self.max_ratio is not None and self.max_ratio <= 0:
            raise ValueError("배율 상한은 0보다 커야 합니다.")
        if not 1 <= self.leverage <= 125:
            raise ValueError("레버리지는 1~125 범위여야 합니다.")
        if self.margin_mode.upper() not in ("ISOLATED", "CROSS", "CROSSED"):
            raise ValueError("마진 모드는 ISOLATED 또는 CROSS만 가능합니다.")
        if not 5 <= self.poll_seconds <= 600:
            raise ValueError("점검 주기는 5~600초여야 합니다.")
        if (self.take_profit_percent is not None
                and not 0 < self.take_profit_percent <= 1):
            raise ValueError("익절률은 0 초과 1 이하여야 합니다.")

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class MirrorState:
    """팔로워의 심볼 1개에 대한 카피 상태."""

    mirrored_buys: int = 0
    skipped_buys: int = 0
    last_skip_reason: str | None = None
    last_signal_ts: float | None = None
    last_ratio: float | None = None
    # 리더의 익절률을 기억해 둔다. 서버 재시작 후 신호가 오기 전에도
    # 익절 주문을 이어서 관리해야 한다.
    take_profit_percent: float = 0.01
    tp_order_id: str | None = None
    tp_price: float | None = None
    tp_amount: float | None = None
    last_position_size: float = 0.0
    realized_trades: int = 0
    settings_verified: bool = False
    last_error: str | None = None


# --------------------------------------------------------------- 크기 계산

def compute_ratio(cfg: FollowerConfig, signal: dict[str, Any],
                  my_equity: float) -> tuple[float, str]:
    """
    리더 수량 대비 내 배율과, 그 근거 한 줄을 돌려준다.

    세 방식 모두 결국 '배율' 하나로 환산된다. 그래야 포지션 상한이나
    배율 상한 같은 안전장치를 한 군데에서 일관되게 걸 수 있다.
    """
    if cfg.sizing_mode == SizingMode.MULTIPLIER:
        return cfg.multiplier, f"고정 배수 {cfg.multiplier:g}x"

    if cfg.sizing_mode == SizingMode.FIXED:
        leader_notional = float(signal.get("leaderNotional") or 0.0)
        if leader_notional <= 0:
            return 0.0, "리더 주문금액을 알 수 없음"
        ratio = cfg.fixed_notional / leader_notional
        return ratio, f"고정 금액 ${cfg.fixed_notional:,.2f}"

    # 자산 비례
    leader_balance = float(signal.get("leaderBalance") or 0.0)
    if leader_balance <= 0:
        return 0.0, "리더 잔고를 알 수 없음"
    ratio = (my_equity / leader_balance) * cfg.equity_scale
    scale = "" if cfg.equity_scale == 1.0 else f" x{cfg.equity_scale:g}"
    return ratio, f"자산 비례{scale}"


@dataclass
class SizingResult:
    amount: float = 0.0
    notional: float = 0.0
    ratio: float = 0.0
    reason: str = ""
    skip_reason: str | None = None


def plan_order(ex: BinanceFutures, cfg: FollowerConfig, signal: dict[str, Any],
               my_equity: float, position_size: float) -> SizingResult:
    """신호 1건 -> 내가 실제로 낼 주문. 못 내면 skip_reason 에 이유를 담는다."""
    symbol = signal["symbol"]
    price = float(signal.get("price") or 0.0)
    leader_amount = float(signal.get("leaderAmount") or 0.0)

    ratio, reason = compute_ratio(cfg, signal, my_equity)
    if cfg.max_ratio is not None and ratio > cfg.max_ratio:
        ratio = cfg.max_ratio
        reason += f" (배율 상한 {cfg.max_ratio:g}x 적용)"

    if ratio <= 0 or price <= 0 or leader_amount <= 0:
        return SizingResult(ratio=ratio, reason=reason,
                            skip_reason=f"주문 크기를 계산할 수 없음 — {reason}")

    target_notional = leader_amount * price * ratio

    cap = cfg.max_position_notional
    if cap:
        current = abs(position_size) * price
        if current >= cap:
            return SizingResult(
                ratio=ratio, reason=reason,
                skip_reason=f"포지션 상한 ${cap:,.0f} 도달 (현재 ${current:,.0f})")
        target_notional = min(target_notional, cap - current)

    spec = ex.spec(symbol)
    floor = spec.hard_floor_notional(price)
    if target_notional < floor:
        if cfg.below_minimum == BelowMinimum.SKIP:
            return SizingResult(
                ratio=ratio, reason=reason,
                skip_reason=(f"주문액 ${target_notional:,.2f} < "
                             f"{spec.base} 최소 ${floor:,.2f} — 건너뜀"))
        # ROUND_UP: order_amount 가 알아서 바닥까지 올려준다

    amount, notional = ex.order_amount(
        symbol, target_notional, price, cfg.min_notional_round_up
    )
    return SizingResult(amount=amount, notional=notional, ratio=ratio,
                        reason=reason)


# ------------------------------------------------------------------ 실행기

class CopyRunner:
    """팔로워 계정 1개의 실행 수명주기."""

    SNAPSHOT_INTERVAL = 300   # 잔고 기록 주기 (초)

    def __init__(self, manager: "CopyManager", follower_id: int, label: str,
                 config: FollowerConfig, state: dict[str, Any] | None = None):
        self.mgr = manager
        self.id = follower_id
        self.label = label
        self.cfg = config
        self.ex: BinanceFutures | None = None
        self.task: asyncio.Task | None = None
        self.running = False
        self.last_error: str | None = None
        self.consecutive_errors = 0
        self.started_at: float | None = None
        self.last_tick_at: float | None = None
        self.last_snapshot: dict[str, Any] | None = None
        self._last_snapshot_at = 0.0
        self.mirrors: dict[str, MirrorState] = {}
        # 신호 처리와 주기 점검이 같은 심볼을 동시에 건드리면 익절 주문이
        # 중복 등록된다. 심볼 단위로 직렬화한다.
        self._locks: dict[str, asyncio.Lock] = {}
        if state:
            self.restore(state)

    # ------------------------------------------------------------ 상태 저장

    def snapshot_state(self) -> dict[str, Any]:
        return {
            "mirrors": {s: asdict(m) for s, m in self.mirrors.items()},
            "startedAt": self.started_at,
        }

    def restore(self, state: dict[str, Any]) -> None:
        for symbol, raw in (state.get("mirrors") or {}).items():
            known = {f: raw[f] for f in MirrorState.__dataclass_fields__
                     if f in raw}
            self.mirrors[symbol] = MirrorState(**known)
        self.started_at = state.get("startedAt")

    def persist(self) -> None:
        self.mgr.store.save_follower(
            self.id, state=self.snapshot_state(), enabled=self.running
        )

    # ---------------------------------------------------------------- 로그

    def emit(self, level: str, message: str, symbol: str | None = None,
             data: dict[str, Any] | None = None) -> None:
        self.mgr.publish(self.id, level, message, symbol, data)

    def mirror(self, symbol: str) -> MirrorState:
        if symbol not in self.mirrors:
            self.mirrors[symbol] = MirrorState()
        return self.mirrors[symbol]

    def lock(self, symbol: str) -> asyncio.Lock:
        if symbol not in self._locks:
            self._locks[symbol] = asyncio.Lock()
        return self._locks[symbol]

    def tracks(self, symbol: str) -> bool:
        """이 팔로워가 따라갈 종목인가. 빈 목록이면 리더가 돌리는 전부."""
        return not self.cfg.symbols or symbol in self.cfg.symbols

    # -------------------------------------------------------------- 시작/정지

    async def start(self) -> dict[str, Any]:
        if self.running:
            return {"ok": True, "message": "이미 실행 중입니다."}
        self.ex = self.mgr.new_exchange(self.id)
        await asyncio.to_thread(self.ex.load)
        self.running = True
        self.started_at = self.started_at or time.time()
        self.consecutive_errors = 0
        self.last_error = None
        self.task = asyncio.create_task(self._loop(), name=f"copy:{self.id}")
        self.persist()
        self.emit("info", f"카피 시작 — {SizingMode.LABELS[self.cfg.sizing_mode]}")
        return {"ok": True, "followerId": self.id}

    async def stop(self, cancel_orders: bool = False) -> dict[str, Any]:
        self.running = False
        if self.task:
            self.task.cancel()
            try:
                await self.task
            except (asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
            self.task = None
        cancelled = 0
        if cancel_orders and self.ex and not self.cfg.dry_run:
            for symbol in list(self.mirrors):
                try:
                    cancelled += await asyncio.to_thread(self.ex.cancel_all, symbol)
                except Exception as exc:  # noqa: BLE001
                    self.emit("error", f"{symbol} 주문 취소 실패: {exc}", symbol)
        self.persist()
        self.emit("info", f"카피 정지 (취소 주문 {cancelled}건)")
        return {"ok": True, "cancelledOrders": cancelled}

    async def panic(self) -> dict[str, Any]:
        """내 포지션만 전량 시장가 청산. 리더와 다른 팔로워는 건드리지 않는다."""
        await self.stop(cancel_orders=True)
        closed: dict[str, bool] = {}
        if self.ex and not self.cfg.dry_run:
            for symbol in list(self.mirrors):
                try:
                    result = await asyncio.to_thread(
                        self.ex.close_position_market, symbol
                    )
                    closed[symbol] = bool(result)
                except Exception as exc:  # noqa: BLE001
                    self.emit("error", f"{symbol} 청산 실패: {exc}", symbol)
                    closed[symbol] = False
        for state in self.mirrors.values():
            state.tp_order_id = state.tp_price = state.tp_amount = None
            state.last_position_size = 0.0
        self.persist()
        self.emit("error", "긴급 청산 실행", None, {"closed": closed})
        return {"ok": True, "closed": closed}

    # ------------------------------------------------------------ 신호 처리

    async def on_signal(self, signal: dict[str, Any]) -> None:
        """리더가 매수했다. 내 배율로 따라 산다."""
        symbol = signal.get("symbol")
        if not self.running or not self.ex or not symbol or not self.tracks(symbol):
            return

        async with self.lock(symbol):
            state = self.mirror(symbol)
            state.last_signal_ts = float(signal.get("ts") or time.time())
            if signal.get("takeProfitPercent"):
                state.take_profit_percent = float(signal["takeProfitPercent"])
            try:
                await self._mirror_buy(symbol, signal, state)
            except Exception as exc:  # noqa: BLE001 - 한 신호 실패로 죽으면 안 된다
                state.last_error = str(exc)
                self.emit("error", f"{symbol} 카피 매수 실패: {exc}", symbol)
            self.persist()

    async def _mirror_buy(self, symbol: str, signal: dict[str, Any],
                          state: MirrorState) -> None:
        assert self.ex is not None

        if not state.settings_verified:
            await self._apply_exchange_settings(symbol, state)

        summary = await asyncio.to_thread(self.ex.account_summary)
        position = await asyncio.to_thread(self.ex.position, symbol)

        plan = await asyncio.to_thread(
            plan_order, self.ex, self.cfg, signal, summary["equity"],
            position["size"],
        )
        state.last_ratio = plan.ratio

        if plan.skip_reason:
            state.skipped_buys += 1
            state.last_skip_reason = plan.skip_reason
            self.emit("warn", f"{symbol} 신호 건너뜀 — {plan.skip_reason}", symbol,
                      {"ratio": plan.ratio, "timeframe": signal.get("timeframe")})
            return

        if self.cfg.dry_run:
            state.mirrored_buys += 1
            state.last_skip_reason = None
            self.emit("trade",
                      f"[모의] {symbol} 카피 매수 {plan.amount} "
                      f"(${plan.notional:,.2f}) — {plan.reason}", symbol,
                      {"amount": plan.amount, "notional": plan.notional,
                       "ratio": plan.ratio, "dryRun": True})
            return

        order = await asyncio.to_thread(self.ex.market_buy, symbol, plan.amount)
        state.mirrored_buys += 1
        state.last_skip_reason = None
        state.last_error = None
        self.emit("trade",
                  f"{symbol} 카피 매수 {plan.amount} (${plan.notional:,.2f}) "
                  f"— {plan.reason}, 배율 {plan.ratio:.4g}x", symbol,
                  {"amount": plan.amount, "notional": plan.notional,
                   "ratio": plan.ratio, "orderId": order.get("id"),
                   "timeframe": signal.get("timeframe"),
                   "mirroredBuys": state.mirrored_buys})

        # 평단이 즉시 바뀌므로 익절 주문도 바로 맞춘다
        await self._sync_tp(symbol, state)

    async def _apply_exchange_settings(self, symbol: str,
                                       state: MirrorState) -> None:
        assert self.ex is not None
        lev = await asyncio.to_thread(
            self.ex.apply_leverage, symbol, self.cfg.leverage
        )
        mar = await asyncio.to_thread(
            self.ex.apply_margin_mode, symbol, self.cfg.margin_mode
        )
        state.settings_verified = lev["verified"] and mar["verified"]
        for result in (lev, mar):
            self.emit("info" if result["verified"] else "error",
                      result["message"], symbol,
                      {k: v for k, v in result.items() if k != "message"})

    # ------------------------------------------------------------ 익절 관리

    async def _sync_tp(self, symbol: str, state: MirrorState) -> None:
        """내 평단 기준으로 내 익절 지정가를 맞춘다. 리더 값을 베끼지 않는다."""
        assert self.ex is not None
        position = await asyncio.to_thread(self.ex.position, symbol)
        size, entry = position["size"], position["entryPrice"]

        if state.last_position_size > 0 and size == 0:
            state.realized_trades += 1
            self.emit("trade", f"{symbol} 익절 청산 완료 "
                               f"(누적 {state.realized_trades}회)", symbol,
                      {"realizedTrades": state.realized_trades})

        result = await asyncio.to_thread(
            sync_take_profit, self.ex, symbol, size, entry,
            state.take_profit_percent, self.cfg.dry_run,
            lambda level, message, data: self.emit(level, message, symbol, data),
        )
        state.tp_order_id = result["orderId"]
        state.tp_price = result["price"]
        state.tp_amount = result["amount"]
        state.last_position_size = size

    # -------------------------------------------------------------- 루프

    async def _loop(self) -> None:
        """
        주기 점검. 신호가 없어도 돌아야 한다.

        신호만으로 굴리면 서버가 잠깐 죽거나 익절이 체결된 순간을 놓쳤을 때
        지정가 주문이 사라진 채 방치된다. 매 주기 거래소의 실제 상태를 보고
        있어야 할 모습으로 되돌린다.
        """
        while self.running:
            started = time.monotonic()
            try:
                await self._tick()
                self.consecutive_errors = 0
                self.last_error = None
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                self.consecutive_errors += 1
                self.last_error = str(exc)
                self.emit("error",
                          f"점검 실패({self.consecutive_errors}회): {exc}")
                await asyncio.sleep(min(300, 10 * self.consecutive_errors))

            try:
                self.persist()
            except Exception:  # noqa: BLE001 - 저장 실패로 카피가 멈추면 안 된다
                pass

            elapsed = time.monotonic() - started
            await asyncio.sleep(max(1.0, self.cfg.poll_seconds - elapsed))

    async def _tick(self) -> None:
        assert self.ex is not None
        self.last_tick_at = time.time()

        # 리더가 새로 돌리기 시작한 종목을 추적 목록에 편입
        for symbol, info in self.mgr.leader_view().items():
            if self.tracks(symbol) and symbol not in self.mirrors:
                state = self.mirror(symbol)
                state.take_profit_percent = info.get("takeProfitPercent") or 0.01

        for symbol in list(self.mirrors):
            async with self.lock(symbol):
                try:
                    await self._sync_tp(symbol, self.mirrors[symbol])
                    self.mirrors[symbol].last_error = None
                except Exception as exc:  # noqa: BLE001 - 심볼 단위 격리
                    self.mirrors[symbol].last_error = str(exc)
                    self.emit("error", f"{symbol} 익절 주문 점검 실패: {exc}", symbol)

        if time.time() - self._last_snapshot_at >= self.SNAPSHOT_INTERVAL:
            await self.snapshot_balance()

    async def snapshot_balance(self) -> dict[str, Any] | None:
        """내 계좌 스냅샷 1건. 앱의 잔고 그래프가 이걸 그린다."""
        if not self.ex:
            return None
        try:
            summary = await asyncio.to_thread(self.ex.account_summary)
        except Exception as exc:  # noqa: BLE001
            self.emit("warn", f"잔고 스냅샷 실패: {exc}")
            return None
        self.mgr.store.add_follower_balance_point(self.id, summary)
        self.last_snapshot = summary
        self._last_snapshot_at = time.time()
        return summary

    # ------------------------------------------------------------- 조회

    async def open_orders(self) -> list[dict[str, Any]]:
        """
        거래소에 실제로 살아있는 내 미체결 주문.

        저장된 상태가 아니라 거래소에 직접 묻는다. 앱의 '지정가 주문 상태'는
        서버가 믿는 값이 아니라 거래소의 사실을 보여줘야 의미가 있다.
        """
        if not self.ex:
            return []
        out: list[dict[str, Any]] = []
        for symbol in list(self.mirrors):
            try:
                orders = await asyncio.to_thread(self.ex.open_orders, symbol)
                price = await asyncio.to_thread(self.ex.last_price, symbol)
            except Exception as exc:  # noqa: BLE001
                out.append({"symbol": symbol, "error": str(exc)})
                continue
            for order in orders:
                info = order.get("info", {})
                limit_price = float(order.get("price") or 0.0)
                amount = float(order.get("amount") or 0.0)
                filled = float(order.get("filled") or 0.0)
                out.append({
                    "symbol": symbol,
                    "orderId": str(order.get("id") or ""),
                    "side": order.get("side"),
                    "type": order.get("type"),
                    "status": order.get("status"),
                    "price": limit_price,
                    "amount": amount,
                    "filled": filled,
                    "remaining": max(0.0, amount - filled),
                    "notional": limit_price * amount,
                    "reduceOnly": bool(
                        order.get("reduceOnly")
                        or str(info.get("reduceOnly", "")).lower() == "true"
                    ),
                    "createdAt": order.get("timestamp"),
                    "markPrice": price,
                    # 지금 가격에서 익절가까지 얼마나 남았나 — 앱이 바로 쓴다
                    "distancePercent": ((limit_price - price) / price * 100.0
                                        if price and limit_price else None),
                })
        return out

    def status(self) -> dict[str, Any]:
        leader = self.mgr.leader_view()
        symbols = []
        for symbol, state in self.mirrors.items():
            info = leader.get(symbol, {})
            symbols.append({
                "symbol": symbol,
                "leaderRunning": bool(info.get("running")),
                "leaderHasPosition": bool(info.get("hasPosition")),
                "mirroredBuys": state.mirrored_buys,
                "skippedBuys": state.skipped_buys,
                "lastSkipReason": state.last_skip_reason,
                "lastRatio": state.last_ratio,
                "positionSize": state.last_position_size,
                "takeProfitPercent": state.take_profit_percent,
                "takeProfit": {
                    "orderId": state.tp_order_id,
                    "price": state.tp_price,
                    "amount": state.tp_amount,
                },
                "realizedTrades": state.realized_trades,
                "settingsVerified": state.settings_verified,
                "error": state.last_error,
                # 리더는 청산됐는데 내 지정가는 아직 안 팔린 상태.
                # 이상 상황이 아니라 정상이지만 반드시 보여야 한다.
                "waitingAlone": bool(state.last_position_size > 0
                                     and not info.get("hasPosition")),
            })
        symbols.sort(key=lambda x: x["symbol"])

        account = self.last_snapshot or {}
        return {
            "followerId": self.id,
            "label": self.label,
            "running": self.running,
            "lastError": self.last_error,
            "consecutiveErrors": self.consecutive_errors,
            "startedAt": self.started_at,
            "lastTickAt": self.last_tick_at,
            "config": self.cfg.to_dict(),
            "sizingLabel": SizingMode.LABELS.get(self.cfg.sizing_mode, ""),
            "account": {
                "wallet": account.get("wallet"),
                "equity": account.get("equity"),
                "unrealizedPnl": account.get("unrealizedPnl"),
                "available": account.get("available"),
                "positionNotional": account.get("positionNotional"),
                "openPositions": account.get("openPositions"),
            },
            "symbols": symbols,
        }


# ------------------------------------------------------------------ 관리자

class CopyManager:
    """팔로워 전체 + 리더 신호 중계."""

    def __init__(self, store: Store, supervisor: Any):
        self.store = store
        self.sup = supervisor
        self.runners: dict[int, CopyRunner] = {}
        self._subscribers: dict[int, set[Callable[[dict[str, Any]], None]]] = {}
        self._queue: asyncio.Queue | None = None
        self._dispatcher: asyncio.Task | None = None
        self._loop: asyncio.AbstractEventLoop | None = None

    # --------------------------------------------------------- 리더 신호 수신

    def attach(self) -> None:
        """
        리더 supervisor 의 이벤트 스트림에 붙는다.

        publish 는 ccxt 를 돌리는 워커 스레드에서 불린다. 그 자리에서
        코루틴을 만들면 이벤트 루프 밖이라 터진다. 큐에 넣고 루프 위의
        디스패처가 꺼내 쓰게 한다.
        """
        self._loop = asyncio.get_running_loop()
        self._queue = asyncio.Queue(maxsize=1000)
        self.sup.subscribe(self._on_leader_event)
        self._dispatcher = asyncio.create_task(
            self._dispatch_loop(), name="copy-dispatch"
        )

    def _on_leader_event(self, event: dict[str, Any]) -> None:
        # 팔로워가 만든 이벤트가 되돌아오면 무한루프가 된다
        if event.get("followerId") is not None:
            return
        signal = (event.get("data") or {}).get("copy_signal")
        if not signal or not self._loop or not self._queue:
            return
        self._loop.call_soon_threadsafe(self._offer, signal)

    def _offer(self, signal: dict[str, Any]) -> None:
        if self._queue is None:
            return
        try:
            self._queue.put_nowait(signal)
        except asyncio.QueueFull:
            pass

    async def _dispatch_loop(self) -> None:
        assert self._queue is not None
        while True:
            signal = await self._queue.get()
            # 팔로워끼리 순차 실행하면 앞사람의 거래소 지연이 뒷사람을 늦춘다.
            # 각자 자기 API 키로 각자의 레이트리밋을 쓰므로 동시에 보낸다.
            runners = [r for r in self.runners.values() if r.running]
            if not runners:
                continue
            await asyncio.gather(
                *(r.on_signal(signal) for r in runners),
                return_exceptions=True,
            )

    def leader_view(self) -> dict[str, dict[str, Any]]:
        """
        팔로워에게 보여도 되는 리더 정보만 추린다.

        리더의 잔고·주문금액은 절대 넣지 않는다. 배율 계산에는 쓰지만
        앱으로 내려가는 건 '포지션이 있느냐'까지다.
        """
        out: dict[str, dict[str, Any]] = {}
        for runner in getattr(self.sup, "bots", {}).values():
            live = runner.last_result or {}
            config = runner.cfg
            out[runner.symbol] = {
                "running": runner.running,
                "hasPosition": bool((live.get("positionSize") or 0) > 0),
                "takeProfitPercent": config.take_profit_percent,
                "entryTrigger": config.entry_trigger,
                "timeframes": list(config.timeframes),
            }
        return out

    # ------------------------------------------------------------ 이벤트

    def subscribe(self, follower_id: int,
                  callback: Callable[[dict[str, Any]], None]) -> None:
        self._subscribers.setdefault(follower_id, set()).add(callback)

    def unsubscribe(self, follower_id: int,
                    callback: Callable[[dict[str, Any]], None]) -> None:
        self._subscribers.get(follower_id, set()).discard(callback)

    def publish(self, follower_id: int, level: str, message: str,
                symbol: str | None = None,
                data: dict[str, Any] | None = None) -> None:
        event = self.store.add_event(level, message, symbol, data,
                                     follower_id=follower_id)
        for callback in list(self._subscribers.get(follower_id, set())):
            try:
                callback(event)
            except Exception:  # noqa: BLE001
                self._subscribers.get(follower_id, set()).discard(callback)

    # ---------------------------------------------------------- 자격증명

    def _key_name(self, follower_id: int, which: str) -> str:
        return f"follower:{follower_id}:{which}"

    @staticmethod
    def owner_of(follower_id: int) -> str:
        return f"follower:{follower_id}"

    def set_credentials(self, follower_id: int, api_key: str, api_secret: str,
                        testnet: bool = False) -> None:
        """
        같은 키가 다른 계정에 이미 등록돼 있으면 거부한다.

        앱을 다시 설치하면 [연결 해제]가 서버에 알리지 않으므로 계정이 하나
        더 생긴다. 옛 계정이 키를 쥔 채 살아 있으면 같은 바이낸스 계정에
        카피 엔진이 둘 붙어 리더 신호 하나에 주문이 두 번 나간다.
        여기서 막지 않으면 그대로 자금 손실이 된다.
        """
        owner = self.owner_of(follower_id)
        holder = self.store.credential_owner(api_key)
        if holder and holder != owner:
            raise ExchangeError(
                f"이 API 키는 이미 {describe_owner(holder)}에 등록되어 있습니다. "
                "같은 바이낸스 계정에 두 엔진이 붙으면 리더 신호 하나에 주문이 "
                "두 번 나갑니다. 앱을 다시 설치하신 경우라면 리더에게 "
                "[팔로워 관리]에서 예전 계정을 삭제해 달라고 요청하세요."
            )
        self.store.put_secret(self._key_name(follower_id, "api_key"), api_key)
        self.store.put_secret(self._key_name(follower_id, "api_secret"), api_secret)
        self.store.put(f"follower:{follower_id}:testnet", bool(testnet))
        self.store.claim_credential(api_key, owner)

    def clear_credentials(self, follower_id: int) -> None:
        self.store.release_credential(self.owner_of(follower_id))
        self.store.delete_secret(self._key_name(follower_id, "api_key"))
        self.store.delete_secret(self._key_name(follower_id, "api_secret"))

    def has_credentials(self, follower_id: int) -> bool:
        return (self.store.has_secret(self._key_name(follower_id, "api_key"))
                and self.store.has_secret(self._key_name(follower_id, "api_secret")))

    def credential_info(self, follower_id: int) -> dict[str, Any]:
        from .store import mask

        key = self.store.get_secret(self._key_name(follower_id, "api_key"))
        return {
            "configured": self.has_credentials(follower_id),
            "apiKeyMasked": mask(key),
            "testnet": bool(self.store.get(f"follower:{follower_id}:testnet", False)),
        }

    def new_exchange(self, follower_id: int) -> BinanceFutures:
        key = self.store.get_secret(self._key_name(follower_id, "api_key"))
        secret = self.store.get_secret(self._key_name(follower_id, "api_secret"))
        if not key or not secret:
            raise ExchangeError(
                "API 키가 없습니다. 앱의 [API 키] 화면에서 먼저 등록하세요."
            )
        return BinanceFutures(
            key, secret,
            testnet=bool(self.store.get(f"follower:{follower_id}:testnet", False)),
        )

    # ------------------------------------------------------------- 팔로워

    def get(self, follower_id: int) -> CopyRunner:
        if follower_id in self.runners:
            return self.runners[follower_id]
        saved = self.store.get_follower(follower_id)
        if not saved:
            raise ExchangeError("팔로워 계정을 찾을 수 없습니다.")
        runner = CopyRunner(self, follower_id, saved["label"],
                            FollowerConfig(**saved["config"]), saved["state"])
        self.runners[follower_id] = runner
        return runner

    def create(self, label: str, config: FollowerConfig | None = None) -> int:
        cfg = config or FollowerConfig()
        cfg.validate()
        return self.store.create_follower(label, cfg.to_dict())

    async def update_config(self, follower_id: int,
                            config: FollowerConfig) -> dict[str, Any]:
        config.validate()
        runner = self.get(follower_id)
        was_running = runner.running
        if was_running:
            await runner.stop()
        runner.cfg = config
        # 레버리지/마진이 바뀌었을 수 있으니 다음 매수 때 다시 검증하게 한다
        for state in runner.mirrors.values():
            state.settings_verified = False
        self.store.save_follower(follower_id, config=config.to_dict())
        if was_running:
            await runner.start()
        return runner.status()

    async def delete(self, follower_id: int) -> None:
        runner = self.runners.pop(follower_id, None)
        if runner:
            await runner.stop()
        self.store.delete_follower(follower_id)

    def status_all(self) -> list[dict[str, Any]]:
        """리더(서버 주인)용 관리 목록. 팔로워의 API 키는 마스킹된 것만."""
        out = []
        for saved in self.store.load_followers():
            runner = self.runners.get(saved["id"])
            out.append({
                "id": saved["id"],
                "label": saved["label"],
                "enabled": saved["enabled"],
                "running": bool(runner and runner.running),
                "createdAt": saved["createdAt"],
                "sizingMode": saved["config"].get("sizing_mode"),
                "credentials": self.credential_info(saved["id"]),
                "devices": len(self.store.follower_devices(saved["id"])),
                "lastError": runner.last_error if runner else None,
            })
        return out

    # -------------------------------------------------------------- 부팅

    async def restore_on_boot(self) -> None:
        """서버 재시작 후 켜져 있던 팔로워를 자동 복구."""
        for saved in self.store.load_followers():
            if not saved["enabled"]:
                continue
            if not self.has_credentials(saved["id"]):
                self.publish(saved["id"], "warn",
                             "API 키가 없어 카피를 시작하지 못했습니다.")
                continue
            try:
                runner = self.get(saved["id"])
                await runner.start()
            except Exception as exc:  # noqa: BLE001
                self.publish(saved["id"], "error", f"자동 복구 실패: {exc}")

    async def shutdown(self) -> None:
        if self._dispatcher:
            self._dispatcher.cancel()
        for runner in list(self.runners.values()):
            runner.running = False
            if runner.task:
                runner.task.cancel()
