package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageRepository;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.ChatMessageMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

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
    public List<ChatMessage> findVisibleMessagesAfterCursor(
            String threadId,
            String currentAgentId,
            Instant lastInjectedCreatedAt,
            String lastInjectedMessageId,
            int limit) {
        return chatMessageMapper.findVisibleMessagesAfterCursor(
                threadId,
                currentAgentId,
                lastInjectedCreatedAt,
                lastInjectedMessageId,
                limit);
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
