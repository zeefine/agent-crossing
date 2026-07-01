package com.agentcrossing.platform.domain.invocation;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryInvocationRepository implements InvocationRepository {
    private final ConcurrentMap<String, Invocation> invocations = new ConcurrentHashMap<>();

    @Override
    public Invocation save(Invocation invocation) {
        invocations.put(invocation.invocationId(), invocation);
        return invocation;
    }

    @Override
    public Optional<Invocation> findByInvocationId(String invocationId) {
        return Optional.ofNullable(invocations.get(invocationId));
    }

    @Override
    public Optional<Invocation> findByInvocationIdAndUserId(String invocationId, String userId) {
        return findByInvocationId(invocationId).filter(invocation -> invocation.userId().equals(userId));
    }

    @Override
    public List<Invocation> findByTaskId(String taskId) {
        return invocations.values().stream()
                .filter(invocation -> invocation.taskId().equals(taskId))
                .sorted(Comparator.comparing(Invocation::createdAt).thenComparing(Invocation::invocationId))
                .toList();
    }

    @Override
    public List<Invocation> findByTaskIdAndUserId(String taskId, String userId) {
        return invocations.values().stream()
                .filter(invocation -> invocation.taskId().equals(taskId) && invocation.userId().equals(userId))
                .sorted(Comparator.comparing(Invocation::createdAt).thenComparing(Invocation::invocationId))
                .toList();
    }

    @Override
    public List<Invocation> findRunningByAgentId(String agentId) {
        return invocations.values().stream()
                .filter(invocation -> invocation.agentId().equals(agentId))
                .filter(invocation -> invocation.status() == InvocationStatus.RUNNING)
                .sorted(Comparator.comparing(Invocation::createdAt).thenComparing(Invocation::invocationId))
                .toList();
    }

    @Override
    public List<Invocation> findRunningByAgentIdAndUserId(String agentId, String userId) {
        return invocations.values().stream()
                .filter(invocation -> invocation.agentId().equals(agentId))
                .filter(invocation -> invocation.userId().equals(userId))
                .filter(invocation -> invocation.status() == InvocationStatus.RUNNING)
                .sorted(Comparator.comparing(Invocation::createdAt).thenComparing(Invocation::invocationId))
                .toList();
    }

    @Override
    public Invocation updateStatus(String invocationId, InvocationStatus status) {
        return invocations.compute(invocationId, (ignored, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Invocation not found: " + invocationId);
            }
            return existing.withStatus(status);
        });
    }

    @Override
    public List<Invocation> findAll() {
        return invocations.values().stream()
                .sorted(Comparator.comparing(Invocation::createdAt).thenComparing(Invocation::invocationId))
                .toList();
    }
}
