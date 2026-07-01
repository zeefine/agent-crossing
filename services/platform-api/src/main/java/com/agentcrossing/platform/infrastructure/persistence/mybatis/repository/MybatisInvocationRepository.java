package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.InvocationMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "mysql")
public class MybatisInvocationRepository implements InvocationRepository {
    private final InvocationMapper invocationMapper;

    public MybatisInvocationRepository(InvocationMapper invocationMapper) {
        this.invocationMapper = invocationMapper;
    }

    @Override
    public Invocation save(Invocation invocation) {
        invocationMapper.upsert(invocation);
        return invocation;
    }

    @Override
    public Optional<Invocation> findByInvocationId(String invocationId) {
        return Optional.ofNullable(invocationMapper.findByInvocationId(invocationId));
    }

    @Override
    public Optional<Invocation> findByInvocationIdAndUserId(String invocationId, String userId) {
        return Optional.ofNullable(invocationMapper.findByInvocationIdAndUserId(invocationId, userId));
    }

    @Override
    public List<Invocation> findByTaskId(String taskId) {
        return invocationMapper.findByTaskId(taskId);
    }

    @Override
    public List<Invocation> findByTaskIdAndUserId(String taskId, String userId) {
        return invocationMapper.findByTaskIdAndUserId(taskId, userId);
    }

    @Override
    public List<Invocation> findRunningByAgentId(String agentId) {
        return invocationMapper.findRunningByAgentId(agentId, InvocationStatus.RUNNING.name());
    }

    @Override
    public List<Invocation> findRunningByAgentIdAndUserId(String agentId, String userId) {
        return invocationMapper.findRunningByAgentIdAndUserId(agentId, userId, InvocationStatus.RUNNING.name());
    }

    @Override
    public Invocation updateStatus(String invocationId, InvocationStatus status) {
        Invocation existing = findByInvocationId(invocationId)
                .orElseThrow(() -> new IllegalArgumentException("Invocation not found: " + invocationId));
        Invocation updated = existing.withStatus(status);
        invocationMapper.upsert(updated);
        return updated;
    }

    @Override
    public List<Invocation> findAll() {
        return invocationMapper.findAll();
    }
}
