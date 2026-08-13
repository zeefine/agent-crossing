package com.agentcrossing.platform.domain.invocation;

import java.util.Collection;
import java.util.Optional;

public interface InvocationUsageRepository {
    InvocationUsage save(InvocationUsage usage);

    Optional<InvocationUsage> findByInvocationId(String invocationId);

    void deleteByInvocationIds(Collection<String> invocationIds);
}
