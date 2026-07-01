package com.agentcrossing.platform.domain.message;

import java.time.Instant;
import java.util.Objects;

/**
 * MODEL_SYNC(ChatMessage) — 改字段时四处同步（无 codegen）：
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/domain/message/ChatMessage.java  ← 本文件
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/api/dto/ChatMessageResponse.java
 *   · services/platform-web/lib/api.ts (export type ChatMessage)
 *   · services/platform-api/src/main/resources/schema-mysql.sql (chat_message)
 *     + services/platform-api/src/main/resources/mapper/ChatMessageMapper.xml
 */
public record ChatMessage(
        String messageId,
        String threadId,
        ChatMessageRole role,
        String content,
        ChatMessageStatus status,
        String invocationId,
        String taskId,
        String agentId,
        Instant createdAt,
        Instant updatedAt) {
    public ChatMessage {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(threadId, "threadId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
