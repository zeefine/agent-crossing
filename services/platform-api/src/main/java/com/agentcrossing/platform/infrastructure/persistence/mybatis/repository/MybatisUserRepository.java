package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.user.UserAccount;
import com.agentcrossing.platform.domain.user.UserRepository;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.UserMapper;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "mysql")
public class MybatisUserRepository implements UserRepository {
    private final UserMapper userMapper;

    public MybatisUserRepository(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserAccount save(UserAccount user) {
        userMapper.upsert(user);
        return user;
    }

    @Override
    public Optional<UserAccount> findByUserId(String userId) {
        return Optional.ofNullable(userMapper.findByUserId(userId));
    }
}
