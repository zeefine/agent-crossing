package com.agentcrossing.platform.application.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agentcrossing.platform.TestAgentRegistries;
import com.agentcrossing.platform.application.invocation.AgentExecutionResult;
import com.agentcrossing.platform.application.invocation.AgentRuntimeClient;
import com.agentcrossing.platform.application.invocation.InvocationService;
import com.agentcrossing.platform.application.parser.QuestParserClient;
import com.agentcrossing.platform.application.parser.QuestParserService;
import com.agentcrossing.platform.application.task.TaskEventService;
import com.agentcrossing.platform.domain.agent.Agent;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.task.InMemoryTaskDependencyRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskDependency;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class QuestRouterServiceTests {
    private final InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
    private final InMemoryTaskDependencyRepository dependencyRepository = new InMemoryTaskDependencyRepository();
    private final InMemoryInvocationRepository invocationRepository = new InMemoryInvocationRepository();
    private final QuestHub questHub = new QuestHub();
    private final QuestRouterService router = router(request -> new AgentExecutionResult(List.of()), Runnable::run);

    @Test
    void dispatchesReadyTask() {
        Task task = task("task-ready", "opencode");
        enqueue(task);

        assertThat(router.processNext()).map(Task::taskId).contains("task-ready");
        assertThat(taskRepository.findByTaskId(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void waitsUntilAllDependenciesComplete() {
        Task parentA = task("task-a", "opencode");
        Task parentB = task("task-b", "claude-code");
        Task child = task("task-c", "codex");
        taskRepository.save(parentA);
        taskRepository.save(parentB);
        enqueue(child);
        dependencyRepository.saveAll(List.of(
                new TaskDependency("task-a", "task-c"),
                new TaskDependency("task-b", "task-c")));

        assertThat(router.processNext()).isEmpty();
        taskRepository.updateStatus("task-a", TaskStatus.COMPLETED);
        assertThat(router.processNext()).isEmpty();
        taskRepository.updateStatus("task-b", TaskStatus.COMPLETED);

        assertThat(router.processNext()).map(Task::taskId).contains("task-c");
    }

    @Test
    void blocksDescendantsWhenDependencyFails() {
        Task parent = task("task-parent", "opencode");
        Task child = task("task-child", "claude-code");
        Task grandchild = task("task-grandchild", "codex");
        taskRepository.save(parent);
        taskRepository.updateStatus(parent.taskId(), TaskStatus.FAILED);
        enqueue(child);
        enqueue(grandchild);
        dependencyRepository.saveAll(List.of(
                new TaskDependency(parent.taskId(), child.taskId()),
                new TaskDependency(child.taskId(), grandchild.taskId())));

        assertThat(router.processNext()).isEmpty();
        assertThat(taskRepository.findByTaskId(child.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.BLOCKED);
        assertThat(taskRepository.findByTaskId(grandchild.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.BLOCKED);
        assertThat(questHub.snapshot()).isEmpty();
    }

    @Test
    void skipsBusyAgentAndDispatchesAnotherAgent() {
        Task busyAgentTask = task("task-opencode", "opencode");
        Task idleAgentTask = task("task-claude", "claude-code");
        enqueue(busyAgentTask);
        enqueue(idleAgentTask);
        invocationRepository.save(runningInvocation("invocation-running", "other-task", "opencode"));

        assertThat(router.processNext()).map(Task::taskId).contains("task-claude");
        assertThat(questHub.snapshot()).containsExactly("task-opencode");
    }

    @Test
    void busyAgentOnlyBlocksSameUser() {
        Task otherUserTask = task("task-other-user", "user-b", "opencode");
        enqueue(otherUserTask);
        invocationRepository.save(runningInvocation("invocation-running", "user-a", "other-task", "opencode"));

        assertThat(router.processNext()).map(Task::taskId).contains("task-other-user");
    }

    @Test
    void processingReservationPreventsSameAgentDoubleDispatch() throws Exception {
        BlockingRuntimeClient runtimeClient = new BlockingRuntimeClient("task-1");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        QuestRouterService concurrentRouter = router(runtimeClient, workers);
        enqueue(task("task-1", "opencode"));
        enqueue(task("task-2", "opencode"));

        assertThat(concurrentRouter.processNext()).map(Task::taskId).contains("task-1");
        assertThat(runtimeClient.awaitStarted()).isTrue();
        assertThat(concurrentRouter.processNext()).isEmpty();

        runtimeClient.release();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (taskRepository.findByTaskId("task-1").orElseThrow().status() != TaskStatus.COMPLETED
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(concurrentRouter.processNext()).map(Task::taskId).contains("task-2");
        workers.shutdown();
        assertThat(workers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void releasesRoutingLockBeforeExecutingTask() throws Exception {
        BlockingRuntimeClient runtimeClient = new BlockingRuntimeClient("task-1");
        QuestRouterService localRouter = router(runtimeClient, Runnable::run);
        enqueue(task("task-1", "opencode"));
        enqueue(task("task-2", "claude-code"));

        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<Optional<Task>> first = caller.submit(localRouter::processNext);
        assertThat(runtimeClient.awaitStarted()).isTrue();

        assertThat(localRouter.processNext()).map(Task::taskId).contains("task-2");
        runtimeClient.release();
        assertThat(first.get(2, TimeUnit.SECONDS)).map(Task::taskId).contains("task-1");
        caller.shutdownNow();
    }

    @Test
    void restoresQueuedTaskWhenBusinessWorkerRejectsSubmission() {
        Task task = task("task-rejected", "opencode");
        enqueue(task);
        QuestRouterService rejectingRouter = router(
                request -> new AgentExecutionResult(List.of()),
                ignored -> {
                    throw new RejectedExecutionException("worker queue full");
                });

        assertThat(rejectingRouter.processNext()).isEmpty();
        assertThat(taskRepository.findByTaskId(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.QUEUED);
        assertThat(questHub.snapshot()).containsExactly(task.taskId());
        assertThat(invocationRepository.findAll()).isEmpty();
    }

    private QuestRouterService router(AgentRuntimeClient runtimeClient, java.util.concurrent.Executor workerExecutor) {
        QuestParserService parserService = new QuestParserService(
                new EmptyParserClient(),
                TestAgentRegistries.withDefaultAgent(),
                taskRepository,
                dependencyRepository,
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
                dependencyRepository,
                null,
                null);
        return new QuestRouterService(
                questHub,
                taskRepository,
                dependencyRepository,
                invocationRepository,
                new ParallelTaskWorker(invocationService, workerExecutor),
                mock(TaskEventService.class));
    }

    private void enqueue(Task task) {
        taskRepository.save(task);
        questHub.enqueue(task.taskId());
    }

    private static Task task(String taskId, String agentId) {
        return task(taskId, "anonymous", agentId);
    }

    private static Task task(String taskId, String userId, String agentId) {
        Instant now = Instant.now();
        return new Task(
                taskId,
                userId,
                "trace-1",
                null,
                TaskStatus.QUEUED,
                TaskSource.USER,
                0,
                agentId,
                "context",
                now,
                now);
    }

    private static Invocation runningInvocation(String invocationId, String taskId, String agentId) {
        return runningInvocation(invocationId, "anonymous", taskId, agentId);
    }

    private static Invocation runningInvocation(String invocationId, String userId, String taskId, String agentId) {
        Instant now = Instant.now();
        return new Invocation(invocationId, userId, taskId, "trace-1", agentId, InvocationStatus.RUNNING, now, now, null);
    }

    private static final class EmptyParserClient implements QuestParserClient {
        @Override
        public com.agentcrossing.platform.application.parser.UserInputParseResult parseUserInput(
                String input, List<Agent> availableAgents) {
            return new com.agentcrossing.platform.application.parser.UserInputParseResult(List.of(), null);
        }

    }

    private static final class BlockingRuntimeClient implements AgentRuntimeClient {
        private final String blockingTaskId;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingRuntimeClient(String blockingTaskId) {
            this.blockingTaskId = blockingTaskId;
        }

        @Override
        public AgentExecutionResult execute(com.agentcrossing.platform.application.invocation.AgentExecutionRequest request) {
            if (blockingTaskId.equals(request.taskId())) {
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(exception);
                }
            }
            return new AgentExecutionResult(List.of());
        }

        private boolean awaitStarted() throws InterruptedException {
            return started.await(2, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }
}
