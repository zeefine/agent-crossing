#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "${ROOT_DIR}/.env" ]]; then
  set -a
  # .env is trusted local shell configuration; never print its credentials.
  source "${ROOT_DIR}/.env"
  set +a
fi
exec python3 "${ROOT_DIR}/scripts/dev_services.py" up
