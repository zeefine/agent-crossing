package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import com.agentcrossing.platform.domain.context.AgentContextCursor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentContextCursorMapper {
    AgentContextCursor find(
            @Param("userId") String userId,
            @Param("threadId") String threadId,
            @Param("agentId") String agentId);

    void upsert(AgentContextCursor cursor);
}
