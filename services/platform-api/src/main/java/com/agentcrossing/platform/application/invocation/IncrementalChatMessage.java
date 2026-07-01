package com.agentcrossing.platform.application.invocation;

public record IncrementalChatMessage(
        String messageId,
        String role,
        String agentId,
        String taskId,
        String content,
        String createdAt) {}
