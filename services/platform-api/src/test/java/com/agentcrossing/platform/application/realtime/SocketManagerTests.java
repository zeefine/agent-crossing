package com.agentcrossing.platform.application.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.domain.event.InMemoryEventLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void slowSessionDoesNotBlockEmitCaller() throws Exception {
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SocketManager socketManager = new SocketManager(new ObjectMapper(), eventLogRepository, executor);
        WebSocketSession session = mock(WebSocketSession.class);
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
                    sendStarted.countDown();
                    releaseSend.await(1, TimeUnit.SECONDS);
                    return null;
                })
                .when(session)
                .sendMessage(any());

        long startedAt = System.nanoTime();
        socketManager.subscribe("thread-1", session);
        socketManager.emit("thread-1", "chatMessage", "payload");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(sendStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(elapsedMs).isLessThan(100);
        releaseSend.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
}
