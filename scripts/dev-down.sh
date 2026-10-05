#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Shutdown must still work if .env was removed or now contains a syntax error.
exec python3 "${ROOT_DIR}/scripts/dev_services.py" down
