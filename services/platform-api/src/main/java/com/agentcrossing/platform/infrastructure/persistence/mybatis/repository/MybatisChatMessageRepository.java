package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageRepository;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.ChatMessageMapper;
import com.agentcrossing.platform.domain.message.ContextMessageReceipt;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "mysql")
public class MybatisChatMessageRepository implements ChatMessageRepository {
    private final ChatMessageMapper chatMessageMapper;

    public MybatisChatMessageRepository(ChatMessageMapper chatMessageMapper) {
        this.chatMessageMapper = chatMessageMapper;
    }

    @Override
    public ChatMessage save(ChatMessage message) {
        chatMessageMapper.upsert(message);
        return message;
    }

    @Override
    public List<ChatMessage> findByThreadId(String threadId) {
        return chatMessageMapper.findByThreadId(threadId);
    }

    @Override
    public List<ChatMessage> findLatestAgentConclusions(String threadId, String excludedAgentId, int limit) {
        return limit <= 0 ? List.of() : chatMessageMapper.findLatestAgentConclusions(threadId, excludedAgentId, limit);
    }

    @Override
    public List<ChatMessage> findUnacknowledgedVisibleMessages(
            String userId,
            String threadId,
            String currentAgentId,
            int limit) {
        return limit <= 0 ? List.of() : chatMessageMapper.findUnacknowledgedVisibleMessages(
                userId,
                threadId,
                currentAgentId,
                limit);
    }

    @Override
    public void acknowledgeContextMessages(String userId, String threadId, String agentId,
            List<ContextMessageReceipt> receipts) {
        acknowledge(userId, threadId, agentId, receipts, false);
    }

    @Override
    @Transactional
    public void acknowledgeSummarizedMessages(String userId, String threadId, String agentId,
            List<ContextMessageReceipt> receipts, boolean replacePreviousSummary) {
        if (replacePreviousSummary) {
            chatMessageMapper.clearSummarizedContextMessages(userId, threadId, agentId);
        }
        acknowledge(userId, threadId, agentId, receipts, true);
    }

    @Override
    public List<ContextMessageReceipt> findSummarizedContextMessages(String userId, String threadId, String agentId) {
        return chatMessageMapper.findSummarizedContextMessages(userId, threadId, agentId);
    }

    private void acknowledge(String userId, String threadId, String agentId,
            List<ContextMessageReceipt> receipts, boolean summarized) {
        // Compression can cover a long history; bound parameters per statement.
        for (int start = 0; start < receipts.size(); start += 200) {
            chatMessageMapper.acknowledgeContextMessages(userId, threadId, agentId,
                    receipts.subList(start, Math.min(start + 200, receipts.size())), summarized);
        }
    }

    @Override
    public Optional<ChatMessage> findAssistantStreamByInvocationId(String invocationId) {
        if (invocationId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(chatMessageMapper.findAssistantStreamByInvocationId(invocationId));
    }

    @Override
    public void deleteByThreadId(String threadId) {
        chatMessageMapper.deleteByThreadId(threadId);
    }
}
