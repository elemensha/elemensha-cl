"""카피 트레이딩 스모크 테스트. 거래소 없이 돌아가는 부분만 전부 확인한다."""
import os
import sqlite3
import sys
import tempfile
from pathlib import Path

DATA = Path(tempfile.mkdtemp(prefix="elemensha-smoke-"))

# --- 1) 구버전 DB 를 먼저 만들어 마이그레이션을 강제로 태운다
db = sqlite3.connect(DATA / "elemensha.db")
db.executescript("""
CREATE TABLE devices (token_hash TEXT PRIMARY KEY, label TEXT,
                      created_at REAL NOT NULL, last_seen REAL);
CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL NOT NULL,
                     symbol TEXT, level TEXT NOT NULL, message TEXT NOT NULL,
                     data TEXT);
INSERT INTO devices VALUES('deadbeef','old-leader-device',1.0,2.0);
INSERT INTO events(ts,symbol,level,message,data)
     VALUES(1.0,'BTC/USDT:USDT','info','구버전 이벤트',NULL);
""")
db.commit()
db.close()

os.environ["ELEMENSHA_DATA_DIR"] = str(DATA)
os.environ["ELEMENSHA_PAIRING_CODE"] = "TESTCODE"

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from fastapi.testclient import TestClient   # noqa: E402

from app.copy import (BelowMinimum, FollowerConfig, SizingMode,  # noqa: E402
                      compute_ratio, plan_order)
from app.main import app, store            # noqa: E402

ok = 0
fail = []


def check(name, condition, detail=""):
    global ok
    if condition:
        ok += 1
        print(f"  PASS  {name}")
    else:
        fail.append(name)
        print(f"  FAIL  {name}  {detail}")


print("\n[1] 구버전 DB 마이그레이션")
cols = {r[1] for r in store.db.execute("PRAGMA table_info(devices)")}
check("devices.follower_id 추가됨", "follower_id" in cols, cols)
cols = {r[1] for r in store.db.execute("PRAGMA table_info(events)")}
check("events.follower_id 추가됨", "follower_id" in cols, cols)
old_events = store.recent_events(10)
check("구버전 이벤트가 리더 것으로 보존됨",
      len(old_events) == 1 and old_events[0]["message"] == "구버전 이벤트",
      old_events)

