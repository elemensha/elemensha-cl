# elemensha

바이낸스 USDⓈ-M 선물 RSI DCA 봇 — 서버 + 안드로이드 원격조종 앱 + **카피 트레이딩**.
**월 운영비 $0** (Oracle Cloud Always Free + GitHub Actions/Releases).

앱은 두 개다.

| 앱 | 패키지 | 누구용 |
|---|---|---|
| **elemensha** | `com.elemensha.app` | 리더 — 봇을 직접 돌린다 |
| **elemensha copy** | `com.elemensha.copy` | 팔로워 — 리더의 매매를 자기 계정으로 따라 한다 |

서버는 하나다. 팔로워는 리더 서버에 초대코드로 가입하고 자기 API 키를 등록한다.

---

## 왜 이 구조인가

봇을 앱 안에서 돌리면 휴대폰이 꺼지거나 절전에 들어갈 때 매매가 멈춘다.
그래서 **봇은 서버에서 24시간 돌고, 앱은 원격조종기**로 만든다.

```
 [elemensha]  리더 앱                [Oracle Cloud Always Free VM]
  - 파라미터 설정      HTTPS          Caddy (무료 자동 TLS)
  - 실시간 RSI/포지션 ──────────▶          │
  - 시작/정지/긴급청산   WebSocket         ▼
  - 초대코드 발급     ◀──────────      FastAPI ──▶ 리더 바이낸스 계정
                                          │
 [elemensha copy]  팔로워 앱               │  매수 신호
  - 내 자산/포지션     HTTPS               ▼
  - 내 지정가 주문   ──────────▶      카피 엔진 ──▶ 팔로워 A 바이낸스 계정
  - 주문 크기 방식      WebSocket                └▶ 팔로워 B 바이낸스 계정
  - 시작/정지/긴급청산 ◀──────────
                                     SQLite (설정·상태·로그·잔고)
        │                            Fernet 암호화된 API 키 (계정별 분리)
        ▼
  GitHub Releases (APK 2종 무료 호스팅)
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
│   ├── main.py       FastAPI — REST + WebSocket, 리더용 팔로워 관리
│   ├── engine.py     심볼별 봇 감독자 (asyncio)
│   ├── strategy.py   RSI DCA 전략 엔진 + 익절 주문 공용 로직
│   ├── copy.py       카피 엔진 — 사이징·미러링·팔로워 수명주기
│   ├── copy_api.py   팔로워 전용 API (토큰으로 범위 강제)
│   ├── exchange.py   바이낸스 선물 래퍼 (심볼/정밀도/최소금액/레버리지)
│   ├── store.py      SQLite + API 키 암호화(Fernet), 테넌트 격리
│   └── config.py     환경변수 설정
├── tests/
│   ├── test_copy_api.py    권한 격리·초대코드·마이그레이션
│   └── test_copy_flow.py   신호→매수→익절→복구 (가짜 거래소)
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

## 카피 트레이딩

리더 봇이 매수하면 팔로워 계정들이 각자의 배율로 같은 종목을 따라 산다.
팔로워는 **리더 서버에 세들어 사는 별개 테넌트**다.

### 진입은 따라가고, 청산은 각자 한다

이게 이 구현의 핵심 결정이다.

| | 동작 |
|---|---|
| 리더 매수 | 팔로워도 즉시 시장가 매수 (배율 적용) |
| 팔로워 익절 | **팔로워 자기 평단** × (1 + 익절률) 에 자기 reduceOnly 지정가 |
| 리더 익절 완료 | 팔로워 지정가는 **그대로 유지** — 강제 청산하지 않는다 |
| 손절 | 없음 (리더 전략과 동일) |

리더의 익절가를 그대로 베끼지 않는 이유: 체결가가 미세하게 달라 평단이
어긋나므로, 베끼면 팔로워는 자기 목표보다 낮은 값에 팔게 된다.
리더가 먼저 익절돼도 팔로워를 시장가로 밀어내지 않는 이유: 손실이 확정된다.
이 상태(`waitingAlone`)는 이상이 아니라 정상이며, 앱에 그대로 표시된다.

### 주문 크기 — 앱에서 세 방식 중 선택

| 방식 | 계산 | 쓰임 |
|---|---|---|
| **자산 비례** *(기본)* | 내 순자산 ÷ 리더 잔고 × 보정값 | 자산이 리더의 1/10이면 주문도 1/10 |
| **고정 배수** | 리더 수량 × N | 0.5 = 절반, 2 = 두 배 |
| **고정 금액** | 매번 정해진 USDT | 리더가 몇 번을 사든 나는 항상 $N |

세 방식 모두 내부적으로 **배율 하나**로 환산된다. 그래야 배율 상한과
포지션 상한 같은 안전장치를 한 군데에서 일관되게 걸 수 있다.

### 최소 주문금액 처리

팔로워 자산이 작으면 계산된 주문액이 거래소 최소치에 못 미친다.
BTC는 `minQty 0.001 × 가격`이 실질 최소라 가격이 $110,000이면 $110이다.

- **건너뛰기** *(기본)* — 사지 않고 이유를 로그와 [계좌] 화면에 남긴다.
- **최소 금액으로 올림** — 최소치까지 올려 산다. 의도한 배율보다 훨씬
  커질 수 있어 기본값이 아니다.

### 안전장치

| 항목 | 기본값 | 효과 |
|---|---|---|
| 배율 상한 | 없음 | 리더 대비 배율이 이 값을 넘지 않는다 |
| 종목별 포지션 상한 | 없음 | 이 금액에 닿으면 추가 매수 중단 |
| 모의 실행 | 꺼짐 | 실제 주문 없이 계산 결과만 로그에 남긴다 |

### 격리 — 누가 무엇을 볼 수 있나

| | 리더 | 팔로워 본인 | 다른 팔로워 |
|---|---|---|---|
| 팔로워 API 키 | 마스킹만 | 마스킹만 | ✗ |
| 팔로워 잔고·포지션·주문 | ✗ | ✓ | ✗ |
| 팔로워 로그 | ✗ | ✓ | ✗ |
| 팔로워 카피 정지·계정 삭제 | ✓ | ✓ | ✗ |
| 팔로워 포지션 청산 | ✗ | ✓ | ✗ |
| 리더 봇 제어·리더 잔고 | ✓ | ✗ | ✗ |

토큰에서 뽑은 `follower_id` 로 모든 조회 범위가 강제된다. 경로나 본문으로
받은 id 는 신뢰하지 않는다. 리더는 팔로워를 **정지·삭제**할 수 있지만
**청산**은 못 한다 — 남의 계좌를 대신 정리해서는 안 되기 때문이다.

리더 토큰과 팔로워 토큰은 같은 `devices` 테이블에 살되 `follower_id` 로
갈린다. `store.verify_token()` 은 `follower_id IS NULL` 인 리더 토큰만
통과시키므로, 팔로워 토큰으로는 리더 엔드포인트에 닿을 수 없다.

### 팔로워 초대

리더 앱 **더보기 > 팔로워 관리**에서 코드를 발급하고 바로 공유한다.

```
리더 앱 [팔로워 관리]                    팔로워 앱
  코드 발급 → 복사/공유   ──▶  코드 전달  ──▶  초대코드 입력 → 가입
                                                        │
                                                   토큰 + 계정 생성
