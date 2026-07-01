package com.agentcrossing.platform.api.dto;

import com.agentcrossing.platform.domain.message.ChatMessage;

/**
 * MODEL_SYNC(ChatMessage) — 改字段时四处同步（无 codegen）：
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/domain/message/ChatMessage.java
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/api/dto/ChatMessageResponse.java  ← 本文件
 *   · services/platform-web/lib/api.ts (export type ChatMessage)
 *   · services/platform-api/src/main/resources/schema-mysql.sql (chat_message)
 *     + services/platform-api/src/main/resources/mapper/ChatMessageMapper.xml
 */
public record ChatMessageResponse(
        String messageId,
        String threadId,
        String role,
        String content,
        String status,
        String invocationId,
        String taskId,
        String agentId,
        String createdAt,
        String updatedAt) {
    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(
                message.messageId(),
                message.threadId(),
                message.role().wireValue(),
                message.content(),
                message.status().wireValue(),
                message.invocationId(),
                message.taskId(),
                message.agentId(),
                message.createdAt().toString(),
                message.updatedAt().toString());
    }
}
