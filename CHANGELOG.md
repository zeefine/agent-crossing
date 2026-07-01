# Changelog

All notable changes to Agent Crossing are documented in this file.

Format follows a product-engineering changelog style: each version includes implementation date, scope, user-visible impact, backend changes, compatibility notes, and known follow-ups.

## [Unreleased] - 2026-06-23

### Added

- Added a small animated `正在处理中...` indicator in the web chat timeline while the system is waiting for agent output (2026-06-25).
- Added mirrored `MODEL_SYNC(ChatMessage)` comments across Java domain, Java DTO, frontend API type, MySQL schema, and MyBatis mapper to prevent future `ChatMessage` field drift.
- Added a dedicated Java `DagValidator` (2026-06-24) for parsed task plans, covering cycle detection, orphan warnings, and redundant dependency edge detection before task enqueue.
- Added durable `agent_session` persistence for provider-native sessions keyed by `userId + threadId + agentId + provider`.
- Added `AgentExecutionRequest.providerSessionId` so platform-api can pass persisted provider sessions into agent-runtime.
- Added OpenCode provider session round-trip: runtime uses `providerSessionId` as `opencode -s <sessionId> run ...` and returns captured session ids in `DONE.raw.providerSessionId`.
- Added `MODEL_SYNC(AgentExecutionRequest)` comments across runtime schema, Java request record, and Python pydantic model.
- Added `claudecode` business agent registration in platform-api and `ClaudeCodeProvider` in agent-runtime.
- Added Claude Code CLI NDJSON parsing for `system/init`, `assistant` text/tool blocks, `result/error`, session capture, and `--resume` provider session reuse.
- Added `AGENT_RUNTIME_CLAUDECODE_MCP_CONFIG_JSON` to pass Claude Code CLI `--mcp-config` JSON as a single safe argument.

### Changed

