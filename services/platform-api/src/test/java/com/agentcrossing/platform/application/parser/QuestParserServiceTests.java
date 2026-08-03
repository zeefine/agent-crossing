package com.agentcrossing.platform.application.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcrossing.platform.TestAgentRegistries;
import com.agentcrossing.platform.application.routing.LoopGuardService;
import com.agentcrossing.platform.domain.agent.Agent;
import com.agentcrossing.platform.domain.agent.AgentRegistry;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.task.InMemoryTaskDependencyRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestParserServiceTests {
    private final InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
    private final InMemoryTaskDependencyRepository taskDependencyRepository = new InMemoryTaskDependencyRepository();
    private final QuestHub questHub = new QuestHub();
    private final AgentRegistry agentRegistry = TestAgentRegistries.withDefaultAgent();
    private final FakeQuestParserClient parserClient = new FakeQuestParserClient();
    private final QuestParserService service = new QuestParserService(
            parserClient,
            agentRegistry,
            taskRepository,
            taskDependencyRepository,
            questHub,
            new LoopGuardService(taskRepository));

    @Test
    void routesSingleExplicitKnownAgentWithoutCallingMasterAgent() {
        UserInputParseResult result = service.parseUserInput(
                "user-1",
                "thread-1",
                "trace-1",
                "  @opencode：你是谁");

        assertThat(result.tasks()).singleElement().satisfies(task -> {
            assertThat(task.taskId()).startsWith("task-direct-");
            assertThat(task.agentId()).isEqualTo("opencode");
            assertThat(task.context()).isEqualTo("你是谁");
            assertThat(task.dependsOn()).isEmpty();
        });
        assertThat(result.directAnswer()).isNull();
        assertThat(parserClient.parseCalls).isZero();
    }

    @Test
    void directExplicitAgentRouteStillUsesTheNormalDagEnqueuePath() {
        UserInputEnqueueResult result = service.parseUserInputAndEnqueueWithResult(
                "user-1",
                "@opencode请直接回答",
                "trace-direct");

        assertThat(result.tasks()).singleElement().satisfies(task -> {
            assertThat(task.agentId()).isEqualTo("opencode");
            assertThat(task.context()).isEqualTo("请直接回答");
            assertThat(task.status()).isEqualTo(TaskStatus.QUEUED);
            assertThat(task.source()).isEqualTo(TaskSource.USER);
            assertThat(task.depth()).isZero();
        });
        assertThat(questHub.snapshot()).containsExactly(result.tasks().getFirst().taskId());
        assertThat(taskDependencyRepository.findParentTaskIds(result.tasks().getFirst().taskId())).isEmpty();
        assertThat(parserClient.parseCalls).isZero();
    }

    @Test
    void leavesMultipleUnknownAndEmptyExplicitMentionsForMasterAgent() {
        parserClient.userTasks = List.of(new ParsedTask("task-master", "opencode", "planned", List.of()));

        assertThat(service.parseUserInput("user-1", "thread-1", "trace-1", "@opencode 请 @claudecode 回答")
                        .tasks())
                .extracting(ParsedTask::taskId)
                .containsExactly("task-master");
        assertThat(service.parseUserInput("user-1", "thread-1", "trace-1", "@missing 你是谁").tasks())
                .extracting(ParsedTask::taskId)
                .containsExactly("task-master");
        assertThat(service.parseUserInput("user-1", "thread-1", "trace-1", "@opencode").tasks())
                .extracting(ParsedTask::taskId)
                .containsExactly("task-master");
        assertThat(parserClient.parseCalls).isEqualTo(3);
    }

    @Test
    void parsesUserInputCompletesTaskMetadataAndEnqueuesKnownAgents() {
        parserClient.userTasks = List.of(
                new ParsedTask("task-1", "opencode", "do work", List.of()),
                new ParsedTask("task-2", "opencode", "follow up", List.of("task-1")),
                new ParsedTask("task-unknown", "missing", "drop me", List.of()),
                new ParsedTask("task-duplicate", "opencode", "first duplicate", List.of()),
                new ParsedTask("task-duplicate", "opencode", "second duplicate", List.of()),
                new ParsedTask("task-bad-relation", "opencode", "bad relation", List.of("missing-task")),
                new ParsedTask("task-empty-context", "opencode", " ", List.of()));

        List<Task> tasks = service.parseUserInputAndEnqueue("anonymous", "hello", "trace-1");

        assertThat(tasks).hasSize(2);
        Task task = tasks.getFirst();
        assertThat(task.taskId()).isEqualTo("task-1");
        assertThat(task.traceId()).startsWith("trace-");
        assertThat(task.createdByTaskId()).isNull();
        assertThat(task.source()).isEqualTo(TaskSource.USER);
        assertThat(task.depth()).isZero();
        assertThat(task.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(taskRepository.findByTaskId("task-1")).contains(task);
        assertThat(questHub.snapshot()).containsExactly("task-1", "task-2");
        assertThat(taskDependencyRepository.findParentTaskIds("task-2")).containsExactly("task-1");
    }

    @Test
    void remapsUserTaskIdsThatAlreadyExistAndPreservesDependencies() {
        taskRepository.save(task("task-answer"));
        parserClient.userTasks = List.of(
                new ParsedTask("task-answer", "opencode", "answer", List.of()),
                new ParsedTask("task-follow", "opencode", "follow", List.of("task-answer")));

        List<Task> tasks = service.parseUserInputAndEnqueue("user-1", "hello again", "trace-2");

        assertThat(tasks).hasSize(2);
        assertThat(tasks.getFirst().taskId()).startsWith("task-answer-");
        assertThat(tasks.getFirst().taskId()).isNotEqualTo("task-answer");
        assertThat(tasks.get(1).taskId()).isEqualTo("task-follow");
        assertThat(taskDependencyRepository.findParentTaskIds("task-follow"))
                .containsExactly(tasks.getFirst().taskId());
        assertThat(questHub.snapshot())
                .containsExactly(tasks.getFirst().taskId(), "task-follow");
    }

    @Test
    void rejectsUserPlansWithDependencyCycles() {
        parserClient.userTasks = List.of(
                new ParsedTask("task-a", "opencode", "first", List.of("task-b")),
                new ParsedTask("task-b", "opencode", "second", List.of("task-a")));

        assertThatThrownBy(() -> service.parseUserInputAndEnqueue("anonymous", "cycle", "trace-cycle"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DAG 中存在环");
        assertThat(questHub.snapshot()).isEmpty();
    }

    @Test
    void appendAgentTasksInheritsTraceDepthAndSourceTask() {
        Task sourceTask = sourceTask(0);
        taskRepository.save(sourceTask);

        List<Task> tasks = service.appendAgentTasks(
                sourceTask,
                List.of(new ParsedTask("task-child", "opencode", "continue", List.of())));

        assertThat(tasks).hasSize(1);
        Task task = tasks.getFirst();
        assertThat(task.traceId()).isEqualTo(sourceTask.traceId());
        assertThat(task.createdByTaskId()).isEqualTo(sourceTask.taskId());
        assertThat(task.source()).isEqualTo(TaskSource.AGENT);
        assertThat(task.depth()).isEqualTo(1);
        assertThat(questHub.snapshot()).containsExactly(task.taskId());
    }

    @Test
    void appendedTasksInheritTheAuthoritativeSelfOrchestrationContract() {
        String contract = """
                [Self-Orchestration Contract]
                {"originalRequest":"debate","participants":["claudecode","opencode"],"turnsPerParticipant":3,"requiresFinalResult":true}
                rules
                [/Self-Orchestration Contract]
                """.strip();
        Task sourceTask = sourceTask(0, "first turn\n\n" + contract);
        taskRepository.save(sourceTask);

        List<Task> tasks = service.appendAgentTasks(
                sourceTask,
                List.of(new ParsedTask(
                        "task-child",
                        "opencode",
                        "second turn\n\n[Self-Orchestration Contract]\nturnsPerParticipant: 2\n[/Self-Orchestration Contract]",
                        List.of())));

        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().context())
                .isEqualTo("second turn\n\n" + contract)
                .contains("\"turnsPerParticipant\":3")
                .doesNotContain("turnsPerParticipant: 2");
    }

    @Test
    void dropsAppendedAgentTasksRejectedByLoopGuard() {
        Task sourceTask = sourceTask(10);
        taskRepository.save(sourceTask);

        List<Task> tasks = service.appendAgentTasks(
                sourceTask,
                List.of(new ParsedTask("too-deep", "opencode", "continue", List.of())));

        assertThat(tasks).isEmpty();
        assertThat(questHub.snapshot()).isEmpty();
        assertThat(taskRepository.findByTaskId("too-deep")).isEmpty();
    }

    @Test
    void sameClientTaskIdReturnsTheOriginalTaskWithoutReenqueuingEvenWithAnotherRequestKey() {
        Task sourceTask = sourceTask(0);
        taskRepository.save(sourceTask);
        List<ParsedTask> request = List.of(new ParsedTask("task-next", "opencode", "continue", List.of()));

        List<Task> first = service.appendAgentTasks(sourceTask, request, "append-source-task-1");
        List<Task> retry = service.appendAgentTasks(sourceTask, request, "append-source-task-retry");

        assertThat(first).hasSize(1);
        assertThat(retry).containsExactly(first.getFirst());
        assertThat(first.getFirst().taskId()).startsWith("agent-task-");
        assertThat(taskRepository.findAll()).hasSize(2);
        assertThat(questHub.snapshot()).containsExactly(first.getFirst().taskId());
    }

    @Test
    void clientTaskDependenciesResolveToTheCreatedPlatformTaskIds() {
        Task sourceTask = sourceTask(0);
        taskRepository.save(sourceTask);

        List<Task> created = service.appendAgentTasks(
                sourceTask,
                List.of(
                        new ParsedTask("task-first", "opencode", "first", List.of()),
                        new ParsedTask("task-second", "opencode", "second", List.of("task-first"))),
                "append-source-task-2");

        Task first = created.getFirst();
        Task second = created.get(1);
        assertThat(taskDependencyRepository.findParentTaskIds(first.taskId())).containsExactly(sourceTask.taskId());
        assertThat(taskDependencyRepository.findParentTaskIds(second.taskId())).containsExactly(first.taskId());
    }

    private static Task sourceTask(int depth) {
        return sourceTask(depth, "source");
    }

    private static Task sourceTask(int depth, String context) {
        Instant now = Instant.now();
        return new Task(
                "source-task",
                "anonymous",
                "trace-1",
                null,
                TaskStatus.COMPLETED,
                TaskSource.USER,
                depth,
                "opencode",
                context,
                now,
                now);
    }

    private static Task task(String taskId) {
        Instant now = Instant.now();
        return new Task(
                taskId,
                "anonymous",
                "trace-existing",
                null,
                TaskStatus.COMPLETED,
                TaskSource.USER,
                0,
                "opencode",
                "existing",
                now,
                now);
    }

    private static final class FakeQuestParserClient implements QuestParserClient {
        private List<ParsedTask> userTasks = new ArrayList<>();
        private int parseCalls;

        @Override
        public UserInputParseResult parseUserInput(String input, List<Agent> availableAgents) {
            parseCalls++;
            return new UserInputParseResult(userTasks, null);
        }
    }
}
