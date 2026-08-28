"""
리더 매수 신호 -> 팔로워 카피 매수까지 전 경로를 가짜 거래소로 태워 본다.

거래소만 가짜고 나머지(이벤트 브로드캐스트, 디스패처, 사이징, 익절 정합)는
전부 실제 코드다.
"""
import asyncio
import os
import sys
import tempfile
from pathlib import Path

DATA = Path(tempfile.mkdtemp(prefix="elemensha-flow-"))
os.environ["ELEMENSHA_DATA_DIR"] = str(DATA)
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.copy import CopyManager, FollowerConfig, SizingMode  # noqa: E402
from app.store import Store                                   # noqa: E402
from app.strategy import (StrategyConfig, StrategyEngine,      # noqa: E402
                          sync_take_profit)

ok, fail = 0, []


def check(name, condition, detail=""):
    global ok
    if condition:
        ok += 1
        print(f"  PASS  {name}")
    else:
        fail.append(name)
        print(f"  FAIL  {name}  {detail}")


# ------------------------------------------------------------- 가짜 거래소

class Spec:
    def __init__(self, base="BTC"):
        self.symbol = f"{base}/USDT:USDT"
        self.base = base
        self.min_qty = 0.001
        self.qty_step = 0.001
        self.min_notional = 100.0
        self.price_tick = 0.1
        self.max_leverage = 125

    def hard_floor_notional(self, price):
        return max(self.min_notional, self.min_qty * price)


class Client:
    def price_to_precision(self, symbol, price):
        return round(float(price), 1)

    def amount_to_precision(self, symbol, amount):
        return round(float(amount), 3)


class FakeExchange:
    """ccxt 대신 쓰는 최소 구현. 주문을 기록해 검증에 쓴다."""

    def __init__(self, equity=1_000.0, price=100_000.0):
        self.client = Client()
        self.equity = equity
        self.price = price
        self.size = 0.0
        self.entry = 0.0
        self.orders = []          # 체결된 시장가 매수
        self.limit_orders = []    # 살아있는 지정가
        self.cancelled = []
        self._next_id = 1

    # --- 조회
    def load(self, reload=False):
        return {}

    def normalize(self, symbol):
        return symbol

    def spec(self, symbol):
        return Spec(symbol.split("/")[0])

    def last_price(self, symbol):
        return self.price

    def usdt_balance(self):
        return self.equity

    def account_summary(self):
        return {"wallet": self.equity, "equity": self.equity,
                "unrealizedPnl": 0.0, "available": self.equity,
                "positionNotional": abs(self.size) * self.price,
                "openPositions": 1 if self.size else 0}

    def position(self, symbol):
        return {"size": self.size, "entryPrice": self.entry,
                "unrealizedPnl": 0.0, "liquidationPrice": 0.0,
                "leverage": 1, "marginMode": "ISOLATED"}

    def apply_leverage(self, symbol, leverage):
        return {"symbol": symbol, "requested": leverage, "actual": leverage,
                "verified": True, "message": f"레버리지 {leverage}x 적용 확인됨"}

    def apply_margin_mode(self, symbol, mode):
        return {"symbol": symbol, "requested": mode, "actual": mode,
                "verified": True, "message": f"마진모드 {mode} 적용 확인됨"}

    # --- 주문
    def order_amount(self, symbol, notional, price, round_up):
        spec = self.spec(symbol)
        target = max(notional, spec.hard_floor_notional(price))
        amount = max(round(target / price, 3), spec.min_qty)
        while amount * price < spec.hard_floor_notional(price):
            amount = round(amount + spec.qty_step, 8)
        return amount, amount * price

    def market_buy(self, symbol, amount):
        total = self.size * self.entry + amount * self.price
        self.size = round(self.size + amount, 8)
        self.entry = total / self.size
        self.orders.append({"symbol": symbol, "amount": amount})
        return {"id": f"m{len(self.orders)}"}

    def limit_sell_reduce_only(self, symbol, amount, price):
        order = {"id": f"L{self._next_id}", "symbol": symbol, "side": "sell",
                 "type": "limit", "price": price, "amount": amount,
                 "reduceOnly": True, "filled": 0.0, "status": "open",
                 "info": {"reduceOnly": "true"}}
        self._next_id += 1
        self.limit_orders.append(order)
        return order

    def open_orders(self, symbol):
        return [o for o in self.limit_orders if o["symbol"] == symbol]

    def cancel(self, order_id, symbol):
        self.cancelled.append(order_id)
        self.limit_orders = [o for o in self.limit_orders if o["id"] != order_id]
        return {"id": order_id}

    def cancel_all(self, symbol):
        n = len(self.open_orders(symbol))
        self.limit_orders = [o for o in self.limit_orders
                             if o["symbol"] != symbol]
        return n

    def close_position_market(self, symbol):
        if self.size == 0:
            return None
        self.size, self.entry = 0.0, 0.0
        return {"id": "close"}


