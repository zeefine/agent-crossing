package com.agentcrossing.platform.domain.invocation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface InvocationRepository {
    Invocation save(Invocation invocation);

    Optional<Invocation> findByInvocationId(String invocationId);

    Optional<Invocation> findByInvocationIdAndUserId(String invocationId, String userId);

    List<Invocation> findByTaskId(String taskId);

    List<Invocation> findByTaskIdAndUserId(String taskId, String userId);

    List<Invocation> findByTraceIdAndUserId(String traceId, String userId);

    List<Invocation> findRunningByAgentId(String agentId);

    List<Invocation> findRunningByAgentIdAndUserId(String agentId, String userId);

    Invocation updateStatus(String invocationId, InvocationStatus status);

    /** Atomic comparison; false means missing row or a status outside expected. Never inserts. */
    boolean updateStatusIfCurrent(String invocationId, Set<InvocationStatus> expected, InvocationStatus status);

    void deleteByTraceIdAndUserId(String traceId, String userId);

    List<Invocation> findAll();
}
