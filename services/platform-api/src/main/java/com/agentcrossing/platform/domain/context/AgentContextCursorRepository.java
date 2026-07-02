package com.agentcrossing.platform.domain.context;

import java.util.Optional;

public interface AgentContextCursorRepository {
    Optional<AgentContextCursor> find(String userId, String threadId, String agentId);

    AgentContextCursor save(AgentContextCursor cursor);

    void deleteByThreadId(String userId, String threadId);
}
