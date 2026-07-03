package com.agentcrossing.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.TestAgentRegistries;
import com.agentcrossing.platform.api.controller.TaskController;
import com.agentcrossing.platform.api.dto.SubmitTaskRequest;
import com.agentcrossing.platform.api.dto.TaskResponse;
import com.agentcrossing.platform.application.chat.ChatEventService;
import com.agentcrossing.platform.application.invocation.AgentExecutionResult;
import com.agentcrossing.platform.application.invocation.AgentMessage;
import com.agentcrossing.platform.application.invocation.AgentMessageType;
import com.agentcrossing.platform.application.invocation.AgentRuntimeClient;
import com.agentcrossing.platform.application.invocation.InvocationService;
import com.agentcrossing.platform.application.parser.ParsedTask;
import com.agentcrossing.platform.application.parser.QuestParserClient;
import com.agentcrossing.platform.application.parser.QuestParserService;
import com.agentcrossing.platform.application.realtime.SocketManager;
import com.agentcrossing.platform.application.routing.LoopGuardService;
import com.agentcrossing.platform.application.routing.ParallelTaskWorker;
import com.agentcrossing.platform.application.routing.QuestRouterService;
import com.agentcrossing.platform.application.task.TaskEventService;
import com.agentcrossing.platform.domain.agent.Agent;
import com.agentcrossing.platform.domain.agent.AgentRegistry;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.event.InMemoryEventLogRepository;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.task.InMemoryTaskDependencyRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class IntegrationFlowTests {
    private final InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
    private final InMemoryInvocationRepository invocationRepository = new InMemoryInvocationRepository();
    private final InMemoryTaskDependencyRepository taskDependencyRepository = new InMemoryTaskDependencyRepository();
    private final QuestHub questHub = new QuestHub();
    private final FakeParserClient parserClient = new FakeParserClient();
    private final AgentRegistry agentRegistry = TestAgentRegistries.withDefaultAgent();
    private final QuestParserService parserService = new QuestParserService(
            parserClient,
            agentRegistry,
            taskRepository,
            taskDependencyRepository,
            questHub,
            new LoopGuardService(taskRepository));
    private final InvocationService invocationService = new InvocationService(
            invocationRepository,
            taskRepository,
            new FakeRuntimeClient(),
            parserService,
            "http://127.0.0.1:8080/api/callback",
            com.agentcrossing.platform.application.routing.TaskDispatchSignal.NOOP,
            null,
            null,
            null,
            null,
            null,
            null,
            taskDependencyRepository,
            null,
            null);
    private final Executor directExecutor = Runnable::run;
    private final QuestRouterService routerService = new QuestRouterService(
            questHub,
            taskRepository,
            taskDependencyRepository,
            invocationRepository,
            new ParallelTaskWorker(invocationService, directExecutor),
            org.mockito.Mockito.mock(TaskEventService.class));
    private final TaskController taskController = new TaskController(
            parserService, taskRepository, taskDependencyRepository, questHub);

    @Test
    void runsUserInputToInvocationWithoutParsingNaturalLanguageFollowUpTasks() {
        List<TaskResponse> submitted = taskController.submit("anonymous", new SubmitTaskRequest("please start")).data();

        assertThat(submitted).extracting(TaskResponse::taskId).containsExactly("task-root");
        assertThat(questHub.snapshot()).containsExactly("task-root");

        Task processed = routerService.processNext().orElseThrow();

        assertThat(processed.taskId()).isEqualTo("task-root");
        assertThat(taskRepository.findByTaskId("task-root").orElseThrow().status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(invocationRepository.findAll())
                .extracting(Invocation::status)
                .containsExactly(InvocationStatus.SUCCEEDED);
        assertThat(questHub.snapshot()).isEmpty();
        assertThat(taskRepository.findByTaskId("task-follow-up")).isEmpty();
    }

    @Test
    void userInputCanCreateDagTasksAndRouterExecutesReadyNodeFirst() {
        List<TaskResponse> submitted = taskController.submit("anonymous", new SubmitTaskRequest("serial")).data();

        assertThat(submitted).extracting(TaskResponse::taskId).containsExactly("task-a", "task-b");

        Task processed = routerService.processNext().orElseThrow();

        assertThat(processed.taskId()).isEqualTo("task-a");
        assertThat(taskRepository.findByTaskId("task-a").orElseThrow().status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(questHub.snapshot()).containsExactly("task-b");
    }

    @Test
    void userInputDropsUnknownAgentTasks() {
        List<TaskResponse> submitted = taskController.submit("anonymous", new SubmitTaskRequest("unknown")).data();

        assertThat(submitted).isEmpty();
        assertThat(questHub.snapshot()).isEmpty();
        assertThat(taskRepository.findAll()).isEmpty();
    }

    @Test
    void appendedAgentTasksDeeperThanTenAreDropped() {
        Instant now = Instant.now();
        Task sourceTask = new Task(
                "deep-source",
                "anonymous",
                "trace-deep",
                null,
                TaskStatus.COMPLETED,
                TaskSource.USER,
                10,
                "opencode",
                "source",
                now,
                now);
        taskRepository.save(sourceTask);

        List<Task> created = parserService.appendAgentTasks(
                sourceTask,
                List.of(new ParsedTask("task-follow-up", "opencode", "too deep", List.of())));

        assertThat(created).isEmpty();
        assertThat(questHub.snapshot()).isEmpty();
        assertThat(taskRepository.findByTaskId("task-follow-up")).isEmpty();
    }

    @Test
    void taskEventsAppearInQueuedProcessingCompletedOrder() {
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        ChatEventService chatEventService =
                new ChatEventService(new SocketManager(new ObjectMapper(), eventLogRepository), eventLogRepository);
        TaskEventService taskEventService = new TaskEventService(
                threadRepository, chatEventService, taskDependencyRepository);
        QuestParserService parserService = new QuestParserService(
                parserClient,
                agentRegistry,
                taskRepository,
                taskDependencyRepository,
                questHub,
                new LoopGuardService(taskRepository),
                com.agentcrossing.platform.application.routing.TaskDispatchSignal.NOOP,
                taskEventService);
        InvocationService invocationService = new InvocationService(
                invocationRepository,
                taskRepository,
                new FakeRuntimeClient(),
                parserService,
                "http://127.0.0.1:8080/api/callback",
                com.agentcrossing.platform.application.routing.TaskDispatchSignal.NOOP,
                null,
                threadRepository,
                null,
                chatEventService,
                taskEventService,
                null,
                taskDependencyRepository,
                null,
                null);
        QuestRouterService routerService = new QuestRouterService(
                questHub,
                taskRepository,
                taskDependencyRepository,
                invocationRepository,
                new ParallelTaskWorker(invocationService, Runnable::run),
                taskEventService);
        threadRepository.save(new ChatThread(
                "thread-task-events",
                "anonymous",
                "Task events",
                ChatThreadStatus.RUNNING,
                "trace-task-events",
                Instant.now(),
                Instant.now()));

        parserService.parseUserInputAndEnqueue("anonymous", "please start", "trace-task-events");
        routerService.processNext().orElseThrow();

        assertThat(eventLogRepository.findAfter("thread-task-events", 0L, 20))
                .filteredOn(event -> "task".equals(event.type()))
                .extracting(event -> ((com.agentcrossing.platform.api.dto.TaskResponse) event.payload()).status())
                .containsSubsequence("queued", "processing", "completed");
    }

    private static final class FakeParserClient implements QuestParserClient {
        @Override
        public com.agentcrossing.platform.application.parser.UserInputParseResult parseUserInput(
                String input, List<Agent> availableAgents) {
            if ("serial".equals(input)) {
                return new com.agentcrossing.platform.application.parser.UserInputParseResult(List.of(
                        new ParsedTask("task-a", "opencode", "first", List.of()),
                        new ParsedTask("task-b", "opencode", "second", List.of("task-a"))), null);
            }
            if ("unknown".equals(input)) {
                return new com.agentcrossing.platform.application.parser.UserInputParseResult(
                        List.of(new ParsedTask("task-unknown", "missing-agent", input, List.of())), null);
            }
            return new com.agentcrossing.platform.application.parser.UserInputParseResult(
                    List.of(new ParsedTask("task-root", "opencode", input, List.of())), null);
        }

    }

    private static final class FakeRuntimeClient implements AgentRuntimeClient {
        @Override
        public AgentExecutionResult execute(com.agentcrossing.platform.application.invocation.AgentExecutionRequest request) {
            return new AgentExecutionResult(List.of(new AgentMessage(
                    request.invocationId(),
                    request.taskId(),
                    request.traceId(),
                    request.agentId(),
                    AgentMessageType.MESSAGE,
                    "@opencode follow up",
                    null,
                    Instant.now())));
        }
    }
}
