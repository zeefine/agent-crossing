package com.agentcrossing.platform.application.realtime;

import com.agentcrossing.platform.api.dto.ChatEventResponse;
import com.agentcrossing.platform.domain.event.EventLogRepository;
import com.agentcrossing.platform.domain.event.RealtimeEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Service
public class SocketManager {
    private static final int REPLAY_LIMIT = 500;
    private static final int MAX_OUTBOUND_MESSAGES_PER_SESSION = 256;

    private final ObjectMapper objectMapper;
    private final EventLogRepository eventLogRepository;
    private final Executor websocketOutboundExecutor;
    private final ConcurrentMap<String, Set<WebSocketSession>> sessionsByThreadId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> threadIdBySessionId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SessionOutboundQueue> outboundBySessionId = new ConcurrentHashMap<>();

    public SocketManager(ObjectMapper objectMapper, EventLogRepository eventLogRepository) {
        this(objectMapper, eventLogRepository, Runnable::run);
    }

    @Autowired
    public SocketManager(
            ObjectMapper objectMapper,
            EventLogRepository eventLogRepository,
            @Qualifier("websocketOutboundExecutor") Executor websocketOutboundExecutor) {
        this.objectMapper = objectMapper;
        this.eventLogRepository = eventLogRepository;
        this.websocketOutboundExecutor = websocketOutboundExecutor;
    }

    public void subscribe(String threadId, WebSocketSession session) {
        subscribe(threadId, session, 0L);
    }

    public void subscribe(String threadId, WebSocketSession session, long lastEventId) {
        unsubscribe(session);
        sessionsByThreadId.computeIfAbsent(threadId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        threadIdBySessionId.put(session.getId(), threadId);
        outboundBySessionId.put(session.getId(), new SessionOutboundQueue(session));
        emitToSession(session, ChatEventResponse.of(threadId, RealtimeEventTypes.CONNECTION, "connected"));
        replayMissedEvents(threadId, session, lastEventId);
    }

    public void unsubscribe(WebSocketSession session) {
        String threadId = threadIdBySessionId.remove(session.getId());
        SessionOutboundQueue outbound = outboundBySessionId.remove(session.getId());
        if (outbound != null) {
            outbound.clear();
        }
        if (threadId == null) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByThreadId.get(threadId);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionsByThreadId.remove(threadId);
        }
    }

    public ChatEventResponse emit(String threadId, String eventName, Object payload) {
        RealtimeEvent event = recordEvent(threadId, eventName, payload);
        ChatEventResponse response = ChatEventResponse.from(event);
        emit(response);
        return response;
    }

    /**
     * 只持久化 realtime_event，不广播。供 ChatEventService 拆分"事务内持久化 + afterCommit 广播"用。
     * 当事务回滚时，这一行 realtime_event INSERT 也会跟着撤销，保证不会留下没有对应业务数据的孤儿事件。
     */
    public RealtimeEvent recordEvent(String threadId, String eventName, Object payload) {
        return eventLogRepository.save(threadId, eventName, payload);
    }

    public void emit(ChatEventResponse event) {
        Set<WebSocketSession> sessions = sessionsByThreadId.get(event.threadId());
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String serialized = serialize(event);
        if (serialized == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            enqueue(session, serialized);
        }
    }

    private void emitToSession(WebSocketSession session, ChatEventResponse event) {
        String serialized = serialize(event);
        if (serialized != null) {
            enqueue(session, serialized);
        }
    }

    private String serialize(ChatEventResponse event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (IOException exception) {
            return null;
        }
    }

    private void enqueue(WebSocketSession session, String serialized) {
        if (!session.isOpen()) {
            unsubscribe(session);
            return;
        }
        SessionOutboundQueue outbound = outboundBySessionId.computeIfAbsent(
                session.getId(), ignored -> new SessionOutboundQueue(session));
        if (!outbound.offer(new TextMessage(serialized))) {
            unsubscribe(session);
            closeSlowSession(session);
            return;
        }
        scheduleDrain(outbound);
    }

    private void scheduleDrain(SessionOutboundQueue outbound) {
        if (!outbound.draining.compareAndSet(false, true)) {
            return;
        }
        try {
            websocketOutboundExecutor.execute(() -> drain(outbound));
        } catch (RuntimeException exception) {
            outbound.draining.set(false);
            unsubscribe(outbound.session);
        }
    }

    private void drain(SessionOutboundQueue outbound) {
        while (true) {
            TextMessage message = outbound.poll();
            if (message == null) {
                outbound.draining.set(false);
                if (!outbound.isEmpty()) {
                    scheduleDrain(outbound);
                }
                return;
            }
            WebSocketSession session = outbound.session;
            if (!session.isOpen()) {
                unsubscribe(session);
                return;
            }
            try {
                session.sendMessage(message);
            } catch (IOException | IllegalStateException exception) {
                unsubscribe(session);
                return;
            }
        }
    }

    private void closeSlowSession(WebSocketSession session) {
        try {
            websocketOutboundExecutor.execute(() -> {
                try {
                    if (session.isOpen()) {
                        session.close(CloseStatus.SESSION_NOT_RELIABLE);
                    }
                } catch (IOException ignored) {
                    // Session 已不可用，无需继续处理。
                }
            });
        } catch (RuntimeException ignored) {
            // 应用正在关闭时 executor 可能拒绝任务；session 映射已经移除。
        }
    }

    private void replayMissedEvents(String threadId, WebSocketSession session, long lastEventId) {
        if (lastEventId < 0) {
            return;
        }
        List<RealtimeEvent> missedEvents = eventLogRepository.findAfter(threadId, lastEventId, REPLAY_LIMIT);
        for (RealtimeEvent event : missedEvents) {
            emitToSession(session, ChatEventResponse.from(event));
        }
    }

    private static final class SessionOutboundQueue {
        private final WebSocketSession session;
        private final Deque<TextMessage> messages = new ArrayDeque<>();
        private final AtomicBoolean draining = new AtomicBoolean(false);

        private SessionOutboundQueue(WebSocketSession session) {
            this.session = session;
        }

        private synchronized boolean offer(TextMessage message) {
            if (messages.size() >= MAX_OUTBOUND_MESSAGES_PER_SESSION) {
                return false;
            }
            messages.addLast(message);
            return true;
        }

        private synchronized TextMessage poll() {
            return messages.pollFirst();
        }

        private synchronized boolean isEmpty() {
            return messages.isEmpty();
        }

        private synchronized void clear() {
            messages.clear();
        }
    }
}
