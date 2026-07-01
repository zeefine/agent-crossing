#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${ROOT_DIR}/run"
LOG_DIR="${ROOT_DIR}/logs"
PLATFORM_PID_FILE="${RUN_DIR}/platform-api.pid"
AGENT_PID_FILE="${RUN_DIR}/agent-runtime.pid"
WEB_PID_FILE="${RUN_DIR}/platform-web.pid"

if [[ -f "${ROOT_DIR}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT_DIR}/.env"
  set +a
fi

PLATFORM_API_HOST="${PLATFORM_API_HOST:-127.0.0.1}"
PLATFORM_API_PORT="${PLATFORM_API_PORT:-8080}"
AGENT_RUNTIME_HOST="${AGENT_RUNTIME_HOST:-127.0.0.1}"
AGENT_RUNTIME_PORT="${AGENT_RUNTIME_PORT:-8090}"
PLATFORM_WEB_HOST="${PLATFORM_WEB_HOST:-127.0.0.1}"
PLATFORM_WEB_PORT="${PLATFORM_WEB_PORT:-3000}"

echo "Agent Crossing dev-up"
echo "Root: ${ROOT_DIR}"

mkdir -p "${RUN_DIR}" "${LOG_DIR}"

is_running() {
  local pid_file="$1"
  [[ -f "${pid_file}" ]] && kill -0 "$(cat "${pid_file}")" >/dev/null 2>&1
}

wait_for_http() {
  local url="$1"
  local name="$2"
  for _ in {1..60}; do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      echo "${name} is ready: ${url}"
      return 0
    fi
    sleep 1
  done
  echo "${name} did not become ready: ${url}" >&2
  return 1
}

wait_for_http_process() {
  local url="$1"
  local name="$2"
  local pid_file="$3"
  for _ in {1..60}; do
    if ! is_running "${pid_file}"; then
      echo "${name} exited before becoming ready. Check logs/${name}.log" >&2
      return 1
    fi
    if curl -fsS "${url}" >/dev/null 2>&1; then
      echo "${name} is ready: ${url}"
      return 0
    fi
    sleep 1
  done
  echo "${name} did not become ready: ${url}" >&2
  return 1
}

ensure_port_available() {
  local host="$1"
  local port="$2"
  local name="$3"
  local pid

  pid="$(lsof -nP -tiTCP@"${host}":"${port}" -sTCP:LISTEN 2>/dev/null | head -n 1 || true)"
  if [[ -n "${pid}" ]]; then
    echo "${name} cannot start because ${host}:${port} is already used by pid ${pid}." >&2
    echo "Stop that process first, or set a different port in .env." >&2
    return 1
  fi
}

if [[ ! -f "${ROOT_DIR}/services/platform-api/pom.xml" ]]; then
  echo "platform-api is not bootstrapped yet."
elif is_running "${PLATFORM_PID_FILE}"; then
  echo "platform-api already running with pid $(cat "${PLATFORM_PID_FILE}")"
else
  ensure_port_available "${PLATFORM_API_HOST}" "${PLATFORM_API_PORT}" "platform-api"
  echo "Starting platform-api on ${PLATFORM_API_HOST}:${PLATFORM_API_PORT}"
  nohup bash -c '
    cd "$1"
    exec env PLATFORM_API_HOST="$2" PLATFORM_API_PORT="$3" mvn spring-boot:run
  ' _ "${ROOT_DIR}/services/platform-api" "${PLATFORM_API_HOST}" "${PLATFORM_API_PORT}" \
    >"${LOG_DIR}/platform-api.log" 2>&1 &
  echo "$!" >"${PLATFORM_PID_FILE}"
  wait_for_http_process "http://${PLATFORM_API_HOST}:${PLATFORM_API_PORT}/api/health" "platform-api" "${PLATFORM_PID_FILE}"
fi

if [[ ! -f "${ROOT_DIR}/services/agent-runtime/pyproject.toml" ]]; then
  echo "agent-runtime is not bootstrapped yet."
elif is_running "${AGENT_PID_FILE}"; then
  echo "agent-runtime already running with pid $(cat "${AGENT_PID_FILE}")"
else
  if ! command -v uv >/dev/null 2>&1; then
    echo "uv is required to start agent-runtime" >&2
    exit 1
  fi
  ensure_port_available "${AGENT_RUNTIME_HOST}" "${AGENT_RUNTIME_PORT}" "agent-runtime"
  echo "Starting agent-runtime on ${AGENT_RUNTIME_HOST}:${AGENT_RUNTIME_PORT}"
  if [[ ! -x "${ROOT_DIR}/services/agent-runtime/.venv/bin/uvicorn" ]]; then
    uv --directory "${ROOT_DIR}/services/agent-runtime" sync
  fi
  nohup bash -c '
    cd "$1"
    exec .venv/bin/uvicorn agent_runtime.main:app --host "$2" --port "$3"
  ' _ "${ROOT_DIR}/services/agent-runtime" "${AGENT_RUNTIME_HOST}" "${AGENT_RUNTIME_PORT}" \
    </dev/null >"${LOG_DIR}/agent-runtime.log" 2>&1 &
  echo "$!" >"${AGENT_PID_FILE}"
  wait_for_http_process "http://${AGENT_RUNTIME_HOST}:${AGENT_RUNTIME_PORT}/api/health" "agent-runtime" "${AGENT_PID_FILE}"
fi

if [[ ! -f "${ROOT_DIR}/services/platform-web/package.json" ]]; then
  echo "platform-web is not bootstrapped yet."
elif is_running "${WEB_PID_FILE}"; then
  echo "platform-web already running with pid $(cat "${WEB_PID_FILE}")"
else
  ensure_port_available "${PLATFORM_WEB_HOST}" "${PLATFORM_WEB_PORT}" "platform-web"
  echo "Starting platform-web on ${PLATFORM_WEB_HOST}:${PLATFORM_WEB_PORT}"
  nohup bash -c '
    cd "$1"
    rm -rf .next
    exec env PLATFORM_API_BASE_URL="$2" NEXT_PUBLIC_PLATFORM_WS_URL="$3" npm run dev -- -H "$4" -p "$5"
  ' _ "${ROOT_DIR}/services/platform-web" "http://${PLATFORM_API_HOST}:${PLATFORM_API_PORT}" "ws://${PLATFORM_API_HOST}:${PLATFORM_API_PORT}/ws/chat" "${PLATFORM_WEB_HOST}" "${PLATFORM_WEB_PORT}" \
    >"${LOG_DIR}/platform-web.log" 2>&1 &
  echo "$!" >"${WEB_PID_FILE}"
  wait_for_http_process "http://${PLATFORM_WEB_HOST}:${PLATFORM_WEB_PORT}" "platform-web" "${WEB_PID_FILE}"
fi

echo "Logs: ${LOG_DIR}"
