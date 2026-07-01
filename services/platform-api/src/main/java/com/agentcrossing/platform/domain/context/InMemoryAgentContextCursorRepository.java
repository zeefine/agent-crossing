package com.agentcrossing.platform.domain.context;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryAgentContextCursorRepository implements AgentContextCursorRepository {
    private final ConcurrentMap<String, AgentContextCursor> cursors = new ConcurrentHashMap<>();

    @Override
    public Optional<AgentContextCursor> find(String userId, String threadId, String agentId) {
        return Optional.ofNullable(cursors.get(key(userId, threadId, agentId)));
    }

    @Override
    public AgentContextCursor save(AgentContextCursor cursor) {
        cursors.put(key(cursor.userId(), cursor.threadId(), cursor.agentId()), cursor);
        return cursor;
    }

    private static String key(String userId, String threadId, String agentId) {
        return userId + "\n" + threadId + "\n" + agentId;
    }
}
