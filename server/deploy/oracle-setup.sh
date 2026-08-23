#!/usr/bin/env bash
# ============================================================================
#  elemensha-claude-bot 서버 설치 스크립트 — Oracle Cloud
#
#  지원: Ubuntu(apt) / Oracle Linux·RHEL(dnf), x86_64 및 ARM 모두
#
#  ※ 완전 격리
#     기존 "elemensha-bot" 과 완전히 분리된 별도 인스턴스에 올리는 것을 전제로 한다.
#     서비스·디렉터리·계정·포트·Caddy 설정이 전부 다른 이름을 쓰므로,
#     설령 같은 VM 에 올려도 기존 봇을 건드리지 않는다.
#     이름 변경:  sudo APP_NAME=<이름> APP_PORT=<포트> bash oracle-setup.sh
#
#  ※ Always Free 자격 확인 (중요)
#     인스턴스 생성 시 "Always Free eligible" 배지가 붙은 shape 를 골라야
#     무료 체험 기간이 끝나도 유지된다. 배지 없는 shape 는 크레딧 소진과 함께
#     과금되거나 종료된다.
#       - VM.Standard.E2.1.Micro : x86, 1/8 OCPU / 1GB   (계정당 2대)
#       - VM.Standard.A1.Flex    : ARM, 합산 4 OCPU / 24GB
#
#  사용법 (VM 에 ssh 접속 후):
#     sudo bash oracle-setup.sh
# ============================================================================
set -euo pipefail

APP_NAME="${APP_NAME:-elemensha-claude-bot}"
APP_PORT="${APP_PORT:-8090}"          # elemensha-bot 이 8080 을 쓸 수 있으므로 회피
APP_DIR="/opt/${APP_NAME}"
REPO_DIR="${APP_DIR}/server"
VENV="${APP_DIR}/venv"

log()  { echo -e "\n\033[1;32m==>\033[0m $*"; }
warn() { echo -e "\033[1;33m !\033[0m $*"; }
die()  { echo -e "\n\033[1;31m!!\033[0m $*" >&2; exit 1; }

# Ubuntu(apt) / Oracle Linux·RHEL(dnf) 양쪽을 지원한다.
# Oracle Cloud 이미지에 따라 기본 계정이 ubuntu 또는 opc 로 갈린다.
if command -v apt-get &>/dev/null; then
  PKG=apt; FIREWALL=ufw
elif command -v dnf &>/dev/null; then
  PKG=dnf; FIREWALL=firewalld
else
  die "지원하지 않는 배포판입니다 (apt 또는 dnf 필요)."
fi

pkg_install() {
  if [[ $PKG == apt ]]; then
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$@"
  else
    dnf install -y -q "$@"
  fi
}

[[ $EUID -eq 0 ]] || die "sudo 로 실행하세요."
[[ -f "${REPO_DIR}/requirements.txt" ]] \
  || die "${REPO_DIR} 에 서버 코드가 없습니다. 코드를 먼저 올리세요."

log "0/9  기존 설치 확인"
for other in elemensha elemensha-bot; do
  if systemctl list-unit-files "${other}.service" &>/dev/null \
     && systemctl is-active --quiet "$other" 2>/dev/null; then
    warn "'${other}' 서비스가 이 VM 에서 실행 중입니다 — 건드리지 않고 그대로 둡니다."
  fi
done
if ss -ltn 2>/dev/null | grep -q ":${APP_PORT}\b"; then
  die "포트 ${APP_PORT} 가 이미 사용 중입니다. APP_PORT=<다른포트> 로 다시 실행하세요."
fi
echo "  설치 이름: ${APP_NAME}   포트: ${APP_PORT}   경로: ${APP_DIR}"

