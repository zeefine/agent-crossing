package com.agentcrossing.platform.domain.session;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryAgentSessionRepository implements AgentSessionRepository {
    private final ConcurrentMap<String, AgentSession> sessions = new ConcurrentHashMap<>();

    @Override
    public Optional<AgentSession> findByThreadId(String userId, String threadId, String agentId, String provider) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(key(userId, threadId, agentId, provider)));
    }

    @Override
    public AgentSession save(AgentSession session) {
        sessions.put(key(session.userId(), session.threadId(), session.agentId(), session.provider()), session);
        return session;
    }

    private static String key(String userId, String threadId, String agentId, String provider) {
        return userId + "\n" + threadId + "\n" + agentId + "\n" + provider;
    }
}
