package com.agentcrossing.platform.domain.task;

import java.util.List;
import java.util.Optional;

public interface TaskRepository {
    Task save(Task task);

    Optional<Task> findByTaskId(String taskId);

    Optional<Task> findByTaskIdAndUserId(String taskId, String userId);

    List<Task> findByTraceId(String traceId);

    List<Task> findByTraceIdAndUserId(String traceId, String userId);

    List<Task> findByStatus(TaskStatus status);

    List<Task> findByStatusAndUserId(TaskStatus status, String userId);

    Task updateStatus(String taskId, TaskStatus status);

    void deleteByTraceIdAndUserId(String traceId, String userId);

    List<Task> findAll();
}
