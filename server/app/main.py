"""
elemensha 봇 서버 (FastAPI).

안드로이드 앱이 이 서버를 원격 조종한다.
휴대폰이 꺼져 있어도 봇은 서버에서 계속 돈다.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import secrets
import time
from typing import Any

import httpx
from fastapi import (Depends, FastAPI, Header, HTTPException, Query,
                     WebSocket, WebSocketDisconnect)
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from . import copy_api
from .config import settings
from .copy import CopyManager
from .engine import Supervisor
from .exchange import ExchangeError
from .store import BALANCE_PERIODS, Store
from .strategy import (ALL_TIMEFRAMES, DEFAULT_TIMEFRAMES, EntryTrigger,
                       StrategyConfig)

logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s %(levelname)-5s %(message)s",
)
log = logging.getLogger("elemensha")

APP_VERSION = "1.0.0"
APP_VERSION_CODE = 1

store = Store(settings.data_dir)
supervisor = Supervisor(store)
copy_manager = CopyManager(store, supervisor)
copy_api.init(store, copy_manager)


# --------------------------------------------------------------------- 인증

def require_token(authorization: str = Header(default="")) -> str:
    token = authorization.removeprefix("Bearer ").strip()
    if not store.verify_token(token):
        raise HTTPException(401, "인증 실패 - 앱을 다시 페어링하세요.")
    return token


def boot_pairing_code() -> str:
    code = settings.pairing_code or store.get("pairing_code")
    if not code:
        code = secrets.token_hex(4).upper()
        store.put("pairing_code", code)
    return code


# ------------------------------------------------------------------ 스키마

class PairRequest(BaseModel):
    code: str
    label: str = "android"


class CredentialsRequest(BaseModel):
    """[요구사항 7] 앱에서 키/시크릿을 직접 입력받는다."""
    apiKey: str = Field(min_length=8)
    apiSecret: str = Field(min_length=8)
    testnet: bool = False


class ExchangeSettingsRequest(BaseModel):
    """[요구사항 1] 레버리지 / 마진모드."""
    symbol: str
    leverage: int = Field(ge=1, le=125)
    marginMode: str = "ISOLATED"


class BotConfigRequest(BaseModel):
    """앱에서 조절 가능한 파라미터 전부. 기본값 = 원본 코드와 동일."""

    symbol: str = "BTC/USDT:USDT"

    # 거래소 설정 — 앱에서 바꾸면 바이낸스에 적용되고 검증 결과가 돌아온다
    leverage: int = Field(default=1, ge=1, le=125)
    marginMode: str = "ISOLATED"

    # 신호
    timeframes: list[str] = Field(default_factory=lambda: list(DEFAULT_TIMEFRAMES))
    # True면 timeframes를 무시하고 바이낸스 기본 15종 전체를 적용한다
    useAllTimeframes: bool = False
    rsiPeriod: int = Field(default=14, ge=2, le=100)
    rsiLower: float = Field(default=30.0, ge=0, lt=100)
    rsiUpper: float = Field(default=70.0, gt=0, le=100)
    entryTrigger: str = EntryTrigger.CROSS_UP_LOWER

    # 사이징
    walletPercentage: float = Field(default=0.001, gt=0, le=1)
    minNotionalRoundUp: float = Field(default=10.0, ge=0)
    maxAdditionalBuys: int | None = Field(default=None, ge=1)  # None = 무제한

    # 청산 (익절 전용 — 손절/숏/RSI상단청산 없음)
    takeProfitPercent: float = Field(default=0.01, gt=0, le=1)

    maxPositionNotional: float | None = None
    pollSeconds: int = Field(default=20, ge=5, le=600)
    dryRun: bool = False

    def to_strategy(self) -> StrategyConfig:
        return StrategyConfig(
            symbol=self.symbol,
            leverage=self.leverage,
            margin_mode=self.marginMode,
            timeframes=(list(ALL_TIMEFRAMES) if self.useAllTimeframes
                        else self.timeframes),
            rsi_period=self.rsiPeriod,
            rsi_lower=self.rsiLower,
            rsi_upper=self.rsiUpper,
            entry_trigger=self.entryTrigger,
            wallet_percentage=self.walletPercentage,
            min_notional_round_up=self.minNotionalRoundUp,
            max_additional_buys=self.maxAdditionalBuys,
            take_profit_percent=self.takeProfitPercent,
            max_position_notional=self.maxPositionNotional,
            poll_seconds=self.pollSeconds,
            dry_run=self.dryRun,
        )


# --------------------------------------------------------------------- 앱

@contextlib.asynccontextmanager
async def lifespan(_: FastAPI):
    code = boot_pairing_code()
    log.info("=" * 58)
    log.info(" elemensha server v%s", APP_VERSION)
    log.info(" 페어링 코드: %s   (앱 최초 연결 시 1회 입력)", code)
    log.info(" 팔로워 계정: %d개", len(store.load_followers()))
    log.info("=" * 58)
    await supervisor.restore_on_boot()
    copy_manager.attach()
    await copy_manager.restore_on_boot()
    yield
    await copy_manager.shutdown()
    await supervisor.shutdown()


app = FastAPI(title="elemensha", version=APP_VERSION, lifespan=lifespan)
app.add_middleware(
    CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"]
)
app.include_router(copy_api.router)


@app.exception_handler(ExchangeError)
async def exchange_error_handler(_, exc: ExchangeError):
    from fastapi.responses import JSONResponse
    return JSONResponse(status_code=400, content={"detail": str(exc)})


# ------------------------------------------------------------ 공개 엔드포인트

@app.get("/api/health")
async def health() -> dict[str, Any]:
    return {
        "ok": True,
        "version": APP_VERSION,
        "credentialsConfigured": supervisor.has_credentials(),
        "bots": len(supervisor.bots),
        "running": sum(1 for b in supervisor.bots.values() if b.running),
        "followers": len(store.load_followers()),
        "followersRunning": sum(1 for r in copy_manager.runners.values()
                                if r.running),
        "serverTime": int(time.time() * 1000),
    }


# 한 릴리스에 APK 가 둘 올라간다. 이름으로 갈라야 팔로워 앱이 리더 앱을
# 덮어쓰는 사고가 안 난다.
#   리더   : elemensha-1.2.3.apk
#   팔로워 : elemensha-copy-1.2.3.apk
COPY_APK_MARKER = "-copy-"


async def _release_info(want_copy: bool) -> dict[str, Any]:
    """GitHub 최신 릴리스에서 이 앱에 맞는 APK 하나를 골라 온다."""
    fallback = {
        "versionName": APP_VERSION,
        "versionCode": APP_VERSION_CODE,
        "apkUrl": None,
        "notes": "",
        "source": "server",
    }
    if not settings.release_api:
        return fallback
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.get(
                settings.release_api,
                headers={"Accept": "application/vnd.github+json"},
            )
            resp.raise_for_status()
            data = resp.json()
    except Exception as exc:  # noqa: BLE001
        log.warning("릴리스 조회 실패: %s", exc)
        return fallback

    def matches(name: str) -> bool:
        if not name.endswith(".apk"):
            return False
        return (COPY_APK_MARKER in name) if want_copy else (
            COPY_APK_MARKER not in name)

    apk = next((a for a in data.get("assets", [])
                if matches(a.get("name", ""))), None)
    tag = str(data.get("tag_name", "")).lstrip("v")
    return {
        "versionName": tag or APP_VERSION,
        "versionCode": _version_code(tag),
        "apkUrl": apk.get("browser_download_url") if apk else None,
        "apkSize": apk.get("size") if apk else None,
        "notes": data.get("body", "") or "",
        "publishedAt": data.get("published_at"),
        "source": "github",
    }


@app.get("/api/app/version")
async def app_version() -> dict[str, Any]:
    """
    리더 앱 인앱 업데이트용. APK는 GitHub Releases(무료·무제한)에 호스팅하고
    서버는 그 최신 릴리스를 중계한다. 서버가 죽어도 앱은 GitHub을 직접 볼 수 있다.
    """
    return await _release_info(want_copy=False)


@app.get("/api/copy/app/version")
async def copy_app_version() -> dict[str, Any]:
    """팔로워 앱 인앱 업데이트용. copy 표시가 붙은 APK 만 고른다."""
    return await _release_info(want_copy=True)


@app.get("/api/meta")
async def meta() -> dict[str, Any]:
    """앱의 파라미터 선택지를 서버가 알려준다 (앱 하드코딩 방지)."""
    return {
        "timeframes": [
            {"value": tf, "default": tf in DEFAULT_TIMEFRAMES}
            for tf in ALL_TIMEFRAMES
        ],
        "allTimeframes": list(ALL_TIMEFRAMES),
        "entryTriggers": [
            {"value": key, "label": EntryTrigger.LABELS[key]}
            for key in EntryTrigger.ALL
        ],
        "marginModes": [
            {"value": "ISOLATED", "label": "격리 (ISOLATED)"},
            {"value": "CROSSED", "label": "교차 (CROSS)"},
        ],
        "defaults": BotConfigRequest().model_dump(),
        "notes": {
            "closedCandleOnly": True,
            "longOnly": True,
            "stopLoss": False,
            "rsiExit": False,
        },
    }


@app.post("/api/pair")
async def pair(req: PairRequest) -> dict[str, Any]:
    expected = boot_pairing_code()
    if not secrets.compare_digest(req.code.strip().upper(), expected.upper()):
        raise HTTPException(403, "페어링 코드가 올바르지 않습니다.")
    token = store.issue_token(req.label)
    supervisor.publish("info", f"새 기기 연결됨: {req.label}")
    return {"token": token, "serverVersion": APP_VERSION}


# --------------------------------------------------- 자격증명 [요구사항 7]

@app.get("/api/credentials", dependencies=[Depends(require_token)])
async def get_credentials() -> dict[str, Any]:
    return supervisor.credential_info()


@app.post("/api/credentials", dependencies=[Depends(require_token)])
async def set_credentials(req: CredentialsRequest) -> dict[str, Any]:
    supervisor.set_credentials(req.apiKey.strip(), req.apiSecret.strip(),
                               req.testnet)
    exchange = supervisor.new_exchange()
    try:
        info = await asyncio.to_thread(exchange.verify_credentials)
    except ExchangeError:
        supervisor.clear_credentials()
        raise
    supervisor.publish("info",
                       f"API 키 등록 완료 (잔고 {info['usdtBalance']:,.2f} USDT)")
    return {**supervisor.credential_info(), **info}


@app.delete("/api/credentials", dependencies=[Depends(require_token)])
async def delete_credentials() -> dict[str, Any]:
    await supervisor.panic_all()
    supervisor.clear_credentials()
    supervisor.publish("warn", "API 키 삭제됨 - 전체 봇 정지")
    return {"ok": True}


# --------------------------------------------------------- 심볼 [요구사항 3]

@app.get("/api/symbols", dependencies=[Depends(require_token)])
async def symbols(refresh: bool = Query(False)) -> dict[str, Any]:
    data = await supervisor.symbols(force=refresh)
    return {"count": len(data), "symbols": data}


# ------------------------------------------- 레버리지/마진모드 [요구사항 1]

@app.get("/api/exchange-settings", dependencies=[Depends(require_token)])
async def read_exchange_settings(symbol: str) -> dict[str, Any]:
    exchange = supervisor.new_exchange()
    info = await asyncio.to_thread(exchange.read_position_risk, symbol)
    spec = await asyncio.to_thread(exchange.spec, symbol)
    price = await asyncio.to_thread(exchange.last_price, symbol)
    return {
        "symbol": spec.symbol,
        "leverage": info.get("leverage"),
        "marginMode": info.get("marginMode"),
        "maxLeverage": spec.max_leverage,
        "price": price,
        "minNotional": spec.min_notional,
        "minQty": spec.min_qty,
        "effectiveMinNotional": spec.effective_min_notional(price),
        "qtyBound": bool(spec.min_qty * price >= spec.min_notional),
    }


@app.post("/api/exchange-settings", dependencies=[Depends(require_token)])
async def apply_exchange_settings(req: ExchangeSettingsRequest) -> dict[str, Any]:
    """설정을 바이낸스에 적용하고, 되읽어서 반영 여부를 그대로 돌려준다."""
    exchange = supervisor.new_exchange()
    leverage = await asyncio.to_thread(
        exchange.apply_leverage, req.symbol, req.leverage
    )
    margin = await asyncio.to_thread(
        exchange.apply_margin_mode, req.symbol, req.marginMode
    )
    for result in (leverage, margin):
        supervisor.publish("info" if result["verified"] else "error",
                           result["message"], result["symbol"], result)
    return {
        "leverage": leverage,
        "marginMode": margin,
        "allVerified": leverage["verified"] and margin["verified"],
    }


# ---------------------------------------------------------------------- 봇

@app.get("/api/bots", dependencies=[Depends(require_token)])
async def list_bots() -> dict[str, Any]:
    return {"bots": supervisor.status_all()}


@app.get("/api/bots/{symbol:path}/status", dependencies=[Depends(require_token)])
async def bot_status(symbol: str) -> dict[str, Any]:
    return supervisor.status_one(symbol)


@app.post("/api/bots/start", dependencies=[Depends(require_token)])
async def start_bot(req: BotConfigRequest) -> dict[str, Any]:
    try:
        config = req.to_strategy()
        config.validate()
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    return await supervisor.start_bot(config)


@app.post("/api/bots/{symbol:path}/stop", dependencies=[Depends(require_token)])
async def stop_bot(symbol: str) -> dict[str, Any]:
    return await supervisor.stop_bot(symbol)


@app.post("/api/bots/{symbol:path}/panic", dependencies=[Depends(require_token)])
async def panic_bot(symbol: str) -> dict[str, Any]:
    return await supervisor.panic_bot(symbol)


@app.delete("/api/bots/{symbol:path}", dependencies=[Depends(require_token)])
async def delete_bot(symbol: str) -> dict[str, Any]:
    with contextlib.suppress(ExchangeError):
        await supervisor.stop_bot(symbol)
    supervisor.remove_bot(symbol)
    return {"ok": True}


@app.post("/api/panic-all", dependencies=[Depends(require_token)])
async def panic_all() -> dict[str, Any]:
    return {"results": await supervisor.panic_all()}


# ------------------------------------------------------------ 잔고 그래프

# 기간 정의는 store.BALANCE_PERIODS 하나로 통일한다
# (팔로워 그래프도 같은 눈금을 써야 비교가 된다).


@app.get("/api/balance/history", dependencies=[Depends(require_token)])
async def balance_history(period: str = Query("week")) -> dict[str, Any]:
    """잔고·순자산 시계열. 앱의 그래프가 이걸 그린다."""
    if period not in BALANCE_PERIODS:
        raise HTTPException(
            400, f"period 는 {', '.join(BALANCE_PERIODS)} 중 하나여야 합니다."
        )
    window, bucket, label = BALANCE_PERIODS[period]
    since = time.time() - window
    points = await asyncio.to_thread(store.balance_series, since, bucket)
    meta = store.balance_range()

    summary: dict[str, Any] = {
        "startEquity": None, "endEquity": None,
        "change": None, "changePercent": None,
        "minEquity": None, "maxEquity": None,
    }
    if points:
        first, last = points[0], points[-1]
        start, end = first["equity"], last["equity"]
        summary.update({
            "startEquity": start,
            "endEquity": end,
            "change": end - start,
            "changePercent": ((end - start) / start * 100.0) if start else None,
            "minEquity": min(p["low"] for p in points),
            "maxEquity": max(p["high"] for p in points),
            "wallet": last["wallet"],
            "unrealizedPnl": last["unrealizedPnl"],
            "positionNotional": last["positionNotional"],
            "openPositions": last["openPositions"],
        })

    return {
        "period": period,
        "label": label,
        "bucketSeconds": bucket,
        "points": points,
        "summary": summary,
        "recording": {
            "firstTs": meta["firstTs"],
            "totalSamples": meta["count"],
            "intervalSeconds": supervisor.SNAPSHOT_INTERVAL,
        },
    }


@app.post("/api/balance/snapshot", dependencies=[Depends(require_token)])
async def balance_snapshot_now() -> dict[str, Any]:
    """지금 즉시 한 점 기록한다. 앱에서 당겨서 새로고침할 때 쓴다."""
    snapshot = await supervisor.snapshot_once()
    if snapshot is None:
        raise HTTPException(400, "API 키가 없거나 거래소 조회에 실패했습니다.")
    return snapshot


@app.get("/api/balance/periods", dependencies=[Depends(require_token)])
async def balance_periods() -> dict[str, Any]:
    return {
        "periods": [
            {"value": k, "label": v[2], "windowSeconds": v[0],
             "bucketSeconds": v[1]}
            for k, v in BALANCE_PERIODS.items()
        ]
    }


# ------------------------------------------------- 팔로워 관리 (리더 전용)
#
# 여기 있는 엔드포인트는 전부 require_token 을 지난다 = 리더 기기만 호출할 수
# 있다. 팔로워 토큰은 store.verify_token 에서 걸러지므로 팔로워가 다른
# 팔로워를 들여다볼 수 없다.

class InviteRequest(BaseModel):
    label: str = ""
    maxUses: int = Field(default=1, ge=1, le=1000)
    ttlHours: float | None = Field(default=None, gt=0)


@app.get("/api/invites", dependencies=[Depends(require_token)])
async def list_invites() -> dict[str, Any]:
    return {"invites": store.list_invites()}


@app.post("/api/invites", dependencies=[Depends(require_token)])
async def create_invite(req: InviteRequest) -> dict[str, Any]:
    """팔로워를 초대할 1회용 코드를 만든다. 이 코드로 앱이 가입한다."""
    invite = store.create_invite(
        req.label.strip(), req.maxUses,
        req.ttlHours * 3600 if req.ttlHours else None,
    )
    supervisor.publish("info", f"초대코드 발급: {invite['code']}")
    return invite


@app.delete("/api/invites/{code}", dependencies=[Depends(require_token)])
async def delete_invite(code: str) -> dict[str, Any]:
    store.delete_invite(code)
    return {"ok": True}


@app.get("/api/followers", dependencies=[Depends(require_token)])
async def list_followers() -> dict[str, Any]:
    """팔로워 목록. API 키는 마스킹된 것만 나온다."""
    return {"followers": copy_manager.status_all()}


@app.post("/api/followers/{follower_id}/stop", dependencies=[Depends(require_token)])
async def stop_follower(follower_id: int) -> dict[str, Any]:
    """리더가 팔로워의 카피를 정지시킨다. 포지션은 건드리지 않는다."""
    runner = copy_manager.get(follower_id)
    result = await runner.stop()
    copy_manager.publish(follower_id, "warn", "서버 관리자가 카피를 정지했습니다.")
    return result


@app.delete("/api/followers/{follower_id}", dependencies=[Depends(require_token)])
async def delete_follower(follower_id: int) -> dict[str, Any]:
    """
    팔로워 계정 삭제 — 키·기기·로그·잔고기록이 전부 지워진다.

    포지션은 청산하지 않는다. 남의 계좌를 리더가 임의로 정리해서는 안 된다.
    """
    await copy_manager.delete(follower_id)
    supervisor.publish("warn", f"팔로워 #{follower_id} 삭제됨")
    return {"ok": True}


# ------------------------------------------------------------------ 이벤트

@app.get("/api/events", dependencies=[Depends(require_token)])
async def events(limit: int = Query(200, ge=1, le=2000),
                 symbol: str | None = None,
                 level: str | None = None) -> dict[str, Any]:
    return {"events": store.recent_events(limit, symbol, level)}


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket, token: str = Query("")):
    if not store.verify_token(token):
        await websocket.close(code=4401)
        return
    await websocket.accept()

    loop = asyncio.get_running_loop()
    queue: asyncio.Queue = asyncio.Queue(maxsize=500)

    def on_event(event: dict[str, Any]) -> None:
        loop.call_soon_threadsafe(_offer, queue, event)

    supervisor.subscribe(on_event)
    try:
        await websocket.send_text(json.dumps(
            {"type": "hello", "version": APP_VERSION,
             "bots": supervisor.status_all()},
            ensure_ascii=False, default=str,
        ))
        while True:
            try:
                event = await asyncio.wait_for(queue.get(), timeout=15)
                payload = {"type": "event", "event": event}
            except asyncio.TimeoutError:
                payload = {"type": "status", "bots": supervisor.status_all()}
            await websocket.send_text(
                json.dumps(payload, ensure_ascii=False, default=str)
            )
    except WebSocketDisconnect:
        pass
    except Exception as exc:  # noqa: BLE001
        log.debug("ws 종료: %s", exc)
    finally:
        supervisor.unsubscribe(on_event)


def _offer(queue: asyncio.Queue, item: Any) -> None:
    with contextlib.suppress(asyncio.QueueFull):
        queue.put_nowait(item)


def _version_code(tag: str) -> int:
    """'1.2.3' -> 10203"""
    parts = (tag.split(".") + ["0", "0"])[:3]
    try:
        major, minor, patch = (int("".join(c for c in p if c.isdigit()) or 0)
                               for p in parts)
        return major * 10000 + minor * 100 + patch
    except ValueError:
        return APP_VERSION_CODE


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=settings.host, port=settings.port,
                log_level=settings.log_level)
