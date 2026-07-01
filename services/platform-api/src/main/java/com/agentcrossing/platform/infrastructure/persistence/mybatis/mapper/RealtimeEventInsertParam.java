package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import java.time.Instant;

public class RealtimeEventInsertParam {
    private Long eventId;
    private final String threadId;
    private final String eventType;
    private final Object payload;
    private final Instant createdAt;

    public RealtimeEventInsertParam(String threadId, String eventType, Object payload, Instant createdAt) {
        this.threadId = threadId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getEventType() {
        return eventType;
    }

    public Object getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
