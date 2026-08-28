"""
카피 트레이딩 API — 팔로워 앱이 쓰는 엔드포인트.

경계 규칙(이 파일의 존재 이유):
    팔로워 토큰으로는 리더의 어떤 것도 볼 수 없다. 반대로 리더 토큰으로는
    팔로워의 계좌를 볼 수 없고 관리(가입 승인·정지·삭제)만 할 수 있다.
    모든 조회는 토큰에서 뽑아낸 follower_id 로 범위가 강제되며, 경로나
    본문으로 받은 id 를 신뢰하지 않는다.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import time
from typing import Any

from fastapi import (APIRouter, Depends, Header, HTTPException, Query,
                     WebSocket, WebSocketDisconnect)
from pydantic import BaseModel, Field

from .copy import BelowMinimum, CopyManager, FollowerConfig, SizingMode
from .exchange import ExchangeError
from .store import BALANCE_PERIODS, Store

router = APIRouter()

_store: Store | None = None
_manager: CopyManager | None = None


def init(store: Store, manager: CopyManager) -> None:
    """main.py 가 앱을 만들 때 1회 호출한다."""
    global _store, _manager
    _store, _manager = store, manager


def store() -> Store:
    assert _store is not None, "copy_api.init() 이 호출되지 않았습니다."
    return _store


def manager() -> CopyManager:
    assert _manager is not None, "copy_api.init() 이 호출되지 않았습니다."
    return _manager


# ------------------------------------------------------------------- 인증

def require_follower(authorization: str = Header(default="")) -> int:
    """팔로워 토큰만 통과. 리더 토큰으로는 팔로워 계좌에 접근할 수 없다."""
    token = authorization.removeprefix("Bearer ").strip()
    info = store().resolve_token(token)
    if not info or info["followerId"] is None:
        raise HTTPException(401, "인증 실패 - 앱을 다시 연결하세요.")
    follower_id = int(info["followerId"])
    if store().get_follower(follower_id) is None:
        raise HTTPException(401, "삭제된 계정입니다.")
    return follower_id


def follower_from_ws(token: str) -> int | None:
    info = store().resolve_token(token)
    if not info or info["followerId"] is None:
        return None
    return int(info["followerId"])


# ------------------------------------------------------------------ 스키마

class JoinRequest(BaseModel):
    code: str = Field(min_length=4)
    label: str = "android"


class CopyCredentialsRequest(BaseModel):
    apiKey: str = Field(min_length=8)
    apiSecret: str = Field(min_length=8)
    testnet: bool = False


class CopyConfigRequest(BaseModel):
    """앱에서 조절 가능한 카피 파라미터 전부."""

    # 주문 크기 방식 [사용자 요청: 앱에서 선택]
    sizingMode: str = SizingMode.EQUITY
    equityScale: float = Field(default=1.0, gt=0, le=100)
    multiplier: float = Field(default=1.0, gt=0, le=1000)
    fixedNotional: float = Field(default=20.0, gt=0)

    # 안전장치
    maxRatio: float | None = Field(default=None, gt=0)
    maxPositionNotional: float | None = Field(default=None, gt=0)
    belowMinimum: str = BelowMinimum.SKIP
    minNotionalRoundUp: float = Field(default=0.0, ge=0)

    # 거래소
    leverage: int = Field(default=1, ge=1, le=125)
    marginMode: str = "ISOLATED"

    symbols: list[str] = Field(default_factory=list)
    takeProfitPercent: float | None = Field(default=None, gt=0, le=1)
    pollSeconds: int = Field(default=15, ge=5, le=600)
    dryRun: bool = False

    def to_config(self) -> FollowerConfig:
        return FollowerConfig(
            sizing_mode=self.sizingMode,
            equity_scale=self.equityScale,
            multiplier=self.multiplier,
            fixed_notional=self.fixedNotional,
            max_ratio=self.maxRatio,
            max_position_notional=self.maxPositionNotional,
            below_minimum=self.belowMinimum,
            min_notional_round_up=self.minNotionalRoundUp,
            leverage=self.leverage,
            margin_mode=self.marginMode,
            symbols=self.symbols,
            take_profit_percent=self.takeProfitPercent,
            poll_seconds=self.pollSeconds,
            dry_run=self.dryRun,
        )

    @staticmethod
    def from_config(cfg: FollowerConfig) -> dict[str, Any]:
        return {
            "sizingMode": cfg.sizing_mode,
            "equityScale": cfg.equity_scale,
            "multiplier": cfg.multiplier,
            "fixedNotional": cfg.fixed_notional,
            "maxRatio": cfg.max_ratio,
            "maxPositionNotional": cfg.max_position_notional,
            "belowMinimum": cfg.below_minimum,
            "minNotionalRoundUp": cfg.min_notional_round_up,
            "leverage": cfg.leverage,
            "marginMode": cfg.margin_mode,
            "symbols": list(cfg.symbols),
            "takeProfitPercent": cfg.take_profit_percent,
            "pollSeconds": cfg.poll_seconds,
            "dryRun": cfg.dry_run,
        }


class InviteRequest(BaseModel):
    label: str = ""
    maxUses: int = Field(default=1, ge=1, le=1000)
    ttlHours: float | None = Field(default=None, gt=0)


# --------------------------------------------------------- 공개 엔드포인트

@router.get("/api/copy/meta")
async def copy_meta() -> dict[str, Any]:
    """앱의 선택지를 서버가 알려준다 (앱 하드코딩 방지)."""
    return {
        "sizingModes": [
            {"value": mode, "label": SizingMode.LABELS[mode],
             "description": SizingMode.DESCRIPTIONS[mode]}
            for mode in SizingMode.ALL
        ],
        "belowMinimumModes": [
            {"value": mode, "label": BelowMinimum.LABELS[mode]}
            for mode in BelowMinimum.ALL
        ],
        "marginModes": [
            {"value": "ISOLATED", "label": "격리 (ISOLATED)"},
            {"value": "CROSSED", "label": "교차 (CROSS)"},
        ],
        "balancePeriods": [
            {"value": key, "label": value[2], "windowSeconds": value[0],
             "bucketSeconds": value[1]}
            for key, value in BALANCE_PERIODS.items()
        ],
        "defaults": CopyConfigRequest().model_dump(),
        "notes": {
            "entriesMirrored": True,
            "exitIndependent": True,
            "stopLoss": False,
            "forceCloseOnLeaderExit": False,
        },
    }


@router.post("/api/copy/join")
async def join(req: JoinRequest) -> dict[str, Any]:
    """초대코드로 팔로워 계정을 만들고 기기 토큰을 받는다."""
    if not store().consume_invite(req.code):
        raise HTTPException(403, "초대코드가 올바르지 않거나 이미 사용되었습니다.")
    label = req.label.strip() or "follower"
    follower_id = manager().create(label)
    token = store().issue_token(label, follower_id=follower_id)
    manager().publish(follower_id, "info", f"기기 연결됨: {label}")
    return {"token": token, "followerId": follower_id, "label": label}


# ------------------------------------------------------------ 내 계정 정보

@router.get("/api/copy/me")
async def me(follower_id: int = Depends(require_follower)) -> dict[str, Any]:
    saved = store().get_follower(follower_id)
    assert saved is not None
    runner = manager().runners.get(follower_id)
    return {
        "followerId": follower_id,
        "label": saved["label"],
        "enabled": saved["enabled"],
        "running": bool(runner and runner.running),
        "createdAt": saved["createdAt"],
        "credentials": manager().credential_info(follower_id),
        "config": CopyConfigRequest.from_config(
            FollowerConfig(**saved["config"])
        ),
    }


@router.get("/api/copy/credentials")
async def get_credentials(
    follower_id: int = Depends(require_follower),
) -> dict[str, Any]:
    return manager().credential_info(follower_id)


@router.post("/api/copy/credentials")
async def set_credentials(
    req: CopyCredentialsRequest,
    follower_id: int = Depends(require_follower),
) -> dict[str, Any]:
    mgr = manager()
    mgr.set_credentials(follower_id, req.apiKey.strip(), req.apiSecret.strip(),
                        req.testnet)
    exchange = mgr.new_exchange(follower_id)
    try:
        info = await asyncio.to_thread(exchange.verify_credentials)
    except ExchangeError:
        mgr.clear_credentials(follower_id)
        raise
    mgr.publish(follower_id, "info",
                f"API 키 등록 완료 (잔고 {info['usdtBalance']:,.2f} USDT)")
    # 등록 직후 잔고 곡선의 첫 점을 찍어둔다 — 그래프가 빈 채로 보이지 않게
    with contextlib.suppress(Exception):
        await mgr.get(follower_id).snapshot_balance()
    return {**mgr.credential_info(follower_id), **info}


@router.delete("/api/copy/credentials")
async def delete_credentials(
    follower_id: int = Depends(require_follower),
) -> dict[str, Any]:
    mgr = manager()
    runner = mgr.runners.get(follower_id)
    if runner:
        await runner.stop()
    mgr.clear_credentials(follower_id)
    mgr.publish(follower_id, "warn", "API 키 삭제됨 - 카피 정지")
    return {"ok": True}


# ---------------------------------------------------------------- 설정

@router.get("/api/copy/config")
async def get_config(
    follower_id: int = Depends(require_follower),
) -> dict[str, Any]:
    saved = store().get_follower(follower_id)
    assert saved is not None
    return CopyConfigRequest.from_config(FollowerConfig(**saved["config"]))


@router.post("/api/copy/config")
async def set_config(
    req: CopyConfigRequest,
    follower_id: int = Depends(require_follower),
) -> dict[str, Any]:
    try:
        config = req.to_config()
        config.validate()
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    return await manager().update_config(follower_id, config)


# ------------------------------------------------------------ 시작/정지

@router.post("/api/copy/start")
async def start(follower_id: int = Depends(require_follower)) -> dict[str, Any]:
    mgr = manager()
    if not mgr.has_credentials(follower_id):
        raise HTTPException(400, "API 키를 먼저 등록하세요.")
    return await mgr.get(follower_id).start()


@router.post("/api/copy/stop")
async def stop(follower_id: int = Depends(require_follower)) -> dict[str, Any]:
    return await manager().get(follower_id).stop()


@router.post("/api/copy/panic")
async def panic(follower_id: int = Depends(require_follower)) -> dict[str, Any]:
    """내 포지션만 전량 시장가 청산. 리더와 다른 팔로워는 영향받지 않는다."""
    return await manager().get(follower_id).panic()


# ---------------------------------------------------------------- 상태

@router.get("/api/copy/status")
async def status(follower_id: int = Depends(require_follower)) -> dict[str, Any]:
    mgr = manager()
    saved = store().get_follower(follower_id)
    assert saved is not None
    runner = mgr.runners.get(follower_id)
    if runner is None:
        # 아직 한 번도 시작 안 한 계정. 빈 상태라도 형태는 같아야 앱이 단순해진다
        return {
            "followerId": follower_id,
            "label": saved["label"],
            "running": False,
            "config": FollowerConfig(**saved["config"]).to_dict(),
            "sizingLabel": SizingMode.LABELS.get(
                saved["config"].get("sizing_mode", ""), ""),
            "account": {},
            "symbols": [],
            "leaderSymbols": _leader_symbols(),
            "credentialsConfigured": mgr.has_credentials(follower_id),
        }
    return {
        **runner.status(),
        "leaderSymbols": _leader_symbols(),
        "credentialsConfigured": mgr.has_credentials(follower_id),
    }


def _leader_symbols() -> list[dict[str, Any]]:
    """팔로워가 골라도 되는 종목 목록. 리더의 금액 정보는 담기지 않는다."""
    return [
        {"symbol": symbol, **info}
        for symbol, info in sorted(manager().leader_view().items())
    ]


@router.get("/api/copy/account")
async def account(follower_id: int = Depends(require_follower)) -> dict[str, Any]:
    """
    내 계좌 현황을 거래소에서 즉시 조회한다.

    [사용자 요청] 카피 앱도 본인 계정 자산을 알 수 있어야 한다.
    저장된 스냅샷이 아니라 지금 이 순간의 값을 준다.
    """
    mgr = manager()
    if not mgr.has_credentials(follower_id):
        raise HTTPException(400, "API 키를 먼저 등록하세요.")
    exchange = mgr.new_exchange(follower_id)
    summary = await asyncio.to_thread(exchange.account_summary)
    store().add_follower_balance_point(follower_id, summary)
    runner = mgr.runners.get(follower_id)
    if runner:
        runner.last_snapshot = summary
    return summary


@router.get("/api/copy/orders")
async def orders(follower_id: int = Depends(require_follower)) -> dict[str, Any]:
    """
    내 미체결 지정가 주문.

    [사용자 요청] 지정가 주문 상태를 알아야 한다. 서버가 기억하는 값이 아니라
    거래소에 직접 물어본 결과를 돌려준다.
    """
    mgr = manager()
    if not mgr.has_credentials(follower_id):
        raise HTTPException(400, "API 키를 먼저 등록하세요.")
    runner = mgr.get(follower_id)
    if runner.ex is None:
        runner.ex = mgr.new_exchange(follower_id)
    rows = await runner.open_orders()
    return {"count": len([r for r in rows if not r.get("error")]), "orders": rows}


@router.get("/api/copy/positions")
async def positions(
    follower_id: int = Depends(require_follower),
) -> dict[str, Any]:
    """내 포지션을 거래소에서 즉시 조회. 평단·미실현손익·청산가."""
    mgr = manager()
    if not mgr.has_credentials(follower_id):
        raise HTTPException(400, "API 키를 먼저 등록하세요.")
    runner = mgr.get(follower_id)
    if runner.ex is None:
        runner.ex = mgr.new_exchange(follower_id)

    out = []
    for symbol in sorted(set(runner.mirrors) | set(mgr.leader_view())):
        if not runner.tracks(symbol):
            continue
        try:
            position = await asyncio.to_thread(runner.ex.position, symbol)
            price = await asyncio.to_thread(runner.ex.last_price, symbol)
        except Exception as exc:  # noqa: BLE001
            out.append({"symbol": symbol, "error": str(exc)})
            continue
        size = position["size"]
        if size == 0:
            continue
        state = runner.mirrors.get(symbol)
        out.append({
            "symbol": symbol,
            "size": size,
            "entryPrice": position["entryPrice"],
            "markPrice": price,
            "notional": abs(size) * price,
            "unrealizedPnl": position["unrealizedPnl"],
            "liquidationPrice": position["liquidationPrice"],
            "leverage": position.get("leverage"),
            "marginMode": position.get("marginMode"),
            "takeProfitPrice": state.tp_price if state else None,
            "takeProfitAmount": state.tp_amount if state else None,
        })
    return {"positions": out}


# ------------------------------------------------------------ 잔고 그래프

@router.get("/api/copy/balance/history")
async def balance_history(
    period: str = Query("week"),
    follower_id: int = Depends(require_follower),
) -> dict[str, Any]:
    """내 잔고·순자산 시계열. 리더의 곡선과 같은 형식이라 앱이 그대로 그린다."""
    if period not in BALANCE_PERIODS:
        raise HTTPException(
            400, f"period 는 {', '.join(BALANCE_PERIODS)} 중 하나여야 합니다."
        )
    window, bucket, label = BALANCE_PERIODS[period]
    since = time.time() - window
    points = await asyncio.to_thread(
        store().follower_balance_series, follower_id, since, bucket
    )
    meta = store().follower_balance_range(follower_id)

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

    from .copy import CopyRunner

    return {
        "period": period,
        "label": label,
        "bucketSeconds": bucket,
        "points": points,
        "summary": summary,
        "recording": {
            "firstTs": meta["firstTs"],
            "totalSamples": meta["count"],
            "intervalSeconds": CopyRunner.SNAPSHOT_INTERVAL,
        },
    }


@router.post("/api/copy/balance/snapshot")
async def balance_snapshot(
    follower_id: int = Depends(require_follower),
) -> dict[str, Any]:
    mgr = manager()
    if not mgr.has_credentials(follower_id):
        raise HTTPException(400, "API 키를 먼저 등록하세요.")
    runner = mgr.get(follower_id)
    if runner.ex is None:
        runner.ex = mgr.new_exchange(follower_id)
    snapshot = await runner.snapshot_balance()
    if snapshot is None:
        raise HTTPException(400, "거래소 조회에 실패했습니다.")
    return snapshot


# ---------------------------------------------------------------- 이벤트

@router.get("/api/copy/events")
async def events(
    limit: int = Query(200, ge=1, le=2000),
    follower_id: int = Depends(require_follower),
) -> dict[str, Any]:
    """내 로그만. 리더와 다른 팔로워의 로그는 절대 섞이지 않는다."""
    return {"events": store().recent_events(limit, follower_id=follower_id)}


@router.websocket("/ws/copy")
async def copy_websocket(websocket: WebSocket, token: str = Query("")) -> None:
    follower_id = follower_from_ws(token)
    if follower_id is None or store().get_follower(follower_id) is None:
        await websocket.close(code=4401)
        return
    await websocket.accept()

    mgr = manager()
    loop = asyncio.get_running_loop()
    queue: asyncio.Queue = asyncio.Queue(maxsize=500)

    def on_event(event: dict[str, Any]) -> None:
        loop.call_soon_threadsafe(_offer, queue, event)

    mgr.subscribe(follower_id, on_event)
    try:
        await websocket.send_text(json.dumps(
            {"type": "hello", "status": _status_payload(follower_id)},
            ensure_ascii=False, default=str,
        ))
        while True:
            try:
                event = await asyncio.wait_for(queue.get(), timeout=15)
                payload = {"type": "event", "event": event}
            except asyncio.TimeoutError:
                payload = {"type": "status", "status": _status_payload(follower_id)}
            await websocket.send_text(
                json.dumps(payload, ensure_ascii=False, default=str)
            )
    except WebSocketDisconnect:
        pass
    except Exception:  # noqa: BLE001
        pass
    finally:
        mgr.unsubscribe(follower_id, on_event)


def _status_payload(follower_id: int) -> dict[str, Any]:
    runner = manager().runners.get(follower_id)
    if runner is None:
        return {"followerId": follower_id, "running": False, "symbols": []}
    return {**runner.status(), "leaderSymbols": _leader_symbols()}


def _offer(queue: asyncio.Queue, item: Any) -> None:
    with contextlib.suppress(asyncio.QueueFull):
        queue.put_nowait(item)
