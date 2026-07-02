package com.agentcrossing.platform.domain.message;

import java.util.List;

public interface InvocationMessageRepository {
    InvocationMessage save(InvocationMessage message);

    List<InvocationMessage> findByInvocationId(String invocationId);

    List<InvocationMessage> findByTraceId(String traceId);

    List<InvocationMessage> findByTraceIdAndUserId(String traceId, String userId);

    void deleteByTraceIdAndUserId(String traceId, String userId);
}
