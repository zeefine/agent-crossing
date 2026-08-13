package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import com.agentcrossing.platform.domain.invocation.InvocationUsage;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InvocationUsageMapper {
    void upsert(InvocationUsage usage);

    InvocationUsage findByInvocationId(@Param("invocationId") String invocationId);

    void deleteByInvocationIds(@Param("invocationIds") List<String> invocationIds);
}
