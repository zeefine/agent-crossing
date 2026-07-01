package com.agentcrossing.platform.application.chat;

import com.agentcrossing.platform.api.dto.ChatEventResponse;
import com.agentcrossing.platform.domain.event.EventLogRepository;
import com.agentcrossing.platform.domain.event.RealtimeEvent;
import com.agentcrossing.platform.application.realtime.RealtimeEventTypes;
import com.agentcrossing.platform.application.realtime.SocketManager;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatEventService {
    private static final long NEVER_TIMEOUT = 0L;
    private static final int REPLAY_LIMIT = 500;

    private final SocketManager socketManager;
    private final EventLogRepository eventLogRepository;
    private final boolean sseEnabled;
    private final ConcurrentMap<String, CopyOnWriteArrayList<SseEmitter>> emittersByThreadId =
            new ConcurrentHashMap<>();

    public ChatEventService(SocketManager socketManager, EventLogRepository eventLogRepository) {
        this(socketManager, eventLogRepository, false);
    }

    @Autowired
    public ChatEventService(
            SocketManager socketManager,
            EventLogRepository eventLogRepository,
            @Value("${agent-crossing.realtime.sse-enabled:false}") boolean sseEnabled) {
        this.socketManager = socketManager;
        this.eventLogRepository = eventLogRepository;
        this.sseEnabled = sseEnabled;
    }

    public SseEmitter subscribe(String threadId) {
        return subscribe(threadId, 0L);
    }

    public SseEmitter subscribe(String threadId, long lastEventId) {
        SseEmitter emitter = new SseEmitter(NEVER_TIMEOUT);
        if (!sseEnabled) {
            send(
                    threadId,
                    emitter,
                    RealtimeEventTypes.CONNECTION,
                    ChatEventResponse.of(threadId, RealtimeEventTypes.CONNECTION, "sse_disabled"));
            emitter.complete();
            return emitter;
        }
        emittersByThreadId.computeIfAbsent(threadId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(threadId, emitter));
        emitter.onTimeout(() -> removeEmitter(threadId, emitter));
        emitter.onError(ignored -> removeEmitter(threadId, emitter));
        replayMissedEvents(threadId, emitter, lastEventId);
        send(
                threadId,
                emitter,
                RealtimeEventTypes.CONNECTION,
                ChatEventResponse.of(threadId, RealtimeEventTypes.CONNECTION, "connected"));
        return emitter;
    }

    /**
     * 持久化事件 publish。
     *
     * <p>事务内部：写 realtime_event（跟业务表同事务，回滚会一起撤销）。
     * 事务 afterCommit 之后：广播给 WS；只有开启 sse-enabled 时才额外广播给 SSE 订阅者。</p>
     *
     * <p>如果当前没有活跃事务（单元测试 / 非 @Transactional 调用点），立刻广播——
     * 保持向后兼容。</p>
     */
    public void publish(String threadId, String eventName, Object payload) {
        RealtimeEvent persisted = socketManager.recordEvent(threadId, eventName, payload);
        ChatEventResponse event = ChatEventResponse.from(persisted);
        broadcastAfterCommit(threadId, eventName, event);
    }

    /**
     * 流式分片专用：只通过 WS 推给在线客户端；开启 sse-enabled 时才额外推 SSE，<b>不写 realtime_event</b>。
     *
     * <p>断线重连时 lastEventId 补发只补持久化事件（thread / task / 终态 chatMessage）；
     * 错过的流式分片不可回放——但 chat_message 行里的累积内容仍是最新的，重连后
     * GET /api/chat/threads/.../messages 能看到完整结果。</p>
     *
     * <p>eventId 使用 "event-uuid" 形态（非数字），前端 useSocket 的 Number(event.eventId)
     * 会得到 NaN → 不会更新 lastEventId 指针，确保下次重连仍从最后一个持久化事件继续。</p>
     *
     * <p>广播也走 afterCommit：流式分片通常和 chat_message 累积 UPDATE 处在同一事务里，
     * 如果事务回滚（写库失败），广播也不应该发出去，否则前端看到一段 mysql 里不存在的内容。</p>
     */
    public void publishTransient(String threadId, String eventName, Object payload) {
        ChatEventResponse event = ChatEventResponse.of(threadId, eventName, payload);
        broadcastAfterCommit(threadId, eventName, event);
    }

    /**
     * 调度广播：有活跃事务则推迟到 commit 后；否则立刻广播。
     *
     * <p>这样保证前端收到的 WS 事件对应的业务数据一定已经在 mysql 里 committed——
     * 多设备同步 / 联邦机器读 mysql 做下一步决策都不会撞到"幽灵事件"。</p>
     */
    private void broadcastAfterCommit(String threadId, String eventName, ChatEventResponse event) {
        Runnable broadcast = () -> {
            socketManager.emit(event);
            fanOutToSse(threadId, eventName, event);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    broadcast.run();
                }
            });
        } else {
            broadcast.run();
        }
    }

    private void fanOutToSse(String threadId, String eventName, ChatEventResponse event) {
        if (!sseEnabled) {
            return;
        }
        List<SseEmitter> emitters = emittersByThreadId.getOrDefault(threadId, new CopyOnWriteArrayList<>());
        for (SseEmitter emitter : emitters) {
            try {
                send(threadId, emitter, eventName, event);
            } catch (Exception exception) {
                removeEmitter(threadId, emitter);
            }
        }
    }

    private void replayMissedEvents(String threadId, SseEmitter emitter, long lastEventId) {
        if (lastEventId < 0) {
            return;
        }
        for (var event : eventLogRepository.findAfter(threadId, lastEventId, REPLAY_LIMIT)) {
            send(threadId, emitter, event.type(), ChatEventResponse.from(event));
        }
    }

    private void send(String threadId, SseEmitter emitter, String eventName, ChatEventResponse event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.eventId())
                    .name(eventName)
                    .data(event, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException exception) {
            removeEmitter(threadId, emitter);
        } catch (RuntimeException exception) {
            removeEmitter(threadId, emitter);
        }
    }

    private void removeEmitter(String threadId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByThreadId.get(threadId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByThreadId.remove(threadId);
        }
    }

    int activeSseEmitterCount(String threadId) {
        return emittersByThreadId.getOrDefault(threadId, new CopyOnWriteArrayList<>()).size();
    }
}
