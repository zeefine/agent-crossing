package com.agentcrossing.platform.domain.message;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryInvocationMessageRepository implements InvocationMessageRepository {
    private final ConcurrentMap<String, InvocationMessage> messages = new ConcurrentHashMap<>();

    @Override
    public InvocationMessage save(InvocationMessage message) {
        messages.put(message.messageId(), message);
        return message;
    }

    @Override
    public List<InvocationMessage> findByInvocationId(String invocationId) {
        return messages.values().stream()
                .filter(message -> message.invocationId().equals(invocationId))
                .sorted(Comparator.comparing(InvocationMessage::createdAt).thenComparing(InvocationMessage::messageId))
                .toList();
    }

    @Override
    public List<InvocationMessage> findByTraceId(String traceId) {
        return messages.values().stream()
                .filter(message -> message.traceId().equals(traceId))
                .sorted(Comparator.comparing(InvocationMessage::createdAt).thenComparing(InvocationMessage::messageId))
                .toList();
    }

    @Override
    public List<InvocationMessage> findByTraceIdAndUserId(String traceId, String userId) {
        return messages.values().stream()
                .filter(message -> message.traceId().equals(traceId) && message.userId().equals(userId))
                .sorted(Comparator.comparing(InvocationMessage::createdAt).thenComparing(InvocationMessage::messageId))
                .toList();
    }
}
