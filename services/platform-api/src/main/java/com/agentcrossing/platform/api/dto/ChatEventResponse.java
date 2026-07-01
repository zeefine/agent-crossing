package com.agentcrossing.platform.api.dto;

import com.agentcrossing.platform.domain.event.RealtimeEvent;
import java.time.Instant;

public record ChatEventResponse(
        String eventId,
        String threadId,
        String type,
        Object payload,
        String createdAt) {
    public static ChatEventResponse of(String threadId, String type, Object payload) {
        return new ChatEventResponse(
                "event-" + java.util.UUID.randomUUID(),
                threadId,
                type,
                payload,
                Instant.now().toString());
    }

    public static ChatEventResponse from(RealtimeEvent event) {
        return new ChatEventResponse(
                Long.toString(event.eventId()),
                event.threadId(),
                event.type(),
                event.payload(),
                event.createdAt().toString());
    }
}
