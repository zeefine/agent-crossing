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
    private final ConcurrentMap<String, String> messageIdByInvocationSequence = new ConcurrentHashMap<>();

    @Override
    public InvocationMessage save(InvocationMessage message) {
        messages.put(message.messageId(), message);
        if (message.sequence() != null) {
            messageIdByInvocationSequence.put(sequenceKey(message.invocationId(), message.sequence()), message.messageId());
        }
        return message;
    }

    @Override
    public boolean saveIfAbsent(InvocationMessage message) {
        String sequenceKey = message.sequence() == null
                ? null
                : sequenceKey(message.invocationId(), message.sequence());
        if (sequenceKey != null
                && messageIdByInvocationSequence.putIfAbsent(sequenceKey, message.messageId()) != null) {
            return false;
        }
        if (messages.putIfAbsent(message.messageId(), message) != null) {
            if (sequenceKey != null) {
                messageIdByInvocationSequence.remove(sequenceKey, message.messageId());
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean existsByInvocationId(String invocationId) {
        return messages.values().stream()
                .anyMatch(message -> message.invocationId().equals(invocationId));
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

    @Override
    public void deleteByTraceIdAndUserId(String traceId, String userId) {
        messages.values().removeIf(message -> {
            boolean matches = message.traceId().equals(traceId) && message.userId().equals(userId);
            if (matches && message.sequence() != null) {
                messageIdByInvocationSequence.remove(
                        sequenceKey(message.invocationId(), message.sequence()), message.messageId());
            }
            return matches;
        });
    }

    private static String sequenceKey(String invocationId, long sequence) {
        return invocationId + "\u0000" + sequence;
    }
}