```

같은 화면에서 팔로워 목록(카피 여부·키 등록 여부·기기 수)을 보고
카피 정지나 계정 삭제도 할 수 있다. 팔로워의 잔고·포지션·로그는 나오지 않는다.

초대코드는 1회용이 기본이고 유효기간을 걸 수 있다. 사용 검사와 카운트 증가를
한 SQL 문 안에서 처리하므로, 동시에 들어온 두 요청이 같은 1회분을 함께
통과하지 못한다.

앱 없이 직접 부를 수도 있다:

```bash
curl -X POST https://<서버>/api/invites \
  -H "Authorization: Bearer <리더 토큰>" \
  -H "Content-Type: application/json" \
  -d '{"label":"친구1","maxUses":1,"ttlHours":24}'
```

### 팔로워 앱 화면

| 탭 | 내용 |
|---|---|
| 계좌 | 내 순자산·지갑·미실현손익, 포지션(평단·청산가·익절가), 종목별 카피 현황, 긴급 청산 |
| 주문 | 내 미체결 지정가 — 지정가·현재가·익절까지 남은 거리·부분체결·reduceOnly 여부 |
| 잔고 | 내 순자산 곡선 (일/주/월/분기/연) |
| 설정 | 주문 크기 방식, 안전장치, 레버리지·마진, 익절률, 따라갈 종목, 시작/정지 |
| 더보기 | 내 API 키, 로그, 앱 업데이트, 연결 해제 |

주문·계좌 화면은 서버가 기억하는 값이 아니라 **거래소에 직접 물어본 결과**를
보여준다. 둘이 어긋나는 순간이 바로 확인이 필요한 순간이기 때문이다.

### 카피 API

전부 팔로워 토큰으로만 접근된다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/copy/join` | 초대코드 → 토큰 + 계정 생성 |
| GET | `/api/copy/meta` | 사이징 방식·기본값 (인증 불필요) |
| GET | `/api/copy/me` | 내 계정 정보 |
| GET/POST/DELETE | `/api/copy/credentials` | 내 API 키 |
| GET/POST | `/api/copy/config` | 카피 설정 |
| POST | `/api/copy/start` `/stop` `/panic` | 시작 / 정지 / 내 포지션만 긴급청산 |
| GET | `/api/copy/status` | 카피 현황 + 리더 종목 목록 |
| GET | `/api/copy/account` | 내 계좌 (거래소 실시간) |
| GET | `/api/copy/orders` | 내 미체결 주문 (거래소 실시간) |
| GET | `/api/copy/positions` | 내 포지션 (거래소 실시간) |
| GET | `/api/copy/balance/history` | 내 잔고 곡선 |
| GET | `/api/copy/events` | 내 로그 |
| WS | `/ws/copy?token=` | 내 실시간 스트림 |
| GET | `/api/copy/app/version` | 팔로워 APK 최신 버전 |

