package com.agentcrossing.platform.domain.message;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository {
    ChatMessage save(ChatMessage message);

    List<ChatMessage> findByThreadId(String threadId);

    /** Latest completed assistant message per nonblank agent, excluding the given agent.
     * Ordered by createdAt DESC, messageId ASC; nonpositive limits return no rows. */
    List<ChatMessage> findLatestAgentConclusions(String threadId, String excludedAgentId, int limit);

    /** Latest unacknowledged COMPLETED user/other-agent messages, returned in creation order.
     * Omitted rows remain eligible; timestamps and earlier content versions do not consume them. */
    List<ChatMessage> findUnacknowledgedVisibleMessages(
            String userId,
            String threadId,
            String currentAgentId,
            int limit);

    /** Acknowledge the supplied content snapshots, not the current rows; ignore deleted messages. */
    void acknowledgeContextMessages(String userId, String threadId, String agentId,
            List<ContextMessageReceipt> receipts);

    /** Versions included in the cumulative summary; independent of ordinary delivery receipts. */
    List<ContextMessageReceipt> findSummarizedContextMessages(String userId, String threadId, String agentId);

    /** Atomically replace coverage when no previous summary was inherited, otherwise extend it. */
    void acknowledgeSummarizedMessages(String userId, String threadId, String agentId,
            List<ContextMessageReceipt> receipts, boolean replacePreviousSummary);

    Optional<ChatMessage> findAssistantStreamByInvocationId(String invocationId);

    void deleteByThreadId(String threadId);
}
