package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import com.agentcrossing.platform.domain.invocation.Invocation;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InvocationMapper {
    void upsert(Invocation invocation);

    Invocation findByInvocationId(@Param("invocationId") String invocationId);

    Invocation findByInvocationIdAndUserId(@Param("invocationId") String invocationId, @Param("userId") String userId);

    List<Invocation> findByTaskId(@Param("taskId") String taskId);

    List<Invocation> findByTaskIdAndUserId(@Param("taskId") String taskId, @Param("userId") String userId);

    List<Invocation> findByTraceIdAndUserId(@Param("traceId") String traceId, @Param("userId") String userId);

    List<Invocation> findRunningByAgentId(@Param("agentId") String agentId, @Param("status") String status);

    List<Invocation> findRunningByAgentIdAndUserId(
            @Param("agentId") String agentId, @Param("userId") String userId, @Param("status") String status);

    List<Invocation> findAll();

    void deleteByTraceIdAndUserId(@Param("traceId") String traceId, @Param("userId") String userId);
}