- Hardened provider session reuse so invocation execution resolves the owning `threadId` once and skips `agent_session` lookup/save when a task has become orphaned after thread deletion, preventing successful provider runs from failing during session persistence (2026-07-01).
- Replaced example MySQL credentials and default password fallbacks with placeholders/empty defaults in `.env.example`, `README.md`, and MySQL configuration (2026-07-01).
- Cleaned Python generated artifacts from `services/agent-runtime/src` (`__pycache__`, `*.pyc`, and `agent_crossing_agent_runtime.egg-info`) and added explicit ignore rules so stale cache files such as the old `opencode_planner` bytecode no longer pollute search or review results (2026-07-01).
- Expanded `.gitignore` coverage for local AI tooling (`.agents/`, `.codex/`, `.claude/settings.local.json`, `skills-lock.json`), stale Next.js cache directories (`.next.stale-*`), and TypeScript incremental build metadata (`*.tsbuildinfo`) after auditing generated and machine-local files (2026-07-01).
- Added `spec/` to `.gitignore` so local planning/spec drafts stay out of repository commits (2026-07-01).
- Changed router agent-busy checks from global `agentId` scope to `userId + agentId` scope so one user's running OpenCode/ClaudeCode task no longer blocks the same agent for other users (2026-07-01).
- Fixed invocation completion semantics so runtime `ERROR` messages returned over HTTP 200 now mark the invocation/task as failed instead of saving a failed chat message while completing the task (2026-07-01).
- Removed the MasterAgent planning-session stale-id fallback; MCP submissions now require an exact `planningSessionId` match even when there is only one active planning session (2026-07-01).
- Removed production `anonymous` convenience overloads from chat/parser services, controller helpers, and core domain records so user ownership must be passed explicitly beyond the request-boundary `CurrentUserResolver` fallback (2026-07-01).
- Updated stale realtime-event comments and spec wording from the old `event_log` name to the actual `realtime_event` table/domain naming (2026-07-01).
- Renamed the MasterAgent planner module from `opencode_planner.py` to `master_agent_planner.py` so the filename matches the current Claude Code based MasterAgent role instead of the older OpenCode naming (2026-07-01).
- Removed two unused OpenCodeProvider helpers (`_to_messages` and `_is_plain_status_line`) after the provider settled on streaming collectors plus `_strip_plain_status_prefix` for status-line handling (2026-07-01).
- Updated OpenCode tool-only output tests to reflect the current recovery contract: tool events without final text trigger session export recovery, and only fall back to silent completion when the exported session has no assistant text (2026-07-01).
- Changed `opencode export <sessionId>` recovery reads from pipe-based stdout capture to PTY capture, matching manual terminal behavior and avoiding truncated JSON exports in reused-session recovery (2026-07-01).
- Reduced reused-session OpenCode export polling from 30 attempts to 10 attempts while keeping a 5-second interval, capping delayed session-write recovery at 50 seconds after PTY export stabilized output capture (2026-07-01).
- Removed the old partial-JSON OpenCode export parser and its tests now that `opencode export <sessionId>` is read through PTY and expected to return complete JSON (2026-07-01).
- Set `.env.example` local `AGENT_RUNTIME_PROVIDER_TIMEOUT_SECONDS` to 100 seconds so local provider commands have more headroom than the Python default without waiting as long as the previous 180-second override; the Python config default remains 60 seconds when no environment override is provided (2026-07-01).
- Hardened reused-session OpenCode export parsing so a found previous user marker takes precedence over runtime timestamp gates; recovery now uses export message order plus assistant `parentID`, preventing valid replies from being skipped when provider timestamps differ from the Python process clock (2026-07-01).
- Increased reused-session OpenCode export polling interval from 3 seconds to 5 seconds while keeping the previous 30 attempts, expanding the delayed session-write recovery window to 150 seconds before the later PTY-based stabilization allowed reducing attempts (2026-07-01).
- Fixed reused-session OpenCode export recovery by binding the displayed answer to the assistant message whose `parentID` matches the new user message created by the current `opencode -s <sessionId> run ...` call, preventing later turns from redisplaying the previous answer in the same thread (2026-06-30).
- Added OpenCode silent-completion diagnostics: when `opencode run` exits without parseable assistant text, the fallback message raw payload now records execution mode plus stdout/stderr/PTY character counts and tails for debugging runtime-captured output (2026-06-30).
- Restored a scoped OpenCode session export fallback only for reused provider sessions: when `opencode -s <sessionId> run ...` produces no parseable stdout text, agent-runtime reads `opencode export <sessionId>` and extracts the latest assistant text from that same session (2026-06-30).
- Expanded OpenCode session export recovery to first-run sessions: when a fresh `opencode run ...` emits no parseable stdout but runtime discovers the new session id via `opencode session list`, it now exports that session and shows the latest assistant text instead of the silent-completion fallback (2026-06-30).
- Changed reused-session OpenCode execution to ignore `opencode -s <sessionId> run ...` stdout/PT​Y content and always read the final assistant text from `opencode export <sessionId>`, preventing stale first-answer repeats in the same thread (2026-06-30).
- Tightened reused-session OpenCode execution so `providerSessionId != null` skips stdout/stderr/PT​Y parsing entirely; run output is retained only as diagnostics and the displayed answer must come from `opencode export <providerSessionId>` (2026-06-30).
- Fixed reused-session OpenCode export timing by recording the previous latest assistant message marker before `opencode -s ... run ...` and polling export until a new assistant marker appears, preventing immediate export from redisplaying the previous answer (2026-06-30).
- Hardened reused-session OpenCode export polling with a run-start timestamp gate: exported assistant messages older than the current `opencode -s ... run ...` call are ignored even when the baseline marker cannot be read, preventing delayed session writes from redisplaying stale answers (2026-06-30).
- Reduced the agent-runtime provider command timeout default from 120 seconds to 60 seconds; local development can override it with `AGENT_RUNTIME_PROVIDER_TIMEOUT_SECONDS` in `.env.example` (2026-06-30).
- Changed provider session lookup to be thread-only for both MasterAgent and business agents: platform-api now resolves `agent_session` only by `userId + threadId + agentId + provider`, so trace-only historical rows are no longer reused for provider session restoration (2026-06-30).
- Changed the MySQL `agent_session` primary key to `userId + threadId + agentId + provider`, made `thread_id` required, and migrated older trace-keyed rows by backfilling thread ids, dropping orphan rows, and keeping only the newest duplicate per thread/provider key (2026-06-30).
- Changed `QuestHub` from `Deque<Task>` to `Deque<String>` so the queue stores only task ids and always reloads current task state from `TaskRepository` during routing/API reads (2026-06-30).
- Removed Python provider process-local session caches and fixed-session overrides; OpenCodeProvider and ClaudeCodeProvider now reuse upstream sessions only when platform-api sends `AgentExecutionRequest.providerSessionId` (2026-06-30).
- Changed the OpenCode session export fallback from a broad content recovery path to a session-scoped recovery path; both fresh and reused invocations may recover final text from `opencode export <sessionId>` when runtime has a concrete session id and stdout is empty (2026-06-30).
- Kept OpenCode `opencode session list` for provider session id discovery when `opencode run` omits a session id; discovered ids are used for per-thread provider-session persistence and first-run export recovery when stdout is empty (2026-06-30).
- Fixed MasterAgent planning startup by mounting its FastMCP server with HTTP transport, auto-injecting a default Claude Code `--mcp-config` for `submit_direct_answer/submit_task_plan`, and surfacing Claude stderr diagnostics when no MCP result is submitted (2026-06-29).
- Persisted MasterAgent Claude Code provider sessions in `agent_session` under `agentId=masteragent` and `provider=claudecode`; parser requests now pass the existing session id into Claude Code with `--resume` and write back the latest emitted `session_id` (2026-06-29).
- Changed MasterAgent to use Claude Code CLI by default (`claude -p <prompt> --output-format stream-json --verbose ...`) and pass `AGENT_RUNTIME_CLAUDECODE_MCP_CONFIG_JSON` through `--mcp-config` when configured (2026-06-29).
- Changed MasterAgent OpenCode invocation to use the supported `opencode run <prompt>` form, removing unsupported default `--agent plan`, `-m`, and `--format json` arguments from command construction (2026-06-29).
- Added an OpenCode session discovery fallback: when `opencode run` output does not expose a session id, agent-runtime calls `opencode session list`, uses the latest `ses_...`, and continues provider-session persistence (2026-06-29).
- Added `thread_id` to `agent_session`, including MySQL backfill migration and platform-api writes from the owning chat thread, as the basis for thread-scoped provider session reuse (2026-06-29).
- Changed OpenCode execution to use a PTY by default so `opencode run <prompt>` behaves like manual terminal execution, while preserving `opencode -s <sessionId> run <prompt>` for reused provider sessions (2026-06-29).
- Changed the OpenCode runtime adapter to parse plain stdout from `opencode run` instead of requiring JSON events, filter the formatted model banner, and reuse provider sessions with `opencode -s <sessionId> run ...` when a thread-level session is available (2026-06-29).
- Hardened chat planning execution by moving MasterAgent calls outside database transactions, bounding the planning executor queue, and returning a generic user-facing failure message while preserving detailed server logs (2026-06-26).
- Split chat message submission from MasterAgent planning so `POST /messages` now returns after saving the user message/thread state, while planning, direct answers, and task enqueue continue asynchronously through WebSocket updates (2026-06-26).
- Removed the business-agent prompt fallback that allowed agents to bypass MCP tools by printing `@agentId` task instructions; follow-up task creation must now use MCP tools or be skipped safely (2026-06-25).
- Changed the web chat composer so Enter submits the message, while Shift+Enter still inserts a newline (2026-06-25).
- Upgraded the sidebar online-agent status area into a frosted-glass presence card with richer agent rows and explicit idle/running labels (2026-06-25).
- Changed sidebar conversation `TASK-n` accents from muted blue to warm gray for better alignment with the minimalist palette (2026-06-25).
- Replaced the dot before sidebar conversation `TASK-n` labels with a dash marker (2026-06-25).
- Added a `当前在线` label above the sidebar agent status list (2026-06-25).
- Changed conversation history card accents from dark monochrome to a muted light blue for the active rail, task label, and dot marker (2026-06-25).
- Reworked the web UI toward a premium minimalist style with a warm monochrome palette, flatter surfaces, lighter borders, reduced shadows, and muted semantic accents (2026-06-25).
- Reduced the size of the web sidebar `新建任务` entry by tightening its height, padding, icon, and text scale (2026-06-25).
- Restyled the web sidebar controls in a quiet productivity-navigation style, with a full-width `新建任务` entry and calmer vertical agent status rows while leaving conversation history cards unchanged (2026-06-25).
- Restyled the expanded DAG task view as a smaller embedded right-side card, shortened its height, narrowed its lane, and placed the `任务视图` title next to the collapse action (2026-06-25).
- Changed the expanded DAG panel header so the collapse action sits on the left and the title reads `任务视图` on the right (2026-06-25).
- Restored the user avatar in the web chat timeline as a floating element so it no longer pushes the user message bubble away from the right edge (2026-06-25).
- Removed the user avatar column from the web chat user-message layout so the message bubble itself respects the configured right inset instead of being offset by the avatar gap (2026-06-25).
- Reduced the right-aligned user chat inset to 20px so user messages sit slightly farther right within the stable chat rail (2026-06-25).
- Adjusted the right-aligned user chat inset so user messages sit closer to the right side of the stable chat rail without touching the edge (2026-06-25).
- Fixed the web chat rail sizing so the main conversation keeps a viewport-based stable width instead of resizing with sidebar or DAG column changes (2026-06-25).
- Shifted right-aligned user chat messages slightly inward from the rail edge for a more comfortable reading position (2026-06-25).
- Fixed the web conversation layout around a stable centered chat rail so user messages stay right-aligned, agent messages stay left-aligned, and side panel collapse only shifts the rail without changing internal message wrapping (2026-06-25).
- Changed the web DAG layout so the expanded panel reserves a right-side lane, while the collapsed state only keeps a 64px rail instead of leaving a large empty area (2026-06-25).
- Changed the web chat timeline so user messages align to the right with the user icon on the right, while agent messages remain left-aligned (2026-06-25).
- Simplified the web workspace visual hierarchy by reducing shadows, softening panel backgrounds, lightening chat bubbles and composer styling, and removing the right-side DAG summary counters (2026-06-25).
- Aligned the main conversation layout around a fixed shared horizontal gutter so messages, composer, and send action keep consistent side spacing (2026-06-25).
- Visually merged the chat composer into the main conversation canvas by removing the footer divider and separate composer background (2026-06-25).
- Changed sidebar `TASK-n` labels to use chronological thread order so newly created conversations receive increasing task numbers even when displayed at the top (2026-06-25).
- Refined the web typography system with a premium sans stack, shared type scale, normalized weights, and more harmonious sidebar/chat/DAG text hierarchy (2026-06-25).
- Refined the web UI visual system with a unified neutral palette, blue accent, cleaner panel hierarchy, consistent card styling, and polished chat/DAG surfaces (2026-06-25).
- Restyled the web sidebar conversation cards to show `TASK-n` and title only, removing status and trace metadata from the card body (2026-06-25).
- Further reduced the default web sidebar width for a more compact three-column layout (2026-06-25).
- Reduced the default width of the web sidebar while keeping its collapsed width unchanged (2026-06-25).
- Increased the sidebar brand prominence and reduced the visual width of the new-chat button (2026-06-25).
- Removed the `Threads` label from the web sidebar brand area, leaving `Agent Crossing` as the only brand mark (2026-06-25).
- Removed the conversation search control and filtering logic from the web sidebar (2026-06-25).
- Reorganized the web sidebar header so brand/actions and new-chat entry use a two-row layout (2026-06-25).
- Removed Queue status fetching and Queue API client usage from the web frontend (2026-06-25).
- Removed the left sidebar Queue summary card from the web workspace (2026-06-25).
- Fixed the web chat composer height and disabled manual textarea resizing (2026-06-25).
- Restyled the web chat composer as a larger rounded input card with an embedded send action (2026-06-25).
- Removed the conversation header strip from the web workspace and tightened the three-column layout so history, chat, and DAG panels sit flush together (2026-06-25).
- Changed the web workspace layout to a three-column structure with collapsible history and DAG panels (2026-06-25).
- Removed the React Flow task/dependency count badges from the web DAG canvas (2026-06-25).
- Removed the right-side Developer Log tab from the web DAG panel (2026-06-25), while keeping invocation messages available for task output fallback.
- Changed DAG task inspector creation time (2026-06-25) to show full date and 24-hour time.
- Changed DAG task inspector fields (2026-06-25) to show agent, execution status, creation time, duration, task content, and task output content.
- Changed the web DAG panel (2026-06-25) to use the React Flow + dagre rendering approach from the reference implementation, with automatic vertical layout, zoom/pan controls, disabled manual node dragging, dashed branch edges, and clickable node expansion.
- Changed MasterAgent planning prompt so ordinary Q&A and single-step requests produce one direct task, while only complex work is decomposed into a multi-task DAG.
- Changed the web right-side run panel (2026-06-24) from a linear Steps list to a current-thread DAG status view with dependency levels, upstream task references, downstream counts, and status summary.
- Changed the web DAG panel (2026-06-24) to render tasks as clickable layer-based nodes; selecting a node expands task id, status, agent, dependencies, downstream count, creator, and full task context.

