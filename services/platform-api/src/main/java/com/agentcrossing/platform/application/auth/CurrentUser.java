package com.agentcrossing.platform.application.auth;

import java.util.Objects;

public record CurrentUser(String userId) {
    public static final String ANONYMOUS_USER_ID = "anonymous";

    public CurrentUser {
        Objects.requireNonNull(userId, "userId must not be null");
        if (userId.isBlank()) {
            userId = ANONYMOUS_USER_ID;
        } else {
            userId = userId.strip();
        }
    }
}