# ── 스왑을 '패키지 설치보다 먼저' 만든다 ──────────────────────────────────
# E2.1.Micro 는 가용 RAM 이 500MB 수준이라, 스왑 없이 dnf/apt 를 돌리면
# 메모리가 고갈되어 sshd 까지 응답을 멈춘다(실제로 겪음). 스왑 생성에 필요한
# fallocate/mkswap/swapon 은 기본 이미지에 이미 들어 있으므로 선행 가능하다.
TOTAL_MB=$(free -m | awk '/Mem:/{print $2}')
SWAP_MB=$(free -m | awk '/Swap:/{print $2}')
WANT_SWAP_MB=2048     # 새로 만들 때의 크기
MIN_SWAP_MB=1536      # 이미 이만큼 있으면 손대지 않는다 (cloud-init 이 만들어 둔 경우)
if (( TOTAL_MB < 2048 )) && (( SWAP_MB < MIN_SWAP_MB )); then
  log "1/9  스왑 보강 (RAM ${TOTAL_MB}MB / 현재 스왑 ${SWAP_MB}MB)"
  if ! swapon --show=NAME --noheadings 2>/dev/null | grep -qx /extraswap; then
    # fallocate 는 쓰지 않는다. XFS 에서 구멍(sparse) 있는 파일을 만들면
    # swapon 이 "it appears to have holes" 로 거부한다. dd 로 실제 블록을 채운다.
    rm -f /extraswap
    echo "  ${WANT_SWAP_MB}MB 할당 중..."
    dd if=/dev/zero of=/extraswap bs=1M count="$WANT_SWAP_MB" status=none
    chmod 600 /extraswap
    mkswap /extraswap >/dev/null     # -q 는 배포판에 따라 없다
    swapon /extraswap                # 실패하면 여기서 즉시 중단된다
  fi
  grep -q '/extraswap' /etc/fstab || echo '/extraswap none swap sw 0 0' >> /etc/fstab
  sysctl -w vm.swappiness=60 >/dev/null 2>&1 || true
  grep -q 'vm.swappiness' /etc/sysctl.conf || echo 'vm.swappiness=60' >> /etc/sysctl.conf

  SWAP_MB=$(free -m | awk '/Swap:/{print $2}')
  echo "  스왑 합계: ${SWAP_MB}MB"
  # 조용히 넘어가지 않는다 — 스왑 없이 패키지 설치를 돌리면
  # OOM 으로 sshd 까지 죽어 콘솔 재부팅이 필요해진다.
  (( SWAP_MB >= MIN_SWAP_MB )) \
    || die "스왑 확보 실패 (${SWAP_MB}MB < ${MIN_SWAP_MB}MB).
  서버에서 'swapon /extraswap' 을 직접 실행해 원인을 확인하세요."
else
  log "1/9  스왑 확인 (RAM ${TOTAL_MB}MB / 스왑 ${SWAP_MB}MB) — 충분"
fi

log "2/9  시스템 패키지 (${PKG})"
if [[ $PKG == apt ]]; then
  apt-get update -qq
  pkg_install python3 python3-venv python3-pip git curl ufw iproute2 \
              ca-certificates debian-keyring debian-archive-keyring apt-transport-https
else
  # 메모리가 빠듯하므로 권장 패키지를 끌어오지 않고 캐시도 남기지 않는다
  DNF_OPTS=(--setopt=install_weak_deps=False --setopt=keepcache=0)
  dnf clean packages -q 2>/dev/null || true
  # Oracle Linux 9 는 python3.9 가 기본이라 3.11 을 별도로 올린다
  dnf install -y -q "${DNF_OPTS[@]}" python3.11 python3.11-pip git curl \
      firewalld iproute ca-certificates tar \
    || dnf install -y -q "${DNF_OPTS[@]}" python3 python3-pip git curl \
      firewalld iproute ca-certificates tar
  systemctl enable --now firewalld >/dev/null 2>&1 || true
  # SELinux 가 enforcing 이면 리버스 프록시 연결을 막는다
  if command -v getenforce &>/dev/null && [[ $(getenforce) == Enforcing ]]; then
    setsebool -P httpd_can_network_connect 1 2>/dev/null || true
    echo "  SELinux: 네트워크 연결 허용 설정"
  fi
  dnf clean all -q 2>/dev/null || true
fi

log "3/9  서비스 계정 + 디렉터리"
id -u "$APP_NAME" &>/dev/null \
  || useradd --system --create-home --shell /usr/sbin/nologin "$APP_NAME"
mkdir -p "${APP_DIR}/data"
chown -R "$APP_NAME:$APP_NAME" "$APP_DIR"

log "4/9  Python 가상환경"
PY_BIN=$(command -v python3.11 || command -v python3)
echo "  인터프리터: $PY_BIN ($($PY_BIN -V 2>&1))"
if [[ ! -x "${VENV}/bin/python" ]]; then
  "$PY_BIN" -m venv "$VENV" 2>/dev/null \
    || { pkg_install python3-virtualenv; "$PY_BIN" -m venv "$VENV"; }
fi
# --no-cache-dir: 500MB 머신에서 pip 캐시가 메모리·디스크를 잡아먹는다
"${VENV}/bin/pip" install --quiet --no-cache-dir --upgrade pip
"${VENV}/bin/pip" install --quiet --no-cache-dir -r "${REPO_DIR}/requirements.txt"

log "5/9  환경설정"
ENV_FILE="${APP_DIR}/.env"
if [[ ! -f "$ENV_FILE" ]]; then
  PAIR_CODE=$(head -c8 /dev/urandom | od -An -tx1 | tr -d ' \n' | tr 'a-f' 'A-F')
  cat > "$ENV_FILE" <<ENVEOF
