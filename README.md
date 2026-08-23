# elemensha

바이낸스 USDⓈ-M 선물 RSI DCA 봇 — 서버 + 안드로이드 원격조종 앱.
**월 운영비 $0** (Oracle Cloud Always Free + GitHub Actions/Releases).

---

## 왜 이 구조인가

봇을 앱 안에서 돌리면 휴대폰이 꺼지거나 절전에 들어갈 때 매매가 멈춘다.
그래서 **봇은 서버에서 24시간 돌고, 앱은 원격조종기**로 만든다.

```
 [안드로이드 앱]                    [Oracle Cloud Always Free VM]
  elemensha            HTTPS         Caddy (무료 자동 TLS)
  - 파라미터 설정   ──────────────▶      │
  - 실시간 RSI/포지션   WebSocket        ▼
  - 시작/정지/긴급청산 ◀──────────   FastAPI  ──▶ 바이낸스 선물 API
  - 인앱 업데이트                      SQLite (설정·상태·로그)
        │                              암호화된 API 키
        ▼
  GitHub Releases (APK 무료 호스팅)
        ▲
  GitHub Actions (APK 무료 빌드)
```

## 월 비용 $0 내역

| 항목 | 서비스 | 한도 | 비용 |
|---|---|---|---|
| 서버 | Oracle Cloud **Always Free** | ARM 4 OCPU/24GB 또는 x86 micro 2대 | **영구 $0** |
| 도메인 | DuckDNS | 서브도메인 무제한 | **$0** |
| HTTPS | Caddy + Let's Encrypt | 자동 발급·갱신 | **$0** |
| APK 빌드 | GitHub Actions | 공개 저장소 무제한 | **$0** |
| APK 배포 | GitHub Releases | 용량 무제한 | **$0** |

현재 AWS 월 2만원 → **0원**.

⚠️ 인스턴스 생성 시 **"Always Free eligible" 배지**가 붙은 shape 를 골라야 한다.
현재 계정은 Free *Trial* 상태라 크레딧이 있는 동안은 아무 shape 나 만들어지지만,
체험이 끝나면 자격 없는 인스턴스는 정지·삭제된다.

---

## 서버

```
server/
├── app/
│   ├── main.py       FastAPI — REST + WebSocket
│   ├── engine.py     심볼별 봇 감독자 (asyncio)
│   ├── strategy.py   RSI DCA 전략 엔진
│   ├── exchange.py   바이낸스 선물 래퍼 (심볼/정밀도/최소금액/레버리지)
│   ├── store.py      SQLite + API 키 암호화(Fernet)
│   └── config.py     환경변수 설정
└── deploy/
    ├── oracle-setup.sh    VM 원클릭 설치
    ├── elemensha.service  systemd (자동재시작 + 하드닝)
    └── Caddyfile          자동 HTTPS
```

### 로컬 실행

```bash
cd server && python -m venv venv && venv/bin/pip install -r requirements.txt
ELEMENSHA_DATA_DIR=./data venv/bin/python -m uvicorn app.main:app --reload
```

### 서버 설치

**이름 분리** — 기존에 `elemensha-bot` 인스턴스가 이미 돌고 있으므로, 이 서버는
`elemensha-claude-bot` 이라는 별도 이름·포트·계정·디렉터리로 설치된다. 같은 VM 에 올려도
기존 봇을 건드리지 않는다.

| | 기존 (ChatGPT 작업분) | 신규 (이 프로젝트) |
|---|---|---|
| 인스턴스 이름 | `elemensha-bot` | `elemensha-claude-bot` |
| systemd 서비스 | `elemensha-bot` | `elemensha-claude-bot` |
| 설치 경로 | — | `/opt/elemensha-claude-bot` |
| 내부 포트 | 8080 (추정) | **8090** |
| 도메인 | — | 별도 서브도메인 권장 |

```bash
scp -i "ssh-key-2026-08-22.key" -r server ubuntu@<VM_IP>:/tmp/
ssh -i "ssh-key-2026-08-22.key" ubuntu@<VM_IP>
sudo mkdir -p /opt/elemensha-claude-bot && sudo mv /tmp/server /opt/elemensha-claude-bot/
sudo bash /opt/elemensha-claude-bot/server/deploy/oracle-setup.sh
```

다른 이름으로 설치하려면 `sudo APP_NAME=<이름> APP_PORT=<포트> bash oracle-setup.sh`.

