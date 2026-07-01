package com.agentcrossing.platform.api.ws;

import com.agentcrossing.platform.application.realtime.SocketManager;
import com.agentcrossing.platform.application.auth.CurrentUserResolver;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private final SocketManager socketManager;
    private final ChatThreadRepository chatThreadRepository;
    private final ObjectMapper objectMapper;
    private final CurrentUserResolver currentUserResolver;

    public ChatWebSocketHandler(
            SocketManager socketManager,
            ChatThreadRepository chatThreadRepository,
            ObjectMapper objectMapper,
            CurrentUserResolver currentUserResolver) {
        this.socketManager = socketManager;
        this.chatThreadRepository = chatThreadRepository;
        this.objectMapper = objectMapper;
        this.currentUserResolver = currentUserResolver;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        findThreadId(session.getUri()).ifPresent(threadId -> subscribe(session, threadId, findLastEventId(session.getUri())));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = objectMapper.readTree(message.getPayload());
        String type = node.path("type").asText("");
        if ("subscribe".equals(type)) {
            subscribe(session, node.path("threadId").asText(""), node.path("lastEventId").asLong(0L));
            return;
        }
        if ("ping".equals(type) && session.isOpen()) {
            session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        socketManager.unsubscribe(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        socketManager.unsubscribe(session);
    }

    private void subscribe(WebSocketSession session, String threadId, long lastEventId) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        String userId = currentUserResolver.fromWebSocketSession(session).userId();
        chatThreadRepository
                .findByThreadIdAndUserId(threadId, userId)
                .ifPresent(ignored -> socketManager.subscribe(threadId, session, lastEventId));
    }

    private static Optional<String> findThreadId(URI uri) {
        return findQueryValue(uri, "threadId");
    }

    private static long findLastEventId(URI uri) {
        return findQueryValue(uri, "lastEventId")
                .map(ChatWebSocketHandler::parseLongOrZero)
                .orElse(0L);
    }

    private static Optional<String> findQueryValue(URI uri, String key) {
        if (uri == null || uri.getQuery() == null || uri.getQuery().isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(uri.getQuery().split("&"))
                .map(part -> part.split("=", 2))
                .filter(pair -> pair.length == 2 && key.equals(pair[0]))
                .map(pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8))
                .findFirst();
    }

    private static long parseLongOrZero(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }
}