ELEMENSHA_DATA_DIR=${APP_DIR}/data
ELEMENSHA_HOST=127.0.0.1
ELEMENSHA_PORT=${APP_PORT}
ELEMENSHA_PAIRING_CODE=${PAIR_CODE}
ELEMENSHA_GITHUB_REPO=
ELEMENSHA_LOG_LEVEL=info
ENVEOF
  echo "  페어링 코드: ${PAIR_CODE}   <- 앱 최초 연결 시 입력"
else
  echo "  기존 .env 유지"
fi
chown "$APP_NAME:$APP_NAME" "$ENV_FILE"
chmod 600 "$ENV_FILE"

log "6/9  systemd 서비스 (${APP_NAME}.service)"
# RAM 의 65% 를 메모리 상한으로. 1GB micro 에서는 약 660M.
APP_MEM="$(( TOTAL_MB * 65 / 100 ))M"
echo "  메모리 상한: ${APP_MEM} (전체 ${TOTAL_MB}MB)"
sed -e "s|@APP_MEM@|${APP_MEM}|g" \
    -e "s|@APP_NAME@|${APP_NAME}|g" \
    -e "s|@APP_DIR@|${APP_DIR}|g" \
    -e "s|@APP_PORT@|${APP_PORT}|g" \
    "${REPO_DIR}/deploy/elemensha.service.template" \
    > "/etc/systemd/system/${APP_NAME}.service"
systemctl daemon-reload
systemctl enable "$APP_NAME" >/dev/null

log "7/9  방화벽 (${FIREWALL})"
if [[ $FIREWALL == ufw ]]; then
  ufw allow OpenSSH >/dev/null
  ufw allow 80/tcp   >/dev/null
  ufw allow 443/tcp  >/dev/null
  ufw --force enable >/dev/null
  netfilter-persistent save 2>/dev/null || true
else
  firewall-cmd --permanent --add-service=ssh   >/dev/null
  firewall-cmd --permanent --add-service=http   >/dev/null
  firewall-cmd --permanent --add-service=https  >/dev/null
  firewall-cmd --reload >/dev/null
fi
# Oracle Linux 이미지는 iptables 에 REJECT 규칙이 따로 박혀 있다
if iptables -C INPUT -p tcp --dport 443 -j ACCEPT 2>/dev/null; then :; else
  iptables -I INPUT 1 -p tcp -m multiport --dports 80,443 -j ACCEPT 2>/dev/null || true
  command -v netfilter-persistent >/dev/null && netfilter-persistent save 2>/dev/null ||     (command -v iptables-save >/dev/null && iptables-save > /etc/iptables/rules.v4 2>/dev/null) || true
fi
echo "  ${FIREWALL}: 22/80/443 허용 (앱 포트 ${APP_PORT} 는 외부 비공개, Caddy 만 접근)"
echo "  ※ Oracle 콘솔 > VCN > 보안 목록 에서도 80/443 인그레스를 열어야 합니다."

log "8/9  Caddy (자동 HTTPS)"
install_caddy_binary() {
  # 배포판 저장소가 없거나 실패할 때 쓰는 최후 수단.
  # Caddy 는 의존성 없는 단일 정적 바이너리라 이 방식이 가장 확실하다.
  local arch
  case "$(uname -m)" in
    x86_64)  arch=amd64 ;;
    aarch64) arch=arm64 ;;
    *) die "지원하지 않는 아키텍처: $(uname -m)" ;;
  esac
  local ver
  ver=$(curl -fsSL https://api.github.com/repos/caddyserver/caddy/releases/latest \
        | grep -oP '"tag_name":\s*"v\K[^"]+' | head -1)
  ver="${ver:-2.8.4}"
  echo "  정적 바이너리 설치: caddy v${ver} (${arch})"
  curl -fsSL -o /tmp/caddy.tar.gz \
    "https://github.com/caddyserver/caddy/releases/download/v${ver}/caddy_${ver}_linux_${arch}.tar.gz"
  tar -xzf /tmp/caddy.tar.gz -C /tmp caddy
  install -m 0755 /tmp/caddy /usr/bin/caddy
  rm -f /tmp/caddy.tar.gz /tmp/caddy
  id -u caddy &>/dev/null || useradd --system --home /var/lib/caddy \
    --create-home --shell /usr/sbin/nologin caddy
  cat > /etc/systemd/system/caddy.service <<'CADDYUNIT'
[Unit]
Description=Caddy web server
After=network-online.target
Wants=network-online.target

[Service]
User=caddy
Group=caddy
ExecStart=/usr/bin/caddy run --environ --config /etc/caddy/Caddyfile
ExecReload=/usr/bin/caddy reload --config /etc/caddy/Caddyfile --force
Restart=on-abnormal
TimeoutStopSec=5s
LimitNOFILE=1048576
AmbientCapabilities=CAP_NET_BIND_SERVICE
NoNewPrivileges=true
ProtectSystem=full

