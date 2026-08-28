"""
SQLite 영속 저장소 + API 키 암호화.

[요구사항 7] API 키는 코드에 하드코딩하지 않는다.
앱에서 입력 -> HTTPS로 전송 -> 서버에서 Fernet(AES-128-CBC + HMAC)으로
암호화해 저장. 마스터 키는 서버 파일시스템(0600)에만 존재하며 DB와 분리된다.
조회 API는 항상 마스킹된 값만 돌려준다.
"""
from __future__ import annotations

import json
import os
import secrets
import sqlite3
import time
from pathlib import Path
from typing import Any

from cryptography.fernet import Fernet, InvalidToken

SCHEMA = """
CREATE TABLE IF NOT EXISTS kv (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS secrets (
    name  TEXT PRIMARY KEY,
    blob  BLOB NOT NULL,
    updated_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
    token_hash TEXT PRIMARY KEY,
    label      TEXT,
    created_at REAL NOT NULL,
    last_seen  REAL
);
CREATE TABLE IF NOT EXISTS events (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    ts        REAL NOT NULL,
    symbol    TEXT,
    level     TEXT NOT NULL,
    message   TEXT NOT NULL,
    data      TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts DESC);
CREATE TABLE IF NOT EXISTS balance_history (
    ts                REAL PRIMARY KEY,
    wallet            REAL NOT NULL,
    equity            REAL NOT NULL,
    unrealized_pnl    REAL NOT NULL DEFAULT 0,
    position_notional REAL NOT NULL DEFAULT 0,
    open_positions    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_balance_ts ON balance_history(ts);
CREATE TABLE IF NOT EXISTS bots (
    symbol     TEXT PRIMARY KEY,
    config     TEXT NOT NULL,
    state      TEXT,
    enabled    INTEGER NOT NULL DEFAULT 0,
    updated_at REAL NOT NULL
);
"""

# ------------------------------------------------------------------ 카피 트레이딩
#
# 팔로워는 리더 서버에 세들어 사는 별개 테넌트다. 리더의 데이터와 절대
# 섞이면 안 되므로 잔고 기록은 전용 테이블을 따로 두고, 이벤트/기기에는
# follower_id 를 달아 조회 때마다 강제로 걸러낸다.
COPY_SCHEMA = """
CREATE TABLE IF NOT EXISTS followers (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    label      TEXT NOT NULL,
    config     TEXT NOT NULL,
    enabled    INTEGER NOT NULL DEFAULT 0,
    state      TEXT,
    created_at REAL NOT NULL,
    updated_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS invites (
    code       TEXT PRIMARY KEY,
    label      TEXT,
    max_uses   INTEGER NOT NULL DEFAULT 1,
    uses       INTEGER NOT NULL DEFAULT 0,
    expires_at REAL,
    created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS follower_balance_history (
    follower_id       INTEGER NOT NULL,
    ts                REAL NOT NULL,
    wallet            REAL NOT NULL,
    equity            REAL NOT NULL,
    unrealized_pnl    REAL NOT NULL DEFAULT 0,
    position_notional REAL NOT NULL DEFAULT 0,
    open_positions    INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (follower_id, ts)
);
CREATE INDEX IF NOT EXISTS idx_fbh ON follower_balance_history(follower_id, ts);
"""


