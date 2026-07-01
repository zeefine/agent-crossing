package com.agentcrossing.platform.domain.chat;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryChatThreadRepository implements ChatThreadRepository {
    private final ConcurrentMap<String, ChatThread> threads = new ConcurrentHashMap<>();

    @Override
    public ChatThread save(ChatThread thread) {
        threads.put(thread.threadId(), thread);
        return thread;
    }

    @Override
    public Optional<ChatThread> findByThreadId(String threadId) {
        return Optional.ofNullable(threads.get(threadId));
    }

    @Override
    public Optional<ChatThread> findByThreadIdAndUserId(String threadId, String userId) {
        return findByThreadId(threadId).filter(thread -> thread.userId().equals(userId));
    }

    @Override
    public Optional<ChatThread> findByTraceId(String traceId) {
        return threads.values().stream().filter(thread -> thread.traceId().equals(traceId)).findFirst();
    }

    @Override
    public ChatThread updateStatus(String threadId, ChatThreadStatus status) {
        return threads.compute(threadId, (ignored, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Chat thread not found: " + threadId);
            }
            return existing.withStatus(status);
        });
    }

    @Override
    public ChatThread updateTitle(String threadId, String title) {
        return threads.compute(threadId, (ignored, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Chat thread not found: " + threadId);
            }
            return existing.withTitle(title);
        });
    }

    @Override
    public void deleteByThreadId(String threadId) {
        threads.remove(threadId);
    }

    @Override
    public List<ChatThread> findAll() {
        return threads.values().stream()
                .sorted(Comparator.comparing(ChatThread::updatedAt).reversed().thenComparing(ChatThread::threadId))
                .toList();
    }

    @Override
    public List<ChatThread> findAllByUserId(String userId) {
        return threads.values().stream()
                .filter(thread -> thread.userId().equals(userId))
                .sorted(Comparator.comparing(ChatThread::updatedAt).reversed().thenComparing(ChatThread::threadId))
                .toList();
    }
}
