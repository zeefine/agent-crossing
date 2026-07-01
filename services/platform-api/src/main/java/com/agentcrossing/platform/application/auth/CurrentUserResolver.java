package com.agentcrossing.platform.application.auth;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
public class CurrentUserResolver {
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ID_QUERY_PARAM = "userId";

    public CurrentUser fromHeader(String userId) {
        return new CurrentUser(userId == null ? CurrentUser.ANONYMOUS_USER_ID : userId);
    }

    public CurrentUser fromWebSocketSession(WebSocketSession session) {
        String headerUserId = session.getHandshakeHeaders().getFirst(USER_ID_HEADER);
        if (headerUserId != null && !headerUserId.isBlank()) {
            return new CurrentUser(headerUserId);
        }
        return new CurrentUser(findQueryValue(session.getUri(), USER_ID_QUERY_PARAM)
                .orElse(CurrentUser.ANONYMOUS_USER_ID));
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
}
