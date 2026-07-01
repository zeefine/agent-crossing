#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${ROOT_DIR}/run"

echo "Agent Crossing dev-down"

stop_process() {
  local name="$1"
  local pid_file="$2"
  if [[ ! -f "${pid_file}" ]]; then
    echo "${name} is not running"
    return 0
  fi

  local pid
  pid="$(cat "${pid_file}")"
  if ! kill -0 "${pid}" >/dev/null 2>&1; then
    echo "${name} pid ${pid} is already stopped"
    rm -f "${pid_file}"
    return 0
  fi

  echo "Stopping ${name} pid ${pid}"
  kill "${pid}"
  for _ in {1..20}; do
    if ! kill -0 "${pid}" >/dev/null 2>&1; then
      rm -f "${pid_file}"
      echo "${name} stopped"
      return 0
    fi
    sleep 0.5
  done

  echo "Force stopping ${name} pid ${pid}"
  kill -9 "${pid}" >/dev/null 2>&1 || true
  rm -f "${pid_file}"
}

stop_process "platform-api" "${RUN_DIR}/platform-api.pid"
stop_process "agent-runtime" "${RUN_DIR}/agent-runtime.pid"
stop_process "platform-web" "${RUN_DIR}/platform-web.pid"
