package com.agentcrossing.platform.domain.task;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryTaskCreationRepository implements TaskCreationRepository {
    private final ConcurrentMap<String, TaskCreation> creations = new ConcurrentHashMap<>();

    @Override
    public Optional<TaskCreation> findBySourceTaskIdAndClientTaskId(String sourceTaskId, String clientTaskId) {
        return Optional.ofNullable(creations.get(key(sourceTaskId, clientTaskId)));
    }

    @Override
    public boolean saveIfAbsent(TaskCreation taskCreation) {
        return creations.putIfAbsent(key(taskCreation.sourceTaskId(), taskCreation.clientTaskId()), taskCreation) == null;
    }

    @Override
    public void deleteByTraceIdAndUserId(String traceId, String userId) {
        creations.values().removeIf(creation -> creation.traceId().equals(traceId)
                && creation.userId().equals(userId));
    }

    private static String key(String sourceTaskId, String clientTaskId) {
        return sourceTaskId + "\n" + clientTaskId;
    }
}
