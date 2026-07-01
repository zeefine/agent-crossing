package com.agentcrossing.platform.application.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.api.dto.ChatEventResponse;
import com.agentcrossing.platform.application.realtime.SocketManager;
import com.agentcrossing.platform.domain.event.InMemoryEventLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ChatEventServiceTests {
    @AfterEach
    void clearTxnSyncManager() {
        // 防止某个测试激活的事务同步泄漏给下一个测试。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void publishWritesEventLog() {
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        SocketManager socketManager = new SocketManager(new ObjectMapper(), eventLogRepository);
        ChatEventService service = new ChatEventService(socketManager, eventLogRepository);

        service.publish("thread-1", "thread", "payload-persistent");

        assertThat(eventLogRepository.findAfter("thread-1", 0L, 10))
                .extracting(event -> event.type())
                .containsExactly("thread");
    }

    @Test
    void sseSubscriptionDoesNotRegisterEmitterWhenDisabled() {
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        SocketManager socketManager = new SocketManager(new ObjectMapper(), eventLogRepository);
        ChatEventService service = new ChatEventService(socketManager, eventLogRepository, false);

        service.subscribe("thread-1", 0L);

        assertThat(service.activeSseEmitterCount("thread-1")).isZero();
    }

    @Test
    void sseSubscriptionRegistersEmitterOnlyWhenEnabled() {
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        SocketManager socketManager = new SocketManager(new ObjectMapper(), eventLogRepository);
        ChatEventService service = new ChatEventService(socketManager, eventLogRepository, true);

        service.subscribe("thread-1", 0L);

        assertThat(service.activeSseEmitterCount("thread-1")).isEqualTo(1);
    }

    @Test
    void publishTransientSkipsEventLog() {
        // spec v1.1 §2.5：流式分片走 transient 通道不进 realtime_event，断线重连不可补发。
        // 这条测试守卫这个边界——任何改动让 publishTransient 落库都会让 64 个持久化事件
        // 变成数百个，mysql 写放大恢复，体验回退。
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        SocketManager socketManager = new SocketManager(new ObjectMapper(), eventLogRepository);
        ChatEventService service = new ChatEventService(socketManager, eventLogRepository);

        service.publishTransient("thread-1", "chatMessage", "payload-streaming-chunk");
        service.publishTransient("thread-1", "invocationMessage", "payload-streaming-chunk");

        assertThat(eventLogRepository.findAfter("thread-1", 0L, 10)).isEmpty();
    }

    @Test
    void broadcastDeferredUntilTransactionCommitsThenFiresAfterCommit() {
        // 有活跃事务时，broadcast 不应立刻发；只有 afterCommit() 调用后才推。
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        RecordingSocketManager socketManager = new RecordingSocketManager(eventLogRepository);
        ChatEventService service = new ChatEventService(socketManager, eventLogRepository);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.publish("thread-1", "thread", "p1");
            service.publishTransient("thread-1", "chatMessage", "p2");

            // 事务内：realtime_event 已写一行（thread），但 socket 一次都没广播。
            assertThat(eventLogRepository.findAfter("thread-1", 0L, 10))
                    .extracting(event -> event.type())
                    .containsExactly("thread");
            assertThat(socketManager.broadcastCount).isEqualTo(0);

            // 触发 afterCommit
            for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            assertThat(socketManager.broadcastCount).isEqualTo(2);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void broadcastSkippedWhenTransactionRollsBack() {
        // 事务回滚（不调 afterCommit）→ socket 永远不广播 → 没有"幽灵事件"。
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        RecordingSocketManager socketManager = new RecordingSocketManager(eventLogRepository);
        ChatEventService service = new ChatEventService(socketManager, eventLogRepository);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.publish("thread-1", "thread", "would-be-rolled-back");
            service.publishTransient("thread-1", "chatMessage", "would-be-rolled-back");

            // 模拟回滚：不调 afterCommit，直接 clear。
        } finally {
            TransactionSynchronizationManager.clear();
        }

        assertThat(socketManager.broadcastCount).isEqualTo(0);
    }

    @Test
    void broadcastFiresImmediatelyWhenNoTransactionActive() {
        // 没有事务时（单测 / 非 @Transactional 调用），保持原有"立刻广播"行为。
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        RecordingSocketManager socketManager = new RecordingSocketManager(eventLogRepository);
        ChatEventService service = new ChatEventService(socketManager, eventLogRepository);

        service.publish("thread-1", "thread", "p");
        service.publishTransient("thread-1", "chatMessage", "p");

        assertThat(socketManager.broadcastCount).isEqualTo(2);
    }

    @Test
    void persistAndTransientMixOnlyLogsPersistent() {
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        SocketManager socketManager = new SocketManager(new ObjectMapper(), eventLogRepository);
        ChatEventService service = new ChatEventService(socketManager, eventLogRepository);

        service.publish("thread-1", "thread", "running");
        service.publishTransient("thread-1", "chatMessage", "chunk-1");
        service.publishTransient("thread-1", "chatMessage", "chunk-2");
        service.publish("thread-1", "task", "completed");
        service.publishTransient("thread-1", "chatMessage", "chunk-3");
        service.publish("thread-1", "thread", "completed");

        assertThat(eventLogRepository.findAfter("thread-1", 0L, 20))
                .extracting(event -> event.type())
                .containsExactly("thread", "task", "thread");
    }

    /** 数 socketManager.emit(ChatEventResponse) 的次数，但不影响 record / emitter 行为。 */
    private static final class RecordingSocketManager extends SocketManager {
        int broadcastCount = 0;

        RecordingSocketManager(InMemoryEventLogRepository eventLogRepository) {
            super(new ObjectMapper(), eventLogRepository);
        }

        @Override
        public void emit(ChatEventResponse event) {
            broadcastCount++;
            super.emit(event);
        }
    }
}