# ---------------------------------------------------- 1) 리더 봇 회귀 확인

print("\n[1] 리더 익절 주문 (리팩터링 회귀)")
ex = FakeExchange()
engine = StrategyEngine(ex, StrategyConfig(symbol="BTC/USDT:USDT",
                                           take_profit_percent=0.01))
ex.size, ex.entry = 0.01, 100_000.0

check("포지션 생기면 익절 등록", engine.reconcile_take_profit(0.01, 100_000.0)
      == "placed")
check("익절가 = 평단 +1%", ex.limit_orders[0]["price"] == 101_000.0,
      ex.limit_orders)
check("reduceOnly 붙는다", ex.limit_orders[0]["reduceOnly"] is True)
check("엔진 상태에 반영", engine.tp_price == 101_000.0, engine.tp_price)

check("변화 없으면 재등록 안 함",
      engine.reconcile_take_profit(0.01, 100_000.0) == "unchanged")
check("취소 호출 0회", ex.cancelled == [], ex.cancelled)

check("평단 바뀌면 갱신",
      engine.reconcile_take_profit(0.02, 99_000.0) == "placed")
check("옛 주문은 취소됨", len(ex.cancelled) == 1, ex.cancelled)
check("새 익절가 = 99000 +1%", ex.limit_orders[0]["price"] == 99_990.0,
      ex.limit_orders)

check("포지션 0이면 정리", engine.reconcile_take_profit(0.0, 0.0) == "cleared")
check("잔여 지정가 없음", ex.limit_orders == [], ex.limit_orders)


# --------------------------------------------- 2) 신호 -> 카피 매수 전 경로

print("\n[2] 리더 신호 -> 팔로워 카피")


class FakeSupervisor:
    """Supervisor 중 CopyManager 가 실제로 쓰는 부분만."""

    def __init__(self):
        self.bots = {}
        self._subs = set()

    def subscribe(self, cb):
        self._subs.add(cb)

    def publish(self, level, message, symbol=None, data=None):
        event = {"level": level, "message": message, "symbol": symbol,
                 "data": data, "followerId": None}
        for cb in list(self._subs):
            cb(event)


