---
title: OpenCode Provider 踩坑清单
doc_kind: ops-checklist
created: 2026-07-02
updated: 2026-07-02
status: active
---

# OpenCode Provider 踩坑清单

> 仅针对 `services/agent-runtime` 里的 OpenCode CLI adapter。
> OpenCode 是终端优先的 CLI，**手动终端行为不等于平台子进程行为**。排查时必须同时确认命令、cwd、env、PTY/PIPE 读取模式、provider session 写入和前端消息展示。

## 1. 为什么写这份清单

2026-06 到 2026-07 的 OpenCode 接入反复出现三类问题：前端显示 `session_init`、重复上一轮回答、以及 `OpenCode completed without text output...`。这些问题不是一个单点 bug，而是 **CLI 输出模式 + provider session 复用 + export 延迟写入 + 子进程读取方式** 叠加后的连锁问题。

这份文档把已经验证过的坑固化下来。后续改 OpenCode provider、排查空回复、重复回复、session 不复用前，先按本文 checklist 走一遍。

## 2. 必修：OpenCode 当前正确执行链路

Fresh session:

1. runtime 执行 `opencode run <prompt>`。
2. `run` 在 PTY 模式下可能直接输出模型 banner 和正文。
3. 如果 `run` 输出里没有 sessionId，runtime 执行 `opencode session list` 发现最新 sessionId。
4. Java 侧把 sessionId 写入 `agent_session`，键为 `userId + threadId + agentId + provider`。

Reused session:

1. Java 从 `agent_session` 查出当前 thread 的 `providerSessionId`。
2. runtime 执行 `opencode -s <sessionId> run <prompt>`。
3. 已确认 reused session 的 `run` 通常不向命令行输出最终正文。
4. runtime 执行 `opencode export <sessionId>`，从导出的 messages 中取当前轮 user 对应的 assistant text。

**审查规则**：不要把 fresh run 和 reused run 当成同一种 stdout 语义。fresh run 可以解析 stdout；reused run 的最终答案必须以 export 为准。

## 3. Pitfall 1：误把 OpenCode 当成稳定 NDJSON provider

### 症状

前端出现内部事件，或者 agent 输出缺失。例如：

```text
session_init
```

或者只看到 `step_start` / `tool_use` 之类事件，没有自然语言回复。

### 根因

早期参考 `opencode run --format json` 的事件流思路，把 `step_start`、`text`、`tool_use` 映射成统一 `AgentMessage`。但当前真实稳定链路主要是：

```bash
opencode run <prompt>
opencode -s <sessionId> run <prompt>
opencode export <sessionId>
```

`opencode run <prompt>` 常见输出是普通终端文本，不是稳定 NDJSON。

### 修法

- fresh run 支持 plain stdout 解析。
- `session_init` / `step_start` 只用于 provider 内部状态，不进入用户 chat reply。
- tool event 可以进入执行日志，但不能替代最终 assistant text。

### 规则

**OpenCode provider 不能只按 JSON/NDJSON 设计**。任何新增解析逻辑都要同时覆盖 plain terminal output。

## 4. Pitfall 2：`opencode -s <sessionId> run` 不等于 fresh run

### 症状

同一个 thread 第二轮以后，前端显示：

```text
OpenCode completed without text output. Check the execution log or OpenCode local logs for provider/session details.
```

但手动执行：

```bash
opencode export <sessionId>
```

可以看到 session 里有正常 assistant 回复。

### 根因

`opencode -s <sessionId> run <prompt>` 在 reused session 下可能不向 stdout/PT​Y 输出正文，只把最终内容写入 provider session。平台如果继续按 stdout 解析，就会误判为空输出。

### 修法

`providerSessionId != null` 时：

1. 执行 `opencode -s <sessionId> run <prompt>`。
2. 不依赖 run stdout 展示回复。
3. 执行 `opencode export <sessionId>`。
4. 从 export JSON 中提取当前轮 assistant text。

### 规则

