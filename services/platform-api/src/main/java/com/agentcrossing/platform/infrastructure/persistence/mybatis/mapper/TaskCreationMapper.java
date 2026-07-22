package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import com.agentcrossing.platform.domain.task.TaskCreation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskCreationMapper {
    TaskCreation findBySourceTaskIdAndClientTaskId(
            @Param("sourceTaskId") String sourceTaskId,
            @Param("clientTaskId") String clientTaskId);

    int insertIgnore(TaskCreation taskCreation);

    void deleteByTraceIdAndUserId(@Param("traceId") String traceId, @Param("userId") String userId);
}
