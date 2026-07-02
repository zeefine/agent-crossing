# OpenCode Provider 解析复盘

本文记录 Agent Crossing 接入 OpenCode CLI 过程中已经验证过的事实、犯过的错误判断，以及后续排查规则。目标是避免再次把 CLI 行为、PTY/PIPE 行为、session 持久化和前端展示问题混在一起判断。

## 当前正确链路

Fresh session:

1. runtime 执行 `opencode run <prompt>`。
2. `run` 在 PTY 模式下可能直接输出正文。
3. 如果 `run` 输出里没有 sessionId，runtime 通过 `opencode session list` 发现最新 sessionId。
4. 拿到 sessionId 后，Java 侧把它写入 `agent_session`，键为 `userId + threadId + agentId + provider`。

Reused session:

1. Java 从 `agent_session` 查出当前 `threadId` 对应的 `providerSessionId`。
2. runtime 执行 `opencode -s <sessionId> run <prompt>`。
3. 已确认 reused session 的 `run` 不稳定输出正文，通常正文只写入 provider session。
4. runtime 执行 `opencode export <sessionId>`，从导出的 messages 中取当前轮 user 对应的 assistant text。

## 犯过的错误

### 1. 误以为 OpenCode 必须按 NDJSON 事件流解析

早期按 `opencode run --format json` 的事件流思路接入，尝试把 `step_start`、`text`、`tool_use` 等事件映射成统一 `AgentMessage`。实际当前可用命令主要是：

```bash
opencode run <prompt>
opencode -s <sessionId> run <prompt>
opencode export <sessionId>
```

`opencode run <prompt>` 的常见输出是普通终端文本，例如模型 banner 加正文。结论：OpenCode provider 不能只依赖 JSON/NDJSON，要支持 plain stdout。

### 2. 把 `session_init` 当成可展示回复

早期把 `step_start` 映射成 `session_init` 后进入前端消息流，导致用户看到 `session_init` 这类内部事件。结论：session 初始化只用于 provider 状态和 session 捕获，不应该作为 chat reply 展示给用户。

### 3. 误以为 `opencode -s <sessionId> run` 会像 fresh run 一样输出正文

手动验证后确认：复用 session 时，`opencode -s <sessionId> run <prompt>` 可能没有命令行正文输出，正文写入 session，必须通过 `opencode export <sessionId>` 获取。

结论：`providerSessionId != null` 时，不应该依赖 stdout/PT​Y 正文展示回复；stdout 只能作为诊断，最终文本应从 export 中取。

### 4. 只取最后一条 assistant，导致重复上一轮答案

一开始 export 后直接取最后一条 assistant text。实际 reused session 下，export 写入可能有延迟；如果立刻 export，最后一条 assistant 可能仍然是上一轮回复，于是前端重复显示旧答案。

修正思路：

- 执行前先记录上一轮最新 user marker。
- 执行后循环 export。
- 从 export messages 中找到当前轮新增 user。
- 只取 `assistant.parentID == 当前轮 user message id` 的回复。

### 5. 用时间戳判断当前轮，误丢有效回复

曾经用 runtime 本地时间作为 gate，过滤 export 中早于本轮调用时间的 assistant。后来发现 provider 写入时间和 Python runtime 时间不一定同源，导致有效回复被误判为旧消息。

结论：如果能定位 previous user marker，应优先使用 export 中的消息顺序和 `parentID`，时间戳只适合作为兜底诊断，不适合作为主判断。

### 6. `opencode export` 走 PIPE，导致半截 JSON

曾经用 `asyncio.create_subprocess_exec(... stdout=PIPE ...)` 读取 `opencode export <sessionId>`。平台子进程场景下出现过输出被截断、JSON 只有半截的问题。

修正：`export` 改为 PTY 读取，模拟真实终端行为。结论：手动终端可用，不代表 PIPE 子进程可用；OpenCode 这类 CLI 需要优先按 PTY 行为适配。

### 7. `opencode session list` 仍走 PIPE，导致 sessionId 发现失败

最后一次问题中，OpenCode 实际已经创建 session，日志里能看到：

```text
service=session id=ses_xxx created
```

但平台没有把 sessionId 写入 `agent_session`。根因不是“没有创建 session”，而是 runtime 用 PIPE 执行 `opencode session list`，而该命令在平台子进程里可能失败或输出不稳定。

修正：`session list` 和 `export` 一样改为 PTY 读取。

### 8. 过早判断为 cwd 问题

曾经根据日志中的 `directory=/services/agent-runtime` 推断 session list 查不到是因为运行目录不一致。用户手动在项目根目录执行 `opencode session list` 能看到 session，但进一步日志证明平台确实创建了 session。

正确结论：cwd 可能影响 project 归属，但这次直接根因是 `session list` 读取方式不一致；不能在没有检查 runtime stdout/stderr/returnCode 的情况下把问题归因到 cwd。

### 9. 诊断信息不足

早期很多 fallback 只返回：

```text
OpenCode completed without text output...
```

但没有足够字段说明是 run 无输出、session list 失败、export 非零退出、export JSON 解析失败，还是当前轮 assistant 未匹配。

结论：CLI provider 的 fallback 必须带诊断字段，至少包括：

- command 类型
- read mode: `pty` / `pipe`
- return code
- stdout/stderr 字符数和 tail
- sessionId
- export attempt count
- previous/current user marker
- fallback reason

## 后续排查准则

遇到 OpenCode 前端无输出、重复输出、或 session 不复用时，按以下顺序检查：

1. 先查 `agent_session` 是否写入当前 `userId + threadId + opencode + opencode`。
2. 如果没有写入，查 runtime 是否拿到了 `DONE.raw.providerSessionId`。
3. 如果没有拿到，查 `opencode session list` 的 return code 和输出 tail。
4. 如果 sessionId 有但回复为空，查 `opencode export <sessionId>` 是否能解析完整 JSON。
5. 如果 export 有文本但前端重复旧回复，检查当前轮 user marker 和 assistant `parentID` 是否匹配。
6. 不要只用手动终端结果推断平台行为，必须同时确认平台子进程的 cwd、env、PTY/PIPE 读取模式。

## 稳定性原则

- `opencode run <prompt>`：fresh session，优先 PTY 解析普通 stdout。
- `opencode -s <sessionId> run <prompt>`：reused session，不依赖 stdout 正文。
- `opencode export <sessionId>`：必须 PTY 读取。
- `opencode session list`：必须 PTY 读取。
- session 复用只按 `threadId` 维度，不再使用 trace fallback。
- 任何 silent fallback 都必须可诊断，不能只给用户一句泛化错误。