**reused session 的 run output 只能做诊断，不能做最终回复来源**。

## 5. Pitfall 3：直接取最后一条 assistant 会重复上一轮答案

### 症状

同一个会话里连续提问：

```text
@opencode 1+1=?
@opencode 2+2=?
```

前端两次都显示第一轮答案。

### 根因

`opencode export <sessionId>` 写入可能有延迟。执行 `opencode -s ... run ...` 后立刻 export，最后一条 assistant 仍可能是上一轮回复。只取 “最后一条 assistant” 会把旧消息当成当前轮结果。

### 修法

当前实现应按消息关系取值：

1. 执行前记录上一轮 latest user marker。
2. 执行后循环 export。
3. 从 export messages 中找到当前轮新增 user。
4. 只取 `assistant.parentID == 当前轮 user message id` 的回复。

### 规则

**export 解析必须绑定当前轮 user**。不能只用“最后一条 assistant”，也不能只用文本是否非空判断。

## 6. Pitfall 4：用 runtime 时间戳过滤会误丢有效回复

### 症状

手动 export 能看到当前轮回复，但平台仍然 fallback 到空输出提示。

### 根因

曾经用 Python runtime 调用前后的本地时间作为 gate，过滤 export 里“早于本轮调用时间”的消息。实际 provider 写入时间和 Python 进程时间不一定同源，可能导致有效 assistant 被误判为旧消息。

### 修法

- 如果能定位 previous user marker，优先使用 export messages 的顺序。
- 再通过 assistant `parentID` 绑定当前 user。
- 时间戳只用于辅助诊断或 previous marker 缺失时的弱兜底。

### 规则

**消息拓扑优先于时间戳**：`previous user marker + parentID` 比 runtime wall-clock 更可靠。

## 7. Pitfall 5：PIPE 读取 OpenCode export 会得到半截 JSON

### 症状

`opencode export <sessionId>` 手动终端执行输出完整 JSON，但平台子进程读取时 JSON 被截断，导致解析失败。

### 根因

曾经用：

```python
asyncio.create_subprocess_exec(..., stdout=PIPE, stderr=PIPE)
```

读取 export。OpenCode 是终端优先 CLI，PIPE 模式下的行为和真实终端不同，出现过半截 JSON。

### 修法

`opencode export <sessionId>` 改为 PTY 读取，模拟手动终端。

### 规则

**所有用于读取 OpenCode 终端输出的关键命令优先用 PTY**。手动命令能成功，只能证明 PTY/terminal 路径可用，不能证明 PIPE 路径可用。

## 8. Pitfall 6：`opencode session list` 走 PIPE 会导致 sessionId 发现失败

### 症状

fresh run 已经正常回复，OpenCode 日志里也能看到：

```text
service=session id=ses_xxx created
```

但下一轮平台没有复用 session，`agent_session` 里也没有写入对应 provider session。

### 根因

fresh run 输出里通常没有 sessionId，runtime 需要通过 `opencode session list` 补拿最新 session。最后一次故障中，session 实际已经由平台子进程创建，但 `session list` 仍用 PIPE 读取，可能失败、输出不稳定，或者返回非零退出。

### 修法

`opencode session list` 和 `opencode export` 一样改为 PTY 读取。

### 规则

**session discovery 也是 provider 关键路径**。不能认为它只是辅助命令就继续走 PIPE。

## 9. Pitfall 7：过早把问题归因到 cwd

### 症状

平台创建的 OpenCode session 在日志里显示：

```text
directory=/Users/fine/PyProjects/agent-crossing/services/agent-runtime
```

手动在项目根目录执行 `opencode session list` 又能看到 session，于是容易推断为“cwd 不一致导致查不到”。

### 根因

cwd 确实可能影响 OpenCode 的 project 归属，但这次直接根因不是 cwd，而是平台读取 `session list` 的方式和手动终端不同。没有先拿到 runtime 的 returnCode/stdout/stderr，就过早下结论。

### 修法

排查顺序改成：

