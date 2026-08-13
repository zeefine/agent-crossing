package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.invocation.InvocationUsage;
import com.agentcrossing.platform.domain.invocation.InvocationUsageRepository;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.InvocationUsageMapper;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "mysql")
public class MybatisInvocationUsageRepository implements InvocationUsageRepository {
    private final InvocationUsageMapper invocationUsageMapper;

    public MybatisInvocationUsageRepository(InvocationUsageMapper invocationUsageMapper) {
        this.invocationUsageMapper = invocationUsageMapper;
    }

    @Override
    public InvocationUsage save(InvocationUsage usage) {
        invocationUsageMapper.upsert(usage);
        return usage;
    }

    @Override
    public Optional<InvocationUsage> findByInvocationId(String invocationId) {
        return Optional.ofNullable(invocationUsageMapper.findByInvocationId(invocationId));
    }

    @Override
    public void deleteByInvocationIds(Collection<String> invocationIds) {
        if (invocationIds == null || invocationIds.isEmpty()) {
            return;
        }
        invocationUsageMapper.deleteByInvocationIds(List.copyOf(invocationIds));
    }
}
