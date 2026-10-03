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
| `scripts` | Bash | Local dev startup, shutdown, test, and lint helpers. |
| `docs` | Markdown | Operational notes and implementation pitfalls. |

---

## Quick Start

### 1. Prerequisites

- Java 21
- Maven
- Node.js + npm
- Python 3.11+
- `uv`
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

Then open:

```text
http://127.0.0.1:3000
```

### 4. Stop services

```bash
./scripts/dev-down.sh
```

Logs and pid files are written to ignored local directories:

```text
logs/
run/
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

---

## Runtime Notes

`InvocationService`, `ThreadCancellationService`, and `ChatService` each have one production
constructor. Execution and cancellation receive the same Spring-managed `ExecutionStateService`
at construction; neither creates a temporary instance or replaces it through setter injection.
Test-only factories under `src/test/java/.../support` supply synchronous execution, queues, status
aggregation, and memory-mode state services. Spring still supplies the production executor and
shared planning queue; transaction-manager availability remains optional in memory mode.

Invocation stream deduplication checks event existence with `SELECT EXISTS` without loading
event bodies or raw payloads. A hit skips the assistant-stream lookup; a miss retains that fallback.
The existing invocation-ID-leading index supports this query; no schema migration is required.

### Bounded planning summaries and batched scheduling reads

MasterAgent planning reads status counts grouped in MySQL, the 12 most recently updated tasks,
and at most 6 latest completed business-agent conclusions (one per agent). The conclusion query
ranks IDs/timestamps before loading message bodies. Task ordering is `updated_at DESC,
created_at ASC, task_id ASC`; conclusion ordering is `created_at DESC, message_id ASC`, retaining
the previous stable tie behavior. This changes internal summary reads, not public history endpoints.

In MySQL mode, Router reads up to 128 queued IDs per scheduling query. The projection contains
dependency failure/waiting, per-user agent busy state, and session `COMPACTING` state, without task
context or message content. Queue order still decides dispatch, blocked descendants are revisited
with a fresh snapshot, and a conditional `QUEUED` update must win before reservation or blocking.
Snapshots are per scan, not a cross-request cache. Memory mode evaluates the same predicates locally.

Regression fixtures guard the data-access budget: a summary over 200 tasks / 202 messages returns
12 tasks + 6 conclusions + 1 status-count row instead of 402 full records; the extra aggregate
query is intentional. A scan of 300 busy candidates with no dependencies uses 3 snapshot queries
instead of per-candidate task, dependency, and running-invocation reads. These are deterministic
repository/mapper test budgets, not measured production latency. MySQL `EXPLAIN ANALYZE` and load
tests on representative data are still needed before claiming latency gains or adding indexes.

### Cancellation and terminal execution states

Runtime completion and user cancellation share a state-only transition boundary. MySQL writes use
conditional updates against the expected active statuses (`PROCESSING` for Task, `RUNNING` for
Invocation), updating Task before Invocation in a short transaction. A failed comparison rolls
back the completion before reading the winning state again. The first committed terminal
transition wins: late success/failure cannot overwrite `CANCELED`, and cancellation skips work
that has already completed. Status updates never upsert a missing row. Memory mode coordinates
completion/cancellation through the same repository monitor and uses atomic per-record comparisons.
Task status events and runtime cancellation requests are sent after the state transaction commits; runtime
execution and session compression do not hold that transaction open. No schema change is needed.

Agent Runtime records cancellation before looking for an active execution. Cancellation and execution
registration share one lock, so an execute request arriving after cancellation does not start a Provider.
Markers use a monotonic-clock TTL configured by `AGENT_RUNTIME_CANCELLATION_TTL_SECONDS` (default 300,
positive finite seconds); choose a value longer than the maximum delayed execute request/retry window.
Repeated cancellations renew the TTL without interrupting CLI cleanup again. Rejected retries and normal
cleanup do not consume the marker; expired entries are reclaimed on the next execute/cancel operation.
The cancellation endpoint's `accepted: true` means the request was recorded, not that a CLI was already
running or has finished exiting. This protection is local to one Runtime process and does not survive
restart or coordinate multiple workers; those deployments need shared cancellation state.

### Late streaming callbacks

Callbacks for `SUCCEEDED`, `FAILED`, or `CANCELED` invocations are acknowledged with an empty
successful response, without writing invocation events, chat messages, or realtime updates.
The callback handler acquires the invocation stream lock before starting a fresh transaction
and holds it through commit and after-commit broadcasts. Finalization waits for accepted
callbacks, closes the callback entrance, and drains buffered text before reconciling the final
answer or failure. After the initial buffer drain, success reconciles the authoritative final text and
sets `COMPLETED` in one message read/save/broadcast, without an intermediate `STREAMING` rewrite
or another drain. Missing or blank final text retains the streamed body; existing `FAILED` or
`CANCELED` messages are not overwritten, and an unchanged completed message is not republished.
The entrance stays closed while session compression keeps the invocation
`RUNNING`; compression itself does not hold the stream lock. Once a terminal status is durable,
the in-memory closed marker is released and that status rejects later callbacks.
This synchronization, like the stream buffer, is local to one platform-api process; it is not
a multi-instance coordination mechanism. No database schema change is required.

### Token usage and session compression

`totalInputTokens` records aggregate input consumption for an invocation. Compression uses only
`EXACT` usage with a known `contextInputTokens`, preferring `lastRequestInputTokens` when supplied.
The default threshold is 800,000 tokens (80% of the configured 1,000,000-token window).
Aggregate CLI usage alone is recorded as `TURN_AGGREGATE` with a null context length and does not
trigger compression. Providers must report a reliable request/context length to enable this policy.

The MySQL startup schema migration makes `invocation_usage.context_input_tokens` nullable and
separates legacy aggregate values from reliable context lengths while preserving recorded totals.
Deploy the platform-api/schema update before the runtime update, since older consumers require a
non-null context length.

During compression, the completed agent execution retains its `RUNNING` invocation / `PROCESSING`
task reservation, and its session history is committed as `COMPACTING` before requesting a summary.
The router skips that session even if the task is canceled. Successful compression atomically
archives the old history, saves a `CREATING` generation with the summary, and removes the old
session pointer; a failed summary restores `ACTIVE` and keeps the old session available.
Execution reads the session pointer and its context under the same session lock and database
snapshot, with rotation commits completed before the lock is released. Compression uses a short
reservation transaction, a remote summary call with no database transaction/connection held, and
a short rotation (or failure recovery) transaction. Rotation and recovery revalidate the reserved
record ID, generation, provider session ID, and `COMPACTING` status; stale results cannot mutate a
changed reservation. Eligible compression calls inside an existing transaction are rejected before
reservation: suspending an outer transaction would still retain its connection during the model call.
Callers must invoke compression after their transaction has ended.
In the current single-platform-process deployment, startup restores
interrupted `COMPACTING` histories to `ACTIVE`; this is not a distributed lease or multi-instance
scheduling mechanism. The status column is already `VARCHAR`, so no table alteration is required.

The summary CLI receives prompts over stdin, never as command-line arguments. Runtime settings
`AGENT_RUNTIME_SESSION_COMPRESSION_MAX_INPUT_BYTES` (default 131,072) and
`AGENT_RUNTIME_SESSION_COMPRESSION_MAX_SUMMARY_BYTES` (default 16,384) bound each complete UTF-8
prompt and returned cumulative summary. The input budget includes instructions, JSON escaping,
and the preceding batch's summary; it is a byte limit, not an exact model-token measurement.
Short requests use one call. Long requests are consumed in ordered JSONL fragments, preferring
record boundaries and splitting individual oversized records when necessary. Each call merges
the next fragment into the cumulative summary; history is not silently truncated.
`AGENT_RUNTIME_SESSION_COMPRESSION_MAX_BATCHES` (default 64) limits calls, and all batches share
`AGENT_RUNTIME_MASTER_AGENT_TIMEOUT_SECONDS` (default 60), which should remain below the Java
business HTTP timeout. Oversized summaries, exhausted budgets, or failed batches abort compression
without returning a partial summary, so the platform retains the old session. Increase these
budgets together when longer histories need more calls/time. Timeout or cancellation kills and
reaps the active CLI child.

### OpenCode

The three CLI adapters share process termination, callback-stream cleanup, and last-DONE delivery
metadata helpers in `providers/cli_support.py`. Event parsing, tool-message filtering, and PTY
handling remain provider-specific. Existing timeout policies are unchanged: the shared graceful
termination helper is used only where the adapter previously used that same behavior.

OpenCode is invoked through `opencode run <prompt>` for fresh sessions and `opencode -s <sessionId> run <prompt>` for reused sessions. Reused sessions may require `opencode export <sessionId>` recovery because OpenCode can write final content to the session without emitting it to the command line.

Session recovery uses only an ID reported in the current run's structured session metadata or
explicitly supplied for reuse. The adapter never selects a session from `opencode session list`
or extracts an ID from answer text. If no session ID is available, stdout content is still returned,
but no session is exported or persisted for reuse; an empty response retains the existing
`missing_session_id` diagnostic. CLI versions that omit structured session metadata therefore
cannot establish a reusable session through this adapter.

### ClaudeCode

ClaudeCode is invoked with `claude -p <prompt> --output-format stream-json --verbose`. When a provider session exists, the runtime resumes it with `--resume <sessionId>`.

### Codex

Codex is invoked with `codex exec --json` for a fresh thread and `codex exec resume <sessionId> --json` for a reused provider session. Prompts are sent over stdin, `thread.started.thread_id` is persisted per user/thread/agent, and only completed `agent_message` events become user-visible output. User-level Codex configuration is ignored by default so the process only receives the explicitly configured Agent Crossing MCP server; authentication remains available through `CODEX_HOME`.

### MCP

The runtime exposes MCP tools used by MasterAgent and business agents, including task planning, direct answers, task status snapshots, and append-only task creation. CLI MCP configuration is environment-driven so local users can manage their own provider setup.

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