class Store:
    def __init__(self, data_dir: str | Path):
        self.dir = Path(data_dir)
        self.dir.mkdir(parents=True, exist_ok=True)
        self.db_path = self.dir / "elemensha.db"
        self._fernet = Fernet(self._load_master_key())

        self.db = sqlite3.connect(self.db_path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript(SCHEMA)
        self.db.executescript(COPY_SCHEMA)
        self._migrate()
        self.db.commit()

    # ------------------------------------------------------------ 마이그레이션

    def _columns(self, table: str) -> set[str]:
        return {r["name"] for r in
                self.db.execute(f"PRAGMA table_info({table})").fetchall()}

    def _migrate(self) -> None:
        """
        기존 DB 를 카피 트레이딩용으로 승격한다.

        ALTER TABLE ADD COLUMN 은 되돌릴 수 없으므로 컬럼 존재 여부를 먼저 본다.
        follower_id 가 NULL 인 행은 전부 리더(서버 주인)의 것이다.
        """
        if "follower_id" not in self._columns("devices"):
            self.db.execute("ALTER TABLE devices ADD COLUMN follower_id INTEGER")
        if "follower_id" not in self._columns("events"):
            self.db.execute("ALTER TABLE events ADD COLUMN follower_id INTEGER")
            self.db.execute(
                "CREATE INDEX IF NOT EXISTS idx_events_follower "
                "ON events(follower_id, id DESC)"
            )
        self.db.commit()

    # ------------------------------------------------------------ 마스터 키

    def _load_master_key(self) -> bytes:
        env = os.getenv("ELEMENSHA_MASTER_KEY")
        if env:
            return env.encode()
        path = self.dir / "master.key"
        if path.exists():
            return path.read_bytes().strip()
        key = Fernet.generate_key()
        path.write_bytes(key)
        try:
            path.chmod(0o600)
        except OSError:
            pass  # Windows
        return key

    # ------------------------------------------------------- 암호화 시크릿

    def put_secret(self, name: str, value: str) -> None:
        blob = self._fernet.encrypt(value.encode())
        self.db.execute(
            "INSERT INTO secrets(name, blob, updated_at) VALUES(?,?,?) "
            "ON CONFLICT(name) DO UPDATE SET blob=excluded.blob, "
            "updated_at=excluded.updated_at",
            (name, blob, time.time()),
        )
        self.db.commit()

    def get_secret(self, name: str) -> str | None:
        row = self.db.execute(
            "SELECT blob FROM secrets WHERE name=?", (name,)
        ).fetchone()
        if not row:
            return None
        try:
            return self._fernet.decrypt(row["blob"]).decode()
        except InvalidToken:
            return None

    def delete_secret(self, name: str) -> None:
        self.db.execute("DELETE FROM secrets WHERE name=?", (name,))
        self.db.commit()

    def has_secret(self, name: str) -> bool:
        return self.get_secret(name) is not None

    # ------------------------------------------------------------ 일반 KV

    def put(self, key: str, value: Any) -> None:
        self.db.execute(
            "INSERT INTO kv(key, value, updated_at) VALUES(?,?,?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value, "
            "updated_at=excluded.updated_at",
            (key, json.dumps(value, ensure_ascii=False), time.time()),
        )
        self.db.commit()

    def get(self, key: str, default: Any = None) -> Any:
        row = self.db.execute(
            "SELECT value FROM kv WHERE key=?", (key,)
        ).fetchone()
        return json.loads(row["value"]) if row else default

    # ------------------------------------------------------------ 디바이스

    def issue_token(self, label: str = "",
                    follower_id: int | None = None) -> str:
        """follower_id 가 None 이면 서버 주인(리더) 기기다."""
        import hashlib

        token = secrets.token_urlsafe(32)
        digest = hashlib.sha256(token.encode()).hexdigest()
        self.db.execute(
            "INSERT INTO devices(token_hash, label, created_at, follower_id) "
            "VALUES(?,?,?,?)",
            (digest, label, time.time(), follower_id),
        )
        self.db.commit()
        return token

    def resolve_token(self, token: str) -> dict[str, Any] | None:
        """토큰 -> {'followerId': int|None}. 없으면 None."""
        import hashlib

        if not token:
            return None
        digest = hashlib.sha256(token.encode()).hexdigest()
        row = self.db.execute(
            "SELECT label, follower_id FROM devices WHERE token_hash=?", (digest,)
        ).fetchone()
        if not row:
            return None
        self.db.execute(
            "UPDATE devices SET last_seen=? WHERE token_hash=?",
            (time.time(), digest),
        )
        self.db.commit()
        return {"label": row["label"], "followerId": row["follower_id"]}

    def verify_token(self, token: str) -> bool:
        """
        리더(서버 주인) 전용 검사.

        팔로워 토큰으로는 절대 True 가 되면 안 된다 - 리더의 봇 제어와
        API 키 관리 엔드포인트가 전부 이 함수 하나에 걸려 있다.
        """
        info = self.resolve_token(token)
        return bool(info) and info["followerId"] is None

    def list_devices(self) -> list[dict[str, Any]]:
        rows = self.db.execute(
            "SELECT label, created_at, last_seen, "
            "substr(token_hash,1,8) AS fingerprint FROM devices "
            "ORDER BY created_at DESC"
        ).fetchall()
        return [dict(r) for r in rows]

    def revoke_all_devices(self) -> None:
        self.db.execute("DELETE FROM devices")
        self.db.commit()

    # -------------------------------------------------------------- 이벤트

    def add_event(self, level: str, message: str,
                  symbol: str | None = None,
                  data: dict[str, Any] | None = None,
                  follower_id: int | None = None) -> dict[str, Any]:
        ts = time.time()
        cur = self.db.execute(
            "INSERT INTO events(ts, symbol, level, message, data, follower_id) "
            "VALUES(?,?,?,?,?,?)",
            (ts, symbol, level, message,
             json.dumps(data, ensure_ascii=False, default=str) if data else None,
             follower_id),
        )
        self.db.commit()
        # 보관 상한 (무료 서버 디스크 보호)
        if cur.lastrowid and cur.lastrowid % 500 == 0:
            self.db.execute(
                "DELETE FROM events WHERE id < (SELECT MAX(id)-20000 FROM events)"
            )
            self.db.commit()
        return {"id": cur.lastrowid, "ts": ts, "symbol": symbol,
                "level": level, "message": message, "data": data,
                "followerId": follower_id}

    def recent_events(self, limit: int = 200, symbol: str | None = None,
                      level: str | None = None,
                      follower_id: int | None = None) -> list[dict[str, Any]]:
        """
        follower_id=None 이면 리더의 이벤트만, 숫자면 그 팔로워의 것만.
        기본이 '전부'가 아니라 '리더만'인 게 중요하다 - 팔로워끼리 로그가
        새는 사고를 기본값으로 막는다.
        """
        sql = "SELECT * FROM events"
        clauses, args = [], []
        if follower_id is None:
            clauses.append("follower_id IS NULL")
        else:
            clauses.append("follower_id=?")
            args.append(follower_id)
        if symbol:
            clauses.append("symbol=?")
            args.append(symbol)
        if level:
            clauses.append("level=?")
            args.append(level)
        if clauses:
            sql += " WHERE " + " AND ".join(clauses)
        sql += " ORDER BY id DESC LIMIT ?"
        args.append(limit)
        rows = self.db.execute(sql, args).fetchall()
        out = []
        for r in rows:
            item = dict(r)
            item["data"] = json.loads(item["data"]) if item["data"] else None
            item["followerId"] = item.pop("follower_id", None)
            out.append(item)
        return out

    # ---------------------------------------------------------------- 봇

    def save_bot(self, symbol: str, config: dict[str, Any],
                 state: dict[str, Any] | None, enabled: bool) -> None:
        self.db.execute(
            "INSERT INTO bots(symbol, config, state, enabled, updated_at) "
            "VALUES(?,?,?,?,?) ON CONFLICT(symbol) DO UPDATE SET "
            "config=excluded.config, state=excluded.state, "
            "enabled=excluded.enabled, updated_at=excluded.updated_at",
            (symbol, json.dumps(config, ensure_ascii=False),
             json.dumps(state, ensure_ascii=False, default=str) if state else None,
             1 if enabled else 0, time.time()),
        )
        self.db.commit()

    def load_bots(self) -> list[dict[str, Any]]:
        rows = self.db.execute("SELECT * FROM bots").fetchall()
        out = []
        for r in rows:
            out.append({
                "symbol": r["symbol"],
                "config": json.loads(r["config"]),
                "state": json.loads(r["state"]) if r["state"] else None,
                "enabled": bool(r["enabled"]),
            })
        return out

    def delete_bot(self, symbol: str) -> None:
        self.db.execute("DELETE FROM bots WHERE symbol=?", (symbol,))
        self.db.commit()

    # ------------------------------------------------------------ 잔고 기록

    def add_balance_point(self, snapshot: dict[str, Any]) -> None:
        """계좌 스냅샷 1건 기록. 5분마다 호출된다."""
        self.db.execute(
            "INSERT OR REPLACE INTO balance_history"
            "(ts, wallet, equity, unrealized_pnl, position_notional, open_positions)"
            " VALUES(?,?,?,?,?,?)",
            (time.time(),
             float(snapshot.get("wallet") or 0.0),
             float(snapshot.get("equity") or 0.0),
             float(snapshot.get("unrealizedPnl") or 0.0),
             float(snapshot.get("positionNotional") or 0.0),
             int(snapshot.get("openPositions") or 0)),
        )
        self.db.commit()

    def prune_balance_history(self, keep_days: int = 730) -> int:
        """오래된 기록 정리. 5분 간격이면 2년치가 약 21만 행(수 MB)."""
        cutoff = time.time() - keep_days * 86400
        cur = self.db.execute("DELETE FROM balance_history WHERE ts < ?", (cutoff,))
        self.db.commit()
        return cur.rowcount

    def balance_series(self, since: float, bucket_seconds: int) -> list[dict[str, Any]]:
        """
        구간별로 묶은 잔고 시계열.

        각 구간의 '마지막' 값을 쓴다 — 종가 개념. 평균을 내면 급변이
        뭉개져서 실제로 무슨 일이 있었는지 안 보인다.
        """
        rows = self.db.execute(
            "SELECT"
            "  CAST(ts / ? AS INTEGER) * ? AS bucket,"
            "  MAX(ts) AS last_ts,"
            "  MIN(equity) AS low,"
            "  MAX(equity) AS high,"
            "  COUNT(*) AS samples"
            " FROM balance_history WHERE ts >= ?"
            " GROUP BY bucket ORDER BY bucket",
            (bucket_seconds, bucket_seconds, since),
        ).fetchall()

        out = []
        for r in rows:
            last = self.db.execute(
                "SELECT wallet, equity, unrealized_pnl, position_notional,"
                " open_positions FROM balance_history WHERE ts = ?",
                (r["last_ts"],),
            ).fetchone()
            if not last:
                continue
            out.append({
                "ts": r["bucket"],
                "wallet": last["wallet"],
                "equity": last["equity"],
                "unrealizedPnl": last["unrealized_pnl"],
                "positionNotional": last["position_notional"],
                "openPositions": last["open_positions"],
                "low": r["low"],
                "high": r["high"],
                "samples": r["samples"],
            })
        return out

    def balance_range(self) -> dict[str, Any]:
        """기록이 언제부터 있는지. 앱에서 '데이터 부족' 안내에 쓴다."""
        row = self.db.execute(
            "SELECT MIN(ts) AS first_ts, MAX(ts) AS last_ts, COUNT(*) AS n"
            " FROM balance_history"
        ).fetchone()
        return {"firstTs": row["first_ts"], "lastTs": row["last_ts"],
                "count": row["n"] or 0}


    # ---------------------------------------------------------- 카피: 초대코드

    def create_invite(self, label: str = "", max_uses: int = 1,
                      ttl_seconds: float | None = None) -> dict[str, Any]:
        code = "-".join(secrets.token_hex(2).upper() for _ in range(2))
        expires = time.time() + ttl_seconds if ttl_seconds else None
        self.db.execute(
            "INSERT INTO invites(code, label, max_uses, uses, expires_at, created_at)"
            " VALUES(?,?,?,0,?,?)",
            (code, label, max(1, max_uses), expires, time.time()),
        )
        self.db.commit()
        return {"code": code, "label": label, "maxUses": max(1, max_uses),
                "uses": 0, "expiresAt": expires}

    def list_invites(self) -> list[dict[str, Any]]:
        rows = self.db.execute(
            "SELECT * FROM invites ORDER BY created_at DESC"
        ).fetchall()
        return [{"code": r["code"], "label": r["label"], "maxUses": r["max_uses"],
                 "uses": r["uses"], "expiresAt": r["expires_at"],
                 "createdAt": r["created_at"],
                 "exhausted": r["uses"] >= r["max_uses"]}
                for r in rows]

    def delete_invite(self, code: str) -> None:
        self.db.execute("DELETE FROM invites WHERE code=?", (code.strip().upper(),))
        self.db.commit()

    def consume_invite(self, code: str) -> bool:
        """
        유효하면 사용횟수를 1 올리고 True.

        UPDATE 의 WHERE 절에 모든 조건을 넣어 검사와 증가를 한 문장으로 처리한다.
        검사 후 증가로 나누면 동시에 들어온 두 요청이 같은 1회분 초대코드를
        함께 통과할 수 있다.
        """
        cur = self.db.execute(
            "UPDATE invites SET uses = uses + 1 WHERE code=? AND uses < max_uses"
            " AND (expires_at IS NULL OR expires_at > ?)",
            (code.strip().upper(), time.time()),
        )
        self.db.commit()
        return cur.rowcount > 0

    # ------------------------------------------------------------ 카피: 팔로워

    def create_follower(self, label: str, config: dict[str, Any]) -> int:
        now = time.time()
        cur = self.db.execute(
            "INSERT INTO followers(label, config, enabled, state, created_at,"
            " updated_at) VALUES(?,?,0,NULL,?,?)",
            (label, json.dumps(config, ensure_ascii=False), now, now),
        )
        self.db.commit()
        return int(cur.lastrowid)

    def save_follower(self, follower_id: int, config: dict[str, Any] | None = None,
                      state: dict[str, Any] | None = None,
                      enabled: bool | None = None,
                      label: str | None = None) -> None:
        """None 인 필드는 건드리지 않는다 (부분 갱신)."""
        sets, args = ["updated_at=?"], [time.time()]
        if config is not None:
            sets.append("config=?")
            args.append(json.dumps(config, ensure_ascii=False))
        if state is not None:
            sets.append("state=?")
            args.append(json.dumps(state, ensure_ascii=False, default=str))
        if enabled is not None:
            sets.append("enabled=?")
            args.append(1 if enabled else 0)
        if label is not None:
            sets.append("label=?")
            args.append(label)
        args.append(follower_id)
        self.db.execute(
            "UPDATE followers SET " + ", ".join(sets) + " WHERE id=?", args
        )
        self.db.commit()

    @staticmethod
    def _follower_row(row: Any) -> dict[str, Any]:
        return {
            "id": row["id"],
            "label": row["label"],
            "config": json.loads(row["config"]),
            "state": json.loads(row["state"]) if row["state"] else None,
            "enabled": bool(row["enabled"]),
            "createdAt": row["created_at"],
            "updatedAt": row["updated_at"],
        }

    def get_follower(self, follower_id: int) -> dict[str, Any] | None:
        row = self.db.execute(
            "SELECT * FROM followers WHERE id=?", (follower_id,)
        ).fetchone()
        return self._follower_row(row) if row else None

    def load_followers(self) -> list[dict[str, Any]]:
        rows = self.db.execute("SELECT * FROM followers ORDER BY id").fetchall()
        return [self._follower_row(r) for r in rows]

    def delete_follower(self, follower_id: int) -> None:
        """계정을 지우면 키·기기·로그·잔고기록까지 전부 함께 지운다."""
        self.delete_secret("follower:%d:api_key" % follower_id)
        self.delete_secret("follower:%d:api_secret" % follower_id)
        self.db.execute("DELETE FROM devices WHERE follower_id=?", (follower_id,))
        self.db.execute("DELETE FROM events WHERE follower_id=?", (follower_id,))
        self.db.execute("DELETE FROM follower_balance_history WHERE follower_id=?",
                        (follower_id,))
        self.db.execute("DELETE FROM followers WHERE id=?", (follower_id,))
        self.db.commit()

    def follower_devices(self, follower_id: int) -> list[dict[str, Any]]:
        rows = self.db.execute(
            "SELECT label, created_at, last_seen, substr(token_hash,1,8) AS"
            " fingerprint FROM devices WHERE follower_id=? ORDER BY created_at DESC",
            (follower_id,),
        ).fetchall()
        return [dict(r) for r in rows]

    def revoke_follower_devices(self, follower_id: int) -> None:
        self.db.execute("DELETE FROM devices WHERE follower_id=?", (follower_id,))
        self.db.commit()

    # ------------------------------------------------------- 카피: 잔고 기록

    def add_follower_balance_point(self, follower_id: int,
                                   snapshot: dict[str, Any]) -> None:
        self.db.execute(
            "INSERT OR REPLACE INTO follower_balance_history"
            "(follower_id, ts, wallet, equity, unrealized_pnl,"
            " position_notional, open_positions) VALUES(?,?,?,?,?,?,?)",
            (follower_id, time.time(),
             float(snapshot.get("wallet") or 0.0),
             float(snapshot.get("equity") or 0.0),
             float(snapshot.get("unrealizedPnl") or 0.0),
             float(snapshot.get("positionNotional") or 0.0),
             int(snapshot.get("openPositions") or 0)),
        )
        self.db.commit()

    def prune_follower_balance_history(self, keep_days: int = 730) -> int:
        cutoff = time.time() - keep_days * 86400
        cur = self.db.execute(
            "DELETE FROM follower_balance_history WHERE ts < ?", (cutoff,)
        )
        self.db.commit()
        return cur.rowcount

    def follower_balance_series(self, follower_id: int, since: float,
                                bucket_seconds: int) -> list[dict[str, Any]]:
        """리더의 balance_series 와 같은 규칙(구간 종가)을 팔로워 테이블에 적용."""
        rows = self.db.execute(
            "SELECT"
            "  CAST(ts / ? AS INTEGER) * ? AS bucket,"
            "  MAX(ts) AS last_ts,"
            "  MIN(equity) AS low,"
            "  MAX(equity) AS high,"
            "  COUNT(*) AS samples"
            " FROM follower_balance_history WHERE follower_id=? AND ts >= ?"
            " GROUP BY bucket ORDER BY bucket",
            (bucket_seconds, bucket_seconds, follower_id, since),
        ).fetchall()

        out = []
        for r in rows:
            last = self.db.execute(
                "SELECT wallet, equity, unrealized_pnl, position_notional,"
                " open_positions FROM follower_balance_history"
                " WHERE follower_id=? AND ts = ?",
                (follower_id, r["last_ts"]),
            ).fetchone()
            if not last:
                continue
            out.append({
                "ts": r["bucket"],
                "wallet": last["wallet"],
                "equity": last["equity"],
                "unrealizedPnl": last["unrealized_pnl"],
                "positionNotional": last["position_notional"],
                "openPositions": last["open_positions"],
                "low": r["low"],
                "high": r["high"],
                "samples": r["samples"],
            })
        return out

    def follower_balance_range(self, follower_id: int) -> dict[str, Any]:
        row = self.db.execute(
            "SELECT MIN(ts) AS first_ts, MAX(ts) AS last_ts, COUNT(*) AS n"
            " FROM follower_balance_history WHERE follower_id=?",
            (follower_id,),
        ).fetchone()
        return {"firstTs": row["first_ts"], "lastTs": row["last_ts"],
                "count": row["n"] or 0}


# 잔고 그래프의 기간별 조회 창과 묶음 단위. 리더와 팔로워가 같은 눈금을 쓴다.
# (창, 묶음, 라벨) — 점 개수가 100개 안팎이 되도록 잡았다.
BALANCE_PERIODS: dict[str, tuple[int, int, str]] = {
    "day":     (86400,        3600,      "최근 24시간 · 1시간 단위"),
    "week":    (7 * 86400,    86400,     "최근 7일 · 1일 단위"),
    "month":   (30 * 86400,   86400,     "최근 30일 · 1일 단위"),
    "quarter": (90 * 86400,   3 * 86400, "최근 90일 · 3일 단위"),
    "year":    (365 * 86400,  7 * 86400, "최근 1년 · 1주 단위"),
}


def mask(value: str | None, keep: int = 4) -> str:
    """API 키를 앱에 되돌려줄 때 쓰는 마스킹."""
    if not value:
        return ""
    if len(value) <= keep * 2:
        return "*" * len(value)
    return f"{value[:keep]}{'*' * 8}{value[-keep:]}"