with TestClient(app) as client:
    print("\n[2] 리더 페어링 + 초대코드")
    r = client.post("/api/pair", json={"code": "TESTCODE", "label": "leader-phone"})
    check("페어링 성공", r.status_code == 200, r.text)
    leader = {"Authorization": f"Bearer {r.json()['token']}"}

    check("구버전 토큰도 리더로 인정됨", store.verify_token("x") is False)

    r = client.post("/api/invites", headers=leader,
                    json={"label": "친구1", "maxUses": 1})
    check("초대코드 발급", r.status_code == 200, r.text)
    code = r.json()["code"]

    print("\n[3] 팔로워 가입")
    r = client.post("/api/copy/join", json={"code": code, "label": "friend-phone"})
    check("초대코드로 가입", r.status_code == 200, r.text)
    follower = {"Authorization": f"Bearer {r.json()['token']}"}
    fid = r.json()["followerId"]

    r = client.post("/api/copy/join", json={"code": code, "label": "재사용"})
    check("1회용 코드 재사용 차단", r.status_code == 403, r.text)

    r = client.post("/api/copy/join", json={"code": "NOPE-NOPE", "label": "x"})
    check("잘못된 코드 거부", r.status_code == 403, r.text)

    print("\n[4] 권한 격리")
    r = client.get("/api/bots", headers=follower)
    check("팔로워는 리더 봇을 못 본다", r.status_code == 401, r.status_code)
    r = client.get("/api/credentials", headers=follower)
    check("팔로워는 리더 API 키를 못 본다", r.status_code == 401, r.status_code)
    r = client.get("/api/followers", headers=follower)
    check("팔로워는 팔로워 목록을 못 본다", r.status_code == 401, r.status_code)
    r = client.post("/api/invites", headers=follower, json={})
    check("팔로워는 초대코드를 못 만든다", r.status_code == 401, r.status_code)
    r = client.get("/api/copy/status", headers=leader)
    check("리더는 팔로워 계좌를 못 본다", r.status_code == 401, r.status_code)
    r = client.get("/api/copy/me")
    check("토큰 없이는 거부", r.status_code == 401, r.status_code)

    print("\n[5] 팔로워 설정")
    r = client.get("/api/copy/meta")
    modes = [m["value"] for m in r.json()["sizingModes"]]
    check("사이징 3종 제공", modes == ["equity", "multiplier", "fixed"], modes)

    r = client.get("/api/copy/me", headers=follower)
    check("내 정보 조회", r.status_code == 200 and r.json()["followerId"] == fid,
          r.text)
    check("기본 사이징은 자산 비례",
          r.json()["config"]["sizingMode"] == "equity", r.text)

    r = client.post("/api/copy/config", headers=follower, json={
        "sizingMode": "fixed", "fixedNotional": 25.0, "leverage": 3,
        "belowMinimum": "skip", "maxRatio": 2.0,
    })
    check("설정 저장", r.status_code == 200, r.text)
    r = client.get("/api/copy/config", headers=follower)
    check("설정 반영됨",
          r.json()["sizingMode"] == "fixed" and r.json()["fixedNotional"] == 25.0,
          r.text)

    r = client.post("/api/copy/config", headers=follower,
                    json={"sizingMode": "없는모드"})
    check("잘못된 사이징 거부", r.status_code == 400, r.status_code)

    print("\n[6] 키 없이 시작 거부")
    r = client.post("/api/copy/start", headers=follower)
    check("API 키 없으면 시작 불가", r.status_code == 400, r.text)
    r = client.get("/api/copy/orders", headers=follower)
    check("API 키 없으면 주문조회 불가", r.status_code == 400, r.status_code)

    print("\n[7] 잔고 그래프 / 로그 격리")
    r = client.get("/api/copy/balance/history?period=week", headers=follower)
    check("빈 그래프도 형태는 정상",
          r.status_code == 200 and r.json()["points"] == [], r.text)
    r = client.get("/api/copy/balance/history?period=엉뚱", headers=follower)
    check("잘못된 기간 거부", r.status_code == 400, r.status_code)

    r = client.get("/api/copy/events", headers=follower)
    messages = [e["message"] for e in r.json()["events"]]
    check("내 로그만 보인다", "구버전 이벤트" not in messages, messages)
    check("가입 로그는 보인다", any("기기 연결됨" in m for m in messages), messages)

    r = client.get("/api/events", headers=leader)
    messages = [e["message"] for e in r.json()["events"]]
    check("리더 로그에 팔로워 로그가 안 섞인다",
          not any("friend-phone" in m for m in messages), messages)

    print("\n[8] 리더 관리 화면")
    r = client.get("/api/followers", headers=leader)
    rows = r.json()["followers"]
    check("팔로워 1명 보임", len(rows) == 1 and rows[0]["id"] == fid, rows)
    check("팔로워 API 키는 마스킹만",
          rows[0]["credentials"]["configured"] is False, rows)

    r = client.get("/api/health")
    check("health 에 팔로워 수 노출", r.json()["followers"] == 1, r.json())

    print("\n[9] 계정 삭제")
    r = client.delete(f"/api/followers/{fid}", headers=leader)
    check("삭제 성공", r.status_code == 200, r.text)
    r = client.get("/api/copy/me", headers=follower)
    check("삭제된 계정 토큰 무효", r.status_code == 401, r.status_code)
    check("잔고기록도 삭제됨",
          store.follower_balance_range(fid)["count"] == 0)

print("\n[10] 주문 크기 계산 (거래소 없이 순수 계산)")


class FakeSpec:
    base = "BTC"
    min_qty = 0.001
    qty_step = 0.001
    min_notional = 100.0
    price_tick = 0.1

    def hard_floor_notional(self, price):
        return max(self.min_notional, self.min_qty * price)


