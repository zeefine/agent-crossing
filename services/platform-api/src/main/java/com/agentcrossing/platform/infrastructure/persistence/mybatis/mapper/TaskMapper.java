package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import com.agentcrossing.platform.domain.task.Task;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskMapper {
    void upsert(Task task);

    Task findByTaskId(@Param("taskId") String taskId);

    Task findByTaskIdAndUserId(@Param("taskId") String taskId, @Param("userId") String userId);

    List<Task> findByTraceId(@Param("traceId") String traceId);

    List<Task> findByTraceIdAndUserId(@Param("traceId") String traceId, @Param("userId") String userId);

    List<Task> findByStatus(@Param("status") String status);

    List<Task> findByStatusAndUserId(@Param("status") String status, @Param("userId") String userId);

    List<Task> findAll();
}