리더 전용 관리 엔드포인트:

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET/POST | `/api/invites` | 초대코드 목록 / 발급 |
| DELETE | `/api/invites/{code}` | 초대코드 폐기 |
| GET | `/api/followers` | 팔로워 목록 (키는 마스킹) |
| POST | `/api/followers/{id}/stop` | 카피 정지 |
| DELETE | `/api/followers/{id}` | 계정 삭제 (포지션은 건드리지 않음) |

### 테스트

거래소 없이 전 경로를 검증한다. CI 에서 매 푸시마다 돈다.

```bash
cd server
python tests/test_copy_api.py    # 권한 격리·초대코드·마이그레이션 45건
python tests/test_copy_flow.py   # 신호→매수→익절→복구 전 경로 36건
```

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

두 모듈이 한 프로젝트에 있다. `assembleRelease` 는 둘 다 만든다.

```bash
cd /c/Users/eleme/dev/elemensha-android
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.8-hotspot" \
  /c/Users/eleme/AppData/Local/Gradle/gradle-8.11.1/bin/gradle assembleRelease
```

`:app` 만 또는 `:copyapp` 만 빌드하려면 태스크 앞에 모듈명을 붙인다
(`gradle :copyapp:assembleDebug`).

### 화면 (리더 앱)

| 탭 | 내용 |
|---|---|
| 대시보드 | 봇별 포지션·평단·미실현손익·청산가, 봉별 RSI, 정지/긴급청산 |
| 잔고 | 순자산 곡선 |
| 설정 | 위 파라미터 전부 + 레버리지·마진 적용 및 검증 결과 |
| 로그 | 실시간 이벤트 (WebSocket) |
| 더보기 | API 키 등록, **팔로워 관리**, 인앱 업데이트, 연결 해제 |

팔로워 앱 화면은 [카피 트레이딩](#팔로워-앱-화면) 절 참고.

### 배포

APK 최초 1회는 직접 설치하고, 이후에는 앱 안의 **더보기 > 앱 업데이트**로 갱신한다.
`git tag v1.0.1 && git push origin v1.0.1` 하면 GitHub Actions 가 APK **2종**을
빌드해 Releases 에 올리고, 각 앱이 자기 것을 받아 설치한다.

| 릴리스 자산 | 받는 앱 | 서버 경로 |
|---|---|---|
| `elemensha-1.0.1.apk` | 리더 | `/api/app/version` |
| `elemensha-copy-1.0.1.apk` | 팔로워 | `/api/copy/app/version` |

> `elemensha-copy-` 라는 이름 규칙으로 서버가 둘을 구분한다
> (`main.py` 의 `COPY_APK_MARKER`). 규칙을 바꾸면 팔로워 앱의 인앱
> 업데이트가 리더 APK 를 받아 깔려 든다.

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

### 카피 트레이딩을 쓸 때

- 팔로워의 API 키가 **리더 서버에 보관**된다. 이 서버를 운영하는 사람은
  남의 거래 권한을 맡고 있는 것이다. 초대는 신뢰하는 상대에게만.
- 팔로워도 **출금 권한 없는 선물 전용 키**를 써야 한다. 그러면 서버가
  뚫려도 자금을 빼갈 수는 없다.
- 서버 파일시스템의 `master.key` 하나가 모든 팔로워 키를 푼다.
  이 파일은 백업에 넣지 말 것.
- 처음 붙이는 팔로워는 **테스트넷** 또는 **모의 실행**으로 며칠 돌려
  주문 금액이 의도대로 나오는지 확인하고 실거래로 넘어가는 게 안전하다.
