"""
Binance USD-M 선물 래퍼.

원본 코드의 문제를 여기서 전부 흡수한다.
  - 심볼 표기 통일         : 'BTCUSDT' -> ccxt 통합심볼 'BTC/USDT:USDT'
  - 레버리지/마진모드 적용  : 설정 후 거래소에서 되읽어 검증(피드백)   [요구사항 1]
  - 최소 주문금액          : 거래소 실제 filter를 읽어 $10 단위 올림   [요구사항 2]
  - 전 종목 지원           : USDT 무기한 선물 전체 목록 제공           [요구사항 3]
  - reduceOnly             : 청산 주문에 강제 부여                     [요구사항 4]
  - 수량/가격 정밀도        : amount_to_precision / price_to_precision
"""
from __future__ import annotations

import math
import time
from dataclasses import dataclass
from typing import Any

import ccxt


class ExchangeError(RuntimeError):
    """앱으로 그대로 보여줄 수 있는 사용자향 에러."""


@dataclass
class SymbolSpec:
    """거래소가 알려준 심볼별 주문 제약."""

    symbol: str          # 'BTC/USDT:USDT'
    market_id: str       # 'BTCUSDT'
    base: str            # 'BTC'
    min_qty: float
    qty_step: float
    min_notional: float  # 거래소 원본 최소 주문금액
    price_tick: float
    max_leverage: int

    def hard_floor_notional(self, price: float) -> float:
        """
        코인별 '진짜' 최소 주문액.

        거래소는 두 가지를 동시에 요구한다.
          - MIN_NOTIONAL : 금액 하한 (BTC $50, ETH $20, 대부분 알트 $5)
          - LOT_SIZE.minQty : 수량 하한 (BTC 0.001)
        BTC는 수량 쪽이 더 커서 0.001 x 가격 = 가격의 0.1% 가 실질 최소가 된다.
        이 값은 올림하지 않는다 — 올리면 수량 스텝이 한 칸 뛰어 주문이 2배가 된다.
        """
        return max(self.min_notional, self.min_qty * price)

    def effective_min_notional(self, price: float = 0.0,
                               round_up_to: float = 10.0) -> float:
        """
        요구사항 2: 코인별 실제 최소 주문액을 $10 단위로 올림.

        단, 수량 하한이 구속조건인 코인(BTC)은 올림하지 않는다.
        올리는 순간 수량이 한 스텝 뛰어 주문액이 2배가 되기 때문이다.
        """
        if round_up_to <= 0:
            return self.hard_floor_notional(price) if price else self.min_notional
        if price and self.min_qty * price >= self.min_notional:
            return self.min_qty * price          # 수량 구속 -> 올림 금지
        return math.ceil(self.min_notional / round_up_to) * round_up_to