### Fixed

- Fixed MasterAgent ordinary Q&A failures when Claude Code emits assistant text on stdout but does not call the final `submit_direct_answer` MCP tool; stdout assistant text is now used as a direct-answer fallback with provider session capture preserved (2026-06-30).
- Fixed `scripts/dev-up.sh` agent-runtime startup stability by launching the project venv `uvicorn` directly as the long-lived background process and detaching stdin from `/dev/null`; this prevents readiness from passing through a short-lived wrapper/session and then leaving platform-api with `Connection refused` on `8090` (2026-06-30).
- Fixed MasterAgent timeouts caused by reused Claude Code sessions calling MCP tools with a stale `planningSessionId`; when there is exactly one active planning session, any stale/unknown planning id is now safely resolved to that active session, while concurrent sessions still reject ambiguous stale ids (2026-06-30).
- Fixed MasterAgent FastMCP HTTP integration by attaching the MCP sub-application lifespan to FastAPI, so Claude Code can actually call `submit_direct_answer` and `submit_task_plan` instead of receiving `Task group is not initialized` from the MCP endpoint (2026-06-29).
- Fixed OpenCode PTY parsing when the formatted model banner and assistant text arrive on the same terminal line: the parser now strips only the banner prefix and preserves the remaining answer text (2026-06-29).
- Fixed OpenCode plain stdout parsing so ANSI reset/control sequences are stripped and the formatted model banner is filtered before chat messages are emitted (2026-06-29).
- Fixed OpenCode reused-session id propagation so agent-runtime falls back to the request `providerSessionId` when `opencode -s <sessionId> run ...` exits without emitting a fresh `sessionID` event (2026-06-25).
- Fixed OpenCode silent-completion handling for newer `part` events by parsing `part` text before deciding stdout has no assistant content (2026-06-25).
- Fixed MasterAgent prompt loading failure caused by a stray trailing character in `services/agent-runtime/config/prompts.json` after the JSON document (2026-06-25).
- Fixed the sidebar online-agent list so `claudecode` consistently renders as `ClaudeCode`, and aligned the backend agent catalog display name source (2026-06-25).
- Fixed DAG node expansion overlap (2026-06-25) by moving expanded task metadata into a separate scrollable inspector panel instead of rendering large details inside React Flow nodes.
- Fixed ClaudeCode display label (2026-06-25) so the web UI shows `ClaudeCode` instead of `Claude Code`.
- Corrected `MODEL_SYNC(Task)` comments to separate wire shape from domain node shape; `dependsOn` is documented as dependency-repository data instead of a `Task.java` field.
- Changed `TaskResponse.depth` from primitive `int` to boxed `Integer` to match `Task.depth` and the MyBatis boxed-number rule.
- Updated `CallbackController` transaction comments to match the current transient realtime event and `AssistantStreamBuffer` write path.
- Translated agent-runtime parser HTTP errors in `HttpQuestParserClient` so MasterAgent failures surface with their runtime code/message instead of a generic `Unexpected server error`.
- Fixed OpenCode/Claude Code MCP connection setup by exposing the MasterAgent MCP endpoint with FastMCP HTTP transport and a generated `--mcp-config`.
- Fixed business agent MCP task-status lookups by passing `userId` through `AgentExecutionRequest` and injecting it into CLI prompts; simple Q&A prompts now explicitly avoid MCP tool calls.
- Fixed repeated MasterAgent task ids leaving later user messages stuck in `running` (2026-06-24): user/agent task plans now remap ids that already exist and preserve remapped dependencies.
- Changed simple MasterAgent replies (2026-06-24): when no downstream task is required, MasterAgent now submits a direct answer that platform-api saves as an assistant `chat_message`; the frontend displays it without creating a task.
- Improved chat attribution (2026-06-24): MasterAgent direct replies are now persisted with `agentId=masteragent`, and the web chat timeline visually distinguishes MasterAgent, OpenCode, and ClaudeCode messages.
- Added explicit agent routing rule (2026-06-24): user inputs starting with `@agentId` must be planned as a single task for that available agent instead of being answered directly by MasterAgent.
- Improved business-agent delegation fallback (2026-06-24): business prompts now require concise user-facing acknowledgements instead of JSON tool-call prose, and the agent-output parser can recover `create_tasks` JSON blocks into real follow-up tasks when an agent prints them.
- Fixed OpenCode empty-reply handling (2026-06-24, later hardened): current runtime attempts session-scoped `opencode export <sessionId>` recovery when a concrete session id exists, and emits a silent-completion diagnostic only when recovery cannot find assistant text.

