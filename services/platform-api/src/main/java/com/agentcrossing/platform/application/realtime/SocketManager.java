package com.agentcrossing.platform.application.realtime;

import com.agentcrossing.platform.api.dto.ChatEventResponse;
import com.agentcrossing.platform.domain.event.EventLogRepository;
import com.agentcrossing.platform.domain.event.RealtimeEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Service
public class SocketManager {
    private static final int REPLAY_LIMIT = 500;

    private final ObjectMapper objectMapper;
    private final EventLogRepository eventLogRepository;
    private final ConcurrentMap<String, Set<WebSocketSession>> sessionsByThreadId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> threadIdBySessionId = new ConcurrentHashMap<>();

    public SocketManager(ObjectMapper objectMapper, EventLogRepository eventLogRepository) {
        this.objectMapper = objectMapper;
        this.eventLogRepository = eventLogRepository;
    }

    public void subscribe(String threadId, WebSocketSession session) {
        subscribe(threadId, session, 0L);
    }

    public void subscribe(String threadId, WebSocketSession session, long lastEventId) {
        unsubscribe(session);
        sessionsByThreadId.computeIfAbsent(threadId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        threadIdBySessionId.put(session.getId(), threadId);
        emitToSession(session, ChatEventResponse.of(threadId, RealtimeEventTypes.CONNECTION, "connected"));
        replayMissedEvents(threadId, session, lastEventId);
    }

    public void unsubscribe(WebSocketSession session) {
        String threadId = threadIdBySessionId.remove(session.getId());
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
        for (WebSocketSession session : sessions) {
            emitToSession(session, event);
        }
    }

    private void emitToSession(WebSocketSession session, ChatEventResponse event) {
        if (!session.isOpen()) {
            unsubscribe(session);
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
        } catch (IOException | IllegalStateException exception) {
            unsubscribe(session);
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
}
