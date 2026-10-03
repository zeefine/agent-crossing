package com.agentcrossing.platform.domain.message;

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
    private final ConcurrentMap<ReceiptKey, String> receipts = new ConcurrentHashMap<>();
    private final ConcurrentMap<ReceiptKey, String> summarizedVersions = new ConcurrentHashMap<>();

    private record ReceiptKey(String userId, String threadId, String agentId, String messageId) {}

    @Override
    public synchronized ChatMessage save(ChatMessage message) {
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
    public List<ChatMessage> findLatestAgentConclusions(String threadId, String excludedAgentId, int limit) {
        var latest = new java.util.LinkedHashMap<String, ChatMessage>();
        messages.values().stream()
                .filter(message -> message.threadId().equals(threadId))
                .filter(message -> message.role() == ChatMessageRole.ASSISTANT
                        && message.status() == ChatMessageStatus.COMPLETED)
                .filter(message -> message.agentId() != null && !message.agentId().isBlank()
                        && !message.agentId().equals(excludedAgentId))
                .sorted(Comparator.comparing(ChatMessage::createdAt).reversed().thenComparing(ChatMessage::messageId))
                .forEach(message -> latest.putIfAbsent(message.agentId(), message));
        return latest.values().stream().limit(Math.max(0, limit)).toList();
    }

    @Override
    public synchronized List<ChatMessage> findUnacknowledgedVisibleMessages(
            String userId,
            String threadId,
            String currentAgentId,
            int limit) {
        Comparator<ChatMessage> chronological =
                Comparator.comparing(ChatMessage::createdAt).thenComparing(ChatMessage::messageId);
        // 先截取最新消息，再恢复时间正序，避免 Prompt 中的对话顺序倒置。
        return messages.values().stream()
                .filter(message -> message.threadId().equals(threadId))
                .filter(message -> message.status() == ChatMessageStatus.COMPLETED)
                .filter(message -> isVisibleToAgent(message, currentAgentId))
                .filter(message -> !ContextMessageReceipt.of(message.messageId(), message.content()).contentVersion()
                        .equals(receipts.get(new ReceiptKey(userId, threadId, currentAgentId, message.messageId()))))
                .sorted(chronological.reversed())
                .limit(Math.max(limit, 0))
                .sorted(chronological)
                .toList();
    }

    @Override
    public synchronized void acknowledgeContextMessages(String userId, String threadId, String agentId,
            List<ContextMessageReceipt> delivered) {
        acknowledge(userId, threadId, agentId, delivered, false);
    }

    @Override
    public synchronized void acknowledgeSummarizedMessages(String userId, String threadId, String agentId,
            List<ContextMessageReceipt> summarized, boolean replacePreviousSummary) {
        if (replacePreviousSummary) {
            summarizedVersions.keySet().removeIf(key -> key.userId().equals(userId)
                    && key.threadId().equals(threadId) && key.agentId().equals(agentId));
        }
        acknowledge(userId, threadId, agentId, summarized, true);
    }

    @Override
    public synchronized List<ContextMessageReceipt> findSummarizedContextMessages(
            String userId, String threadId, String agentId) {
        return summarizedVersions.entrySet().stream()
                .filter(entry -> entry.getKey().userId().equals(userId)
                        && entry.getKey().threadId().equals(threadId) && entry.getKey().agentId().equals(agentId))
                .map(entry -> new ContextMessageReceipt(entry.getKey().messageId(), entry.getValue())).toList();
    }

    private void acknowledge(String userId, String threadId, String agentId,
            List<ContextMessageReceipt> delivered, boolean summarized) {
        for (ContextMessageReceipt receipt : delivered) {
            ChatMessage message = messages.get(receipt.messageId());
            if (message != null && message.threadId().equals(threadId)) {
                ReceiptKey key = new ReceiptKey(userId, threadId, agentId, receipt.messageId());
                receipts.put(key, receipt.contentVersion());
                if (summarized) {
                    summarizedVersions.put(key, receipt.contentVersion());
                }
            }
        }
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
    public synchronized void deleteByThreadId(String threadId) {
        messages.values().removeIf(message -> message.threadId().equals(threadId));
        receipts.keySet().removeIf(key -> key.threadId().equals(threadId));
        summarizedVersions.keySet().removeIf(key -> key.threadId().equals(threadId));
    }

    private static boolean isVisibleToAgent(ChatMessage message, String currentAgentId) {
        if (message.role() == ChatMessageRole.USER) {
            return true;
        }
        return message.role() == ChatMessageRole.ASSISTANT
                && message.agentId() != null
                && !message.agentId().equals(currentAgentId);
    }

}
