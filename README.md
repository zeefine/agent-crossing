# Agent Crossing: Multi-Agent CLI Collaboration Platform

> **A local-first orchestration platform for CLI agents** - Route user intent through a hidden MasterAgent, decompose work into task DAGs, execute specialized CLI agents such as OpenCode, Claude Code, and Codex, and stream results back to a WebSocket-powered chat UI.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-007396.svg)](services/platform-api/pom.xml)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.7-6DB33F.svg)](services/platform-api/pom.xml)
[![FastAPI](https://img.shields.io/badge/FastAPI-runtime-009688.svg)](services/agent-runtime/pyproject.toml)
[![Next.js](https://img.shields.io/badge/Next.js-14-black.svg)](services/platform-web/package.json)

---

## What Is This?

**Agent Crossing** is a multi-agent orchestration platform that turns an open-ended user request into a structured, trackable execution graph.

Instead of treating CLI agents as isolated chatbots, Agent Crossing gives them a shared control plane: user intent is planned by an internal **MasterAgent**, persisted as a task DAG, routed by dependency state, executed by specialized CLI agents, and streamed back to the browser as both conversation and task progress.

The core workflow is:

1. A user enters a complex question or task in the web interface.
2. The internal **MasterAgent** analyzes intent, decides whether to answer directly or decompose the request, and emits a task DAG.
3. Business agents such as **OpenCode**, **ClaudeCode**, and **Codex** execute ready DAG nodes, share progress through platform state, and return intermediate and final results to the user.

The project value is in the coordination layer: Java owns durable state, dependency-aware scheduling, and realtime delivery; Python isolates CLI/provider adaptation; the frontend makes the hidden multi-agent execution understandable through chat and DAG views.

---

## Core Features

- **Intent-to-DAG planning**: the internal MasterAgent uses the runtime planner and MCP tools to return either a direct answer or a validated task plan.
- **Dependency-aware scheduling**: the router evaluates `task_dependency`, task status, and per-user agent availability before reserving work for execution.
- **Dynamic serial and parallel execution**: DAG edges model ordered work, while independent ready nodes can be dispatched to different agents concurrently.
- **Global execution context**: agents receive incremental conversation context and task-state snapshots so each invocation can understand the wider collaboration state.
- **CLI provider abstraction**: the Python runtime adapts OpenCode, ClaudeCode, and Codex subprocess behavior into a common `AgentMessage` contract, including session reuse and output normalization.
- **Realtime operator surface**: WebSocket events keep the chat timeline, agent status, task status, and DAG visualization synchronized with backend execution.

---

## Agent Roster

Business agent cards are initialized by the platform and can be expanded as new CLI providers are added.

Currently adapted CLI providers: **ClaudeCode CLI**, **OpenCode CLI**, and **Codex CLI**.

---

## Architecture

```text
User
  ↓
Next.js platform-web
  ↓ HTTP + WebSocket
Spring Boot platform-api
  ├─ ChatThread / ChatMessage
  ├─ Task / TaskDependency
  ├─ Invocation / InvocationMessage
  ├─ AgentSession
  ├─ QuestHub queue
  └─ QuestRouter scheduler
       ↓ HTTP
FastAPI agent-runtime
  ├─ MasterAgent planner
  ├─ OpenCode provider
  ├─ ClaudeCode provider
  ├─ Codex provider
  └─ FastMCP tools
       ↓ subprocess / MCP
CLI agents and model providers
```

### Service Ownership

| Path | Stack | Responsibility |
| --- | --- | --- |
| `services/platform-api` | Spring Boot, MyBatis, WebSocket | Source of truth for users, threads, tasks, invocations, sessions, routing, and realtime events. |
| `services/agent-runtime` | FastAPI, Pydantic, FastMCP | Runtime adapter layer for MasterAgent, OpenCode, ClaudeCode, Codex, provider stdout/JSON parsing, and MCP tools. |
| `services/platform-web` | Next.js, React, TypeScript | Chat workspace, agent status, WebSocket updates, and task DAG visualization. |
| `contracts` | JSON Schema | Cross-language request, response, task, invocation, and realtime event contracts. |
| `scripts` | Bash + Python standard library | Local dev startup, shutdown, test, and lint helpers. |
| `docs` | Markdown | Operational notes and implementation pitfalls. |

---

## Quick Start

### 1. Prerequisites

- Java 21
- Maven
- Node.js + npm
- Python 3.11+
- `uv`
- `python3`, `ps` and `curl` available on PATH for the macOS/Linux lifecycle scripts
- MySQL 8+ if running with the `mysql` Spring profile
- Installed and authenticated CLI agents:
  - `opencode`
  - `claude`
  - `codex`

### 2. Configure environment

```bash
cp .env.example .env
```

Default local ports:

| Service | URL |
| --- | --- |
| platform-api | `http://127.0.0.1:8080` |
| agent-runtime | `http://127.0.0.1:8090` |
| platform-web | `http://127.0.0.1:3000` |

For MySQL-backed mode, set:

```env
SPRING_PROFILES_ACTIVE=mysql
MYSQL_SERVER=localhost
MYSQL_PORT=13306
MYSQL_DB=agent_crossing
MYSQL_USER=<mysql-user>
MYSQL_PASSWORD=<mysql-password>
```

### 3. Start everything

```bash
./scripts/dev-up.sh
```

Startup checks MySQL before launching application services when the `mysql` profile
or MySQL storage mode is enabled. It waits for a MySQL server greeting, not just an
open TCP port; database credentials and schema are still validated by the Java backend.
`SPRING_DATASOURCE_URL`, when set, takes precedence over `MYSQL_SERVER`/`MYSQL_PORT`
for this check (single-host `jdbc:mysql://` URLs are supported).

MySQL is **not started automatically by default**. To explicitly allow starting an
existing local Docker container, add `DEV_MYSQL_CONTAINER=mysql` to `.env`, replacing
`mysql` with its actual name. The script verifies the published port, starts it only
if stopped, and waits for its Docker health check when one exists. It never creates
containers or stops MySQL during application rollback/shutdown.

Timeouts in `.env` are seconds: `DEV_MYSQL_TIMEOUT=30`, `DEV_READY_TIMEOUT=60`
(per service), `DEV_HTTP_TIMEOUT=2` (per HTTP probe), `DEV_STOP_TIMEOUT=10`, and
`DEV_INSTALL_TIMEOUT=180` (initial runtime dependency sync). HTTP probes have both
connection and overall timeouts. Startup failure or Ctrl+C/TERM rolls back only
application services started by that invocation, in reverse order; existing
services are preserved. Re-running startup checks existing services' health.

Then open:

```text
http://127.0.0.1:3000
```

### 4. Stop services

```bash
./scripts/dev-down.sh
```

Both entry points use the standard-library-only `scripts/dev_services.py` manager.
Each application runs in a dedicated process group with a persistent supervisor,
so stopping Maven/npm also terminates their ordinary child processes. Shutdown
tries TERM before KILL after the configured grace period and checks that the group
has exited. Programs deliberately creating a separate session are outside this
process-group boundary.

The manager records PID, process group, start time, project identity and a unique
launch token in `run/<service>.json`; `.pid` files are retained for inspection but
are not trusted for signaling. Legacy live PID files, identity mismatches, or
orphaned groups (for example after manually killing a supervisor with SIGKILL)
cause a safe refusal: inspect the reported metadata and process manually instead
of deleting records and blindly killing PIDs. A process lock prevents overlapping
startup/shutdown operations. Shutdown uses the recorded grace period and does not
source `.env`, so it remains usable if that file is missing or broken.

Logs and pid files are written to ignored local directories:

```text
logs/
run/
```

Service logs are appended rather than overwritten. Lifecycle regression tests run
only temporary fake services on ephemeral ports (no real agents, Docker or database):

```bash
python3 -m unittest discover -s scripts/tests -v
```

---

## Development Commands

```bash
# Start platform-api, agent-runtime, and platform-web
./scripts/dev-up.sh

# Stop local services
./scripts/dev-down.sh

# Run Java tests, Python tests, and web build
./scripts/test.sh

# Run configured lint checks
./scripts/lint.sh
```

Service-specific commands:

```bash
# Spring Boot API
mvn -f services/platform-api/pom.xml test
mvn -f services/platform-api/pom.xml spring-boot:run

# FastAPI runtime
uv --directory services/agent-runtime sync
uv --directory services/agent-runtime run pytest
uv --directory services/agent-runtime run uvicorn agent_runtime.main:app --host 127.0.0.1 --port 8090

# Next.js frontend
cd services/platform-web
npm install
npm run dev
npm run build
```

### Frontend component system

The collaboration workspace uses **shadcn/ui + Tailwind CSS 4**, with locally
owned components in `services/platform-web/components/ui` and business compositions
in `components/workspace`. `app/globals.css` owns light/dark semantic tokens and
workspace layout. Theme selection defaults to the system and is remembered across reloads.
React Flow and Lucide remain the task graph and icon foundations.

Use existing components and semantic tokens for new controls. Generate additional
components from `services/platform-web` using `npx shadcn@latest add <component>`;
review generated changes for React 18 ref compatibility. Do not overwrite project
customizations blindly. See [the component-system decision](docs/decisions/001-web-component-system.md).

```bash
cd services/platform-web
npm ci
npx playwright install chromium
npm run lint
npm run typecheck
npm test
npm run build
PLAYWRIGHT_PRODUCTION=1 npm test
```

Browser tests start an isolated frontend on port 3100 and mock the platform API and
WebSocket. They do not launch real agents or modify real threads. Test screenshots
and traces are generated under the ignored `test-results/` directory.

---

## API Surface

Important platform-api areas:

- `GET /api/health`
- Chat thread and message APIs under `ChatController`
- Task and DAG APIs under `TaskController`
- Agent status APIs under `AgentController`
- Runtime callback APIs under `CallbackController`
- WebSocket endpoint: `ws://127.0.0.1:8080/ws/chat`

Important agent-runtime areas:

- `GET /api/health`
- Runtime execution endpoint
- Parser / MasterAgent planning endpoint
- FastMCP server mounted by the runtime

---

## Repository Layout

```text
agent-crossing/
├── contracts/
│   ├── examples/
│   └── schemas/
├── docs/
├── scripts/
├── services/
│   ├── agent-runtime/
│   ├── platform-api/
│   └── platform-web/
├── CHANGELOG.md
├── LICENSE
└── README.md
```

`spec/` is intentionally ignored because it is used for local planning drafts.

---

## Storage Model

Current durable tables include:

- `user`
- `chat_thread`
- `chat_message`
- `task`
- `task_dependency`
- `invocation`
- `invocation_message`
- `realtime_event`
- `agent_session`
- `agent_context_cursor`
- `agent_context_message_receipt`
- `agent_card`

The same application layer can also run with in-memory repositories for lightweight development.

---

## Roadmap

- Production-grade WebSocket delivery with async send isolation.
- Stronger observability around CLI execution latency and provider recovery.
- More business agents beyond OpenCode, ClaudeCode, and Codex.
- More compact context summarization for long-running threads.
- Multi-user authentication beyond the current lightweight local user resolver.

---

## License

Agent Crossing is released under the [MIT License](LICENSE).
