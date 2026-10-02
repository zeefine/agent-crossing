package com.agentcrossing.platform.domain.message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository {
    ChatMessage save(ChatMessage message);

    List<ChatMessage> findByThreadId(String threadId);

    /** Latest completed assistant message per nonblank agent, excluding the given agent.
     * Ordered by createdAt DESC, messageId ASC; nonpositive limits return no rows. */
    List<ChatMessage> findLatestAgentConclusions(String threadId, String excludedAgentId, int limit);

    List<ChatMessage> findVisibleMessagesAfterCursor(
            String threadId,
            String currentAgentId,
            Instant lastInjectedCreatedAt,
            String lastInjectedMessageId,
            int limit);

    Optional<ChatMessage> findAssistantStreamByInvocationId(String invocationId);

    void deleteByThreadId(String threadId);
}
