package com.agentcrossing.platform.domain.user;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryUserRepository implements UserRepository {
    private final ConcurrentMap<String, UserAccount> users = new ConcurrentHashMap<>();

    @Override
    public UserAccount save(UserAccount user) {
        users.put(user.userId(), user);
        return user;
    }

    @Override
    public Optional<UserAccount> findByUserId(String userId) {
        return Optional.ofNullable(users.get(userId));
    }
}