class BinanceFutures:
    def __init__(self, api_key: str, api_secret: str, testnet: bool = False):
        self.testnet = testnet
        self.client = ccxt.binance({
            "apiKey": api_key,
            "secret": api_secret,
            "enableRateLimit": True,
            "options": {
                "defaultType": "future",
                "adjustForTimeDifference": True,
                "warnOnFetchOpenOrdersWithoutSymbol": False,
            },
        })
        if testnet:
            self.client.set_sandbox_mode(True)
        self._markets: dict[str, Any] | None = None
        self._specs: dict[str, SymbolSpec] = {}

    # ------------------------------------------------------------------ 마켓

    def load(self, reload: bool = False) -> dict[str, Any]:
        if self._markets is None or reload:
            self._markets = self.client.load_markets(reload)
            self._specs.clear()
        return self._markets

    def normalize(self, symbol: str) -> str:
        """'BTCUSDT' / 'BTC/USDT' / 'BTC' 무엇을 넣어도 선물 통합심볼로."""
        self.load()
        if symbol in self.client.markets:
            m = self.client.markets[symbol]
            if m.get("swap") and m.get("quote") == "USDT":
                return m["symbol"]
        s = symbol.upper().replace(" ", "")
        base = s.split("/")[0].removesuffix("USDT") or s
        # SHIB/PEPE 처럼 선물에서는 1000배 단위로 상장된 종목까지 흡수
        candidates = {
            s, s.replace("/", ""), f"{s}USDT", f"{s}/USDT:USDT",
            base, f"1000{base}", f"1000000{base}",
        }
        for m in self.client.markets.values():
            if not (m.get("swap") and m.get("linear") and m.get("quote") == "USDT"):
                continue
            if candidates & {m["id"], m["symbol"], m["base"], m["base"] + "USDT"}:
                return m["symbol"]
        raise ExchangeError(f"USDT 선물에서 '{symbol}' 심볼을 찾을 수 없습니다.")

    def list_symbols(self) -> list[dict[str, Any]]:
        """
        요구사항 3: 앱에서 고를 수 있는 전체 USDT 무기한 선물 목록.

        최소 주문액은 가격에 좌우되므로(BTC = 0.001 x 가격) 시세를 한 번에
        받아와 종목별 실제 최소액을 함께 내려준다.
        """
        self.load()
        try:
            tickers = self.client.fetch_tickers()
        except Exception:  # noqa: BLE001 - 시세 실패해도 목록은 준다
            tickers = {}

        out = []
        for m in self.client.markets.values():
            if not (m.get("swap") and m.get("linear") and m.get("quote") == "USDT"):
                continue
            if not m.get("active", True):
                continue
            spec = self.spec(m["symbol"])
            price = _f((tickers.get(m["symbol"]) or {}).get("last")) or 0.0
            out.append({
                "symbol": m["symbol"],
                "id": m["id"],
                "base": m["base"],
                "price": price,
                "minNotional": spec.min_notional,
                "minQty": spec.min_qty,
                "effectiveMinNotional": spec.effective_min_notional(price),
                "qtyBound": bool(price and spec.min_qty * price >= spec.min_notional),
                "maxLeverage": spec.max_leverage,
            })
        out.sort(key=lambda x: x["base"])
        return out

    def spec(self, symbol: str) -> SymbolSpec:
        symbol = self.normalize(symbol)
        if symbol in self._specs:
            return self._specs[symbol]

        m = self.client.market(symbol)
        limits = m.get("limits", {})
        precision = m.get("precision", {})
        info = m.get("info", {})

        min_qty = _f(limits.get("amount", {}).get("min")) or 0.0
        min_notional = _f(limits.get("cost", {}).get("min")) or 0.0
        qty_step = _step(precision.get("amount"))
        price_tick = _step(precision.get("price"))

        # ccxt가 못 채운 값은 거래소 원본 filter에서 직접 회수
        for flt in info.get("filters", []):
            kind = flt.get("filterType")
            if kind == "LOT_SIZE":
                min_qty = _f(flt.get("minQty")) or min_qty
                qty_step = _f(flt.get("stepSize")) or qty_step
            elif kind in ("MIN_NOTIONAL", "NOTIONAL"):
                min_notional = (_f(flt.get("notional"))
                                or _f(flt.get("minNotional"))
                                or min_notional)
            elif kind == "PRICE_FILTER":
                price_tick = _f(flt.get("tickSize")) or price_tick

        spec = SymbolSpec(
            symbol=symbol,
            market_id=m["id"],
            base=m["base"],
            min_qty=min_qty or 0.0,
            qty_step=qty_step or 0.0,
            min_notional=min_notional or 5.0,
            price_tick=price_tick or 0.0,
            max_leverage=_max_leverage(m),
        )
        self._specs[symbol] = spec
        return spec

    # ------------------------------------------ 레버리지 / 마진모드 [요구사항 1]

    def apply_leverage(self, symbol: str, leverage: int) -> dict[str, Any]:
        """설정 -> 거래소에서 되읽어 실제 반영 여부까지 확인해서 반환."""
        symbol = self.normalize(symbol)
        spec = self.spec(symbol)
        if not 1 <= leverage <= spec.max_leverage:
            raise ExchangeError(
                f"{spec.base} 레버리지는 1~{spec.max_leverage}x 범위만 가능합니다 "
                f"(요청: {leverage}x)."
            )

        sent_msg = "설정 요청 전송됨"
        try:
            self.client.set_leverage(leverage, symbol)
        except Exception as exc:  # noqa: BLE001 - 이미 같은 값이면 거래소가 에러를 준다
            sent_msg = str(exc)

        actual = self.read_position_risk(symbol)
        got = actual.get("leverage")
        verified = got == leverage
        return {
            "symbol": symbol,
            "requested": leverage,
            "actual": got,
            "verified": verified,
            "message": (
                f"레버리지 {leverage}x 적용 확인됨"
                if verified
                else f"반영 실패 - 거래소 현재값 {got}x ({sent_msg})"
            ),
        }

    def apply_margin_mode(self, symbol: str, margin_mode: str) -> dict[str, Any]:
        """설정 -> 거래소에서 되읽어 실제 반영 여부까지 확인해서 반환."""
        symbol = self.normalize(symbol)
        mode = margin_mode.upper()
        if mode == "CROSS":
            mode = "CROSSED"
        if mode not in ("ISOLATED", "CROSSED"):
            raise ExchangeError("마진 모드는 ISOLATED 또는 CROSS만 가능합니다.")

        sent_msg = "설정 요청 전송됨"
        try:
            self.client.set_margin_mode(
                "isolated" if mode == "ISOLATED" else "cross", symbol
            )
        except Exception as exc:  # noqa: BLE001 - 'No need to change margin type' 포함
            sent_msg = str(exc)

        actual = self.read_position_risk(symbol)
        got = actual.get("marginMode")
        verified = got == mode
        return {
            "symbol": symbol,
            "requested": mode,
            "actual": got,
            "verified": verified,
            "message": (
                f"마진모드 {mode} 적용 확인됨"
                if verified
                else f"반영 실패 - 거래소 현재값 {got} ({sent_msg})"
            ),
        }

    def read_position_risk(self, symbol: str) -> dict[str, Any]:
        """거래소가 보고하는 '현재 진짜 값'. 모든 피드백의 근거."""
        symbol = self.normalize(symbol)
        spec = self.spec(symbol)
        try:
            rows = self.client.fapiPrivateV2GetPositionRisk({"symbol": spec.market_id})
        except Exception as exc:  # noqa: BLE001
            raise ExchangeError(f"포지션 정보 조회 실패: {exc}") from exc
        if not rows:
            return {"leverage": None, "marginMode": None}

        row = rows[0]
        raw_mode = str(row.get("marginType", "")).upper()
        return {
            "leverage": int(float(row.get("leverage", 0))) or None,
            "marginMode": ("ISOLATED" if raw_mode == "ISOLATED"
                           else "CROSSED" if raw_mode else None),
            "entryPrice": _f(row.get("entryPrice")) or 0.0,
            "positionAmt": _f(row.get("positionAmt")) or 0.0,
            "unrealizedPnl": _f(row.get("unRealizedProfit")) or 0.0,
            "liquidationPrice": _f(row.get("liquidationPrice")) or 0.0,
        }

    # ------------------------------------------------------------------ 계좌

    def usdt_balance(self) -> float:
        bal = self.client.fetch_balance(params={"type": "future"})
        return float(bal["total"].get("USDT") or 0.0)

    def position(self, symbol: str) -> dict[str, Any]:
        """포지션 수량+평단을 한 번의 호출로. 원본의 2회 호출을 대체."""
        info = self.read_position_risk(symbol)
        return {
            "size": info.get("positionAmt") or 0.0,
            "entryPrice": info.get("entryPrice") or 0.0,
            "unrealizedPnl": info.get("unrealizedPnl") or 0.0,
            "liquidationPrice": info.get("liquidationPrice") or 0.0,
            "leverage": info.get("leverage"),
            "marginMode": info.get("marginMode"),
        }

    def last_price(self, symbol: str) -> float:
        return float(self.client.fetch_ticker(self.normalize(symbol))["last"])

    def ohlcv(self, symbol: str, timeframe: str, limit: int) -> list[list[float]]:
        return self.client.fetch_ohlcv(
            self.normalize(symbol), timeframe=timeframe, limit=limit
        )

    # ------------------------------------------------------------------ 주문

    def order_amount(
        self,
        symbol: str,
        notional_usd: float,
        price: float,
        round_up_to: float = 10.0,
    ) -> tuple[float, float]:
        """
        주문 금액 -> 거래소가 받아주는 수량. (수량, 실제 주문금액) 반환.

        주의: ccxt의 amount_to_precision은 최소 정밀도 미만 값을 받으면 0을
        돌려주는 게 아니라 InvalidOrder를 던진다. 그래서 스텝 단위로 '먼저'
        올림한 뒤에 포매팅한다.
        """
        spec = self.spec(symbol)
        step = spec.qty_step or 0.0
        floor_usd = spec.hard_floor_notional(price)   # 올림 없는 진짜 바닥

        # [요구사항 2] $10 올림은 '요청 금액'에만 적용한다.
        # 거래소 바닥값까지 올려버리면 BTC처럼 스텝이 굵은 종목은 주문이 2배가 된다.
        wanted = float(notional_usd)
        if round_up_to > 0:
            wanted = math.ceil(wanted / round_up_to) * round_up_to
        target = max(wanted, floor_usd)

        # 스텝에 '가까운' 쪽으로 맞춘 뒤, 바닥 미달분만 올린다
        amount = _round_to_step(target / price, step) if step else target / price
        amount = max(amount, spec.min_qty)
        guard = 0
        while amount * price < floor_usd and guard < 100_000:
            amount = _ceil_to_step(amount + (step or amount * 0.01), step)
            guard += 1

        if step:  # 거래소 표기로 정규화 (이미 최소치 이상이라 예외가 나지 않는다)
            amount = float(self.client.amount_to_precision(spec.symbol, amount))
            if amount * price < floor_usd:      # 포매팅이 내림했을 경우 보정
                amount = float(self.client.amount_to_precision(
                    spec.symbol, _ceil_to_step(amount + step, step)))

        if amount <= 0:
            raise ExchangeError(
                f"{spec.base} 주문 수량 계산 실패 (최소 ${floor_usd:,.2f})."
            )
        return amount, amount * price

    def market_buy(self, symbol: str, amount: float) -> dict[str, Any]:
        return self.client.create_order(self.normalize(symbol), "market", "buy", amount)

    def limit_sell_reduce_only(
        self, symbol: str, amount: float, price: float
    ) -> dict[str, Any]:
        """요구사항 4: 청산 지정가에 reduceOnly 강제."""
        symbol = self.normalize(symbol)
        amt = float(self.client.amount_to_precision(symbol, amount))
        px = float(self.client.price_to_precision(symbol, price))
        return self.client.create_order(
            symbol, "limit", "sell", amt, px, {"reduceOnly": True}
        )

    def open_orders(self, symbol: str) -> list[dict[str, Any]]:
        return self.client.fetch_open_orders(self.normalize(symbol))

    def cancel(self, order_id: str, symbol: str) -> dict[str, Any]:
        return self.client.cancel_order(order_id, self.normalize(symbol))

    def cancel_all(self, symbol: str) -> int:
        count = 0
        for order in self.open_orders(symbol):
            try:
                self.cancel(order["id"], symbol)
                count += 1
            except Exception:  # noqa: BLE001 - 이미 체결/취소된 주문은 무시
                pass
        return count

    def close_position_market(self, symbol: str) -> dict[str, Any] | None:
        """긴급 정지용 전량 시장가 청산."""
        size = self.position(symbol)["size"]
        if size == 0:
            return None
        side = "sell" if size > 0 else "buy"
        return self.client.create_order(
            self.normalize(symbol), "market", side, abs(size), None,
            {"reduceOnly": True},
        )

    def verify_credentials(self) -> dict[str, Any]:
        """요구사항 7: 앱에서 키 입력 직후 호출. 권한까지 확인해서 돌려준다."""
        try:
            self.load()
            balance = self.usdt_balance()
        except ccxt.AuthenticationError as exc:
            raise ExchangeError(f"API 키 인증 실패: {exc}") from exc
        except Exception as exc:  # noqa: BLE001
            raise ExchangeError(f"거래소 연결 실패: {exc}") from exc

        can_trade = True
        try:
            perm = self.client.fapiPrivateV2GetAccount()
            can_trade = bool(perm.get("canTrade", True))
        except Exception:  # noqa: BLE001
            pass
        return {
            "ok": True,
            "testnet": self.testnet,
            "usdtBalance": balance,
            "canTrade": can_trade,
            "serverTime": int(time.time() * 1000),
        }


