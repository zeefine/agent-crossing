package com.agentcrossing.platform.application.invocation;

import com.agentcrossing.platform.domain.agent.AgentRegistry;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.context.AgentContextCursor;
import com.agentcrossing.platform.domain.context.AgentContextCursorRepository;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageRepository;
import com.agentcrossing.platform.domain.message.ChatMessageRole;
import com.agentcrossing.platform.domain.message.ChatMessageStatus;
import com.agentcrossing.platform.domain.session.AgentSessionHistory;
import com.agentcrossing.platform.domain.session.AgentSessionHistoryRepository;
import com.agentcrossing.platform.domain.task.Task;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AgentContextService {
    private static final int DEFAULT_INCREMENTAL_MESSAGE_LIMIT = 20;

    private final ChatThreadRepository chatThreadRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AgentContextCursorRepository agentContextCursorRepository;
    private final AgentRegistry agentRegistry;
    private final AgentSessionHistoryRepository agentSessionHistoryRepository;

    @Autowired
    public AgentContextService(
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            AgentContextCursorRepository agentContextCursorRepository,
            AgentRegistry agentRegistry,
            AgentSessionHistoryRepository agentSessionHistoryRepository) {
        this.chatThreadRepository = chatThreadRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.agentContextCursorRepository = agentContextCursorRepository;
        this.agentRegistry = agentRegistry;
        this.agentSessionHistoryRepository = agentSessionHistoryRepository;
    }

    public AgentContextService(
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            AgentContextCursorRepository agentContextCursorRepository) {
        this(chatThreadRepository, chatMessageRepository, agentContextCursorRepository, null, null);
    }

    public AgentContextService(
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            AgentContextCursorRepository agentContextCursorRepository,
            AgentRegistry agentRegistry) {
        this(chatThreadRepository, chatMessageRepository, agentContextCursorRepository, agentRegistry, null);
    }

    public AgentContextPack buildContextPack(Task task) {
        List<AvailableAgentContext> availableAgents = agentRegistry == null
                ? List.of()
                : agentRegistry.findAll().stream().map(AvailableAgentContext::from).toList();
        AgentSessionHistory pending = findPendingHistory(task);
        List<IncrementalChatMessage> incrementalMessages = chatThreadRepository.findByTraceId(task.traceId())
                .map(thread -> {
                    if (pending != null) {
                        return retainedTail(thread.threadId(), pending);
                    }
                    AgentContextCursor cursor = agentContextCursorRepository
                            .find(task.userId(), thread.threadId(), task.agentId())
                            .orElse(null);
                    List<ChatMessage> visibleMessages = chatMessageRepository.findVisibleMessagesAfterCursor(
                            thread.threadId(),
                            task.agentId(),
                            cursor == null ? null : cursor.lastInjectedCreatedAt(),
                            cursor == null ? null : cursor.lastInjectedMessageId(),
                            DEFAULT_INCREMENTAL_MESSAGE_LIMIT);
                    return visibleMessages.stream()
                            .map(this::toIncrementalChatMessage)
                            .toList();
                })
                .orElseGet(List::of);
        return new AgentContextPack(
                incrementalMessages, availableAgents, pending == null ? null : pending.startupSummary());
    }

    public void acknowledgeInjectedMessages(Task task, AgentContextPack contextPack) {
        List<IncrementalChatMessage> messages = contextPack.incrementalChatMessages();
        if (messages.isEmpty()) {
            return;
        }
        IncrementalChatMessage last = messages.getLast();
        chatThreadRepository.findByTraceId(task.traceId()).ifPresent(thread -> agentContextCursorRepository.save(
                new AgentContextCursor(
                        task.userId(),
                        thread.threadId(),
                        task.agentId(),
                        Instant.parse(last.createdAt()),
                        last.messageId(),
                        Instant.now())));
    }

    private IncrementalChatMessage toIncrementalChatMessage(ChatMessage message) {
        return new IncrementalChatMessage(
                message.messageId(),
                message.role().wireValue(),
                message.agentId(),
                message.taskId(),
                message.content(),
                message.createdAt().toString());
    }

    private AgentSessionHistory findPendingHistory(Task task) {
        if (agentSessionHistoryRepository == null) {
            return null;
        }
        return chatThreadRepository.findByTraceId(task.traceId())
                .flatMap(thread -> agentSessionHistoryRepository.findCreating(
                        task.userId(), thread.threadId(), task.agentId(), task.agentId()))
                .orElse(null);
    }

    private List<IncrementalChatMessage> retainedTail(String threadId, AgentSessionHistory pending) {
        List<ChatMessage> messages = chatMessageRepository.findByThreadId(threadId);
        int start = 0;
        if (pending.keepTailFromMessageId() != null) {
            for (int index = 0; index < messages.size(); index++) {
                if (messages.get(index).messageId().equals(pending.keepTailFromMessageId())) {
                    start = index;
                    break;
                }
            }
        }
        return messages.subList(start, messages.size()).stream()
                .filter(message -> message.status() == ChatMessageStatus.COMPLETED)
                .filter(message -> message.role() == ChatMessageRole.USER || message.role() == ChatMessageRole.ASSISTANT)
                .map(this::toIncrementalChatMessage)
                .toList();
    }
}
