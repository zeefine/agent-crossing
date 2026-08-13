package com.agentcrossing.platform.domain.invocation;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryInvocationUsageRepository implements InvocationUsageRepository {
    private final ConcurrentMap<String, InvocationUsage> usages = new ConcurrentHashMap<>();

    @Override
    public InvocationUsage save(InvocationUsage usage) {
        usages.put(usage.invocationId(), usage);
        return usage;
    }

    @Override
    public Optional<InvocationUsage> findByInvocationId(String invocationId) {
        return Optional.ofNullable(usages.get(invocationId));
    }

    @Override
    public void deleteByInvocationIds(Collection<String> invocationIds) {
        invocationIds.forEach(usages::remove);
    }
}