def _ceil_to_step(value: float, step: float) -> float:
    """스텝 배수로 올림. 부동소수 잔차(0.0010000000000000002)를 제거한다."""
    if step <= 0:
        return value
    decimals = max(0, min(18, -math.floor(math.log10(step)) + 2))
    steps = math.ceil(round(value / step, 9))
    return round(steps * step, decimals)


def _round_to_step(value: float, step: float) -> float:
    """스텝 배수로 반올림. 올림만 하면 BTC처럼 스텝이 굵은 종목이 크게 넘친다."""
    if step <= 0:
        return value
    decimals = max(0, min(18, -math.floor(math.log10(step)) + 2))
    return round(round(value / step) * step, decimals)


def _f(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _step(precision: Any) -> float:
    """ccxt precision(자릿수 또는 스텝값) -> 스텝값."""
    value = _f(precision)
    if value is None:
        return 0.0
    if value >= 1 and float(value).is_integer():  # 자릿수 표기
        return 10 ** -int(value)
    return value


def _max_leverage(market: dict[str, Any]) -> int:
    limit = market.get("limits", {}).get("leverage", {}).get("max")
    value = _f(limit)
    if value:
        return int(value)
    try:
        brackets = market.get("info", {}).get("leverageBracket")
        if brackets:
            return int(brackets[0]["initialLeverage"])
    except Exception:  # noqa: BLE001
        pass
    return 125
