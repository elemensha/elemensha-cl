# 설치 체크리스트

순서대로 따라가면 됩니다. 전부 무료입니다.

---

## 0. 바이낸스 API 키 (먼저)

기존 키는 코드에 평문으로 박혀 OneDrive에 동기화되어 있었습니다. **폐기하고 새로 발급하세요.**

1. [바이낸스 API 관리](https://www.binance.com/en/my/settings/api-management) 접속
2. 기존 키 **삭제**
3. 새 키 생성 시:
   - ✅ **Enable Futures** (선물 거래)
   - ❌ **Enable Withdrawals** — 절대 켜지 말 것
   - ✅ IP 접근 제한 → 서버 공인 IP만 등록 (서버 구축 후 설정)
4. 키는 어디에도 저장하지 말고, 앱에서 바로 입력하세요.

---

## 1. Oracle Cloud 서버 (신규 인스턴스)

기존 `elemensha-bot` 과 **완전히 분리된** 새 인스턴스를 만듭니다.

### 1-1. 인스턴스 생성

Oracle 콘솔 → **Compute > Instances > Create instance**

| 항목 | 값 |
|---|---|
| Name | **`elemensha-claude-bot`** |
| Image | **Canonical Ubuntu 22.04** — 반드시 이것 (아래 이유 참고) |
| Shape | 아래 표 참고 — **"Always Free eligible" 배지 필수** |
| SSH key | 기존 `ssh-key-2026-08-22.key` 의 공개키 재사용 가능 |

> ### Oracle Linux 9 를 쓰면 안 되는 이유 (실측)
>
> 같은 `VM.Standard.E2.1.Micro` 인데도 사용 가능한 메모리가 2배 넘게 차이난다.
>
> | | Oracle Linux 9 | Ubuntu 22.04 |
> |---|---|---|
> | `free -m` 총 RAM | **498MB** | **956MB** |
> | 부팅 직후 가용 | 209MB | **564MB** |
> | 패키지 관리자 | `dnf` (파이썬, 200~400MB) | `apt` (C, 훨씬 가벼움) |
> | 결과 | **스왑 2.5GB 를 붙여도 `dnf` 가 OOM-Kill** | 정상 |
>
> OL9 는 kdump 용 crashkernel 로 RAM 절반을 예약해 버린다. 여기에 메모리를
> 많이 쓰는 `dnf` 가 겹치면서, 스왑을 아무리 늘려도 설치가 끝까지 실패했다.
> 실제로 그 상태에서 SSH 까지 두 번 응답 불능이 됐다.

> ⚠️ **Always Free 배지를 반드시 확인하세요.** 현재 계정은 Free **Trial** 상태라
> 크레딧이 있는 동안은 아무 shape 나 만들어지지만, 체험이 끝나면 Always Free
> 자격이 없는 인스턴스는 **정지·삭제**됩니다. "무료 평생 운용"의 핵심 조건입니다.

| Shape | 사양 | Always Free 한도 | 비고 |
|---|---|---|---|
| `VM.Standard.A1.Flex` | ARM, 1 OCPU / 6GB | 합산 4 OCPU / 24GB | **권장** — 여유롭다 |
| `VM.Standard.E2.1.Micro` | x86, 1/8 OCPU / 1GB | 계정당 **2대** | 기존 봇이 1대 사용 중 → 1대 남음 |

기존 `elemensha-bot` 이 E2.1.Micro 를 쓰고 있으므로 두 shape 모두 여유가 있습니다.
A1.Flex 는 리전에 따라 "Out of capacity" 가 자주 뜨는데, 그럴 땐 E2.1.Micro 로
만들면 됩니다.

**1GB micro 대응** — 설치 스크립트가 자동으로 처리합니다.

| 문제 | 자동 처리 |
|---|---|
| pip 설치 중 OOM | 스왑 2GB 생성 (`/swapfile`, fstab 등록) |
| 메모리 폭주 | systemd `MemoryMax` 를 RAM 의 65% 로 자동 계산 |
| Oracle Linux 9 의 python3.9 | `python3.11` 우선 설치 후 venv 구성 |
| SELinux enforcing | `httpd_can_network_connect` 활성화 |
| iptables REJECT 기본 규칙 | 80/443 ACCEPT 삽입 |
| COPR 저장소 실패 | Caddy 정적 바이너리 직접 설치로 폴백 |

### 1-2. 네트워크 열기

**네트워킹 > 가상 클라우드 네트워크 > 서브넷 > 보안 목록** 에서 인그레스 추가:

| 소스 | 프로토콜 | 포트 | 용도 |
|---|---|---|---|
| 0.0.0.0/0 | TCP | 22 | SSH |
| 0.0.0.0/0 | TCP | 80 | Let's Encrypt 인증 |
| 0.0.0.0/0 | TCP | 443 | 앱 접속 |

### 1-3. 배포

Git Bash 에서 한 줄이면 됩니다. 계정(`ubuntu`/`opc`)은 자동 감지합니다.

```bash
bash server/deploy/upload.sh <새_VM_공인IP>
```

코드 업로드 → 스왑 → Python 환경 → systemd → 방화벽 → Caddy → 기동까지 하고
마지막에 **페어링 코드**를 출력합니다.

| 설치되는 것 | 값 |
|---|---|
| systemd 서비스 | `elemensha-claude-bot` |
| 경로 | `/opt/elemensha-claude-bot` |
| 내부 포트 | 8090 (외부 비공개, Caddy 만 접근) |
| 서비스 계정 | `elemensha-claude-bot` |

---

## 2. 도메인 + HTTPS

앱이 API 키를 보내는 경로이므로 HTTPS가 필수입니다.

1. [DuckDNS](https://www.duckdns.org) 로그인 (무료)
2. 서브도메인 생성 — 기존 봇과 겹치지 않게 (예: `elemensha-claude`)
3. **새** VM 공인 IP 입력
4. 서버에서 도메인 반영:

```bash
sudo sed -i 's/elemensha.duckdns.org/내서브도메인.duckdns.org/' /etc/caddy/elemensha-claude-bot.caddy
sudo systemctl reload caddy
```

Caddy가 Let's Encrypt 인증서를 자동 발급·갱신합니다.

---

## 3. GitHub 저장소 (APK 빌드·배포)

1. 저장소 생성 — 공개면 Actions 분당 요금이 무제한 무료
2. 이 폴더를 push
3. 서명 키 생성:

```bash
keytool -genkey -v -keystore elemensha.jks -keyalg RSA -keysize 2048 -validity 10000 -alias elemensha
```

4. 저장소 **Settings > Secrets and variables > Actions**에 등록:

| 이름 | 값 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 elemensha.jks` 출력 |
| `KEYSTORE_PASSWORD` | 키스토어 비밀번호 |
| `KEY_ALIAS` | `elemensha` |
| `KEY_PASSWORD` | 키 비밀번호 |

5. 서버 `.env`에 저장소 연결:

```bash
sudo sed -i 's|ELEMENSHA_GITHUB_REPO=.*|ELEMENSHA_GITHUB_REPO=사용자명/저장소명|' /opt/elemensha-claude-bot/.env
sudo systemctl restart elemensha-claude-bot
```

6. 릴리스 발행:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

Actions가 APK를 빌드해 Releases에 올립니다.

---

## 4. 앱 설치 (리더)

릴리스에는 APK가 **두 개** 올라옵니다. 리더는 앞의 것을 받습니다.

| APK | 누구용 |
|---|---|
| `elemensha-<버전>.apk` | **리더** — 봇을 직접 돌리는 사람 |
| `elemensha-copy-<버전>.apk` | 팔로워 — 리더의 매매를 카피하는 사람 |

1. GitHub Releases에서 `elemensha-<버전>.apk` 다운로드 → 폰에서 설치
   (‘알 수 없는 앱 설치’ 1회 허용 필요)
2. 앱 실행 → 서버 주소 `https://내서브도메인.duckdns.org` + 페어링 코드 입력
3. **더보기 > API 키**에서 바이낸스 키 등록 → 잔고가 뜨면 연결 성공
4. **설정** 탭에서 파라미터 지정 후 봇 시작

이후 새 버전은 **더보기 > 앱 업데이트**에서 갱신합니다.

---

## 5. 팔로워 붙이기 (카피 트레이딩)

리더 봇이 잘 돌기 시작한 다음에 한다.

### 5-1. 초대코드 발급 (리더가)

```bash
curl -X POST https://<도메인>/api/invites   -H "Authorization: Bearer <리더 앱 토큰>"   -H "Content-Type: application/json"   -d '{"label":"친구1","maxUses":1,"ttlHours":24}'
```

응답의 `code`(`A1B2-C3D4` 꼴)를 팔로워에게 전달한다. 1회용이라 가입하면 소진된다.

### 5-2. 팔로워 앱 설치 (팔로워가)

릴리스에서 **`elemensha-copy-<버전>.apk`** 를 받는다.
`elemensha-<버전>.apk` 는 리더용이니 헷갈리지 말 것. 둘은 별개 앱이라
한 기기에 함께 깔 수 있다.

1. 앱을 열고 서버 주소 + 초대코드 입력 → 가입
2. **더보기 > API 키** 에서 자기 바이낸스 키 등록
   - **출금 권한 없이** 선물 거래 권한만
   - 가능하면 서버 공인 IP 화이트리스트
3. **설정** 에서 주문 크기 방식 선택
   - 자산 비례 / 고정 배수 / 고정 금액
4. **설정 > 모의 실행** 을 켜고 며칠 돌려 금액이 의도대로 나오는지 확인
5. 확인되면 모의 실행을 끄고 **카피 시작**

### 5-3. 리더가 팔로워를 볼 때

```bash
curl https://<도메인>/api/followers -H "Authorization: Bearer <리더 토큰>"
```

팔로워의 API 키는 마스킹된 값만 나온다. 잔고·포지션·로그는 아예 보이지 않는다.
문제가 있으면 정지(`POST /api/followers/<id>/stop`)나 삭제
(`DELETE /api/followers/<id>`)는 할 수 있지만, 팔로워의 포지션을 대신
청산할 수는 없다. 청산은 팔로워가 자기 앱의 [계좌 > 긴급 청산]으로 한다.

### 5-4. 팔로워 자산이 작을 때

BTC의 실질 최소 주문액은 `0.001 × 가격` 이라 가격이 $110,000이면 $110이다.
자산 비례 배율로 계산한 금액이 여기 못 미치면 기본 설정에서는 **건너뛴다**.
[계좌] 화면과 로그에 이유가 남는다. 방법은 셋 중 하나다.

- 최소 주문액이 작은 알트코인만 따라가도록 [설정 > 따라갈 종목] 에서 고른다
- 레버리지를 올려 같은 증거금으로 더 큰 명목가치를 잡는다 (위험도 함께 커진다)
- [설정 > 최소 주문금액에 못 미칠 때] 를 '최소 금액으로 올려 매수'로 바꾼다
  (의도한 배율보다 훨씬 큰 주문이 나가므로 권장하지 않는다)

---

## 안드로이드 로컬 빌드 (선택)

한글 경로에서는 빌드가 안 됩니다. ASCII 경로로 clone하세요.

```bash
git clone <저장소> /c/Users/eleme/dev/elemensha
cd /c/Users/eleme/dev/elemensha/android
printf 'sdk.dir=C:/Users/eleme/AppData/Local/Android/Sdk\n' > local.properties
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.8-hotspot" \
  /c/Users/eleme/AppData/Local/Gradle/gradle-8.11.1/bin/gradle assembleDebug
```

모듈이 둘입니다. `assembleDebug` 는 둘 다 만들고, 하나만 필요하면
`:app:assembleDebug` 또는 `:copyapp:assembleDebug` 를 씁니다.

| 모듈 | 결과물 | 앱 |
|---|---|---|
| `:app` | `app/build/outputs/apk/` | 리더 |
| `:copyapp` | `copyapp/build/outputs/apk/` | 팔로워 |

Android Studio로 열 때는 **Settings > Build Tools > Gradle > Gradle JDK**를
**17**로 바꿔야 합니다. 번들 JBR 25는 Gradle 8.11이 지원하지 않습니다.

---

## 알려진 함정 (실제로 겪은 것들)

| 증상 | 원인 | 대응 |
|---|---|---|
| `set: pipefail: invalid option name` | Windows 에서 편집한 스크립트가 **CRLF** 로 올라감. bash 가 `pipefail
` 로 읽는다 | `.gitattributes` 로 `*.sh eol=lf` 강제 + `upload.sh` 가 업로드 직후 CR 제거 |
| SSH 가 `banner exchange` 에서 타임아웃 | **메모리 고갈**. 498MB 머신에서 `dnf list` 같은 명령 하나로도 sshd 가 fork 실패 | 콘솔에서 **Reboot**. 무거운 명령을 직접 실행하지 말 것 |
| ARM 인스턴스 `Out of capacity` | 도쿄 리전 A1.Flex 품절. AD 는 AD-1 하나뿐이라 우회 불가 | 시간대를 바꿔 재시도하거나 micro 로 진행 |
| Kotlin 이 `Unclosed comment` 로 파일 끝에서 터짐 | KDoc 본문에 `/api/copy/*` 처럼 `/*` 를 썼다. Kotlin 블록 주석은 **중첩**되므로 안쪽 주석이 열리고 바깥이 안 닫힌다 | 주석 안에서는 경로를 `/api/copy/` 처럼 쓰거나 백틱으로 감싼다 |
| 팔로워 앱이 리더 APK 를 받아 깔려 함 | 릴리스에 APK 가 둘인데 앱이 첫 번째 `.apk` 자산을 집었다 | 이름으로 구분한다 — `elemensha-copy-` 가 붙은 쪽이 팔로워용 (`main.py` 의 `COPY_APK_MARKER`) |
| 인스턴스가 `Stopping` 에서 멈춤 | 메모리 부족으로 OS 가 종료 신호에 응답 못 함 | 5분 넘으면 **Actions > Reset** |

### 서버에서 직접 명령을 실행할 때

RAM 498MB 이므로 아래는 sshd 를 죽일 수 있습니다.

```bash
dnf list --available ...      # 메타데이터 로딩만으로 수백 MB
dnf update                    # 스왑 확보 전에는 금지
pip install pandas/numpy      # 이 프로젝트는 애초에 쓰지 않는다
```

안전하게 상태만 보려면:

```bash
sudo systemctl status elemensha-claude-bot
sudo journalctl -u elemensha-claude-bot -n 50 --no-pager
free -m
```
