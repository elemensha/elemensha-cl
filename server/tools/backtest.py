"""
elemensha 전략 백테스터.

실제 봇의 신호 판정 코드(StrategyEngine._rsi_series, signal_fires)를 그대로
불러다 쓴다. 재구현하면 백테스트와 실매매가 갈라져 결과를 믿을 수 없다.

시뮬레이션 규칙 — 실매매와 동일하게:
  - 진입: 선택한 각 타임프레임의 '확정봉'에서 조건 충족 시 시장가 매수.
          같은 봉에서는 1회만 (실제 봇과 동일)
  - 사이징: 지갑 잔고 x 비율, 코인별 최소 주문액으로 하한 보정
  - 청산: 평단 x (1 + 익절률) 지정가. 기준봉 고가가 이를 넘으면 체결
  - 손절 없음, 숏 없음, 매수 횟수 무제한

현실 반영:
  - 수수료: 시장가 매수는 taker, 익절 지정가는 maker
  - 슬리피지: 시장가 매수에만 적용
  - 미실현손익을 포함한 순자산 곡선으로 MDD 를 잰다. 지갑 잔고만 보면
    물려 있는 구간이 안 보인다.

사용:
  python -m tools.backtest --symbol BTC --days 365 --base 5m
  python -m tools.backtest --symbol BTC --days 1460 --base 1h --timeframes 1h,4h,1d
"""
from __future__ import annotations

import argparse
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone

sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[1]))

from app.exchange import BinanceFutures                      # noqa: E402
from app.strategy import (ALL_TIMEFRAMES, TIMEFRAME_SECONDS,  # noqa: E402
                          StrategyConfig, StrategyEngine)

TAKER_FEE = 0.0004     # 시장가 매수
MAKER_FEE = 0.0002     # 익절 지정가 매도


# ------------------------------------------------------------------ 데이터

def fetch_klines(ex: BinanceFutures, symbol: str, timeframe: str,
                 since_ms: int, until_ms: int) -> list[list[float]]:
    """페이지네이션으로 구간 전체를 받아온다. 바이낸스는 요청당 1500개 상한."""
    out: list[list[float]] = []
    cursor = since_ms
    step = TIMEFRAME_SECONDS[timeframe] * 1000
    while cursor < until_ms:
        batch = ex.client.fetch_ohlcv(symbol, timeframe, since=cursor, limit=1500)
        if not batch:
            break
        # 이미 받은 구간은 버린다 (거래소가 겹쳐서 줄 때가 있다)
        batch = [b for b in batch if not out or b[0] > out[-1][0]]
        if not batch:
            break
        out.extend(batch)
        cursor = batch[-1][0] + step
        if len(batch) < 1500:
            break
        time.sleep(ex.client.rateLimit / 1000)
    return [b for b in out if b[0] < until_ms]


# ------------------------------------------------------------------ 결과

@dataclass
class Trade:
    ts: int
    side: str          # buy / tp
    timeframe: str
    price: float
    amount: float
    notional: float
    fee: float


@dataclass
class Result:
    symbol: str
    start: datetime
    end: datetime
    timeframes: list[str]
    initial: float
    wallet: float                     # 실현 기준 잔고
    position: float = 0.0             # 보유 수량
    avg_entry: float = 0.0
    trades: list[Trade] = field(default_factory=list)
    equity_curve: list[tuple[int, float, float]] = field(default_factory=list)
    buys_by_tf: dict[str, int] = field(default_factory=dict)
    tp_count: int = 0
    fees_paid: float = 0.0
    max_position_notional: float = 0.0
    skipped_no_funds: int = 0


# ------------------------------------------------------------------ 실행

