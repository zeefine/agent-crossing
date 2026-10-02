package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskRepository;
import com.agentcrossing.platform.domain.task.TaskStatus;
import com.agentcrossing.platform.domain.task.TaskStatusCount;
import com.agentcrossing.platform.domain.task.TaskDispatchSnapshot;
import com.agentcrossing.platform.domain.task.TaskDispatchSnapshotRepository;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.TaskMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "mysql")
public class MybatisTaskRepository implements TaskRepository, TaskDispatchSnapshotRepository {
    private final TaskMapper taskMapper;

    public MybatisTaskRepository(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Override
    public List<TaskDispatchSnapshot> findDispatchSnapshots(List<String> taskIds) {
        return taskIds.isEmpty() ? List.of() : taskMapper.findDispatchSnapshots(taskIds);
    }

    @Override
    public Task save(Task task) {
        taskMapper.upsert(task);
        return task;
    }

    @Override
    public Optional<Task> findByTaskId(String taskId) {
        return Optional.ofNullable(taskMapper.findByTaskId(taskId));
    }

    @Override
    public Optional<Task> findByTaskIdAndUserId(String taskId, String userId) {
        return Optional.ofNullable(taskMapper.findByTaskIdAndUserId(taskId, userId));
    }

    @Override
    public List<Task> findByTraceId(String traceId) {
        return taskMapper.findByTraceId(traceId);
    }

    @Override
    public List<Task> findByTraceIdAndUserId(String traceId, String userId) {
        return taskMapper.findByTraceIdAndUserId(traceId, userId);
    }

    @Override
    public List<TaskStatusCount> countByStatusForTrace(String traceId, String userId) {
        return taskMapper.countByStatusForTrace(traceId, userId);
    }

    @Override
    public List<Task> findRecentByTraceIdAndUserId(String traceId, String userId, int limit) {
        return limit <= 0 ? List.of() : taskMapper.findRecentByTraceIdAndUserId(traceId, userId, limit);
    }

    @Override
    public List<Task> findByStatus(TaskStatus status) {
        return taskMapper.findByStatus(status.name());
    }

    @Override
    public List<Task> findByStatusAndUserId(TaskStatus status, String userId) {
        return taskMapper.findByStatusAndUserId(status.name(), userId);
    }

    @Override
    public Task updateStatus(String taskId, TaskStatus status) {
        Task existing = findByTaskId(taskId).orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (existing.status() != TaskStatus.CANCELED) {
            updateStatusIfCurrent(taskId, Set.of(existing.status()), status);
        }
        return findByTaskId(taskId).orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    @Override
    public boolean updateStatusIfCurrent(String taskId, Set<TaskStatus> expected, TaskStatus status) {
        if (expected.isEmpty()) {
            return false;
        }
        return taskMapper.updateStatusIfCurrent(taskId,
                expected.stream().map(Enum::name).collect(Collectors.toSet()), status.name(), Instant.now()) == 1;
    }

    @Override
    public void deleteByTraceIdAndUserId(String traceId, String userId) {
        taskMapper.deleteByTraceIdAndUserId(traceId, userId);
    }

    @Override
    public List<Task> findAll() {
        return taskMapper.findAll();
    }
}
