package com.agentcrossing.platform.application.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.application.invocation.AgentExecutionResult;
import com.agentcrossing.platform.application.invocation.AgentRuntimeClient;
import com.agentcrossing.platform.application.realtime.SocketManager;
import com.agentcrossing.platform.application.routing.TaskDispatchSignal;
import com.agentcrossing.platform.application.task.TaskEventService;
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
import org.junit.jupiter.api.Test;

class ThreadCancellationServiceTests {
    @Test
    void persistsCancellationBeforeForwardingRunningInvocationToRuntime() {
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
        InMemoryInvocationRepository invocationRepository = new InMemoryInvocationRepository();
        InMemoryTaskDependencyRepository dependencyRepository = new InMemoryTaskDependencyRepository();
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        ChatEventService chatEventService = new ChatEventService(
                new SocketManager(new ObjectMapper(), eventLogRepository), eventLogRepository);
        QuestHub questHub = new QuestHub();
        ThreadPlanningQueue planningQueue = new ThreadPlanningQueue(Runnable::run);
        ThreadStatusAggregator statusAggregator = new ThreadStatusAggregator(
                threadRepository, taskRepository, invocationRepository, planningQueue, chatEventService);
        TaskEventService taskEventService = new TaskEventService(threadRepository, chatEventService, dependencyRepository);
        CapturingRuntimeClient runtimeClient = new CapturingRuntimeClient(invocationRepository, taskRepository);
        ThreadCancellationService service = new ThreadCancellationService(
                threadRepository,
                taskRepository,
                invocationRepository,
                questHub,
                planningQueue,
                runtimeClient,
                taskEventService,
                statusAggregator,
                TaskDispatchSignal.NOOP);

        Instant now = Instant.now();
        threadRepository.save(new ChatThread(
                "thread-1", "user-1", "thread", ChatThreadStatus.RUNNING, "trace-1", now, now));
        Task task = new Task(
                "task-1",
                "user-1",
                "trace-1",
                null,
                TaskStatus.PROCESSING,
                TaskSource.USER,
                0,
                "opencode",
                "long running task",
                now,
                now);
        taskRepository.save(task);
        questHub.enqueue(task.taskId());
        Invocation invocation = invocationRepository.save(new Invocation(
                "invocation-1",
                "user-1",
                task.taskId(),
                task.traceId(),
                task.agentId(),
                InvocationStatus.RUNNING,
                now,
                now,
                null));
        planningQueue.reserve("user-1", "thread-1");

        CancelThreadWorkResult result = service.cancel("user-1", "thread-1");

        assertThat(result.canceledTaskIds()).containsExactly(task.taskId());
        assertThat(result.canceledInvocationIds()).containsExactly(invocation.invocationId());
        assertThat(taskRepository.findByTaskId(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.CANCELED);
        assertThat(invocationRepository.findByInvocationId(invocation.invocationId()).orElseThrow().status())
                .isEqualTo(InvocationStatus.CANCELED);
        assertThat(questHub.snapshot()).isEmpty();
        assertThat(planningQueue.hasPending("user-1", "thread-1")).isFalse();
        assertThat(runtimeClient.canceledInvocationIds).containsExactly(invocation.invocationId());
        assertThat(runtimeClient.observedStatuses).containsExactly(InvocationStatus.CANCELED, TaskStatus.CANCELED);
    }

    private static final class CapturingRuntimeClient implements AgentRuntimeClient {
        private final InMemoryInvocationRepository invocationRepository;
        private final InMemoryTaskRepository taskRepository;
        private final List<Object> observedStatuses = new java.util.ArrayList<>();
        private final List<String> canceledInvocationIds = new java.util.ArrayList<>();

        private CapturingRuntimeClient(
                InMemoryInvocationRepository invocationRepository,
                InMemoryTaskRepository taskRepository) {
            this.invocationRepository = invocationRepository;
            this.taskRepository = taskRepository;
        }

        @Override
        public AgentExecutionResult execute(com.agentcrossing.platform.application.invocation.AgentExecutionRequest request) {
            return new AgentExecutionResult(List.of());
        }

        @Override
        public void cancel(String invocationId) {
            Invocation invocation = invocationRepository.findByInvocationId(invocationId).orElseThrow();
            observedStatuses.add(invocation.status());
            observedStatuses.add(taskRepository.findByTaskId(invocation.taskId()).orElseThrow().status());
            canceledInvocationIds.add(invocationId);
        }
    }
}