## [v1.1.0] - 2026-06-23

Status: Implemented

Spec: [spec/v1.1.md](spec/v1.1.md)

### Summary

v1.1 upgrades the platform from a minimal task dispatcher into a realtime, thread-oriented multi-agent collaboration system. The official web realtime path is now WebSocket, while backend SSE is retained only for debugging and fallback integration.

### Added

- Added `ChatThread`, `ChatMessage`, and `InvocationMessage` models for conversation-oriented product flows.
- Added Next.js web UI under `services/platform-web`, including thread list, chat timeline, agent status, and execution log.
- Added WebSocket event delivery for `thread`, `chatMessage`, `invocationMessage`, and `task` events.
- Added persisted realtime event log backed by `realtime_event` with `lastEventId` replay for reconnect recovery.
- Added transient realtime events for streaming message fragments to reduce MySQL write amplification.
- Added `task` event push when tasks are created or transition through `queued`, `processing`, `completed`, `failed`, and `blocked`.
- Added one-thread-one-trace behavior: all user inputs in a thread now continue the same long-running task context.
- Added task DAG support with `dependsOn: string[]` and `task_dependency` edge persistence.
- Added MasterAgent planning flow: user input is routed through an internal planner before task creation.
- Added FastMCP-based tools for task planning, downstream task status lookup, and append-only task creation.
- Added minimal multi-user isolation through `X-User-Id` and WebSocket `userId` query parameter.
- Added `AgentCard`/agent catalog support with static role, capability, tool, and runtime metadata.
- Added MyBatis persistence mode for task, task dependency, invocation, chat thread, chat message, invocation message, `realtime_event` event log, agent catalog, user, and agent context cursor data.
- Added `agent_context_cursor` and incremental prompt history injection.
- Added configurable static prompts for MasterAgent and business agents.
- Added OpenCode provider-session discovery and persistence so runtime can reuse per-thread sessions even when `opencode run` output only exposes session metadata.
- Added per-thread OpenCode session reuse keyed by `userId + threadId + agentId + provider` through platform-managed provider sessions.

