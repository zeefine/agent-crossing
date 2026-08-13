package com.agentcrossing.platform.domain.session;

import java.util.List;
import java.util.Optional;

public interface AgentSessionHistoryRepository {
    AgentSessionHistory save(AgentSessionHistory history);

    Optional<AgentSessionHistory> findBySessionRecordId(String sessionRecordId);

    Optional<AgentSessionHistory> findByProviderSessionId(String provider, String providerSessionId);

    Optional<AgentSessionHistory> findByGeneration(
            String userId, String threadId, String agentId, String provider, int generation);

    Optional<AgentSessionHistory> findActive(String userId, String threadId, String agentId, String provider);

    Optional<AgentSessionHistory> findCreating(String userId, String threadId, String agentId, String provider);

    List<AgentSessionHistory> findByThreadId(String userId, String threadId, String agentId, String provider);

    void deleteByThreadId(String userId, String threadId);
}
