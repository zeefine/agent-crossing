package com.agentcrossing.platform.domain.session;

import java.util.Optional;

public interface AgentSessionRepository {
    Optional<AgentSession> findByThreadId(String userId, String threadId, String agentId, String provider);

    AgentSession save(AgentSession session);
}
