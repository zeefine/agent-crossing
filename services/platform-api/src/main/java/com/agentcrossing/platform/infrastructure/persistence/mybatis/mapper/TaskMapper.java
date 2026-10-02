package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskStatusCount;
import com.agentcrossing.platform.domain.task.TaskDispatchSnapshot;
import java.util.List;
import java.util.Set;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskMapper {
    void upsert(Task task);

    List<TaskDispatchSnapshot> findDispatchSnapshots(@Param("taskIds") List<String> taskIds);

    int updateStatusIfCurrent(@Param("taskId") String taskId, @Param("expected") Set<String> expected,
            @Param("status") String status, @Param("now") Instant now);

    Task findByTaskId(@Param("taskId") String taskId);

    Task findByTaskIdAndUserId(@Param("taskId") String taskId, @Param("userId") String userId);

    List<Task> findByTraceId(@Param("traceId") String traceId);

    List<Task> findByTraceIdAndUserId(@Param("traceId") String traceId, @Param("userId") String userId);

    List<TaskStatusCount> countByStatusForTrace(@Param("traceId") String traceId, @Param("userId") String userId);

    List<Task> findRecentByTraceIdAndUserId(@Param("traceId") String traceId, @Param("userId") String userId,
            @Param("limit") int limit);

    List<Task> findByStatus(@Param("status") String status);

    List<Task> findByStatusAndUserId(@Param("status") String status, @Param("userId") String userId);

    List<Task> findAll();

    void deleteByTraceIdAndUserId(@Param("traceId") String traceId, @Param("userId") String userId);
}