> **Oracle Always Free 쿼터 주의**: ARM Ampere A1 의 4 OCPU / 24GB 는 인스턴스별이
> 아니라 **계정 전체 합산** 한도다. `elemensha-bot` 이 이미 쓰는 몫을 빼고 남는
> 만큼으로 새 인스턴스를 잡아야 한다. 이 봇은 **1 OCPU / 1GB** 면 충분하다.

---

## 전략

### 고정 설계 (변경 불가)

- **롱 전용.** 숏 없음.
- **청산은 익절 하나뿐.** 손절 없음, RSI 상단 청산 없음.
- **진입 판정은 항상 봉 완성(확정봉) 기준.** 진행 중인 봉은 판정에 쓰지 않는다.

### 앱에서 조절 가능한 파라미터 (기본값 = 원본 코드)

| 파라미터 | 기본값 | 범위 |
|---|---|---|
| 심볼 | BTC/USDT:USDT | USDT 무기한 **696종** 전체 |
| 타임프레임 | 1m, 5m, 15m, 1h, 4h, 1d | 바이낸스 기본 **15종** 중 다중 선택 |
| RSI 기간 | 14 | 2~100 |
| RSI 하한선 | 30 | 0~100 |
| RSI 상한선 | 70 | 0~100 |
| **진입 조건** | 하한선 상향 돌파 | 아래 6종 |
| 매수 금액 | 지갑 USDT의 **0.1%** | 0~100% |
| 최소주문 올림 단위 | $10 | 0 = 올림 안 함 |
| 익절률 | 평단 **+1%** | 0~100% |
| 최대 매수 횟수 | **무제한** | 무제한 또는 N회 |
| 레버리지 | 1x | 1~종목별 최대 |
| 마진 모드 | ISOLATED | ISOLATED / CROSS |
| 폴링 주기 | 20초 | 5~600초 |

**선택 가능한 타임프레임 15종**
`1m` `3m` `5m` `15m` `30m` `1h` `2h` `4h` `6h` `8h` `12h` `1d` `3d` `1w` `1M`

**진입 조건 6종** — 각 타임프레임에서 독립적으로 판정된다.

| 값 | 의미 | 발동 |
|---|---|---|
| `cross_up_lower` | 하한선 상향 돌파 *(기본)* | 직전봉 < 하한 ≤ 현재봉 |
| `cross_down_lower` | 하한선 하향 돌파 | 직전봉 ≥ 하한 > 현재봉 |
| `below_lower` | 하한선 아래 위치 | 현재봉 < 하한 (매 확정봉) |
| `cross_up_upper` | 상한선 상향 돌파 | 직전봉 < 상한 ≤ 현재봉 |
| `cross_down_upper` | 상한선 하향 돌파 | 직전봉 ≥ 상한 > 현재봉 |
| `above_upper` | 상한선 위 위치 | 현재봉 > 상한 (매 확정봉) |

`below_lower` / `above_upper`는 상태 조건이라 조건이 유지되는 동안 **확정봉마다** 매수한다.
돌파 조건은 넘어가는 그 봉에서 **1회만** 발동한다.

### 청산

포지션이 있는 동안 항상 **평단 × (1 + 익절률)** 에 `reduceOnly` 지정가 전량 매도가 걸려 있다.
추가 매수로 평단이 바뀌면 익절가도 따라 움직이고, **평단이나 수량이 실제로 바뀔 때만** 주문을 갱신한다.

### 원본 대비 수정 사항

| # | 문제 | 수정 |
|---|---|---|
| 1 | `set_leverage`/`set_margin_mode`가 호출조차 안 됨 → 1x가 거짓 | 시작 시 적용 + **거래소에서 되읽어 검증**, 앱에 결과 표시 |
| 2 | `min_order_notional=6` (현물 기준, 선물에선 거부됨) | 코인별 실제 최소액 조회 → **$10 단위 올림**. 단 수량 구속 종목(BTC·BNB)은 올리지 않음 |
| 3 | `'BTCUSDT'` raw id 사용, BTC만 지원 | ccxt 통합심볼 정규화 + **USDT 무기한 696종 전체** |
| 4 | 청산 지정가에 `reduceOnly` 없음 | 강제 부여 |
| 5 | 60초마다 TP 무조건 취소·재등록 (하루 1,440회) | **평단/수량이 실제로 바뀔 때만** 갱신 |
| 6 | 매수 실패 시 나머지 TF의 RSI 추적 중단 | try/except를 **TF 단위 안쪽**으로 이동 |
| 7 | API 키 하드코딩 | 앱에서 입력 → 서버에 **암호화 저장**, 조회 시 마스킹 |
| + | 미완성봉 RSI로 리페인팅·중복매수 | **봉마감 확정봉** 고정 + 캔들 타임스탬프 중복 차단 |
| + | 6개 TF, RSI 30 상향돌파 고정 | TF **15종** + RSI 상·하한선 + **진입조건 6종** 선택 |
| + | 매수 399회 상한 | **무제한** |
| + | `pandas_ta` (numpy 2.x에서 import 실패) | Wilder RSI 직접 구현 (pandas_ta와 소수 4자리 일치) |
| + | 재시작 시 상태 소실 | SQLite 영속화 + 부팅 시 봇 자동 복구 |
| + | 긴급 정지 수단 없음 | 전 종목 즉시 취소+시장가 청산 |

