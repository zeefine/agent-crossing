package com.agentcrossing.platform.application.invocation;

import com.agentcrossing.platform.application.chat.AssistantStreamBuffer;
import com.agentcrossing.platform.application.chat.ChatEventService;
import com.agentcrossing.platform.application.chat.ThreadStatusAggregator;
import com.agentcrossing.platform.application.realtime.RealtimeEventTypes;
import com.agentcrossing.platform.application.routing.TaskDispatchSignal;
import com.agentcrossing.platform.application.task.TaskEventService;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.invocation.InvocationUsage;
import com.agentcrossing.platform.domain.invocation.InvocationUsageRepository;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageRepository;
import com.agentcrossing.platform.domain.message.ChatMessageRole;
import com.agentcrossing.platform.domain.message.ChatMessageStatus;
import com.agentcrossing.platform.domain.message.InvocationMessage;
import com.agentcrossing.platform.domain.message.InvocationMessageRepository;
import com.agentcrossing.platform.domain.session.AgentSession;
import com.agentcrossing.platform.domain.session.AgentSessionRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskDependencyRepository;
import com.agentcrossing.platform.domain.task.TaskRepository;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class InvocationService {
    private static final Logger log = LoggerFactory.getLogger(InvocationService.class);
    private final InvocationRepository invocationRepository;
    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final AgentRuntimeClient agentRuntimeClient;
    private final String callbackBaseUrl;
    private final TaskDispatchSignal taskDispatchSignal;
    private final InvocationMessageRepository invocationMessageRepository;
    private final ChatThreadRepository chatThreadRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatEventService chatEventService;
    private final TaskEventService taskEventService;
    private final AssistantStreamBuffer assistantStreamBuffer;
    private final AgentContextService agentContextService;
    private final AgentSessionRepository agentSessionRepository;
    private final InvocationUsageRepository invocationUsageRepository;
    private final AgentSessionCompressionService sessionCompressionService;
    private final ThreadStatusAggregator threadStatusAggregator;
    private final ExecutionStateService executionStateService;

    public InvocationService(
            InvocationRepository invocationRepository,
            TaskRepository taskRepository,
            AgentRuntimeClient agentRuntimeClient,
            @Value("${agent-crossing.callback-base-url:http://127.0.0.1:8080/api/callback}") String callbackBaseUrl,
            TaskDispatchSignal taskDispatchSignal,
            InvocationMessageRepository invocationMessageRepository,
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            ChatEventService chatEventService,
            TaskEventService taskEventService,
            AssistantStreamBuffer assistantStreamBuffer,
            TaskDependencyRepository taskDependencyRepository,
            AgentContextService agentContextService,
            AgentSessionRepository agentSessionRepository,
            InvocationUsageRepository invocationUsageRepository,
            AgentSessionCompressionService sessionCompressionService,
            ThreadStatusAggregator threadStatusAggregator,
            ExecutionStateService executionStateService) {
        this.invocationRepository = invocationRepository;
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.agentRuntimeClient = agentRuntimeClient;
        this.callbackBaseUrl = callbackBaseUrl;
        this.taskDispatchSignal = taskDispatchSignal;
        this.invocationMessageRepository = invocationMessageRepository;
        this.chatThreadRepository = chatThreadRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.chatEventService = chatEventService;
        this.taskEventService = taskEventService;
        this.assistantStreamBuffer = assistantStreamBuffer;
        this.agentContextService = agentContextService;
        this.agentSessionRepository = agentSessionRepository;
        this.invocationUsageRepository = invocationUsageRepository;
        this.sessionCompressionService = sessionCompressionService;
        this.threadStatusAggregator = threadStatusAggregator;
        this.executionStateService = java.util.Objects.requireNonNull(executionStateService, "executionStateService");
    }

    public Invocation execute(Task task) {
        // invocation 表示“某个 task 的一次 agent 执行”，重试时会创建新的 invocation。
        Invocation invocation = newInvocation(task);
        try {
            executionStateService.withExistingTask(task.taskId(), () -> invocationRepository.save(invocation));
            return executeRegistered(task, invocation);
        } catch (DeletedExecutionException deleted) {
            if (assistantStreamBuffer != null) {
                assistantStreamBuffer.closeCallbacksAndDiscard(invocation.invocationId());
            }
            log.info("Discarded late execution result for deleted task taskId={} invocationId={}",
                    task.taskId(), invocation.invocationId());
            // An ephemeral result for the caller only: never reinsert deleted execution state.
            return invocation.withStatus(InvocationStatus.CANCELED);
        } finally {
            releaseClosedCallbackStream(invocation.invocationId());
            taskDispatchSignal.signal();
        }
    }

    private Invocation executeRegistered(Task task, Invocation invocation) {
        // Router 已预留 PROCESSING；启动时用条件更新确认状态，不能覆盖刚刚提交的取消。
        try {
            ExecutionStateService.State started = executionStateService.start(task.taskId(), invocation.invocationId());
            if (started.canceled()) {
                return finishCanceledInvocation(invocation, task);
            }
            if (started.invocation().status() != InvocationStatus.RUNNING || started.task().status() != TaskStatus.PROCESSING) {
                return started.invocation();
            }
            AgentExecutionSnapshot snapshot = prepareExecutionSnapshot(task);
            long runtimeStartedAt = System.nanoTime();
            AgentExecutionResult runtimeResult;
            try {
                runtimeResult = executeRuntime(invocation, task, snapshot);
            } catch (PromptVersionChangedException changed) {
                // Only this explicit pre-CLI rejection is retryable. Never retry unknown HTTP outcomes.
                if (snapshot.providerSession() == null || sessionCompressionService == null) {
                    throw changed;
                }
                if (executionStateService.reconcileCancellation(task.taskId(), invocation.invocationId()).canceled()) {
                    return finishCanceledInvocation(invocation, task);
                }
                sessionCompressionService.rotateForPromptChange(task, snapshot.providerSession());
                snapshot = prepareExecutionSnapshot(task);
                if (snapshot.providerSession() != null) {
                    throw new IllegalStateException("Prompt rotation did not prepare a new session");
                }
                if (executionStateService.reconcileCancellation(task.taskId(), invocation.invocationId()).canceled()) {
                    return finishCanceledInvocation(invocation, task);
                }
                runtimeResult = executeRuntime(invocation, task, snapshot);
            }
            AgentExecutionResult result = runtimeResult;
            AgentContextPack contextPack = snapshot.contextPack();
            log.info(
                    "agent_crossing_perf event=business_agent_runtime durationMs={} userId={} invocationId={} taskId={} traceId={} agentId={} messages={}",
                    elapsedMs(runtimeStartedAt),
                    invocation.userId(),
                    invocation.invocationId(),
                    task.taskId(),
                    task.traceId(),
                    task.agentId(),
                    result.messages().size());
            InvocationUsage invocationUsage = executionStateService.withExistingExecution(
                    task.taskId(), invocation.invocationId(), () -> {
                        InvocationUsage saved = persistInvocationUsage(invocation, task, result);
                        closeCallbackStream(invocation.invocationId());
                        if (!isCanceled(invocation.invocationId(), task.taskId())) {
                            persistAgentMessages(invocation, result);
                        }
                        return saved;
                    });
            if (isCanceled(invocation.invocationId(), task.taskId())) {
                return finishCanceledInvocation(invocation, task);
            }
            if (result.hasError()) {
                throw new RuntimeException(result.errorOutput());
            }
            rememberProviderSession(task, invocation, result);
            executionStateService.withExistingExecution(task.taskId(), invocation.invocationId(), () -> {
                if (!isCanceled(invocation.invocationId(), task.taskId())) {
                    completeAssistantStream(invocation, result.finalText());
                    acknowledgeInjectedContext(task, contextPack);
                }
                return null;
            });
            compactSessionIfNeeded(task, invocationUsage);
            // Keep the router's RUNNING/PROCESSING reservation until rotation has committed (or failed).
            // The visible answer is already final and can be included in the compression snapshot above.
            if (isCanceled(invocation.invocationId(), task.taskId())) {
                return finishCanceledInvocation(invocation, task);
            }
            ExecutionStateService.State completed = executionStateService.finish(
                    task.taskId(), invocation.invocationId(), InvocationStatus.SUCCEEDED);
            if (completed.canceled()) {
                return finishCanceledInvocation(invocation, task);
            }
            if (completed.changed()) {
                executionStateService.withExistingExecution(task.taskId(), invocation.invocationId(), () -> {
                    publishTask(completed.task());
                    threadStatusAggregator.refreshForTrace(completed.task().userId(), completed.task().traceId());
                    return null;
                });
            }
            return completed.invocation();
        } catch (DeletedExecutionException deleted) {
            throw deleted;
        } catch (RuntimeException exception) {
            executionStateService.withExistingExecution(task.taskId(), invocation.invocationId(), () -> {
                closeCallbackStream(invocation.invocationId());
                return null;
            });
            if (isCanceled(invocation.invocationId(), task.taskId())) {
                return finishCanceledInvocation(invocation, task);
            }
            ExecutionStateService.State failed = executionStateService.finish(
                    task.taskId(), invocation.invocationId(), InvocationStatus.FAILED);
            if (failed.canceled()) {
                return finishCanceledInvocation(invocation, task);
            }
            if (!failed.changed()) {
                return failed.invocation();
            }
            return executionStateService.withExistingExecution(task.taskId(), invocation.invocationId(),
                    () -> finishFailedInvocation(failed, exception));
        }
    }

    private Invocation finishFailedInvocation(ExecutionStateService.State failed, RuntimeException exception) {
        Invocation invocation = failed.invocation();
        Task failedTask = failed.task();
        publishTask(failedTask);
        // 若已存在流式 assistant 行则原地标记 FAILED 并追加异常信息，否则新开一行。
        String errorMessage = exception.getMessage();
        ChatMessage existingAssistant = chatMessageRepository == null
                ? null
                : chatMessageRepository.findAssistantStreamByInvocationId(invocation.invocationId()).orElse(null);
        if (existingAssistant != null) {
            String appendOnFailure = existingAssistant.status() == ChatMessageStatus.FAILED ? null : errorMessage;
            markAssistantStreamFinal(invocation.invocationId(), ChatMessageStatus.FAILED, appendOnFailure);
        } else {
            saveAssistantMessage(
                    failedTask.traceId(), invocation.invocationId(), failedTask.taskId(),
                    failedTask.agentId(), errorMessage, ChatMessageStatus.FAILED);
        }
        blockDependentDescendants(failedTask);
        threadStatusAggregator.refreshForTrace(failedTask.userId(), failedTask.traceId());
        return invocation;
    }

    /**
     * A stop request updates persistence before the runtime is interrupted. Runtime HTTP can still return normally
     * (or fail) afterwards. This check is only a fast path; conditional transactional writes decide the winner.
     */
    private boolean isCanceled(String invocationId, String taskId) {
        boolean invocationCanceled = invocationRepository.findByInvocationId(invocationId)
                .map(current -> current.status() == InvocationStatus.CANCELED)
                .orElse(false);
        boolean taskCanceled = taskRepository.findByTaskId(taskId)
                .map(current -> current.status() == TaskStatus.CANCELED)
                .orElse(false);
        return invocationCanceled || taskCanceled;
    }

    private Invocation finishCanceledInvocation(Invocation invocation, Task task) {
        ExecutionStateService.State state = executionStateService.reconcileCancellation(task.taskId(), invocation.invocationId());
        if (!state.canceled()) {
            return state.invocation();
        }
        return executionStateService.withExistingExecution(task.taskId(), invocation.invocationId(), () -> {
            closeCallbackStream(invocation.invocationId());
            Invocation canceled = state.invocation();
            Task canceledTask = state.task();
            markAssistantStreamFinal(canceled.invocationId(), ChatMessageStatus.CANCELED, null);
            publishTask(canceledTask);
            threadStatusAggregator.refreshForTrace(canceledTask.userId(), canceledTask.traceId());
            return canceled;
        });
    }

    private void closeCallbackStream(String invocationId) {
        if (assistantStreamBuffer != null) {
            assistantStreamBuffer.closeCallbacksAndDrain(invocationId);
        }
    }

    private void releaseClosedCallbackStream(String invocationId) {
        if (assistantStreamBuffer == null) {
            return;
        }
        try {
            assistantStreamBuffer.withInvocationLock(invocationId, () -> {
                boolean terminalOrDeleted = invocationRepository.findByInvocationId(invocationId)
                        .map(current -> current.status().isTerminal()).orElse(true);
                if (terminalOrDeleted) {
                    assistantStreamBuffer.forgetClosedCallbacks(invocationId);
                }
                return null;
            });
        } catch (RuntimeException exception) {
            // Keep the closed gate if persistence is unavailable; never reopen an already finalized stream.
            log.warn("Could not release closed callback stream invocationId={}", invocationId, exception);
        }
    }

    private Invocation newInvocation(Task task) {
        Instant now = Instant.now();
        Invocation invocation = new Invocation(
                "invocation-" + UUID.randomUUID(),
                task.userId(),
                task.taskId(),
                task.traceId(),
                task.agentId(),
                InvocationStatus.QUEUED,
                now,
                null,
                null);
        return invocation;
    }

    private AgentContextPack buildContextPack(Task task) {
        return agentContextService == null ? new AgentContextPack(List.of()) : agentContextService.buildContextPack(task);
    }

    private AgentExecutionSnapshot loadExecutionSnapshot(Task task) {
        return new AgentExecutionSnapshot(buildContextPack(task), findProviderSession(task));
    }

    private AgentExecutionSnapshot prepareExecutionSnapshot(Task task) {
        return sessionCompressionService == null ? loadExecutionSnapshot(task)
                : sessionCompressionService.prepareExecution(task, () -> loadExecutionSnapshot(task));
    }

    private AgentExecutionResult executeRuntime(Invocation invocation, Task task, AgentExecutionSnapshot snapshot) {
        AgentSession session = snapshot.providerSession();
        return agentRuntimeClient.execute(new AgentExecutionRequest(
                invocation.invocationId(), invocation.userId(), task.taskId(), task.traceId(), task.agentId(),
                task.context(), callbackBaseUrl, snapshot.contextPack(),
                session == null ? null : session.providerSessionId(), session == null ? null : session.promptVersion()));
    }

    private void acknowledgeInjectedContext(Task task, AgentContextPack contextPack) {
        if (agentContextService != null) {
            agentContextService.acknowledgeInjectedMessages(task, contextPack);
        }
    }

    private AgentSession findProviderSession(Task task) {
        if (agentSessionRepository == null) {
            return null;
        }
        String threadId = findThreadId(task);
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        return agentSessionRepository
                .findByThreadId(task.userId(), threadId, task.agentId(), providerFor(task))
                .orElse(null);
    }

    private void rememberProviderSession(Task task, Invocation invocation, AgentExecutionResult result) {
        if (agentSessionRepository == null) {
            return;
        }
        String providerSessionId = result.providerSessionId();
        String promptVersion = result.promptVersion();
        if (providerSessionId == null
                || providerSessionId.isBlank()
                || promptVersion == null
                || promptVersion.isBlank()) {
            return;
        }
        String threadId = findThreadId(task);
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        String provider = providerFor(task);
        Instant now = Instant.now();
        Instant createdAt = agentSessionRepository
                .findByThreadId(task.userId(), threadId, task.agentId(), provider)
                .map(AgentSession::createdAt)
                .orElse(now);
        AgentSession session = new AgentSession(
                task.userId(),
                threadId,
                task.traceId(),
                task.agentId(),
                provider,
                providerSessionId,
                promptVersion,
                createdAt,
                now);
        if (sessionCompressionService != null) {
            sessionCompressionService.rememberSession(task, threadId, session);
        } else {
            executionStateService.withExistingExecution(task.taskId(), invocation.invocationId(),
                    () -> agentSessionRepository.save(session));
        }
    }

    private String findThreadId(Task task) {
        if (chatThreadRepository == null) {
            return null;
        }
        return chatThreadRepository
                .findByTraceId(task.traceId())
                .filter(thread -> thread.userId().equals(task.userId()))
                .map(thread -> thread.threadId())
                .orElse(null);
    }

    private static String providerFor(Task task) {
        return task.agentId();
    }

    private InvocationUsage persistInvocationUsage(Invocation invocation, Task task, AgentExecutionResult result) {
        if (invocationUsageRepository == null || result.usage() == null) {
            return null;
        }
        AgentExecutionUsage usage = result.usage();
        String provider = nonBlankOrDefault(usage.provider(), providerFor(task));
        String model = nonBlankOrDefault(usage.model(), "unknown");
        String providerSessionId = nonBlankOrDefault(usage.providerSessionId(), result.providerSessionId());
        try {
            return invocationUsageRepository.save(new InvocationUsage(
                    invocation.invocationId(),
                    provider,
                    model,
                    providerSessionId,
                    usage.totalInputTokens(),
                    usage.lastRequestInputTokens(),
                    usage.usagePrecision(),
                    usage.inputTokens(),
                    usage.cachedInputTokens(),
                    usage.cacheCreationInputTokens(),
                    usage.cacheReadInputTokens(),
                    usage.outputTokens(),
                    usage.reasoningOutputTokens(),
                    usage.contextInputTokens(),
                    usage.rawUsageJson(),
                    usage.providerCliVersion(),
                    usage.observedAt() == null ? Instant.now() : usage.observedAt()));
        } catch (RuntimeException exception) {
            // Usage persistence must not turn a successfully completed provider execution into a failed task.
            log.warn("Failed to persist invocation usage invocationId={}", invocation.invocationId(), exception);
            return null;
        }
    }

    private void compactSessionIfNeeded(Task task, InvocationUsage usage) {
        if (sessionCompressionService == null || usage == null) {
            return;
        }
        try {
            sessionCompressionService.compactIfNeeded(task, usage);
        } catch (DeletedExecutionException deleted) {
            throw deleted;
        } catch (RuntimeException exception) {
            // A summary failure must never change a successfully completed business invocation.
            log.warn("Session compression failed after invocation taskId={} agentId={}",
                    task.taskId(), task.agentId(), exception);
        }
    }

    private static String nonBlankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void persistAgentMessages(Invocation invocation, AgentExecutionResult result) {
        boolean hasStreamedMessages = hasStreamedInvocationMessages(invocation.invocationId());
        for (AgentMessage message : result.messages()) {
            if (hasStreamedMessages && message.type() != AgentMessageType.DONE) {
                continue;
            }
            if (invocationMessageRepository != null) {
                InvocationMessage saved = invocationMessageRepository.save(new InvocationMessage(
                        "invocation-message-" + UUID.randomUUID(),
                        invocation.userId(),
                        invocation.invocationId(),
                        invocation.taskId(),
                        invocation.traceId(),
                        invocation.agentId(),
                        message.type(),
                        message.content(),
                        message.raw(),
                        message.createdAt() == null ? Instant.now() : message.createdAt()));
                publishInvocationMessage(saved);
            }
            if (message.type() == AgentMessageType.TEXT_DELTA || message.type() == AgentMessageType.MESSAGE) {
                saveAssistantMessage(
                        invocation.traceId(),
                        invocation.invocationId(),
                        invocation.taskId(),
                        invocation.agentId(),
                        message.content(),
                        ChatMessageStatus.STREAMING);
            }
            if (message.type() == AgentMessageType.ERROR) {
                saveAssistantMessage(
                        invocation.traceId(),
                        invocation.invocationId(),
                        invocation.taskId(),
                        invocation.agentId(),
                        message.content(),
                        ChatMessageStatus.FAILED);
            }
        }
    }

    private boolean hasStreamedInvocationMessages(String invocationId) {
        if (invocationMessageRepository != null
                && invocationMessageRepository.existsByInvocationId(invocationId)) {
            return true;
        }
        return chatMessageRepository != null
                && chatMessageRepository.findAssistantStreamByInvocationId(invocationId).isPresent();
    }

    private void saveAssistantMessage(
            String traceId,
            String invocationId,
            String taskId,
            String agentId,
            String content,
            ChatMessageStatus status) {
        if (chatThreadRepository == null || chatMessageRepository == null || content == null || content.isBlank()) {
            return;
        }
        chatThreadRepository.findByTraceId(traceId).ifPresent(thread -> {
            // 同一次 invocation 的所有流式分片合并到一行 chat_message，保留 messageId 让前端 upsertTailBy 原地更新。
            ChatMessage existing = chatMessageRepository
                    .findAssistantStreamByInvocationId(invocationId)
                    .orElse(null);
            Instant now = Instant.now();
            ChatMessage saved;
            if (existing == null) {
                saved = chatMessageRepository.save(new ChatMessage(
                        "message-" + UUID.randomUUID(),
                        thread.threadId(),
                        ChatMessageRole.ASSISTANT,
                        content,
                        status,
                        invocationId,
                        taskId,
                        agentId,
                        now,
                        now));
            } else {
                saved = chatMessageRepository.save(new ChatMessage(
                        existing.messageId(),
                        existing.threadId(),
                        existing.role(),
                        existing.content() + content,
                        status,
                        existing.invocationId(),
                        existing.taskId(),
                        existing.agentId(),
                        existing.createdAt(),
                        now));
            }
            publishChatMessage(saved);
        });
    }

    private void markAssistantStreamFinal(
            String invocationId,
            ChatMessageStatus finalStatus,
            String appendOnFailure) {
        if (chatMessageRepository == null) {
            return;
        }
        // 调用方已通过 closeCallbackStream 关闭入口并排空 buffer。
        ChatMessage existing = chatMessageRepository
                .findAssistantStreamByInvocationId(invocationId)
                .orElse(null);
        if (existing == null) {
            return;
        }
        if (existing.status() == ChatMessageStatus.CANCELED && finalStatus != ChatMessageStatus.CANCELED) {
            return;
        }
        String content = existing.content();
        if (finalStatus == ChatMessageStatus.FAILED && appendOnFailure != null && !appendOnFailure.isBlank()) {
            content = content + appendOnFailure;
        }
        ChatMessage saved = chatMessageRepository.save(new ChatMessage(
                existing.messageId(),
                existing.threadId(),
                existing.role(),
                content,
                finalStatus,
                existing.invocationId(),
                existing.taskId(),
                existing.agentId(),
                existing.createdAt(),
                Instant.now()));
        publishChatMessage(saved);
    }

    /**
     * runtime 最终响应里的 finalText 是本轮权威正文。流式 callback 只负责实时体验，可能因为网络
     * 抖动缺少分片；调用方先关闭并排空 buffer，再一次性校准正文、保存 COMPLETED 并广播。
     */
    private void completeAssistantStream(Invocation invocation, String finalText) {
        if (chatMessageRepository == null) {
            return;
        }
        ChatMessage existing = chatMessageRepository
                .findAssistantStreamByInvocationId(invocation.invocationId())
                .orElse(null);
        boolean hasFinalText = finalText != null && !finalText.isBlank();
        if (existing == null) {
            if (!hasFinalText || chatThreadRepository == null) {
                return;
            }
            chatThreadRepository.findByTraceId(invocation.traceId()).ifPresent(thread -> {
                Instant now = Instant.now();
                publishChatMessage(chatMessageRepository.save(new ChatMessage(
                        "message-" + UUID.randomUUID(),
                        thread.threadId(),
                        ChatMessageRole.ASSISTANT,
                        finalText,
                        ChatMessageStatus.COMPLETED,
                        invocation.invocationId(),
                        invocation.taskId(),
                        invocation.agentId(),
                        now,
                        now)));
            });
            return;
        }
        // 必须先检查保护状态，再校准正文，不能把 FAILED/CANCELED 重新打开为成功消息。
        if (existing.status() == ChatMessageStatus.FAILED || existing.status() == ChatMessageStatus.CANCELED) {
            return;
        }
        String content = hasFinalText ? finalText : existing.content();
        if (existing.status() == ChatMessageStatus.COMPLETED && content.equals(existing.content())) {
            return;
        }
        publishChatMessage(chatMessageRepository.save(new ChatMessage(
                existing.messageId(),
                existing.threadId(),
                existing.role(),
                content,
                ChatMessageStatus.COMPLETED,
                existing.invocationId(),
                existing.taskId(),
                existing.agentId(),
                existing.createdAt(),
                Instant.now())));
    }

    private void publishInvocationMessage(InvocationMessage message) {
        if (chatEventService == null || chatThreadRepository == null) {
            return;
        }
        chatThreadRepository.findByTraceId(message.traceId())
                .ifPresent(thread -> chatEventService.publish(
                        thread.threadId(),
                        RealtimeEventTypes.INVOCATION_MESSAGE,
                        message));
    }

    private void publishChatMessage(ChatMessage message) {
        if (chatEventService != null) {
            chatEventService.publish(message.threadId(), RealtimeEventTypes.CHAT_MESSAGE, message);
        }
    }

    private void publishTask(Task task) {
        if (taskEventService != null) {
            taskEventService.publish(task);
        }
    }

    private void blockDependentDescendants(Task failedTask) {
        if (taskDependencyRepository == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        List<String> pending = new java.util.ArrayList<>(
                taskDependencyRepository.findChildTaskIds(failedTask.taskId()));
        while (!pending.isEmpty()) {
            String childTaskId = pending.removeLast();
            if (!seen.add(childTaskId)) {
                continue;
            }
            taskRepository.findByTaskId(childTaskId).ifPresent(child -> {
                if (child.status() == TaskStatus.QUEUED) {
                    Task blockedTask = taskRepository.updateStatus(child.taskId(), TaskStatus.BLOCKED);
                    publishTask(blockedTask);
                }
                pending.addAll(taskDependencyRepository.findChildTaskIds(child.taskId()));
            });
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