class FakeEx:
    def spec(self, symbol):
        return FakeSpec()

    def order_amount(self, symbol, notional, price, round_up):
        spec = FakeSpec()
        floor = spec.hard_floor_notional(price)
        target = max(notional, floor)
        amount = round(target / price / spec.qty_step) * spec.qty_step
        amount = max(amount, spec.min_qty)
        while amount * price < floor:
            amount = round(amount + spec.qty_step, 8)
        return round(amount, 8), round(amount * price, 8)


SIGNAL = {
    "symbol": "BTC/USDT:USDT", "price": 100_000.0,
    "leaderAmount": 0.01, "leaderNotional": 1000.0,
    "leaderBalance": 10_000.0, "takeProfitPercent": 0.01,
}
ex = FakeEx()

ratio, why = compute_ratio(FollowerConfig(sizing_mode=SizingMode.EQUITY),
                           SIGNAL, my_equity=1_000.0)
check("자산 비례: 리더의 1/10 자산 -> 0.1배", abs(ratio - 0.1) < 1e-12, ratio)

ratio, _ = compute_ratio(
    FollowerConfig(sizing_mode=SizingMode.EQUITY, equity_scale=2.0),
    SIGNAL, my_equity=1_000.0)
check("자산 비례 + 보정 2배 -> 0.2배", abs(ratio - 0.2) < 1e-12, ratio)

ratio, _ = compute_ratio(
    FollowerConfig(sizing_mode=SizingMode.MULTIPLIER, multiplier=0.5),
    SIGNAL, my_equity=1_000.0)
check("고정 배수 0.5", ratio == 0.5, ratio)

ratio, _ = compute_ratio(
    FollowerConfig(sizing_mode=SizingMode.FIXED, fixed_notional=250.0),
    SIGNAL, my_equity=1_000.0)
check("고정 금액 $250 / 리더 $1000 -> 0.25배", abs(ratio - 0.25) < 1e-12, ratio)

# 최소금액 미달 -> 건너뜀
plan = plan_order(ex, FollowerConfig(sizing_mode=SizingMode.FIXED,
                                     fixed_notional=20.0),
                  SIGNAL, 1_000.0, 0.0)
check("BTC 최소 $100 미달이면 건너뜀", plan.skip_reason is not None,
      plan.skip_reason)
check("건너뛴 이유가 사람이 읽을 수 있다",
      plan.skip_reason and "최소" in plan.skip_reason, plan.skip_reason)

# round_up 이면 최소금액까지 올려서 매수
plan = plan_order(ex, FollowerConfig(sizing_mode=SizingMode.FIXED,
                                     fixed_notional=20.0,
                                     below_minimum=BelowMinimum.ROUND_UP),
                  SIGNAL, 1_000.0, 0.0)
check("round_up 이면 최소금액으로 매수",
      plan.skip_reason is None and plan.notional >= 100.0, plan)

# 정상 주문
plan = plan_order(ex, FollowerConfig(sizing_mode=SizingMode.FIXED,
                                     fixed_notional=500.0),
                  SIGNAL, 1_000.0, 0.0)
check("고정 $500 -> 0.005 BTC", abs(plan.amount - 0.005) < 1e-9, plan)

# 배율 상한
plan = plan_order(ex, FollowerConfig(sizing_mode=SizingMode.MULTIPLIER,
                                     multiplier=5.0, max_ratio=1.0),
                  SIGNAL, 1_000.0, 0.0)
check("배율 상한 1.0 적용", abs(plan.ratio - 1.0) < 1e-12, plan.ratio)
check("상한 적용이 이유에 남는다", "상한" in plan.reason, plan.reason)

# 포지션 상한
plan = plan_order(ex, FollowerConfig(sizing_mode=SizingMode.MULTIPLIER,
                                     multiplier=1.0,
                                     max_position_notional=500.0),
                  SIGNAL, 1_000.0, position_size=0.006)
check("포지션 상한 도달 시 건너뜀", plan.skip_reason is not None, plan.skip_reason)

print(f"\n{'=' * 50}\n통과 {ok}건" + (f", 실패 {len(fail)}건: {fail}" if fail else ""))
sys.exit(1 if fail else 0)
