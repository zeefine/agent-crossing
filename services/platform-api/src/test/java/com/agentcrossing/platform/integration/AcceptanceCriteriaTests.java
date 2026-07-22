package com.agentcrossing.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agentcrossing.platform.AgentCrossingApplication;
import com.agentcrossing.platform.TestAgentRegistries;
import com.agentcrossing.platform.api.controller.TaskController;
import com.agentcrossing.platform.api.dto.SubmitTaskRequest;
import com.agentcrossing.platform.application.invocation.AgentExecutionRequest;
import com.agentcrossing.platform.application.invocation.AgentExecutionResult;
import com.agentcrossing.platform.application.invocation.AgentMessage;
import com.agentcrossing.platform.application.invocation.AgentMessageType;
import com.agentcrossing.platform.application.invocation.AgentRuntimeClient;
import com.agentcrossing.platform.application.invocation.InvocationService;
import com.agentcrossing.platform.application.parser.ParsedTask;
import com.agentcrossing.platform.application.parser.QuestParserClient;
import com.agentcrossing.platform.application.parser.QuestParserService;
import com.agentcrossing.platform.application.routing.LoopGuardService;
import com.agentcrossing.platform.application.routing.ParallelTaskWorker;
import com.agentcrossing.platform.application.routing.QuestRouterService;
import com.agentcrossing.platform.application.task.TaskEventService;
import com.agentcrossing.platform.domain.agent.Agent;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.task.InMemoryTaskDependencyRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

class AcceptanceCriteriaTests {
    @Test
    void springBootApplicationContextCanStart() {
        try (ConfigurableApplicationContext context = SpringApplication.run(
                AgentCrossingApplication.class,
                "--server.port=0",
                "--spring.main.web-application-type=servlet")) {
            assertThat(context.isActive()).isTrue();
        }
    }

    @Test
    void v1FlowRunsInMemoryWithoutParsingNaturalLanguageFollowUpTasks() {
        TestHarness harness = new TestHarness(new FlowParserClient(), new StaticRuntimeClient("@opencode follow up"));

        List<String> submittedTaskIds = harness.taskController.submit("anonymous", new SubmitTaskRequest("start")).data().stream()
                .map(response -> response.taskId())
                .toList();

        assertThat(submittedTaskIds).containsExactly("task-root");
        assertThat(harness.questHub.snapshot()).containsExactly("task-root");

        Task processed = harness.routerService.processNext().orElseThrow();

        assertThat(processed.taskId()).isEqualTo("task-root");
        assertThat(harness.taskRepository.findByTaskId("task-root").orElseThrow().status())
                .isEqualTo(TaskStatus.COMPLETED);
        assertThat(harness.invocationRepository.findAll())
                .extracting(Invocation::status)
                .containsExactly(InvocationStatus.SUCCEEDED);
        assertThat(harness.questHub.snapshot()).isEmpty();
        assertThat(harness.taskRepository.findByTaskId("task-follow-up")).isEmpty();
    }

    @Test
    void parallelTasksCanExecuteConcurrentlyByAgentWorker() throws Exception {
        BlockingRuntimeClient runtimeClient = new BlockingRuntimeClient(2);
        TestHarness harness = new TestHarness(new FlowParserClient(), runtimeClient, Executors.newFixedThreadPool(2));
        Task first = task("parallel-a", 0);
        Task second = task("parallel-b", 0, "claude-code");
        harness.taskRepository.save(first);
        harness.taskRepository.save(second);
        harness.questHub.enqueue(first.taskId());
        harness.questHub.enqueue(second.taskId());

        harness.routerService.processNext();
        harness.routerService.processNext();

        assertThat(runtimeClient.awaitBothStarted()).isTrue();
        runtimeClient.release();
        assertThat(runtimeClient.awaitDone()).isTrue();
        assertThat(runtimeClient.maxConcurrent()).isGreaterThanOrEqualTo(2);
        assertThat(harness.taskRepository.findByTaskId("parallel-a").orElseThrow().status())
                .isEqualTo(TaskStatus.COMPLETED);
        assertThat(harness.taskRepository.findByTaskId("parallel-b").orElseThrow().status())
                .isEqualTo(TaskStatus.COMPLETED);

        harness.shutdownExecutor();
    }

    @Test
    void dagTasksExecuteAfterTheirDependenciesComplete() {
        RecordingRuntimeClient runtimeClient = new RecordingRuntimeClient();
        TestHarness harness = new TestHarness(new FlowParserClient(), runtimeClient);

        harness.taskController.submit("anonymous", new SubmitTaskRequest("serial"));
        Task first = harness.routerService.processNext().orElseThrow();
        Task second = harness.routerService.processNext().orElseThrow();

        assertThat(first.taskId()).isEqualTo("task-a");
        assertThat(second.taskId()).isEqualTo("task-b");
        assertThat(runtimeClient.executedTaskIds()).containsExactly("task-a", "task-b");
    }