def run(symbol: str, days: int, base_tf: str, timeframes: list[str],
        initial: float, wallet_pct: float, tp_pct: float,
        rsi_period: int, rsi_lower: float, trigger: str,
        slippage: float, verbose: bool) -> Result:

    ex = BinanceFutures("", "")
    ex.load()
    sym = ex.normalize(symbol)
    spec = ex.spec(sym)

    cfg = StrategyConfig(
        symbol=sym, timeframes=timeframes, rsi_period=rsi_period,
        rsi_lower=rsi_lower, entry_trigger=trigger,
        wallet_percentage=wallet_pct, take_profit_percent=tp_pct,
        dry_run=True,
    )
    engine = StrategyEngine(ex, cfg)

    until = int(time.time() * 1000)
    since = until - days * 86400 * 1000

    # 기준봉: 체결·익절 판정의 해상도
    print(f"  {base_tf} 캔들 수집 중...", flush=True)
    base = fetch_klines(ex, sym, base_tf, since, until)
    if len(base) < 100:
        raise SystemExit(f"기준봉 데이터가 부족합니다 ({len(base)}개).")
    print(f"  {base_tf}: {len(base):,}개", flush=True)

    # 각 신호 타임프레임의 RSI 를 미리 계산해 '확정 시각 -> 발동 여부' 로 만든다.
    # 확정 시각 = 봉 시작 + 봉 길이 (그 시점에야 봉이 닫힌다)
    signals: dict[str, dict[int, float]] = {}
    for tf in timeframes:
        need = TIMEFRAME_SECONDS[tf] * 1000
        warmup = since - need * (rsi_period * 12)
        print(f"  {tf} 캔들 수집 중...", flush=True)
        rows = fetch_klines(ex, sym, tf, warmup, until)
        if len(rows) < rsi_period + 3:
            print(f"    {tf}: 데이터 부족 — 제외")
            continue
        closes = [float(r[4]) for r in rows]
        rsi = engine._rsi_series(closes)
        fired: dict[int, float] = {}
        for i in range(1, len(rsi)):
            if rsi[i] is None or rsi[i - 1] is None:
                continue
            if engine.signal_fires(rsi[i - 1], rsi[i]):
                fired[int(rows[i][0]) + need] = rsi[i]   # 확정 시각에 발동
        signals[tf] = fired
        print(f"    {tf}: 캔들 {len(rows):,}개, 신호 {len(fired)}회", flush=True)

    # ---------------------------------------------------------------- 시뮬
    r = Result(symbol=sym,
               start=datetime.fromtimestamp(base[0][0] / 1000, timezone.utc),
               end=datetime.fromtimestamp(base[-1][0] / 1000, timezone.utc),
               timeframes=timeframes, initial=initial, wallet=initial)
    r.buys_by_tf = {tf: 0 for tf in timeframes}
    base_ms = TIMEFRAME_SECONDS[base_tf] * 1000

    for row in base:
        ts, _o, high, _l, close, _v = row[:6]
        ts = int(ts)

        # 1) 익절 체결 — 고가가 목표가에 닿았는가
        if r.position > 0:
            tp_price = r.avg_entry * (1 + tp_pct)
            if high >= tp_price:
                gross = r.position * tp_price
                fee = gross * MAKER_FEE
                r.wallet += gross - fee
                r.fees_paid += fee
                r.trades.append(Trade(ts, "tp", "-", tp_price, r.position, gross, fee))
                r.tp_count += 1
                r.position, r.avg_entry = 0.0, 0.0

        # 2) 이 기준봉이 닫히는 동안 확정된 신호들
        for tf in timeframes:
            fired = signals.get(tf)
            if not fired:
                continue
            hit = [t for t in fired if ts <= t < ts + base_ms]
            if not hit:
                continue

            price = close * (1 + slippage)       # 시장가 매수 슬리피지
            floor_usd = spec.hard_floor_notional(price)
            want = max(r.wallet * wallet_pct, floor_usd)
            step = spec.qty_step or 0.0
            amount = round(want / price / step) * step if step else want / price
            if amount < spec.min_qty:
                amount = spec.min_qty
            while amount * price < floor_usd:
                amount += step or amount * 0.01
            notional = amount * price
            fee = notional * TAKER_FEE

            if notional + fee > r.wallet:        # 잔고 부족
                r.skipped_no_funds += 1
                continue

            r.avg_entry = ((r.avg_entry * r.position + price * amount)
                           / (r.position + amount))
            r.position += amount
            r.wallet -= notional + fee
            r.fees_paid += fee
            r.buys_by_tf[tf] += 1
            r.trades.append(Trade(ts, "buy", tf, price, amount, notional, fee))
            r.max_position_notional = max(r.max_position_notional,
                                          r.position * price)

        equity = r.wallet + r.position * close
        r.equity_curve.append((ts, r.wallet, equity))

    return r


