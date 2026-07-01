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
import org.springframework.stereotype.Service;

@Service
public class QuestRouterService {
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
        Acquisition acquisition = acquireNextDispatchTask();
        acquisition.blockedTasks().forEach(taskEventService::publish);
        if (acquisition.dispatchTask().isEmpty()) {
            return Optional.empty();
        }

        Task task = acquisition.dispatchTask().get();
        taskEventService.publish(task);
        parallelTaskWorker.submit(task);
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
}
