package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.session.AgentSession;
import com.agentcrossing.platform.domain.session.AgentSessionRepository;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.AgentSessionMapper;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "mysql")
public class MybatisAgentSessionRepository implements AgentSessionRepository {
    private final AgentSessionMapper agentSessionMapper;

    public MybatisAgentSessionRepository(AgentSessionMapper agentSessionMapper) {
        this.agentSessionMapper = agentSessionMapper;
    }

    @Override
    public Optional<AgentSession> findByThreadId(String userId, String threadId, String agentId, String provider) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(agentSessionMapper.findByThreadId(userId, threadId, agentId, provider));
    }

    @Override
    public AgentSession save(AgentSession session) {
        agentSessionMapper.upsert(session);
        return session;
    }
}
