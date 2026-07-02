package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import com.agentcrossing.platform.domain.session.AgentSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentSessionMapper {
    AgentSession findByThreadId(
            @Param("userId") String userId,
            @Param("threadId") String threadId,
            @Param("agentId") String agentId,
            @Param("provider") String provider);

    void upsert(AgentSession session);

    void deleteByThreadId(@Param("userId") String userId, @Param("threadId") String threadId);
}
