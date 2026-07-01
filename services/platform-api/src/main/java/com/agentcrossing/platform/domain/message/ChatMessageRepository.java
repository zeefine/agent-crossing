package com.agentcrossing.platform.domain.message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository {
    ChatMessage save(ChatMessage message);

    List<ChatMessage> findByThreadId(String threadId);

    List<ChatMessage> findVisibleMessagesAfterCursor(
            String threadId,
            String currentAgentId,
            Instant lastInjectedCreatedAt,
            String lastInjectedMessageId,
            int limit);

    Optional<ChatMessage> findAssistantStreamByInvocationId(String invocationId);

    void deleteByThreadId(String threadId);
}
