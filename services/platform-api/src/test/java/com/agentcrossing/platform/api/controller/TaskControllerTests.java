package com.agentcrossing.platform.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.TestAgentRegistries;
import com.agentcrossing.platform.api.dto.ApiResponse;
import com.agentcrossing.platform.api.dto.AppendTaskItemRequest;
import com.agentcrossing.platform.api.dto.AppendTaskRequest;
import com.agentcrossing.platform.api.dto.QueueSnapshotResponse;
import com.agentcrossing.platform.api.dto.SubmitTaskRequest;
import com.agentcrossing.platform.api.dto.TaskResponse;
import com.agentcrossing.platform.application.parser.ParsedTask;
import com.agentcrossing.platform.application.parser.QuestParserClient;
import com.agentcrossing.platform.application.parser.QuestParserService;
import com.agentcrossing.platform.application.routing.LoopGuardService;
import com.agentcrossing.platform.domain.agent.Agent;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.task.InMemoryTaskDependencyRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskDependency;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskControllerTests {
    private final InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
    private final InMemoryTaskDependencyRepository taskDependencyRepository = new InMemoryTaskDependencyRepository();
    private final QuestHub questHub = new QuestHub();
    private final TaskController controller = new TaskController(
            new QuestParserService(
                    new FakeParserClient(),
                    TestAgentRegistries.withDefaultAgent(),
                    taskRepository,
                    taskDependencyRepository,
                    questHub,
                    new LoopGuardService(taskRepository)),
            taskRepository,
            taskDependencyRepository,
            questHub);

    @Test
    void submitCreatesTasksAndQueueSnapshotShowsQuestHub() {
        ApiResponse<List<TaskResponse>> response = controller.submit("anonymous", new SubmitTaskRequest("hello"));

        assertThat(response.success()).isTrue();
        assertThat(response.data()).hasSize(1);
        String taskId = response.data().getFirst().taskId();
        assertThat(controller.getTask("anonymous", taskId).data().taskId()).isEqualTo(taskId);
        assertThat(controller.getTasks("anonymous", response.data().getFirst().traceId()).data()).hasSize(1);
        ApiResponse<QueueSnapshotResponse> queues = controller.getQueues("anonymous");
        assertThat(queues.data().questHub()).hasSize(1);
    }

    @Test
    void directDownstreamTasksAreSortedByCreatedAtRegardlessOfStatus() {
        Instant base = Instant.parse("2026-06-22T00:00:00Z");
        taskRepository.save(task("task-a", TaskStatus.COMPLETED, base));
        taskRepository.save(task("task-b", TaskStatus.PROCESSING, base.plusSeconds(20)));
        taskRepository.save(task("task-c", TaskStatus.QUEUED, base.plusSeconds(30)));
        taskRepository.save(task("task-d", TaskStatus.COMPLETED, base.plusSeconds(10)));
        taskDependencyRepository.saveAll(List.of(
                new TaskDependency("task-a", "task-b"),
                new TaskDependency("task-b", "task-c"),
                new TaskDependency("task-a", "task-d")));

        ApiResponse<List<TaskResponse>> response = controller.getDirectDownstreamTasks("anonymous", "task-a");

        assertThat(response.data())
                .extracting(TaskResponse::taskId)
                .containsExactly("task-d", "task-b");
    }

    @Test
    void appendTasksCreatesOnlyNewDownstreamTasksWithoutChangingExistingDag() {
        Instant base = Instant.parse("2026-06-22T00:00:00Z");
        taskRepository.save(task("task-a", TaskStatus.COMPLETED, base));
        taskRepository.save(task("task-b", TaskStatus.QUEUED, base.plusSeconds(10)));
        taskDependencyRepository.saveAll(List.of(new TaskDependency("task-a", "task-b")));

        ApiResponse<List<TaskResponse>> response = controller.appendTasks(
                "anonymous",
                "task-a",
                new AppendTaskRequest(List.of(
                        new AppendTaskItemRequest("task-d", "opencode", "追加 D", List.of()),
                        new AppendTaskItemRequest("task-e", "opencode", "追加 E", List.of("task-d")))));

        assertThat(response.data()).extracting(TaskResponse::taskId).containsExactly("task-d", "task-e");
        assertThat(response.data()).allSatisfy(task -> {
            assertThat(task.traceId()).isEqualTo("trace-1");
            assertThat(task.createdByTaskId()).isEqualTo("task-a");
            assertThat(task.source()).isEqualTo("agent");
        });
        assertThat(taskDependencyRepository.findChildTaskIds("task-a"))
                .containsExactlyInAnyOrder("task-b", "task-d");
        assertThat(taskDependencyRepository.findChildTaskIds("task-d")).containsExactly("task-e");
    }

    private static Task task(String taskId, TaskStatus status, Instant createdAt) {
        return new Task(
                taskId,
                "anonymous",
                "trace-1",
                null,
                status,
                TaskSource.USER,
                0,
                "opencode",
                taskId,
                createdAt,
                createdAt);
    }

    private static final class FakeParserClient implements QuestParserClient {
        @Override
        public com.agentcrossing.platform.application.parser.UserInputParseResult parseUserInput(
                String input, List<Agent> availableAgents) {
            return new com.agentcrossing.platform.application.parser.UserInputParseResult(
                    List.of(new ParsedTask("task-api", "opencode", input, List.of())), null);
        }

    }
}
