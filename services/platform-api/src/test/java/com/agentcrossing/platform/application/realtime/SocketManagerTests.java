package com.agentcrossing.platform.application.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.domain.event.InMemoryEventLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SocketManagerTests {
    @Test
    void persistsCanonicalEventNamesWithoutLegacyAliasRewrite() {
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        SocketManager socketManager = new SocketManager(new ObjectMapper(), eventLogRepository);

        socketManager.emit("thread-1", "thread", "payload-a");
        socketManager.emit("thread-1", "chatMessage", "payload-b");
        socketManager.emit("thread-1", "invocationMessage", "payload-c");
        socketManager.emit("thread-1", "task", "payload-d");

        assertThat(eventLogRepository.findAfter("thread-1", 0L, 10))
                .extracting(event -> event.type())
                .containsExactly("thread", "chatMessage", "invocationMessage", "task");
    }
}
