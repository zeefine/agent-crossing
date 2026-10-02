package com.agentcrossing.platform.domain.session;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryAgentSessionHistoryRepository implements AgentSessionHistoryRepository {
    private final ConcurrentMap<String, AgentSessionHistory> histories = new ConcurrentHashMap<>();

    @Override
    public synchronized AgentSessionHistory save(AgentSessionHistory history) {
        histories.values().stream()
                .filter(existing -> !existing.sessionRecordId().equals(history.sessionRecordId()))
                .filter(existing -> sameProviderSession(existing, history) || sameGeneration(existing, history))
                .findFirst()
                .ifPresent(existing -> {
                    throw new IllegalArgumentException(
                            "Agent session history conflicts with existing record: " + existing.sessionRecordId());
                });
        histories.put(history.sessionRecordId(), history);
        return history;
    }

    @Override
    public Optional<AgentSessionHistory> findBySessionRecordId(String sessionRecordId) {
        return Optional.ofNullable(histories.get(sessionRecordId));
    }

    @Override
    public Optional<AgentSessionHistory> findByProviderSessionId(String provider, String providerSessionId) {
        return histories.values().stream()
                .filter(history -> history.provider().equals(provider))
                .filter(history -> history.providerSessionId() != null)
                .filter(history -> history.providerSessionId().equals(providerSessionId))
                .findFirst();
    }

    @Override
    public Optional<AgentSessionHistory> findByGeneration(
            String userId, String threadId, String agentId, String provider, int generation) {
        return histories.values().stream()
                .filter(history -> sameSession(history, userId, threadId, agentId, provider))
                .filter(history -> history.generation() == generation)
                .findFirst();
    }

    @Override
    public Optional<AgentSessionHistory> findActive(String userId, String threadId, String agentId, String provider) {
        return histories.values().stream()
                .filter(history -> sameSession(history, userId, threadId, agentId, provider))
                .filter(history -> history.status() == AgentSessionHistoryStatus.ACTIVE)
                .max(Comparator.comparingInt(AgentSessionHistory::generation));
    }

    @Override
    public Optional<AgentSessionHistory> findCreating(String userId, String threadId, String agentId, String provider) {
        return histories.values().stream()
                .filter(history -> sameSession(history, userId, threadId, agentId, provider))
                .filter(history -> history.status() == AgentSessionHistoryStatus.CREATING)
                .max(Comparator.comparingInt(AgentSessionHistory::generation));
    }

    @Override
    public Optional<AgentSessionHistory> findCompacting(String userId, String threadId, String agentId, String provider) {
        return histories.values().stream()
                .filter(history -> sameSession(history, userId, threadId, agentId, provider))
                .filter(history -> history.status() == AgentSessionHistoryStatus.COMPACTING)
                .max(Comparator.comparingInt(AgentSessionHistory::generation));
    }

    @Override
    public synchronized int restoreInterruptedCompactions() {
        List<AgentSessionHistory> interrupted = histories.values().stream()
                .filter(history -> history.status() == AgentSessionHistoryStatus.COMPACTING)
                .toList();
        interrupted.forEach(history -> histories.put(history.sessionRecordId(),
                history.withStatus(AgentSessionHistoryStatus.ACTIVE)));
        return interrupted.size();
    }

    @Override
    public List<AgentSessionHistory> findByThreadId(
            String userId, String threadId, String agentId, String provider) {
        return histories.values().stream()
                .filter(history -> sameSession(history, userId, threadId, agentId, provider))
                .sorted(Comparator.comparingInt(AgentSessionHistory::generation))
                .toList();
    }

    @Override
    public void deleteByThreadId(String userId, String threadId) {
        histories.values().removeIf(
                history -> history.userId().equals(userId) && history.threadId().equals(threadId));
    }

    private static boolean sameProviderSession(AgentSessionHistory left, AgentSessionHistory right) {
        return left.providerSessionId() != null
                && right.providerSessionId() != null
                && left.provider().equals(right.provider())
                && left.providerSessionId().equals(right.providerSessionId());
    }

    private static boolean sameGeneration(AgentSessionHistory left, AgentSessionHistory right) {
        return sameSession(left, right.userId(), right.threadId(), right.agentId(), right.provider())
                && left.generation() == right.generation();
    }

    private static boolean sameSession(
            AgentSessionHistory history, String userId, String threadId, String agentId, String provider) {
        return history.userId().equals(userId)
                && history.threadId().equals(threadId)
                && history.agentId().equals(agentId)
                && history.provider().equals(provider);
    }
}