### Changed

- Replaced frontend pull-after-push task refresh with WebSocket-first task event upsert.
- Changed task relationship modeling from `parentId`/`childId` chains to DAG dependencies.
- Removed `taskPackage` from the current DAG scheduling model; all runnable tasks are coordinated from `questHub` and dependency checks.
- Changed router scheduling to dispatch only tasks whose direct dependencies are completed.
- Changed user message submission to reuse the thread trace instead of creating a new trace per message.
- Changed agent runtime prompt construction so static prompt content appears first and dynamic invocation/history context appears last.
- Changed business agent prompt history injection to include user messages and other agents' messages, while excluding the current agent's own chat history.
- Changed context cursor update timing so cursor advances only after successful agent execution.
- Changed WebSocket/SSE event names to lowerCamelCase canonical names; legacy names are no longer part of the frontend contract.
- Changed slow-client realtime delivery behavior so publishing is isolated from critical routing locks.

### Fixed

- Fixed frontend repeated thread auto-creation on page open.
- Fixed chat page scrolling issues when message volume grows.
- Fixed execution log layout overflow and text wrapping problems.
- Fixed raw OpenCode JSON leaking into the chat transcript by extracting assistant text from supported stdout events only.
- Fixed tool-only OpenCode output cases by emitting tool messages plus a silent-completion diagnostic when no final text message is emitted.
- Fixed duplicate or premature context cursor updates that could skip history during failed retries.
- Fixed old compatibility logic around thread trace switching after the product decision that a thread never changes trace.

