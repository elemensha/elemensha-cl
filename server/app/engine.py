"""
봇 감독자(supervisor).

심볼별로 StrategyEngine 하나씩을 asyncio 태스크로 굴린다.
ccxt는 동기 라이브러리이므로 실제 호출은 to_thread로 밀어 이벤트 루프를
막지 않는다. 심볼마다 ccxt 클라이언트를 따로 두어 스레드 경합을 피한다.
"""
from __future__ import annotations

import asyncio
import time
import traceback
from typing import Any, Callable

from .exchange import BinanceFutures, ExchangeError
from .store import LEADER_OWNER, Store, describe_owner
from .strategy import StrategyConfig, StrategyEngine


class BotRunner:
    """심볼 1개의 실행 수명주기."""

    def __init__(self, supervisor: "Supervisor", config: StrategyConfig):
        self.sup = supervisor
        self.cfg = config
        self.symbol = config.symbol
        self.task: asyncio.Task | None = None
        self.engine: StrategyEngine | None = None
        self.running = False
        self.last_error: str | None = None
        self.last_result: dict[str, Any] | None = None
        self.consecutive_errors = 0

    def _emit(self, level: str, message: str, data: dict[str, Any]) -> None:
        self.sup.publish(level, message, self.symbol, data)

    async def start(self, restore: dict[str, Any] | None = None) -> dict[str, Any]:
        if self.running:
            return {"ok": True, "message": "이미 실행 중입니다."}

        exchange = self.sup.new_exchange()
        self.engine = StrategyEngine(exchange, self.cfg, self._emit, restore)
        self.symbol = self.engine.symbol
        self.cfg = self.engine.cfg

        # [요구사항 1] 시작 시 반드시 거래소에 적용하고 검증 결과를 남긴다
        settings = await asyncio.to_thread(self.engine.sync_exchange_settings)

        self.running = True
        self.task = asyncio.create_task(self._loop(), name=f"bot:{self.symbol}")
        self.sup.persist(self)
        return {"ok": True, "symbol": self.symbol, "settings": settings}

    async def stop(self, close_orders: bool = True) -> dict[str, Any]:
        self.running = False
        if self.task:
            self.task.cancel()
            try:
                await self.task
            except (asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
            self.task = None
        cancelled = 0
        if close_orders and self.engine and not self.cfg.dry_run:
            try:
                cancelled = await asyncio.to_thread(
                    self.engine.ex.cancel_all, self.symbol
                )
            except Exception as exc:  # noqa: BLE001
                self._emit("error", f"주문 취소 실패: {exc}", {})
        self._emit("info", f"{self.symbol} 봇 정지 (취소 주문 {cancelled}건)", {})
        self.sup.persist(self)
        return {"ok": True, "cancelledOrders": cancelled}

    async def panic(self) -> dict[str, Any]:
        """긴급 정지: 주문 전량 취소 + 포지션 시장가 청산."""
        await self.stop(close_orders=True)
        closed = None
        if self.engine and not self.cfg.dry_run:
            closed = await asyncio.to_thread(
                self.engine.ex.close_position_market, self.symbol
            )
        self._emit("error", f"{self.symbol} 긴급 청산 실행", {"closed": bool(closed)})
        return {"ok": True, "closed": bool(closed)}

    async def _loop(self) -> None:
        assert self.engine is not None
        self._emit("info", f"{self.symbol} 봇 시작 "
                           f"({', '.join(self.cfg.timeframes)})", {})
        persist_counter = 0
        while self.running:
            started = time.monotonic()
            try:
                result = await asyncio.to_thread(self.engine.tick)
                self.last_result = result
                self.last_error = None
                self.consecutive_errors = 0
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                self.consecutive_errors += 1
                self.last_error = str(exc)
                self._emit("error", f"틱 실패({self.consecutive_errors}회): {exc}",
                           {"trace": traceback.format_exc(limit=3)})
                # 연속 실패 시 백오프 (무료 서버 + 거래소 레이트리밋 보호)
                await asyncio.sleep(min(300, 10 * self.consecutive_errors))

            # 상태 저장이나 스케줄링이 실패해도 봇은 계속 살아 있어야 한다.
            # 이 구간이 보호되지 않으면 태스크가 조용히 죽어 매매가 멈춘다.
            try:
                persist_counter += 1
                if persist_counter % 10 == 0:
                    self.sup.persist(self)
            except Exception as exc:  # noqa: BLE001
                self._emit("warn", f"상태 저장 실패(무시하고 계속): {exc}", {})

            elapsed = time.monotonic() - started
            await asyncio.sleep(max(1.0, self.cfg.poll_seconds - elapsed))

    def status(self) -> dict[str, Any]:
        base: dict[str, Any] = {
            "symbol": self.symbol,
            "running": self.running,
            "lastError": self.last_error,
            "consecutiveErrors": self.consecutive_errors,
            "config": self.cfg.to_dict(),
        }
        if self.engine:
            base.update(self.engine.status())
        if self.last_result:
            base["live"] = {
                k: self.last_result.get(k)
                for k in ("balance", "price", "positionSize", "entryPrice",
                          "unrealizedPnl", "liquidationPrice", "rsi",
                          "buyCounts", "tp")
            }
        return base


class Supervisor:
    """전체 봇 + 자격증명 + 이벤트 브로드캐스트 관리."""

    SNAPSHOT_INTERVAL = 300   # 5분. 하루 288건, 2년치가 수 MB 수준이다.

    def __init__(self, store: Store):
        self.store = store
        self.bots: dict[str, BotRunner] = {}
        self._subscribers: set[Callable[[dict[str, Any]], None]] = set()
        self._symbol_cache: list[dict[str, Any]] | None = None
        self._symbol_cache_at = 0.0
        self._snapshot_task: asyncio.Task | None = None
        self.last_snapshot: dict[str, Any] | None = None

    # ------------------------------------------------------- 자격증명 [7]

    def set_credentials(self, api_key: str, api_secret: str,
                        testnet: bool = False) -> None:
        """
        팔로워가 쓰고 있는 키는 리더로 등록할 수 없다.

        같은 계정을 리더 봇과 카피 엔진이 함께 잡으면, 봇이 산 것을 카피가
        또 따라 사서 의도한 금액의 두 배가 나간다.
        """
        holder = self.store.credential_owner(api_key)
        if holder and holder != LEADER_OWNER:
            raise ExchangeError(
                f"이 API 키는 이미 {describe_owner(holder)}에 등록되어 있습니다. "
                "리더 봇과 카피가 같은 바이낸스 계정을 함께 잡으면 주문이 "
                "두 번 나갑니다. [더보기 > 팔로워 관리]에서 해당 계정을 "
                "정리한 뒤 다시 시도하세요."
            )
        self.store.put_secret("binance_api_key", api_key)
        self.store.put_secret("binance_api_secret", api_secret)
        self.store.put("binance_testnet", bool(testnet))
        self.store.claim_credential(api_key, LEADER_OWNER)
        self._symbol_cache = None
        self.start_snapshots()

    def clear_credentials(self) -> None:
        self.store.release_credential(LEADER_OWNER)
        self.store.delete_secret("binance_api_key")
        self.store.delete_secret("binance_api_secret")

    def has_credentials(self) -> bool:
        return (self.store.has_secret("binance_api_key")
                and self.store.has_secret("binance_api_secret"))

    def credential_info(self) -> dict[str, Any]:
        from .store import mask

        key = self.store.get_secret("binance_api_key")
        return {
            "configured": self.has_credentials(),
            "apiKeyMasked": mask(key),
            "testnet": bool(self.store.get("binance_testnet", False)),
        }

    def new_exchange(self) -> BinanceFutures:
        key = self.store.get_secret("binance_api_key")
        secret = self.store.get_secret("binance_api_secret")
        if not key or not secret:
            raise ExchangeError(
                "API 키가 설정되지 않았습니다. 앱의 설정 화면에서 먼저 입력하세요."
            )
        return BinanceFutures(
            key, secret, testnet=bool(self.store.get("binance_testnet", False))
        )

    # ------------------------------------------------------------ 이벤트

    def subscribe(self, callback: Callable[[dict[str, Any]], None]) -> None:
        self._subscribers.add(callback)

    def unsubscribe(self, callback: Callable[[dict[str, Any]], None]) -> None:
        self._subscribers.discard(callback)

    def publish(self, level: str, message: str, symbol: str | None = None,
                data: dict[str, Any] | None = None) -> None:
        event = self.store.add_event(level, message, symbol, data)
        for callback in list(self._subscribers):
            try:
                callback(event)
            except Exception:  # noqa: BLE001 - 구독자 하나가 죽어도 계속
                self._subscribers.discard(callback)

    # -------------------------------------------------------------- 심볼

    async def symbols(self, force: bool = False) -> list[dict[str, Any]]:
        """[요구사항 3] 전 종목 목록. 10분 캐시."""
        if (not force and self._symbol_cache
                and time.time() - self._symbol_cache_at < 600):
            return self._symbol_cache
        exchange = self.new_exchange()
        data = await asyncio.to_thread(exchange.list_symbols)
        self._symbol_cache = data
        self._symbol_cache_at = time.time()
        return data

    # ---------------------------------------------------------------- 봇

    def persist(self, runner: BotRunner) -> None:
        state = runner.engine.snapshot() if runner.engine else None
        self.store.save_bot(runner.symbol, runner.cfg.to_dict(),
                            state, runner.running)

    async def start_bot(self, config: StrategyConfig) -> dict[str, Any]:
        exchange = self.new_exchange()
        symbol = await asyncio.to_thread(exchange.normalize, config.symbol)
        config.symbol = symbol

        existing = self.bots.get(symbol)
        restore = None
        if existing:
            if existing.running:
                await existing.stop(close_orders=False)
            restore = existing.engine.snapshot() if existing.engine else None
        else:
            saved = next((b for b in self.store.load_bots()
                          if b["symbol"] == symbol), None)
            restore = saved["state"] if saved else None

        runner = BotRunner(self, config)
        self.bots[symbol] = runner
        return await runner.start(restore)

    async def stop_bot(self, symbol: str) -> dict[str, Any]:
        runner = self._require(symbol)
        return await runner.stop()

    async def panic_bot(self, symbol: str) -> dict[str, Any]:
        runner = self._require(symbol)
        return await runner.panic()

    async def panic_all(self) -> dict[str, Any]:
        results = {}
        for symbol in list(self.bots):
            try:
                results[symbol] = await self.bots[symbol].panic()
            except Exception as exc:  # noqa: BLE001
                results[symbol] = {"ok": False, "error": str(exc)}
        return results

    def remove_bot(self, symbol: str) -> None:
        self.bots.pop(symbol, None)
        self.store.delete_bot(symbol)

    def _require(self, symbol: str) -> BotRunner:
        for key, runner in self.bots.items():
            if symbol in (key, runner.symbol) or symbol.upper() in key.upper():
                return runner
        raise ExchangeError(f"{symbol} 봇을 찾을 수 없습니다.")

    def status_all(self) -> list[dict[str, Any]]:
        return [r.status() for r in self.bots.values()]

    def status_one(self, symbol: str) -> dict[str, Any]:
        return self._require(symbol).status()

    # ------------------------------------------------------- 잔고 스냅샷

    async def snapshot_once(self) -> dict[str, Any] | None:
        """계좌 스냅샷 1건을 찍어 기록한다. 봇 실행 여부와 무관하다."""
        if not self.has_credentials():
            return None
        try:
            exchange = self.new_exchange()
            summary = await asyncio.to_thread(exchange.account_summary)
        except Exception as exc:  # noqa: BLE001 - 일시적 장애로 루프가 죽으면 안 된다
            log_data = {"error": str(exc)}
            self.publish("warn", f"잔고 스냅샷 실패: {exc}", None, log_data)
            return None
        self.store.add_balance_point(summary)
        self.last_snapshot = summary
        return summary

    async def _snapshot_loop(self) -> None:
        """
        봇이 멈춰 있어도 잔고 곡선은 계속 이어져야 하므로
        봇 태스크와 분리해서 돌린다.
        """
        prune_counter = 0
        while True:
            try:
                await self.snapshot_once()
                prune_counter += 1
                if prune_counter % 288 == 0:      # 하루에 한 번 정리
                    await asyncio.to_thread(self.store.prune_balance_history)
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001
                pass
            await asyncio.sleep(self.SNAPSHOT_INTERVAL)

    def start_snapshots(self) -> None:
        if self._snapshot_task is None or self._snapshot_task.done():
            self._snapshot_task = asyncio.create_task(
                self._snapshot_loop(), name="balance-snapshots"
            )

    # -------------------------------------------------------------- 부팅

    async def restore_on_boot(self) -> None:
        """서버 재시작 후 켜져 있던 봇을 자동 복구."""
        if not self.has_credentials():
            return
        self.start_snapshots()
        for saved in self.store.load_bots():
            if not saved["enabled"]:
                continue
            try:
                config = StrategyConfig(**saved["config"])
                await self.start_bot(config)
                self.publish("info", f"{saved['symbol']} 봇 자동 복구됨",
                             saved["symbol"])
            except Exception as exc:  # noqa: BLE001
                self.publish("error",
                             f"{saved['symbol']} 자동 복구 실패: {exc}",
                             saved["symbol"])

    async def shutdown(self) -> None:
        if self._snapshot_task:
            self._snapshot_task.cancel()
        for runner in list(self.bots.values()):
            runner.running = False
            if runner.task:
                runner.task.cancel()
