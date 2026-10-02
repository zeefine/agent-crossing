package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.session.AgentSessionHistory;
import com.agentcrossing.platform.domain.session.AgentSessionHistoryRepository;
import com.agentcrossing.platform.domain.session.AgentSessionHistoryStatus;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.AgentSessionHistoryMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "mysql")
public class MybatisAgentSessionHistoryRepository implements AgentSessionHistoryRepository {
    private final AgentSessionHistoryMapper agentSessionHistoryMapper;

    public MybatisAgentSessionHistoryRepository(AgentSessionHistoryMapper agentSessionHistoryMapper) {
        this.agentSessionHistoryMapper = agentSessionHistoryMapper;
    }

    @Override
    public AgentSessionHistory save(AgentSessionHistory history) {
        if (agentSessionHistoryMapper.updateBySessionRecordId(history) == 0) {
            try {
                agentSessionHistoryMapper.insert(history);
            } catch (DuplicateKeyException exception) {
                // A concurrent writer may have inserted the same record after our UPDATE. Retry only when
                // session_record_id now exists; collisions on provider_session_id or generation must still fail.
                if (agentSessionHistoryMapper.updateBySessionRecordId(history) == 0) {
                    throw exception;
                }
            }
        }
        return history;
    }

    @Override
    public Optional<AgentSessionHistory> findBySessionRecordId(String sessionRecordId) {
        return Optional.ofNullable(agentSessionHistoryMapper.findBySessionRecordId(sessionRecordId));
    }

    @Override
    public Optional<AgentSessionHistory> findByProviderSessionId(String provider, String providerSessionId) {
        return Optional.ofNullable(agentSessionHistoryMapper.findByProviderSessionId(provider, providerSessionId));
    }

    @Override
    public Optional<AgentSessionHistory> findByGeneration(
            String userId, String threadId, String agentId, String provider, int generation) {
        return Optional.ofNullable(
                agentSessionHistoryMapper.findByGeneration(userId, threadId, agentId, provider, generation));
    }

    @Override
    public Optional<AgentSessionHistory> findActive(
            String userId, String threadId, String agentId, String provider) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(agentSessionHistoryMapper.findActive(
                userId, threadId, agentId, provider, AgentSessionHistoryStatus.ACTIVE.name()));
    }

    @Override
    public Optional<AgentSessionHistory> findCreating(
            String userId, String threadId, String agentId, String provider) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(agentSessionHistoryMapper.findCreating(
                userId, threadId, agentId, provider, AgentSessionHistoryStatus.CREATING.name()));
    }

    @Override
    public Optional<AgentSessionHistory> findCompacting(
            String userId, String threadId, String agentId, String provider) {
        return Optional.ofNullable(agentSessionHistoryMapper.findActive(
                userId, threadId, agentId, provider, AgentSessionHistoryStatus.COMPACTING.name()));
    }

    @Override
    public int restoreInterruptedCompactions() {
        return agentSessionHistoryMapper.restoreInterruptedCompactions();
    }

    @Override
    public List<AgentSessionHistory> findByThreadId(
            String userId, String threadId, String agentId, String provider) {
        if (threadId == null || threadId.isBlank()) {
            return List.of();
        }
        return agentSessionHistoryMapper.findByThreadId(userId, threadId, agentId, provider);
    }

    @Override
    public void deleteByThreadId(String userId, String threadId) {
        agentSessionHistoryMapper.deleteByThreadId(userId, threadId);
    }
}
