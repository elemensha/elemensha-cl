"""서버 설정. 전부 환경변수로 주입된다 (하드코딩 금지)."""
from __future__ import annotations

import os
from pathlib import Path


class Settings:
    def __init__(self) -> None:
        self.data_dir = Path(os.getenv("ELEMENSHA_DATA_DIR", "./data")).resolve()
        self.host = os.getenv("ELEMENSHA_HOST", "0.0.0.0")
        self.port = int(os.getenv("ELEMENSHA_PORT", "8080"))

        # 앱 최초 연결용 페어링 코드. 미설정 시 부팅 때 생성해 로그에 1회 출력.
        self.pairing_code = os.getenv("ELEMENSHA_PAIRING_CODE", "").strip()

        # 인앱 업데이트: APK는 GitHub Releases(무료)에 올린다.
        self.github_repo = os.getenv("ELEMENSHA_GITHUB_REPO", "").strip()

        self.log_level = os.getenv("ELEMENSHA_LOG_LEVEL", "info")

    @property
    def release_api(self) -> str | None:
        if not self.github_repo:
            return None
        return f"https://api.github.com/repos/{self.github_repo}/releases/latest"


settings = Settings()