# ------------------------------------------------------------------ 보고

def report(r: Result, tp_pct: float) -> None:
    last_close_equity = r.equity_curve[-1][2]
    peak = 0.0
    mdd = 0.0
    mdd_at = 0
    underwater = 0
    for ts, _w, eq in r.equity_curve:
        peak = max(peak, eq)
        dd = (peak - eq) / peak if peak else 0.0
        if dd > mdd:
            mdd, mdd_at = dd, ts
        if eq < peak * 0.999:
            underwater += 1

    total = len(r.equity_curve)
    days = (r.end - r.start).days or 1
    ret = (last_close_equity - r.initial) / r.initial * 100

    print()
    print("=" * 66)
    print(f" {r.symbol}   {r.start:%Y-%m-%d} ~ {r.end:%Y-%m-%d}  ({days}일)")
    print("=" * 66)
    print(f"  적용 봉      : {' '.join(r.timeframes)}")
    print(f"  익절률       : {tp_pct:.2%}")
    print()
    print(f"  초기 자본    : ${r.initial:>14,.2f}")
    print(f"  최종 순자산  : ${last_close_equity:>14,.2f}   ({ret:+.2f}%)")
    print(f"   ├ 지갑      : ${r.wallet:>14,.2f}")
    print(f"   └ 미청산    : ${last_close_equity - r.wallet:>14,.2f}"
          f"   ({r.position:g} 보유)")
    print()
    print(f"  매수 횟수    : {sum(r.buys_by_tf.values()):>14,}")
    print(f"  익절 횟수    : {r.tp_count:>14,}")
    print(f"  지불 수수료  : ${r.fees_paid:>14,.2f}")
    print(f"  잔고부족 스킵: {r.skipped_no_funds:>14,}")
    print()
    print(f"  최대 낙폭    : {mdd*100:>14.2f}%"
          f"   ({datetime.fromtimestamp(mdd_at/1000, timezone.utc):%Y-%m-%d})")
    print(f"  최대 포지션  : ${r.max_position_notional:>14,.2f}"
          f"   (자본의 {r.max_position_notional/r.initial*100:.1f}%)")
    print(f"  고점 아래 시간: {underwater/total*100:>13.1f}%")
    print()
    print("  봉별 매수 횟수")
    for tf, n in r.buys_by_tf.items():
        if n:
            print(f"    {tf:<5} {n:>6,}회")
    nz = [tf for tf, n in r.buys_by_tf.items() if not n]
    if nz:
        print(f"    (신호 없음: {' '.join(nz)})")


def main() -> None:
    p = argparse.ArgumentParser(description="elemensha 전략 백테스트")
    p.add_argument("--symbol", default="BTC")
    p.add_argument("--days", type=int, default=365)
    p.add_argument("--base", default="5m", help="체결·익절 판정 해상도")
    p.add_argument("--timeframes", default="",
                   help="쉼표 구분. 비우면 기준봉 이상의 전체 15종")
    p.add_argument("--initial", type=float, default=10000.0)
    p.add_argument("--wallet-pct", type=float, default=0.001)
    p.add_argument("--tp", type=float, default=0.01)
    p.add_argument("--rsi-period", type=int, default=14)
    p.add_argument("--rsi-lower", type=float, default=30.0)
    p.add_argument("--trigger", default="cross_up_lower")
    p.add_argument("--slippage", type=float, default=0.0002)
    p.add_argument("-v", "--verbose", action="store_true")
    a = p.parse_args()

    if a.timeframes:
        tfs = [t.strip() for t in a.timeframes.split(",") if t.strip()]
    else:
        floor = TIMEFRAME_SECONDS[a.base]
        tfs = [t for t in ALL_TIMEFRAMES if TIMEFRAME_SECONDS[t] >= floor]

    print(f"백테스트 준비: {a.symbol}  {a.days}일  기준봉 {a.base}")
    print(f"  신호 봉: {' '.join(tfs)}")
    r = run(a.symbol, a.days, a.base, tfs, a.initial, a.wallet_pct, a.tp,
            a.rsi_period, a.rsi_lower, a.trigger, a.slippage, a.verbose)
    report(r, a.tp)


if __name__ == "__main__":
    main()
