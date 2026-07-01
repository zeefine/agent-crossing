package com.agentcrossing.platform.domain.message;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryChatMessageRepository implements ChatMessageRepository {
    private final ConcurrentMap<String, ChatMessage> messages = new ConcurrentHashMap<>();

    @Override
    public ChatMessage save(ChatMessage message) {
        messages.put(message.messageId(), message);
        return message;
    }

    @Override
    public List<ChatMessage> findByThreadId(String threadId) {
        return messages.values().stream()
                .filter(message -> message.threadId().equals(threadId))
                .sorted(Comparator.comparing(ChatMessage::createdAt).thenComparing(ChatMessage::messageId))
                .toList();
    }

    @Override
    public List<ChatMessage> findVisibleMessagesAfterCursor(
            String threadId,
            String currentAgentId,
            Instant lastInjectedCreatedAt,
            String lastInjectedMessageId,
            int limit) {
        return messages.values().stream()
                .filter(message -> message.threadId().equals(threadId))
                .filter(message -> isVisibleToAgent(message, currentAgentId))
                .filter(message -> isAfterCursor(message, lastInjectedCreatedAt, lastInjectedMessageId))
                .sorted(Comparator.comparing(ChatMessage::createdAt).thenComparing(ChatMessage::messageId))
                .limit(Math.max(limit, 0))
                .toList();
    }

    @Override
    public Optional<ChatMessage> findAssistantStreamByInvocationId(String invocationId) {
        if (invocationId == null) {
            return Optional.empty();
        }
        return messages.values().stream()
                .filter(message -> message.role() == ChatMessageRole.ASSISTANT
                        && invocationId.equals(message.invocationId()))
                .min(Comparator.comparing(ChatMessage::createdAt).thenComparing(ChatMessage::messageId));
    }

    @Override
    public void deleteByThreadId(String threadId) {
        messages.values().removeIf(message -> message.threadId().equals(threadId));
    }

    private static boolean isVisibleToAgent(ChatMessage message, String currentAgentId) {
        if (message.role() == ChatMessageRole.USER) {
            return true;
        }
        return message.role() == ChatMessageRole.ASSISTANT
                && message.agentId() != null
                && !message.agentId().equals(currentAgentId);
    }

    private static boolean isAfterCursor(
            ChatMessage message,
            Instant lastInjectedCreatedAt,
            String lastInjectedMessageId) {
        if (lastInjectedCreatedAt == null || lastInjectedMessageId == null) {
            return true;
        }
        int timestampComparison = message.createdAt().compareTo(lastInjectedCreatedAt);
        return timestampComparison > 0
                || (timestampComparison == 0 && message.messageId().compareTo(lastInjectedMessageId) > 0);
    }
}