### Compatibility

- Backend SSE endpoints are retained but are not used by the official web frontend.
- Existing `GET /api/tasks?traceId=...` remains available for initialization and fallback recovery.
- In-memory storage remains available for local development; MySQL mode is available for durable state.
- Provider session reuse is persisted in `agent_session` keyed by `userId + threadId + agentId + provider`; agent-runtime restart no longer loses thread-scoped upstream session mappings.

### Known Follow-ups

- Add production login/session management beyond the current `X-User-Id` development header.
- Add bounded retention and cleanup policy for `realtime_event`, invocation messages, and chat messages.

## [v1.0.0] - 2026-06-13

Status: Implemented

Spec: [spec/v1.0.md](spec/v1.0.md)

Task list: [tasklistv1.0.md](spec/tasklistv1.0.md)

### Summary

v1.0 delivers the first minimum viable multi-agent orchestration loop using Spring Boot as the platform control plane and FastAPI as the agent runtime. The initial implementation focuses on in-memory task orchestration, OpenCode CLI integration, callback write-back, and testable local development.

### Added

- Added repository layout with `spec`, `contracts`, `services/platform-api`, `services/agent-runtime`, `deployments`, and `scripts`.
- Added shared JSON schemas and examples for `Task`, `Invocation`, parser requests/responses, runtime execution, and `AgentMessage`.
- Added Spring Boot `platform-api` skeleton with health check, unified API response, exception handling, Maven build, and tests.
- Added FastAPI `agent-runtime` skeleton with pydantic contracts, health check, runtime routes, and pytest setup.
- Added Java domain models for task, invocation, agent, queue, and task status.
- Added in-memory repositories for task and invocation data.
- Added `QuestHub` and initial serial `taskPackage` queues.
- Added parser client flow from Java platform-api to Python agent-runtime.
- Added user input parsing stub that emits structured task arrays.
- Added rule-based agent output parser for line-start `@agentId` task creation.
- Added `QuestRouterService`, `ParallelTaskWorker`, and invocation execution flow.
- Added `LoopGuardService` for depth and same-agent self-trigger limits.
- Added callback message API without token authentication for MVP scope.
- Added OpenCode provider adapter that invokes CLI, reads stdout/stderr, and normalizes output to `AgentMessage`.
- Added local scripts for development startup, shutdown, tests, and lint entry points.
- Added Java and Python unit/integration tests for parser rules, queue ordering, task routing, invocation state, loop guard, and acceptance flow.

### Changed

- Standardized cross-service field names to lowerCamelCase.
- Standardized Python pydantic aliases to match Java/API wire contracts.
- Kept CLI agents constrained to chat/reasoning output for the first version.
- Kept callback token authentication explicitly out of scope for v1.0.

### Fixed

- Fixed unknown agent handling so unsupported `agentId` tasks are discarded.
- Fixed task status progression for successful and failed invocations.
- Fixed serial-chain failure behavior so downstream tasks can be marked `blocked`.
- Fixed queue handling to support peek/poll/remove and safe movement of chain tasks.

### Compatibility

- v1.0 uses in-memory storage only.
- v1.0 does not include Web UI, MySQL, Redis, durable queues, production auth, or distributed scheduling.
- v1.0 serial-chain `parentId`/`childId` semantics were later superseded by v1.1 DAG dependencies.
