package com.agentcrossing.platform.domain.task;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface TaskRepository {
    Task save(Task task);

    Optional<Task> findByTaskId(String taskId);

    Optional<Task> findByTaskIdAndUserId(String taskId, String userId);

    List<Task> findByTraceId(String traceId);

    List<Task> findByTraceIdAndUserId(String traceId, String userId);

    List<TaskStatusCount> countByStatusForTrace(String traceId, String userId);

    /** updatedAt DESC, createdAt ASC, taskId ASC; nonpositive limits return no rows. */
    List<Task> findRecentByTraceIdAndUserId(String traceId, String userId, int limit);

    List<Task> findByStatus(TaskStatus status);

    List<Task> findByStatusAndUserId(TaskStatus status, String userId);

    Task updateStatus(String taskId, TaskStatus status);

    /** Atomic comparison; false means missing row or a status outside expected. Never inserts. */
    boolean updateStatusIfCurrent(String taskId, Set<TaskStatus> expected, TaskStatus status);

    void deleteByTraceIdAndUserId(String traceId, String userId);

    List<Task> findAll();
}
