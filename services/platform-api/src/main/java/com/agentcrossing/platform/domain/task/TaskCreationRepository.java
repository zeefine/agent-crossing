package com.agentcrossing.platform.domain.task;

import java.util.Optional;

/**
 * Coordinates idempotent append requests across concurrent platform instances.
 */
public interface TaskCreationRepository {
    Optional<TaskCreation> findBySourceTaskIdAndClientTaskId(String sourceTaskId, String clientTaskId);

    /**
     * Claims the source/client pair. Returns {@code true} only for the request that created it.
     */
    boolean saveIfAbsent(TaskCreation taskCreation);

    void deleteByTraceIdAndUserId(String traceId, String userId);
}
