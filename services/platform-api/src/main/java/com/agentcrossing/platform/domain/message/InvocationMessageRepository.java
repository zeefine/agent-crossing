package com.agentcrossing.platform.domain.message;

import java.util.List;

public interface InvocationMessageRepository {
    InvocationMessage save(InvocationMessage message);

    boolean saveIfAbsent(InvocationMessage message);

    /** Checks for any event without loading message bodies or raw payloads. */
    boolean existsByInvocationId(String invocationId);

    List<InvocationMessage> findByInvocationId(String invocationId);

    List<InvocationMessage> findByTraceId(String traceId);

    List<InvocationMessage> findByTraceIdAndUserId(String traceId, String userId);

    void deleteByTraceIdAndUserId(String traceId, String userId);
}
