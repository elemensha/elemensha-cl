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

    def issue_token(self, label: str = "") -> str:
        import hashlib

        token = secrets.token_urlsafe(32)
        digest = hashlib.sha256(token.encode()).hexdigest()
        self.db.execute(
            "INSERT INTO devices(token_hash, label, created_at) VALUES(?,?,?)",
            (digest, label, time.time()),
        )
        self.db.commit()
        return token

    def verify_token(self, token: str) -> bool:
        import hashlib

        if not token:
            return False
        digest = hashlib.sha256(token.encode()).hexdigest()
        row = self.db.execute(
            "SELECT token_hash FROM devices WHERE token_hash=?", (digest,)
        ).fetchone()
        if not row:
            return False
        self.db.execute(
            "UPDATE devices SET last_seen=? WHERE token_hash=?",
            (time.time(), digest),
        )
        self.db.commit()
        return True

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
                  data: dict[str, Any] | None = None) -> dict[str, Any]:
        ts = time.time()
        cur = self.db.execute(
            "INSERT INTO events(ts, symbol, level, message, data) "
            "VALUES(?,?,?,?,?)",
            (ts, symbol, level, message,
             json.dumps(data, ensure_ascii=False, default=str) if data else None),
        )
        self.db.commit()
        # 보관 상한 (무료 서버 디스크 보호)
        if cur.lastrowid and cur.lastrowid % 500 == 0:
            self.db.execute(
                "DELETE FROM events WHERE id < (SELECT MAX(id)-20000 FROM events)"
            )
            self.db.commit()
        return {"id": cur.lastrowid, "ts": ts, "symbol": symbol,
                "level": level, "message": message, "data": data}

    def recent_events(self, limit: int = 200, symbol: str | None = None,
                      level: str | None = None) -> list[dict[str, Any]]:
        sql = "SELECT * FROM events"
        clauses, args = [], []
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


def mask(value: str | None, keep: int = 4) -> str:
    """API 키를 앱에 되돌려줄 때 쓰는 마스킹."""
    if not value:
        return ""
    if len(value) <= keep * 2:
        return "*" * len(value)
    return f"{value[:keep]}{'*' * 8}{value[-keep:]}"
