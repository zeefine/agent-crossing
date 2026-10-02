package com.agentcrossing.platform.domain.task;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryTaskRepository implements TaskRepository {
    private final ConcurrentMap<String, Task> tasks = new ConcurrentHashMap<>();

    @Override
    public Task save(Task task) {
        tasks.put(task.taskId(), task);
        return task;
    }

    @Override
    public Optional<Task> findByTaskId(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public Optional<Task> findByTaskIdAndUserId(String taskId, String userId) {
        return findByTaskId(taskId).filter(task -> task.userId().equals(userId));
    }

    @Override
    public List<Task> findByTraceId(String traceId) {
        return tasks.values().stream()
                .filter(task -> task.traceId().equals(traceId))
                .sorted(Comparator.comparing(Task::createdAt).thenComparing(Task::taskId))
                .toList();
    }

    @Override
    public List<Task> findByTraceIdAndUserId(String traceId, String userId) {
        return tasks.values().stream()
                .filter(task -> task.traceId().equals(traceId) && task.userId().equals(userId))
                .sorted(Comparator.comparing(Task::createdAt).thenComparing(Task::taskId))
                .toList();
    }

    @Override
    public List<TaskStatusCount> countByStatusForTrace(String traceId, String userId) {
        var counts = tasks.values().stream()
                .filter(task -> task.traceId().equals(traceId) && task.userId().equals(userId))
                .collect(java.util.stream.Collectors.groupingBy(Task::status, java.util.stream.Collectors.counting()));
        return counts.entrySet().stream().map(entry -> new TaskStatusCount(entry.getKey(), entry.getValue())).toList();
    }

    @Override
    public List<Task> findRecentByTraceIdAndUserId(String traceId, String userId, int limit) {
        return tasks.values().stream()
                .filter(task -> task.traceId().equals(traceId) && task.userId().equals(userId))
                .sorted(Comparator.comparing(Task::updatedAt).reversed()
                        .thenComparing(Task::createdAt).thenComparing(Task::taskId))
                .limit(Math.max(0, limit)).toList();
    }

    @Override
    public List<Task> findByStatus(TaskStatus status) {
        return tasks.values().stream()
                .filter(task -> task.status() == status)
                .sorted(Comparator.comparing(Task::createdAt).thenComparing(Task::taskId))
                .toList();
    }

    @Override
    public List<Task> findByStatusAndUserId(TaskStatus status, String userId) {
        return tasks.values().stream()
                .filter(task -> task.status() == status && task.userId().equals(userId))
                .sorted(Comparator.comparing(Task::createdAt).thenComparing(Task::taskId))
                .toList();
    }

    @Override
    public Task updateStatus(String taskId, TaskStatus status) {
        return tasks.compute(taskId, (ignored, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Task not found: " + taskId);
            }
            return existing.status() == TaskStatus.CANCELED ? existing : existing.withStatus(status);
        });
    }

    @Override
    public boolean updateStatusIfCurrent(String taskId, Set<TaskStatus> expected, TaskStatus status) {
        AtomicBoolean changed = new AtomicBoolean();
        tasks.computeIfPresent(taskId, (ignored, existing) -> {
            if (!expected.contains(existing.status())) {
                return existing;
            }
            changed.set(true);
            return existing.withStatus(status);
        });
        return changed.get();
    }

    @Override
    public void deleteByTraceIdAndUserId(String traceId, String userId) {
        tasks.values().removeIf(task -> task.traceId().equals(traceId) && task.userId().equals(userId));
    }

    @Override
    public List<Task> findAll() {
        return tasks.values().stream()
                .sorted(Comparator.comparing(Task::createdAt).thenComparing(Task::taskId))
                .toList();
    }
}
