package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import com.agentcrossing.platform.domain.session.AgentSessionHistory;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentSessionHistoryMapper {
    int updateBySessionRecordId(AgentSessionHistory history);

    void insert(AgentSessionHistory history);

    int restoreInterruptedCompactions();

    AgentSessionHistory findBySessionRecordId(@Param("sessionRecordId") String sessionRecordId);

    AgentSessionHistory findByProviderSessionId(
            @Param("provider") String provider, @Param("providerSessionId") String providerSessionId);

    AgentSessionHistory findByGeneration(
            @Param("userId") String userId,
            @Param("threadId") String threadId,
            @Param("agentId") String agentId,
            @Param("provider") String provider,
            @Param("generation") int generation);

    AgentSessionHistory findActive(
            @Param("userId") String userId,
            @Param("threadId") String threadId,
            @Param("agentId") String agentId,
            @Param("provider") String provider,
            @Param("status") String status);

    AgentSessionHistory findCreating(
            @Param("userId") String userId,
            @Param("threadId") String threadId,
            @Param("agentId") String agentId,
            @Param("provider") String provider,
            @Param("status") String status);

    List<AgentSessionHistory> findByThreadId(
            @Param("userId") String userId,
            @Param("threadId") String threadId,
            @Param("agentId") String agentId,
            @Param("provider") String provider);

    void deleteByThreadId(@Param("userId") String userId, @Param("threadId") String threadId);
}
