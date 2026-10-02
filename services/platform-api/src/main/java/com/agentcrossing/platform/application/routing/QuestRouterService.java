package com.agentcrossing.platform.application.routing;

import com.agentcrossing.platform.application.task.TaskEventService;
import com.agentcrossing.platform.application.invocation.AgentSessionCompressionService;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskDependencyRepository;
import com.agentcrossing.platform.domain.task.TaskRepository;
import com.agentcrossing.platform.domain.task.TaskStatus;
import com.agentcrossing.platform.domain.task.TaskDispatchSnapshotRepository;
import com.agentcrossing.platform.domain.task.TaskDispatchSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class QuestRouterService {
    private static final Logger log = LoggerFactory.getLogger(QuestRouterService.class);
    private static final int SNAPSHOT_BATCH_SIZE = 128;
    private final Object routingLock = new Object();
    private final QuestHub questHub;
    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final InvocationRepository invocationRepository;
    private final ParallelTaskWorker parallelTaskWorker;
    private final TaskEventService taskEventService;
    private final AgentSessionCompressionService sessionCompressionService;
    private TaskDispatchSnapshotRepository dispatchSnapshotRepository;

    @Autowired(required = false)
    void setDispatchSnapshotRepository(TaskDispatchSnapshotRepository repository) {
        this.dispatchSnapshotRepository = repository;
    }

    public QuestRouterService(
            QuestHub questHub,
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            InvocationRepository invocationRepository,
            ParallelTaskWorker parallelTaskWorker,
            TaskEventService taskEventService) {
        this(questHub, taskRepository, taskDependencyRepository, invocationRepository,
                parallelTaskWorker, taskEventService, null);
    }

    @Autowired
    public QuestRouterService(
            QuestHub questHub,
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            InvocationRepository invocationRepository,
            ParallelTaskWorker parallelTaskWorker,
            TaskEventService taskEventService,
            AgentSessionCompressionService sessionCompressionService) {
        this.questHub = questHub;
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.invocationRepository = invocationRepository;
        this.parallelTaskWorker = parallelTaskWorker;
        this.taskEventService = taskEventService;
        this.sessionCompressionService = sessionCompressionService;
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
                List<String> queuedIds = questHub.snapshot();
                for (int offset = 0; offset < queuedIds.size(); offset += SNAPSHOT_BATCH_SIZE) {
                    List<String> batch = queuedIds.subList(offset, Math.min(offset + SNAPSHOT_BATCH_SIZE, queuedIds.size()));
                    Map<String, TaskDispatchSnapshot> snapshots = loadDispatchSnapshots(batch);
                    // Preserve queue order; storage results are intentionally unordered.
                    for (String taskId : batch) {
                        TaskDispatchSnapshot snapshot = snapshots.get(taskId);
                        if (snapshot == null) {
                            questHub.remove(taskId);
                            changed = true;
                            continue;
                        }
                        if (snapshot.dependencyBlocked()) {
                            if (taskRepository.updateStatusIfCurrent(taskId, Set.of(TaskStatus.QUEUED), TaskStatus.BLOCKED)) {
                                taskRepository.findByTaskId(taskId).ifPresent(blockedTasks::add);
                            }
                            questHub.remove(taskId);
                            changed = true;
                            continue;
                        }
                        if (snapshot.dependencyWaiting() || snapshot.agentBusy() || snapshot.sessionCompacting()) {
                            continue;
                        }
                        // A snapshot is advice, not a claim: cancellation/deletion may have won since it was read.
                        boolean reserved = taskRepository.updateStatusIfCurrent(taskId, Set.of(TaskStatus.QUEUED), TaskStatus.PROCESSING);
                        questHub.remove(taskId);
                        changed = true;
                        if (reserved) {
                            Optional<Task> task = taskRepository.findByTaskId(taskId)
                                    .filter(current -> current.status() == TaskStatus.PROCESSING);
                            if (task.isPresent()) {
                                return new Acquisition(task, blockedTasks);
                            }
                        }
                    }
                }
            } while (changed);
            return new Acquisition(Optional.empty(), blockedTasks);
        }
    }

    private Map<String, TaskDispatchSnapshot> loadDispatchSnapshots(List<String> taskIds) {
        Map<String, TaskDispatchSnapshot> result = new HashMap<>();
        if (dispatchSnapshotRepository != null) {
            dispatchSnapshotRepository.findDispatchSnapshots(taskIds).forEach(snapshot -> result.put(snapshot.taskId(), snapshot));
            return result;
        }
        // Memory storage has no SQL round trips; retain the same predicates for that implementation.
        for (String taskId : taskIds) {
            findQueuedLatestTask(taskId).ifPresent(task -> {
                DependencyState dependency = dependencyState(task);
                result.put(taskId, new TaskDispatchSnapshot(taskId, dependency == DependencyState.BLOCKED,
                        dependency == DependencyState.WAITING, isAgentRunning(task),
                        sessionCompressionService != null && sessionCompressionService.isCompacting(task)));
            });
        }
        return result;
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
