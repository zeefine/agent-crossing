#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "Agent Crossing lint"

if [[ -f "${ROOT_DIR}/services/platform-api/pom.xml" ]]; then
  mvn -f "${ROOT_DIR}/services/platform-api/pom.xml" -DskipTests compile
else
  echo "Skipping platform-api lint: Spring Boot project is not bootstrapped yet."
fi

if [[ -f "${ROOT_DIR}/services/agent-runtime/pyproject.toml" ]]; then
  if command -v uv >/dev/null 2>&1; then
    uv --directory "${ROOT_DIR}/services/agent-runtime" run python -m compileall src tests
  else
    (cd "${ROOT_DIR}/services/agent-runtime" && python3 -m compileall src tests)
  fi
else
  echo "Skipping agent-runtime lint: FastAPI project is not bootstrapped yet."
fi
