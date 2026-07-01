package com.agentcrossing.platform.application.invocation;

import com.agentcrossing.platform.application.chat.AssistantStreamBuffer;
import com.agentcrossing.platform.application.chat.ChatEventService;
import com.agentcrossing.platform.application.parser.QuestParserService;
import com.agentcrossing.platform.application.realtime.RealtimeEventTypes;
import com.agentcrossing.platform.application.routing.TaskDispatchSignal;
import com.agentcrossing.platform.application.task.TaskEventService;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
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
import org.springframework.stereotype.Service;

@Service
public class InvocationService {
    private final InvocationRepository invocationRepository;
    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final AgentRuntimeClient agentRuntimeClient;
    private final QuestParserService questParserService;
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

    public InvocationService(
            InvocationRepository invocationRepository,
            TaskRepository taskRepository,
            AgentRuntimeClient agentRuntimeClient,
            QuestParserService questParserService,
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
            AgentSessionRepository agentSessionRepository) {
        this.invocationRepository = invocationRepository;
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.agentRuntimeClient = agentRuntimeClient;
        this.questParserService = questParserService;
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
    }

    public Invocation execute(Task task) {
        // invocation 表示“某个 task 的一次 agent 执行”，重试时会创建新的 invocation。
        Invocation invocation = createInvocation(task);
        // task.status 已经由 QuestRouterService.reserveForDispatch 置成 PROCESSING（并发了 task 事件）。
        // 这里不再重复 UPDATE，避免每次 invocation 多一次 task UPDATE + 一次 realtime_event INSERT。
        invocationRepository.updateStatus(invocation.invocationId(), InvocationStatus.RUNNING);

        try {
            AgentContextPack contextPack = buildContextPack(task);
            AgentExecutionResult result = agentRuntimeClient.execute(new AgentExecutionRequest(
                    invocation.invocationId(),
                    invocation.userId(),
                    task.taskId(),
                    task.traceId(),
                    task.agentId(),
                    task.context(),
                    callbackBaseUrl,
                    contextPack,
                    findProviderSessionId(task)));
            rememberProviderSession(task, result);
            persistAgentMessages(invocation, result);
            if (result.hasError()) {
                throw new RuntimeException(result.errorOutput());
            }
            invocation = invocationRepository.updateStatus(invocation.invocationId(), InvocationStatus.SUCCEEDED);
            Task completedTask = taskRepository.updateStatus(task.taskId(), TaskStatus.COMPLETED);
            markAssistantStreamFinal(invocation.invocationId(), ChatMessageStatus.COMPLETED, null);
            publishTask(completedTask);
            // 先完成原 task，再解析 agent 输出生成后续任务，避免解析失败影响本次执行状态。
            List<Task> generatedTasks = questParserService.parseAgentOutputAndEnqueue(completedTask, result.aggregateOutput());
            updateThreadStatus(
                    completedTask.traceId(),
                    generatedTasks.isEmpty() ? ChatThreadStatus.COMPLETED : ChatThreadStatus.RUNNING);
            acknowledgeInjectedContext(task, contextPack);
            return invocation;
        } catch (RuntimeException exception) {
            invocation = invocationRepository.updateStatus(invocation.invocationId(), InvocationStatus.FAILED);
            Task failedTask = taskRepository.updateStatus(task.taskId(), TaskStatus.FAILED);
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
                        failedTask.traceId(),
                        invocation.invocationId(),
                        failedTask.taskId(),
                        failedTask.agentId(),
                        errorMessage,
                        ChatMessageStatus.FAILED);
            }
            updateThreadStatus(failedTask.traceId(), ChatThreadStatus.FAILED);
            blockDependentDescendants(failedTask);
            return invocation;
        } finally {
            taskDispatchSignal.signal();
        }
    }

    private Invocation createInvocation(Task task) {
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
        return invocationRepository.save(invocation);
    }

    private AgentContextPack buildContextPack(Task task) {
        return agentContextService == null ? new AgentContextPack(List.of()) : agentContextService.buildContextPack(task);
    }

    private void acknowledgeInjectedContext(Task task, AgentContextPack contextPack) {
        if (agentContextService != null) {
            agentContextService.acknowledgeInjectedMessages(task, contextPack);
        }
    }

    private String findProviderSessionId(Task task) {
        if (agentSessionRepository == null) {
            return null;
        }
        String threadId = findThreadId(task);
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        return agentSessionRepository
                .findByThreadId(task.userId(), threadId, task.agentId(), providerFor(task))
                .map(AgentSession::providerSessionId)
                .orElse(null);
    }

    private void rememberProviderSession(Task task, AgentExecutionResult result) {
        if (agentSessionRepository == null) {
            return;
        }
        String providerSessionId = result.providerSessionId();
        if (providerSessionId == null || providerSessionId.isBlank()) {
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
        agentSessionRepository.save(new AgentSession(
                task.userId(),
                threadId,
                task.traceId(),
                task.agentId(),
                provider,
                providerSessionId,
                createdAt,
                now));
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

    private void persistAgentMessages(Invocation invocation, AgentExecutionResult result) {
        boolean hasStreamedMessages = invocationMessageRepository != null
                && !invocationMessageRepository.findByInvocationId(invocation.invocationId()).isEmpty();
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
        // 终态读 DB 之前先 drain：把内存 buffer 里没 flush 的尾巴强制写回，
        // 确保 findAssistantStreamByInvocationId 看到的是完整 content。
        if (assistantStreamBuffer != null) {
            assistantStreamBuffer.drain(invocationId);
        }
        ChatMessage existing = chatMessageRepository
                .findAssistantStreamByInvocationId(invocationId)
                .orElse(null);
        if (existing == null) {
            return;
        }
        // 流式过程中已经有 ERROR 事件把行标记成 FAILED 时，不要被外层 try-block 的 COMPLETED 覆盖。
        if (existing.status() == ChatMessageStatus.FAILED && finalStatus == ChatMessageStatus.COMPLETED) {
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

    private void updateThreadStatus(String traceId, ChatThreadStatus status) {
        if (chatThreadRepository == null) {
            return;
        }
        chatThreadRepository
                .findByTraceId(traceId)
                .ifPresent(thread -> {
                    var updated = chatThreadRepository.updateStatus(thread.threadId(), status);
                    publishThread(updated);
                });
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

    private void publishThread(com.agentcrossing.platform.domain.chat.ChatThread thread) {
        if (chatEventService != null) {
            chatEventService.publish(thread.threadId(), RealtimeEventTypes.THREAD, thread);
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
}