[Install]
WantedBy=multi-user.target
CADDYUNIT
  systemctl daemon-reload
}

if ! command -v caddy &>/dev/null; then
  if [[ $PKG == apt ]]; then
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
      | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
      > /etc/apt/sources.list.d/caddy-stable.list
    apt-get update -qq
    pkg_install caddy || install_caddy_binary
  else
    # Oracle Linux 9 는 COPR 저장소가 없거나 막히는 경우가 잦다 -> 실패하면 바이너리로
    { dnf install -y -q 'dnf-command(copr)' >/dev/null 2>&1 \
      && dnf copr enable -y -q '@caddy/caddy' >/dev/null 2>&1 \
      && pkg_install caddy >/dev/null 2>&1; } || install_caddy_binary
  fi
fi
command -v caddy >/dev/null || die "Caddy 설치 실패."
mkdir -p /etc/caddy /var/log/caddy
chown -R caddy:caddy /var/log/caddy 2>/dev/null || true
[[ -f /etc/caddy/Caddyfile ]] || touch /etc/caddy/Caddyfile
systemctl enable caddy >/dev/null 2>&1 || true
echo "  caddy $(caddy version 2>/dev/null | head -1)"
SNIPPET="/etc/caddy/${APP_NAME}.caddy"
if [[ ! -f "$SNIPPET" ]]; then
  sed -e "s|@APP_PORT@|${APP_PORT}|g" \
      -e "s|@DOMAIN@|${DOMAIN:-example.duckdns.org}|g" \
      "${REPO_DIR}/deploy/Caddyfile" > "$SNIPPET"
  if [[ -n "${DOMAIN:-}" ]]; then
    echo "  ${SNIPPET} 생성 — 도메인: ${DOMAIN}"
  else
    echo "  ${SNIPPET} 생성 — 도메인을 직접 넣으세요:"
    echo "     sudo sed -i 's/example.duckdns.org/<내도메인>/' ${SNIPPET}"
  fi
fi

# Caddy 기본 설정의 ':80 정적 사이트' 는 우리 사이트와 80번을 다투므로 걷어내고
# import 만 남긴다. 원본은 .bak 으로 보관한다.
if ! grep -q "import ${APP_NAME}.caddy" /etc/caddy/Caddyfile 2>/dev/null; then
  [[ -f /etc/caddy/Caddyfile ]] && cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak
  echo "import ${APP_NAME}.caddy" > /etc/caddy/Caddyfile
fi

if caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile &>/dev/null; then
  systemctl restart caddy || true
  sleep 3
  systemctl is-active --quiet caddy \
    && echo "  caddy 실행 중" \
    || warn "caddy 기동 실패 — sudo journalctl -u caddy -n 20"
else
  warn "Caddyfile 검증 실패 — 도메인을 넣은 뒤 'sudo systemctl restart caddy' 하세요."
fi

log "9/9  기동"
systemctl restart "$APP_NAME"
if systemctl is-active --quiet "$APP_NAME"; then
  # 작은 인스턴스에서는 기동에 5초 안팎 걸린다.
  # 한 번만 찔러보고 실패로 단정하지 말고 최대 30초 기다린다.
  HEALTH=""
  for _ in $(seq 1 15); do
    sleep 2
    HEALTH=$(curl -fsS "localhost:${APP_PORT}/api/health" 2>/dev/null) && break
    HEALTH=""
  done
  if [[ -n "$HEALTH" ]]; then
    echo "  ${APP_NAME} 정상 응답"
    echo "  ${HEALTH}"
  else
    warn "서비스는 떴지만 30초 안에 응답하지 않았습니다. 최근 로그:"
    journalctl -u "$APP_NAME" -n 20 --no-pager
  fi
else
  journalctl -u "$APP_NAME" -n 30 --no-pager
  die "기동 실패"
fi

cat <<DONE

────────────────────────────────────────────────────────
 설치 완료 — ${APP_NAME} (포트 ${APP_PORT})

 로그 보기   : sudo journalctl -u ${APP_NAME} -f
 재시작      : sudo systemctl restart ${APP_NAME}
 페어링 코드 : sudo grep PAIRING ${APP_DIR}/.env
 헬스체크    : curl -s localhost:${APP_PORT}/api/health

 기존 elemensha-bot 인스턴스와 완전히 분리되어 있습니다.

 다음 단계
  1) Oracle 콘솔 > VCN > 보안 목록에 80/443 인그레스 추가
  2) DuckDNS 에서 서브도메인 발급 후 VM 공인 IP 연결
     (elemensha-bot 과 다른 서브도메인을 쓰세요)
  3) ${SNIPPET} 의 도메인 교체 → sudo systemctl reload caddy
  4) 앱에서 https://<도메인> + 페어링 코드로 연결
────────────────────────────────────────────────────────
DONE
