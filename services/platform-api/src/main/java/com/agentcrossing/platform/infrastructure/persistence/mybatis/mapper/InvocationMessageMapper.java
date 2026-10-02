package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import com.agentcrossing.platform.domain.message.InvocationMessage;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InvocationMessageMapper {
    void upsert(InvocationMessage message);

    int insertIgnore(InvocationMessage message);

    boolean existsByInvocationId(@Param("invocationId") String invocationId);

    List<InvocationMessage> findByInvocationId(@Param("invocationId") String invocationId);

    List<InvocationMessage> findByTraceId(@Param("traceId") String traceId);

    List<InvocationMessage> findByTraceIdAndUserId(@Param("traceId") String traceId, @Param("userId") String userId);

    void deleteByTraceIdAndUserId(@Param("traceId") String traceId, @Param("userId") String userId);
}
