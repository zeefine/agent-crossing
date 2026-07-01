package com.agentcrossing.platform.api.dto;

import com.agentcrossing.platform.domain.chat.ChatThread;

public record ChatThreadResponse(
        String threadId,
        String userId,
        String title,
        String status,
        String traceId,
        String createdAt,
        String updatedAt) {
    public static ChatThreadResponse from(ChatThread thread) {
        return new ChatThreadResponse(
                thread.threadId(),
                thread.userId(),
                thread.title(),
                thread.status().wireValue(),
                thread.traceId(),
                thread.createdAt().toString(),
                thread.updatedAt().toString());
    }
}
