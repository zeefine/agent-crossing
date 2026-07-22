package com.agentcrossing.platform.api.controller;

import com.agentcrossing.platform.api.dto.ApiResponse;
import com.agentcrossing.platform.api.dto.AppendTaskRequest;
import com.agentcrossing.platform.api.dto.QueueSnapshotResponse;
import com.agentcrossing.platform.api.dto.SubmitTaskRequest;
import com.agentcrossing.platform.api.dto.TaskResponse;
import com.agentcrossing.platform.application.parser.ParsedTask;
import com.agentcrossing.platform.application.auth.CurrentUserResolver;
import com.agentcrossing.platform.application.parser.QuestParserService;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskDependencyRepository;
import com.agentcrossing.platform.domain.task.TaskRepository;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TaskController {
    private final QuestParserService questParserService;
    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final QuestHub questHub;
    private final CurrentUserResolver currentUserResolver;

    public TaskController(
            QuestParserService questParserService,
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            QuestHub questHub) {
        this(questParserService, taskRepository, taskDependencyRepository, questHub, new CurrentUserResolver());
    }

    @Autowired
    public TaskController(
            QuestParserService questParserService,
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            QuestHub questHub,
            CurrentUserResolver currentUserResolver) {
        this.questParserService = questParserService;
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.questHub = questHub;
        this.currentUserResolver = currentUserResolver;
    }

    @PostMapping("/tasks/submit")
    public ApiResponse<List<TaskResponse>> submit(
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = CurrentUserResolver.USER_ID_HEADER,
                            required = false)
                    String userId,
            @Valid @RequestBody SubmitTaskRequest request) {
        List<Task> tasks = questParserService.parseUserInputAndEnqueue(
                currentUserResolver.fromHeader(userId).userId(),
                request.input(),
                "trace-" + java.util.UUID.randomUUID());
        return ApiResponse.ok(tasks.stream().map(this::toResponse).toList());
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<TaskResponse> getTask(
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = CurrentUserResolver.USER_ID_HEADER,
                            required = false)
                    String userId,
            @PathVariable String taskId) {
        Task task = taskRepository.findByTaskIdAndUserId(taskId, currentUserResolver.fromHeader(userId).userId())
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        return ApiResponse.ok(toResponse(task));
    }

    @PostMapping("/tasks/{sourceTaskId}/append")
    public ApiResponse<List<TaskResponse>> appendTasks(
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = CurrentUserResolver.USER_ID_HEADER,
                            required = false)
                    String userId,
            @PathVariable String sourceTaskId,
            @Valid @RequestBody AppendTaskRequest request) {
        String currentUserId = currentUserResolver.fromHeader(userId).userId();
        Task sourceTask = taskRepository.findByTaskIdAndUserId(sourceTaskId, currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + sourceTaskId));
        List<ParsedTask> plannedTasks = request.tasks().stream()
                .map(task -> new ParsedTask(task.taskId(), task.agentId(), task.context(), task.dependsOn()))
                .toList();
        return ApiResponse.ok(questParserService.appendAgentTasks(sourceTask, plannedTasks, request.idempotencyKey()).stream()
                .map(this::toResponse)
                .toList());
    }

    @GetMapping("/tasks")
    public ApiResponse<List<TaskResponse>> getTasks(
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = CurrentUserResolver.USER_ID_HEADER,
                            required = false)
                    String userId,
            @RequestParam String traceId) {
        return ApiResponse.ok(taskRepository.findByTraceIdAndUserId(
                        traceId, currentUserResolver.fromHeader(userId).userId()).stream()
                .map(this::toResponse)
                .toList());
    }

    @GetMapping("/tasks/{taskId}/downstream")
    public ApiResponse<List<TaskResponse>> getDirectDownstreamTasks(
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = CurrentUserResolver.USER_ID_HEADER,
                            required = false)
                    String userId,
            @PathVariable String taskId) {
        String currentUserId = currentUserResolver.fromHeader(userId).userId();
        taskRepository.findByTaskIdAndUserId(taskId, currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        List<TaskResponse> downstreamTasks = taskDependencyRepository.findChildTaskIds(taskId).stream()
                .map(childTaskId -> taskRepository.findByTaskIdAndUserId(childTaskId, currentUserId))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(Task::createdAt))
                .map(this::toResponse)
                .toList();
        return ApiResponse.ok(downstreamTasks);
    }

    @GetMapping("/queues")
    public ApiResponse<QueueSnapshotResponse> getQueues(
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = CurrentUserResolver.USER_ID_HEADER,
                            required = false)
                    String userId) {
        String currentUserId = currentUserResolver.fromHeader(userId).userId();
        return ApiResponse.ok(new QueueSnapshotResponse(questHub.snapshot().stream()
                .map(taskRepository::findByTaskId)
                .flatMap(Optional::stream)
                .filter(task -> task.userId().equals(currentUserId))
                .map(this::toResponse)
                .toList()));
    }

    private TaskResponse toResponse(Task task) {
        return TaskResponse.from(task, taskDependencyRepository.findParentTaskIds(task.taskId()));
    }
}
