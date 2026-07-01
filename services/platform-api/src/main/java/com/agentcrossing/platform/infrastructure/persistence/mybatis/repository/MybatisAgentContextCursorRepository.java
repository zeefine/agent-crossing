package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.context.AgentContextCursor;
import com.agentcrossing.platform.domain.context.AgentContextCursorRepository;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.AgentContextCursorMapper;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "mysql")
public class MybatisAgentContextCursorRepository implements AgentContextCursorRepository {
    private final AgentContextCursorMapper agentContextCursorMapper;

    public MybatisAgentContextCursorRepository(AgentContextCursorMapper agentContextCursorMapper) {
        this.agentContextCursorMapper = agentContextCursorMapper;
    }

    @Override
    public Optional<AgentContextCursor> find(String userId, String threadId, String agentId) {
        return Optional.ofNullable(agentContextCursorMapper.find(userId, threadId, agentId));
    }

    @Override
    public AgentContextCursor save(AgentContextCursor cursor) {
        agentContextCursorMapper.upsert(cursor);
        return cursor;
    }
}
