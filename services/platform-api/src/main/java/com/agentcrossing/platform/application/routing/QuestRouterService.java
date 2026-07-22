package com.agentcrossing.platform.application.routing;

import com.agentcrossing.platform.application.task.TaskEventService;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskDependencyRepository;
import com.agentcrossing.platform.domain.task.TaskRepository;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class QuestRouterService {
    private static final Logger log = LoggerFactory.getLogger(QuestRouterService.class);
    private final Object routingLock = new Object();
    private final QuestHub questHub;
    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final InvocationRepository invocationRepository;
    private final ParallelTaskWorker parallelTaskWorker;
    private final TaskEventService taskEventService;

    public QuestRouterService(
            QuestHub questHub,
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            InvocationRepository invocationRepository,
            ParallelTaskWorker parallelTaskWorker,
            TaskEventService taskEventService) {
        this.questHub = questHub;
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.invocationRepository = invocationRepository;
        this.parallelTaskWorker = parallelTaskWorker;
        this.taskEventService = taskEventService;
    }

    public Optional<Task> processNext() {
        long startedAt = System.nanoTime();
        Acquisition acquisition = acquireNextDispatchTask();
        acquisition.blockedTasks().forEach(taskEventService::publish);
        if (acquisition.dispatchTask().isEmpty()) {
            log.info(
                    "agent_crossing_perf event=router_drain_empty durationMs={} blockedTasks={}",
                    elapsedMs(startedAt),
                    acquisition.blockedTasks().size());
            return Optional.empty();
        }

        Task task = acquisition.dispatchTask().get();
        taskEventService.publish(task);
        if (!parallelTaskWorker.submit(task)) {
            Task requeuedTask = requeueRejectedDispatch(task);
            taskEventService.publish(requeuedTask);
            log.warn(
                    "agent_crossing_perf event=worker_rejected userId={} taskId={} traceId={} agentId={}",
                    task.userId(),
                    task.taskId(),
                    task.traceId(),
                    task.agentId());
            // 停止当前 drain，等待已有 worker 完成后的调度信号再重试，避免队列满时空转。
            return Optional.empty();
        }
        log.info(
                "agent_crossing_perf event=router_dispatch durationMs={} routerWaitMs={} userId={} taskId={} traceId={} agentId={} blockedTasks={}",
                elapsedMs(startedAt),
                routerWaitMs(task),
                task.userId(),
                task.taskId(),
                task.traceId(),
                task.agentId(),
                acquisition.blockedTasks().size());
        return Optional.of(task);
    }

    private Acquisition acquireNextDispatchTask() {
        synchronized (routingLock) {
            List<Task> blockedTasks = new ArrayList<>();
            boolean changed;
            do {
                changed = false;
                for (String queuedTaskId : questHub.snapshot()) {
                    Optional<Task> latestTask = findQueuedLatestTask(queuedTaskId);
                    if (latestTask.isEmpty()) {
                        questHub.remove(queuedTaskId);
                        changed = true;
                        continue;
                    }

                    Task task = latestTask.get();
                    DependencyState dependencyState = dependencyState(task);
                    if (dependencyState == DependencyState.BLOCKED) {
                        questHub.remove(task.taskId());
                        Task blocked = taskRepository.updateStatus(task.taskId(), TaskStatus.BLOCKED);
                        blockedTasks.add(blocked);
                        changed = true;
                        continue;
                    }
                    if (dependencyState == DependencyState.WAITING || isAgentRunning(task)) {
                        continue;
                    }

                    questHub.remove(task.taskId());
                    Task reserved = taskRepository.updateStatus(task.taskId(), TaskStatus.PROCESSING);
                    return new Acquisition(Optional.of(reserved), blockedTasks);
                }
            } while (changed);
            return new Acquisition(Optional.empty(), blockedTasks);
        }
    }

    private Task requeueRejectedDispatch(Task task) {
        synchronized (routingLock) {
            Task requeued = taskRepository.updateStatus(task.taskId(), TaskStatus.QUEUED);
            questHub.enqueueLast(task.taskId());
            return requeued;
        }
    }

    private Optional<Task> findQueuedLatestTask(String taskId) {
        return taskRepository.findByTaskId(taskId)
                .filter(task -> task.status() == TaskStatus.QUEUED);
    }

    private DependencyState dependencyState(Task task) {
        List<String> parentTaskIds = taskDependencyRepository.findParentTaskIds(task.taskId());
        if (parentTaskIds.isEmpty()) {
            return DependencyState.READY;
        }

        boolean waiting = false;
        for (String parentTaskId : parentTaskIds) {
            Task parent = taskRepository.findByTaskId(parentTaskId).orElse(null);
            if (parent == null
                    || parent.status() == TaskStatus.FAILED
                    || parent.status() == TaskStatus.BLOCKED
                    || parent.status() == TaskStatus.CANCELED) {
                return DependencyState.BLOCKED;
            }
            if (parent.status() != TaskStatus.COMPLETED) {
                waiting = true;
            }
        }
        return waiting ? DependencyState.WAITING : DependencyState.READY;
    }

    private boolean isAgentRunning(Task task) {
        return !invocationRepository.findRunningByAgentIdAndUserId(task.agentId(), task.userId()).isEmpty()
                || taskRepository.findByStatusAndUserId(TaskStatus.PROCESSING, task.userId()).stream()
                        .anyMatch(processingTask -> processingTask.agentId().equals(task.agentId()));
    }

    private enum DependencyState {
        READY,
        WAITING,
        BLOCKED
    }

    private record Acquisition(Optional<Task> dispatchTask, List<Task> blockedTasks) {}

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private static long routerWaitMs(Task task) {
        Instant createdAt = task.createdAt();
        if (createdAt == null) {
            return -1;
        }
        return Math.max(0, Duration.between(createdAt, Instant.now()).toMillis());
    }
}