    @Test
    void depthAndSelfTriggerGuardsDropTasksBeyondLimits() {
        TestHarness harness = new TestHarness(new FlowParserClient(), new StaticRuntimeClient(""));
        Task root = task("root", 0);
        Task depthTen = task("depth-ten", 10);
        harness.taskRepository.save(root);
        harness.taskRepository.save(depthTen);

        assertThat(harness.parserService.appendAgentTasks(
                depthTen,
                List.of(new ParsedTask("too-deep", "opencode", "too deep", List.of()))))
                .isEmpty();

        for (int index = 0; index < 9; index++) {
            harness.taskRepository.save(task("self-" + index, index + 1));
        }
        assertThat(harness.parserService.appendAgentTasks(
                root,
                List.of(new ParsedTask("too-many-self-triggers", "opencode", "too many self triggers", List.of()))))
                .isEmpty();
    }

    private static Task task(String taskId, int depth) {
        return task(taskId, depth, "opencode");
    }

    private static Task task(String taskId, int depth, String agentId) {
        Instant now = Instant.now();
        return new Task(
                taskId,
                "anonymous",
                "trace-acceptance",
                depth == 0 ? null : "root",
                TaskStatus.QUEUED,
                TaskSource.USER,
                depth,
                agentId,
                "context",
                now,
                now);
    }

    private static final class TestHarness {
        private final InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
        private final InMemoryInvocationRepository invocationRepository = new InMemoryInvocationRepository();
        private final InMemoryTaskDependencyRepository taskDependencyRepository = new InMemoryTaskDependencyRepository();
        private final QuestHub questHub = new QuestHub();
        private final QuestParserService parserService;
        private final QuestRouterService routerService;
        private final TaskController taskController;
        private final ExecutorService executorService;

        private TestHarness(QuestParserClient parserClient, AgentRuntimeClient runtimeClient) {
            this(parserClient, runtimeClient, Runnable::run, null);
        }

        private TestHarness(QuestParserClient parserClient, AgentRuntimeClient runtimeClient, ExecutorService executorService) {
            this(parserClient, runtimeClient, executorService, executorService);
        }

        private TestHarness(
                QuestParserClient parserClient,
                AgentRuntimeClient runtimeClient,
                java.util.concurrent.Executor executor,
                ExecutorService executorService) {
            this.executorService = executorService;
            this.parserService = new QuestParserService(
                    parserClient,
                    TestAgentRegistries.withDefaultAgent(),
                    taskRepository,
                    taskDependencyRepository,
                    questHub,
                    new LoopGuardService(taskRepository));
            InvocationService invocationService = new InvocationService(
                    invocationRepository,
                    taskRepository,
                    runtimeClient,
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
            this.routerService = new QuestRouterService(
                    questHub,
                    taskRepository,
                    taskDependencyRepository,
                    invocationRepository,
                    new ParallelTaskWorker(invocationService, executor),
                    mock(TaskEventService.class));
            this.taskController = new TaskController(
                    parserService, taskRepository, taskDependencyRepository, questHub);
        }

        private void shutdownExecutor() {
            if (executorService != null) {
                executorService.shutdownNow();
            }
        }
    }

    private static final class FlowParserClient implements QuestParserClient {
        @Override
        public com.agentcrossing.platform.application.parser.UserInputParseResult parseUserInput(
                String input, List<Agent> availableAgents) {
            if ("serial".equals(input)) {
                return new com.agentcrossing.platform.application.parser.UserInputParseResult(List.of(
                        new ParsedTask("task-a", "opencode", "first", List.of()),
                        new ParsedTask("task-b", "opencode", "second", List.of("task-a"))), null);
            }
            return new com.agentcrossing.platform.application.parser.UserInputParseResult(
                    List.of(new ParsedTask("task-root", "opencode", input, List.of())), null);
        }

    }

    private static final class StaticRuntimeClient implements AgentRuntimeClient {
        private final String output;

        private StaticRuntimeClient(String output) {
            this.output = output;
        }

        @Override
        public AgentExecutionResult execute(AgentExecutionRequest request) {
            return result(request, output);
        }
    }

    private static final class RecordingRuntimeClient implements AgentRuntimeClient {
        private final java.util.ArrayList<String> executedTaskIds = new java.util.ArrayList<>();

        @Override
        public AgentExecutionResult execute(AgentExecutionRequest request) {
            executedTaskIds.add(request.taskId());
            return result(request, "");
        }

        private List<String> executedTaskIds() {
            return executedTaskIds;
        }
    }

    private static final class BlockingRuntimeClient implements AgentRuntimeClient {
        private final CountDownLatch bothStarted;
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch done;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();

        private BlockingRuntimeClient(int expectedTasks) {
            this.bothStarted = new CountDownLatch(expectedTasks);
            this.done = new CountDownLatch(expectedTasks);
        }

        @Override
        public AgentExecutionResult execute(AgentExecutionRequest request) {
            int current = active.incrementAndGet();
            maxConcurrent.accumulateAndGet(current, Math::max);
            bothStarted.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
                return result(request, "");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(exception);
            } finally {
                active.decrementAndGet();
                done.countDown();
            }
        }

        private boolean awaitBothStarted() throws InterruptedException {
            return bothStarted.await(2, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }

        private boolean awaitDone() throws InterruptedException {
            return done.await(2, TimeUnit.SECONDS);
        }

        private int maxConcurrent() {
            return maxConcurrent.get();
        }
    }

    private static AgentExecutionResult result(AgentExecutionRequest request, String content) {
        return new AgentExecutionResult(List.of(new AgentMessage(
                request.invocationId(),
                request.taskId(),
                request.traceId(),
                request.agentId(),
                AgentMessageType.MESSAGE,
                content,
                null,
                Instant.now())));
    }
}
