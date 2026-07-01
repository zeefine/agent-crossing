package com.agentcrossing.platform.domain.user;

import java.util.Optional;

public interface UserRepository {
    UserAccount save(UserAccount user);

    Optional<UserAccount> findByUserId(String userId);

    default UserAccount ensure(String userId) {
        return findByUserId(userId).orElseGet(() -> {
            java.time.Instant now = java.time.Instant.now();
            return save(new UserAccount(userId, now, now));
        });
    }
}
