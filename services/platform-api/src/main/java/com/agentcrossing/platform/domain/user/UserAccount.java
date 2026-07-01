package com.agentcrossing.platform.domain.user;

import java.time.Instant;
import java.util.Objects;

public record UserAccount(String userId, Instant createdAt, Instant updatedAt) {
    public UserAccount {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
    }
}
