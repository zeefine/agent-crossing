package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.ChatThreadMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "mysql")
public class MybatisChatThreadRepository implements ChatThreadRepository {
    private final ChatThreadMapper chatThreadMapper;

    public MybatisChatThreadRepository(ChatThreadMapper chatThreadMapper) {
        this.chatThreadMapper = chatThreadMapper;
    }

    @Override
    public ChatThread save(ChatThread thread) {
        chatThreadMapper.upsert(thread);
        return thread;
    }

    @Override
    public Optional<ChatThread> findByThreadId(String threadId) {
        return Optional.ofNullable(chatThreadMapper.findByThreadId(threadId));
    }

    @Override
    public Optional<ChatThread> findByThreadIdAndUserId(String threadId, String userId) {
        return Optional.ofNullable(chatThreadMapper.findByThreadIdAndUserId(threadId, userId));
    }

    @Override
    public Optional<ChatThread> findByTraceId(String traceId) {
        return Optional.ofNullable(chatThreadMapper.findByTraceId(traceId));
    }

    @Override
    public ChatThread updateStatus(String threadId, ChatThreadStatus status) {
        ChatThread existing = findByThreadId(threadId)
                .orElseThrow(() -> new IllegalArgumentException("Chat thread not found: " + threadId));
        ChatThread updated = existing.withStatus(status);
        chatThreadMapper.upsert(updated);
        return updated;
    }

    @Override
    public ChatThread updateTitle(String threadId, String title) {
        ChatThread existing = findByThreadId(threadId)
                .orElseThrow(() -> new IllegalArgumentException("Chat thread not found: " + threadId));
        ChatThread updated = existing.withTitle(title);
        chatThreadMapper.upsert(updated);
        return updated;
    }

    @Override
    public void deleteByThreadId(String threadId) {
        chatThreadMapper.deleteByThreadId(threadId);
    }

    @Override
    public List<ChatThread> findAll() {
        return chatThreadMapper.findAll();
    }

    @Override
    public List<ChatThread> findAllByUserId(String userId) {
        return chatThreadMapper.findAllByUserId(userId);
    }
}
