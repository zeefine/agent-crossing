package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.InvocationMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.Instant;
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
    public List<Invocation> findByTraceIdAndUserId(String traceId, String userId) {
        return invocationMapper.findByTraceIdAndUserId(traceId, userId);
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
        if (existing.status() != InvocationStatus.CANCELED) {
            updateStatusIfCurrent(invocationId, Set.of(existing.status()), status);
        }
        return findByInvocationId(invocationId).orElseThrow(() -> new IllegalArgumentException("Invocation not found: " + invocationId));
    }

    @Override
    public boolean updateStatusIfCurrent(String invocationId, Set<InvocationStatus> expected, InvocationStatus status) {
        if (expected.isEmpty()) {
            return false;
        }
        return invocationMapper.updateStatusIfCurrent(invocationId,
                expected.stream().map(Enum::name).collect(Collectors.toSet()), status.name(), Instant.now()) == 1;
    }

    @Override
    public void deleteByTraceIdAndUserId(String traceId, String userId) {
        invocationMapper.deleteByTraceIdAndUserId(traceId, userId);
    }

    @Override
    public List<Invocation> findAll() {
        return invocationMapper.findAll();
    }
}