---

## 안드로이드 앱

### 빌드 환경 (실측 확인됨)

| 항목 | 값 |
|---|---|
| JDK | **17** (Temurin) — Android Studio 번들 JBR 25 는 Gradle 8.11 이 거부한다 |
| Gradle | 8.11.1 |
| Android SDK | android-35, build-tools 34/36 |

**프로젝트는 한글이 없는 ASCII 경로에 있어야 빌드된다.** 현재 위치
(`바탕 화면/비트코인 전략/`)에서는 AGP 가 아래 오류로 거부한다.

```
Your project path contains non-ASCII characters.
This will most likely cause the build to fail on Windows.
```

`android.overridePathCheck=true` 로 우회해도 결국 파일명 구문 오류로 실패한다.
그래서 작업 사본을 `C:\Users\eleme\dev\elemensha-android` 에 두고 빌드한다.
GitHub 저장소를 만든 뒤에는 그 경로로 clone 해서 쓰면 된다.
서버(Python)는 리눅스에서 돌기 때문에 이 제약과 무관하다.

`local.properties` 의 SDK 경로는 **반드시 슬래시**로 쓴다. 백슬래시는
Java properties 의 유니코드 이스케이프로 해석되어 경로가 깨진다.

```properties
sdk.dir=C:/Users/eleme/AppData/Local/Android/Sdk
```

### 빌드

```bash
cd /c/Users/eleme/dev/elemensha-android
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.8-hotspot" \
  /c/Users/eleme/AppData/Local/Gradle/gradle-8.11.1/bin/gradle assembleDebug
```

### 화면

| 탭 | 내용 |
|---|---|
| 대시보드 | 봇별 포지션·평단·미실현손익·청산가, 봉별 RSI, 정지/긴급청산 |
| 설정 | 위 파라미터 전부 + 레버리지·마진 적용 및 검증 결과 |
| 로그 | 실시간 이벤트 (WebSocket) |
| 더보기 | API 키 등록, 인앱 업데이트, 연결 해제 |

### 배포

APK 최초 1회는 직접 설치하고, 이후에는 앱 안의 **더보기 > 앱 업데이트**로 갱신한다.
`git tag v1.0.1 && git push origin v1.0.1` 하면 GitHub Actions 가 APK 를 빌드해
Releases 에 올리고, 앱이 그것을 받아 설치한다.

---

## API

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/pair` | 페어링 코드 → 토큰 |
| GET | `/api/health` | 상태 (인증 불필요) |
| GET | `/api/app/version` | 인앱 업데이트용 최신 APK 정보 |
| GET/POST/DELETE | `/api/credentials` | API 키 관리 |
| GET | `/api/meta` | 타임프레임·진입조건·기본값 (앱 드롭다운용) |
| GET | `/api/symbols` | 전 종목 목록 + 코인별 최소 주문액 |
| GET/POST | `/api/exchange-settings` | 레버리지·마진모드 조회/적용+검증 |
| GET | `/api/bots` | 전체 봇 상태 |
| POST | `/api/bots/start` | 봇 시작 |
| POST | `/api/bots/{symbol}/stop` | 정지 |
| POST | `/api/bots/{symbol}/panic` | 긴급 청산 |
| POST | `/api/panic-all` | 전체 긴급 청산 |
| GET | `/api/events` | 로그 |
| WS | `/ws?token=` | 실시간 이벤트 스트림 |

---

## 보안

- API 키는 서버에 **Fernet 암호화** 저장, 마스터 키는 DB와 분리된 0600 파일.
- 앱↔서버는 **HTTPS 필수**. 평문 http로 키를 보내지 말 것.
- 바이낸스 API 키 발급 시 **출금 권한은 절대 켜지 말 것**. 선물 거래 권한만.
- 가능하면 **IP 화이트리스트**에 서버 공인 IP만 등록.