async def main():
    global ok
    store = Store(DATA)
    sup = FakeSupervisor()
    mgr = CopyManager(store, sup)
    mgr.attach()

    # 팔로워: 자산 $1,000, 리더 $10,000 -> 자산 비례 0.1배
    fid = mgr.create("tester", FollowerConfig(sizing_mode=SizingMode.EQUITY))
    mgr.set_credentials(fid, "k" * 16, "s" * 16)
    runner = mgr.get(fid)
    follower_ex = FakeExchange(equity=1_000.0)
    mgr.new_exchange = lambda _id: follower_ex        # 진짜 바이낸스 대신
    await runner.start()

    signal = {
        "symbol": "BTC/USDT:USDT", "action": "buy", "timeframe": "1h",
        "leaderAmount": 0.05, "leaderNotional": 5_000.0,
        "leaderBalance": 10_000.0, "leaderBuyNotional": 10.0,
        "price": 100_000.0, "takeProfitPercent": 0.01,
        "leverage": 1, "marginMode": "ISOLATED", "ts": 1.0,
    }
    sup.publish("trade", "리더 매수", "BTC/USDT:USDT", {"copy_signal": signal})
    await asyncio.sleep(0.4)          # 디스패처가 처리할 틈

    check("팔로워가 따라 샀다", len(follower_ex.orders) == 1, follower_ex.orders)
    check("자산 비례 0.1배 = 0.005 BTC",
          follower_ex.orders and abs(follower_ex.orders[0]["amount"] - 0.005) < 1e-9,
          follower_ex.orders)
    state = runner.mirrors["BTC/USDT:USDT"]
    check("카피 횟수 기록", state.mirrored_buys == 1, state.mirrored_buys)
    check("배율 기록", abs((state.last_ratio or 0) - 0.1) < 1e-9, state.last_ratio)
    check("레버리지/마진 검증됨", state.settings_verified is True)

    check("내 평단 기준 익절 등록", state.tp_price == 101_000.0, state.tp_price)
    check("거래소에 지정가 살아있음", len(follower_ex.limit_orders) == 1,
          follower_ex.limit_orders)

    # 두 번째 신호 — 더 낮은 가격에 물타기
    follower_ex.price = 90_000.0
    signal2 = {**signal, "price": 90_000.0, "leaderNotional": 4_500.0, "ts": 2.0}
    sup.publish("trade", "리더 매수", "BTC/USDT:USDT", {"copy_signal": signal2})
    await asyncio.sleep(0.4)
    check("추가 매수 반영", len(follower_ex.orders) == 2, follower_ex.orders)
    check("평단 내려감", follower_ex.entry < 100_000.0, follower_ex.entry)
    check("익절가도 새 평단 기준으로 갱신",
          abs(state.tp_price - round(follower_ex.entry * 1.01, 1)) < 0.2,
          (state.tp_price, follower_ex.entry))

    # 미체결 주문 조회 [사용자 요청]
    rows = await runner.open_orders()
    check("미체결 지정가 1건 조회", len(rows) == 1, rows)
    check("주문에 reduceOnly 표시", rows and rows[0]["reduceOnly"] is True, rows)
    check("익절까지 남은 거리 계산됨",
          rows and rows[0]["distancePercent"] is not None, rows)

    # 계좌 자산 [사용자 요청]
    before = store.follower_balance_range(fid)["count"]
    snapshot = await runner.snapshot_balance()
    after = store.follower_balance_range(fid)
    check("잔고 스냅샷 기록",
          snapshot is not None and snapshot["equity"] == 1_000.0
          and after["count"] == before + 1, (before, after))

    status = runner.status()
    check("상태에 내 자산 포함", status["account"]["equity"] == 1_000.0,
          status["account"])
    check("상태에 지정가 정보 포함",
          status["symbols"][0]["takeProfit"]["price"] == state.tp_price,
          status["symbols"])

    # 최소 주문금액 미달 -> 건너뛰고 이유를 남긴다
    poor_ex = FakeExchange(equity=5.0)
    fid2 = mgr.create("small", FollowerConfig(sizing_mode=SizingMode.EQUITY))
    mgr.set_credentials(fid2, "k" * 16, "s" * 16)
    mgr.new_exchange = lambda _id: poor_ex
    runner2 = mgr.get(fid2)
    await runner2.start()
    sup.publish("trade", "리더 매수", "BTC/USDT:USDT",
                {"copy_signal": {**signal, "ts": 3.0}})
    await asyncio.sleep(0.4)
    state2 = runner2.mirrors.get("BTC/USDT:USDT")
    check("자산 부족하면 매수 안 함", len(poor_ex.orders) == 0, poor_ex.orders)
    # 신호는 팔로워 '전원'에게 간다 — 자산이 충분한 1번은 이번에도 샀어야 한다
    check("같은 신호가 다른 팔로워에게도 전달됨",
          len(follower_ex.orders) == 3, follower_ex.orders)
    check("건너뛴 이유가 남는다",
          state2 and state2.skipped_buys == 1 and state2.last_skip_reason,
          state2 and state2.last_skip_reason)

    # 리더가 청산돼도 팔로워 지정가는 살아있어야 한다
    mgr.new_exchange = lambda _id: follower_ex
    sup.bots = {}
    await runner._tick()
    check("리더 청산과 무관하게 내 지정가 유지",
          len(follower_ex.limit_orders) == 1, follower_ex.limit_orders)
    check("혼자 기다리는 중임을 표시",
          runner.status()["symbols"][0]["waitingAlone"] is True,
          runner.status()["symbols"])

    # 긴급 청산은 내 것만
    await runner.panic()
    check("내 포지션만 청산", follower_ex.size == 0.0, follower_ex.size)
    check("내 지정가도 취소", follower_ex.limit_orders == [],
          follower_ex.limit_orders)

    # 재시작 후 상태 복구
    saved = store.get_follower(fid)
    fresh = mgr.__class__(store, sup)
    fresh.new_exchange = lambda _id: follower_ex
    restored = fresh.get(fid)
    check("재시작 후 카피 횟수 복구",
          restored.mirrors["BTC/USDT:USDT"].mirrored_buys == 3,
          saved["state"])
    check("재시작 후 익절률도 복구",
          restored.mirrors["BTC/USDT:USDT"].take_profit_percent == 0.01,
          saved["state"])

    await mgr.shutdown()


asyncio.run(main())

print(f"\n{'=' * 50}\n통과 {ok}건" + (f", 실패 {len(fail)}건: {fail}" if fail else ""))
sys.exit(1 if fail else 0)
