package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.event.EventLogRepository;
import com.agentcrossing.platform.domain.event.RealtimeEvent;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.RealtimeEventInsertParam;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.RealtimeEventMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "mysql")
public class MybatisEventLogRepository implements EventLogRepository {
    private final RealtimeEventMapper realtimeEventMapper;

    public MybatisEventLogRepository(RealtimeEventMapper realtimeEventMapper) {
        this.realtimeEventMapper = realtimeEventMapper;
    }

    @Override
    public RealtimeEvent save(String threadId, String type, Object payload) {
        Instant now = Instant.now();
        RealtimeEventInsertParam insertParam = new RealtimeEventInsertParam(threadId, type, payload, now);
        realtimeEventMapper.insert(insertParam);
        long eventId = insertParam.getEventId() == null ? 0L : insertParam.getEventId();
        return realtimeEventMapper.findAfter(threadId, eventId - 1, 1).stream()
                .findFirst()
                .orElse(new RealtimeEvent(eventId, threadId, type, null, now));
    }

    @Override
    public List<RealtimeEvent> findAfter(String threadId, long lastEventId, int limit) {
        return realtimeEventMapper.findAfter(threadId, lastEventId, limit);
    }

    @Override
    public void deleteByThreadId(String threadId) {
        realtimeEventMapper.deleteByThreadId(threadId);
    }
}
