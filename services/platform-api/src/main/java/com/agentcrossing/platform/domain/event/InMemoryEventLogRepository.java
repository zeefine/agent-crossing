package com.agentcrossing.platform.domain.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryEventLogRepository implements EventLogRepository {
    private final AtomicLong sequence = new AtomicLong(0);
    private final CopyOnWriteArrayList<RealtimeEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public RealtimeEvent save(String threadId, String type, Object payload) {
        RealtimeEvent event = new RealtimeEvent(
                sequence.incrementAndGet(),
                threadId,
                type,
                payload,
                Instant.now());
        events.add(event);
        return event;
    }

    @Override
    public List<RealtimeEvent> findAfter(String threadId, long lastEventId, int limit) {
        return events.stream()
                .filter(event -> event.threadId().equals(threadId))
                .filter(event -> event.eventId() > lastEventId)
                .sorted(Comparator.comparingLong(RealtimeEvent::eventId))
                .limit(limit)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public void deleteByThreadId(String threadId) {
        events.removeIf(event -> event.threadId().equals(threadId));
    }
}
