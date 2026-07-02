package com.agentcrossing.platform.domain.invocation;

import java.util.List;
import java.util.Optional;

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

    void deleteByTraceIdAndUserId(String traceId, String userId);

    List<Invocation> findAll();
}