1. 先确认 OpenCode 日志是否创建 session。
2. 再确认 runtime 是否执行 `session list`。
3. 再看 `session list` 的 read mode、return code、stdout/stderr tail。
4. 最后再判断 cwd 是否影响 project/session 归属。

### 规则

**不要只凭 directory 字段归因**。cwd 是候选因素，不是默认根因。

## 10. Pitfall 8：fallback 没有诊断字段

### 症状

前端只看到：

```text
OpenCode completed without text output. Check the execution log or OpenCode local logs for provider/session details.
```

但无法判断到底是 run 无输出、session list 失败、export 非零退出、JSON 解析失败，还是当前轮 assistant 未匹配。

### 根因

CLI provider 的 silent fallback 早期只给用户文案，没有保留足够 raw diagnostics，导致只能靠猜。

### 修法

fallback raw payload 至少保留：

- command 类型
- read mode: `pty` / `pipe`
- return code
- stdout/stderr 字符数和 tail
- sessionId
- export attempt count
- previous/current user marker
- fallback reason

### 规则

**任何 CLI fallback 都必须可诊断**。没有诊断字段的泛化错误，会把下一次排查成本放大。

## 11. 通用 Checklist：改 OpenCode Provider 前

- [ ] fresh run 是否仍走 `opencode run <prompt>`？
- [ ] reused run 是否仍走 `opencode -s <sessionId> run <prompt>`？
- [ ] reused run 是否跳过 stdout 正文作为最终答案？
- [ ] `opencode export <sessionId>` 是否走 PTY？
- [ ] `opencode session list` 是否走 PTY？
- [ ] export 解析是否绑定当前轮 user，而不是直接取最后一条 assistant？
- [ ] 时间戳是否只作为兜底，不能覆盖 `parentID` 匹配？
- [ ] fallback raw 是否包含足够 diagnostics？
- [ ] `AGENT_RUNTIME_CLI_WORKING_DIRECTORY` 是否指向当前项目根目录？
- [ ] Java 侧是否按 `userId + threadId + agentId + provider` 保存和查找 `agent_session`？
- [ ] 测试是否覆盖 fresh session、reused session、无 stdout、export recovery、重复回答防护？

## 12. 排查顺序：前端无输出 / 重复输出 / session 不复用

1. 查 `agent_session` 是否存在当前 `userId + threadId + opencode + opencode`。
2. 如果不存在，查 runtime 返回的 `DONE.raw.providerSessionId`。
3. 如果没有 `providerSessionId`，查 `opencode session list` 是否执行成功。
4. 如果已有 sessionId 但回复为空，手动执行 `opencode export <sessionId>` 看 JSON 是否完整。
5. 如果 export 有文本但前端重复旧回复，检查当前轮 user marker 和 assistant `parentID`。
6. 如果手动终端正常、平台不正常，优先对比 PTY/PIPE、cwd、env，而不是先改业务逻辑。

## 13. 长期改进项

- 给 `session list` 增加短轮询和显式 diagnostics，避免 session index 延迟写入时直接丢失 provider session。
- 增加 provider command audit log，记录 command 类型、cwd、read mode、returnCode，不记录完整 prompt。
- 抽出 OpenCode session export parser 的 fixture 测试，覆盖多轮 user/assistant、tool-only、延迟写入、无 parentID 等边界。
- 保持 `AGENT_RUNTIME_CLI_WORKING_DIRECTORY` 显式配置，避免 runtime 启动目录改变 provider project 归属。

## 14. 参考

- 相关文件：
  - `services/agent-runtime/src/agent_runtime/providers/opencode.py`
  - `services/agent-runtime/tests/test_runtime.py`
  - `services/platform-api/src/main/java/com/agentcrossing/platform/application/invocation/InvocationService.java`
  - `services/platform-api/src/main/resources/mapper/AgentSessionMapper.xml`
  - `services/platform-api/src/main/resources/schema-mysql.sql`
- 相关表：
  - `agent_session`
  - `invocation_message`
  - `chat_message`
