package com.agentcrossing.platform.api.dto;

import com.agentcrossing.platform.domain.message.InvocationMessage;

public record InvocationMessageResponse(
        String messageId,
        String userId,
        String invocationId,
        String taskId,
        String traceId,
        String agentId,
        String type,
        String content,
        Object raw,
        String createdAt) {
    public static InvocationMessageResponse from(InvocationMessage message) {
        return new InvocationMessageResponse(
                message.messageId(),
                message.userId(),
                message.invocationId(),
                message.taskId(),
                message.traceId(),
                message.agentId(),
                message.type().wireValue(),
                message.content(),
                message.raw(),
                message.createdAt().toString());
    }
}
