#!/usr/bin/env bash
# ============================================================================
#  로컬 -> Oracle VM 배포 한 방 스크립트 (Git Bash 에서 실행)
#
#  사용법:
#     bash server/deploy/upload.sh <VM_공인IP> [APP_NAME] [APP_PORT]
#
#  예:
#     bash server/deploy/upload.sh 152.70.xxx.xxx
#     bash server/deploy/upload.sh 152.70.xxx.xxx elemensha-claude-bot 8090 my.duckdns.org
#
#  기존 elemensha-bot 인스턴스는 건드리지 않는다.
# ============================================================================
set -euo pipefail

VM_IP="${1:-}"
APP_NAME="${2:-elemensha-claude-bot}"
APP_PORT="${3:-8090}"
DOMAIN="${4:-${DOMAIN:-}}"   # 예: elemensha-claude.duckdns.org
# Oracle 이미지에 따라 기본 계정이 다르다: Ubuntu=ubuntu, Oracle Linux=opc
SSH_USER="${SSH_USER:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(dirname "$SCRIPT_DIR")"
KEY="${SSH_KEY:-$(dirname "$(dirname "$SERVER_DIR")")/ORACLE CLOUD/ssh-key-2026-08-22.key}"

log() { echo -e "\n\033[1;32m==>\033[0m $*"; }
die() { echo -e "\n\033[1;31m!!\033[0m $*" >&2; exit 1; }

[[ -n "$VM_IP" ]] || die "사용법: bash upload.sh <VM_공인IP> [APP_NAME] [APP_PORT]"
[[ -f "$KEY" ]] || die "SSH 키를 찾을 수 없습니다: $KEY
  SSH_KEY=<키경로> 환경변수로 지정하세요."

# Windows 에서 만든 키는 권한이 너무 열려 있어 ssh 가 거부한다
chmod 600 "$KEY" 2>/dev/null || true

log "1/4  연결 확인  ${SSH_USER}@${VM_IP}"
ssh -i "$KEY" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 \
    "${SSH_USER}@${VM_IP}" 'echo "  접속 OK: $(hostname) / $(uname -m) / $(nproc)코어 $(free -g | awk "/Mem/{print \$2}")GB"' \
  || die "SSH 접속 실패. Oracle 보안 목록에서 22번 포트가 열려 있는지 확인하세요."

log "2/4  코드 업로드"
# __pycache__ / data / .env 는 제외하고 보낸다
TMP_TAR=$(mktemp -t elemensha-XXXX.tar.gz)
tar --exclude='__pycache__' --exclude='data' --exclude='.env' --exclude='*.pyc' \
    -czf "$TMP_TAR" -C "$(dirname "$SERVER_DIR")" server
scp -i "$KEY" -q "$TMP_TAR" "${SSH_USER}@${VM_IP}:/tmp/elemensha-server.tar.gz"
rm -f "$TMP_TAR"
echo "  업로드 완료"

log "3/4  설치 실행 (${APP_NAME}, 포트 ${APP_PORT})"
ssh -i "$KEY" "${SSH_USER}@${VM_IP}" bash -s <<REMOTE
set -euo pipefail
sudo mkdir -p /opt/${APP_NAME}
sudo tar -xzf /tmp/elemensha-server.tar.gz -C /opt/${APP_NAME}
# Windows 에서 편집된 파일이 CRLF 로 올라오면 bash 가 스크립트를 못 읽는다.
# 실행 직전에 셸 스크립트의 CR 을 제거해 둔다 (방어적 조치).
sudo find /opt/${APP_NAME}/server -type f \( -name '*.sh' -o -name '*.template' -o -name 'Caddyfile' -o -name 'requirements.txt' \) -exec sed -i 's/\x0d$//' {} +
rm -f /tmp/elemensha-server.tar.gz
sudo chmod +x /opt/${APP_NAME}/server/deploy/oracle-setup.sh
sudo APP_NAME=${APP_NAME} APP_PORT=${APP_PORT} DOMAIN='${DOMAIN}' \
     bash /opt/${APP_NAME}/server/deploy/oracle-setup.sh
REMOTE

log "4/4  페어링 코드"
ssh -i "$KEY" "${SSH_USER}@${VM_IP}" \
    "sudo grep ELEMENSHA_PAIRING_CODE /opt/${APP_NAME}/.env"

cat <<DONE

────────────────────────────────────────────────────────
 배포 완료

 로그    : ssh -i "<키>" ${SSH_USER}@${VM_IP} 'sudo journalctl -u ${APP_NAME} -f'
 재배포  : 이 스크립트를 다시 실행하면 됩니다 (설정·DB 는 보존)

 다음: DuckDNS 서브도메인을 ${VM_IP} 에 연결하고
       /etc/caddy/${APP_NAME}.caddy 의 도메인을 교체하세요.
────────────────────────────────────────────────────────
DONE
