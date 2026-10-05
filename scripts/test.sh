#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "Agent Crossing test"

# Isolated lifecycle regression tests; no real agents, Docker or MySQL involved.
python3 -m unittest discover -s "${ROOT_DIR}/scripts/tests" -v

if [[ -f "${ROOT_DIR}/services/platform-api/gradlew" ]]; then
  "${ROOT_DIR}/services/platform-api/gradlew" -p "${ROOT_DIR}/services/platform-api" test
elif [[ -f "${ROOT_DIR}/services/platform-api/pom.xml" ]]; then
  mvn -f "${ROOT_DIR}/services/platform-api/pom.xml" test
else
  echo "Skipping platform-api tests: Spring Boot project is not bootstrapped yet."
fi

if [[ -f "${ROOT_DIR}/services/agent-runtime/pyproject.toml" ]]; then
  if command -v uv >/dev/null 2>&1; then
    uv --directory "${ROOT_DIR}/services/agent-runtime" run pytest
  elif python3 -c "import fastapi, pytest" >/dev/null 2>&1; then
    (cd "${ROOT_DIR}/services/agent-runtime" && python3 -m pytest)
  else
    echo "Skipping agent-runtime tests: install uv or FastAPI/pytest dependencies first."
  fi
else
  echo "Skipping agent-runtime tests: FastAPI project is not bootstrapped yet."
fi

if [[ -f "${ROOT_DIR}/services/platform-web/package.json" ]]; then
  if command -v npm >/dev/null 2>&1; then
    (cd "${ROOT_DIR}/services/platform-web" && npm run build)
  else
    echo "Skipping platform-web build: npm is not installed."
  fi
else
  echo "Skipping platform-web build: Next.js project is not bootstrapped yet."
fi
