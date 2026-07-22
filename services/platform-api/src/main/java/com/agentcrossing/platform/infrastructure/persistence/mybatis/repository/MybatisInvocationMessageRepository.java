package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.message.InvocationMessage;
import com.agentcrossing.platform.domain.message.InvocationMessageRepository;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.InvocationMessageMapper;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "mysql")
public class MybatisInvocationMessageRepository implements InvocationMessageRepository {
    private final InvocationMessageMapper invocationMessageMapper;

    public MybatisInvocationMessageRepository(InvocationMessageMapper invocationMessageMapper) {
        this.invocationMessageMapper = invocationMessageMapper;
    }

    @Override
    public InvocationMessage save(InvocationMessage message) {
        invocationMessageMapper.upsert(message);
        return message;
    }

    @Override
    public boolean saveIfAbsent(InvocationMessage message) {
        return invocationMessageMapper.insertIgnore(message) == 1;
    }

    @Override
    public List<InvocationMessage> findByInvocationId(String invocationId) {
        return invocationMessageMapper.findByInvocationId(invocationId);
    }

    @Override
    public List<InvocationMessage> findByTraceId(String traceId) {
        return invocationMessageMapper.findByTraceId(traceId);
    }

    @Override
    public List<InvocationMessage> findByTraceIdAndUserId(String traceId, String userId) {
        return invocationMessageMapper.findByTraceIdAndUserId(traceId, userId);
    }

    @Override
    public void deleteByTraceIdAndUserId(String traceId, String userId) {
        invocationMessageMapper.deleteByTraceIdAndUserId(traceId, userId);
    }
}
