package com.agentcrossing.platform.domain.event;

import java.util.List;

public interface EventLogRepository {
    RealtimeEvent save(String threadId, String type, Object payload);

    List<RealtimeEvent> findAfter(String threadId, long lastEventId, int limit);

    void deleteByThreadId(String threadId);
}
