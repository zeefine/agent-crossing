package com.agentcrossing.platform.application.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import com.agentcrossing.platform.domain.session.AgentSessionHistory;
import com.agentcrossing.platform.domain.session.AgentSessionHistoryStatus;
import com.agentcrossing.platform.domain.session.InMemoryAgentSessionHistoryRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StartupTaskFailureServiceTests {
    private final InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
    private final InMemoryInvocationRepository invocationRepository = new InMemoryInvocationRepository();
    private final InMemoryChatThreadRepository chatThreadRepository = new InMemoryChatThreadRepository();
    private final StartupTaskFailureService service =
            new StartupTaskFailureService(taskRepository, invocationRepository, chatThreadRepository);

    @Test
    void marksStaleTasksInvocationsAndThreadsFailedOnStartup() {
        chatThreadRepository.save(thread("thread-stale", "trace-stale", ChatThreadStatus.RUNNING));
        chatThreadRepository.save(thread("thread-idle", "trace-idle", ChatThreadStatus.OPEN));
        taskRepository.save(task("task-queued", "trace-stale", TaskStatus.QUEUED));
        taskRepository.save(task("task-processing", "trace-stale", TaskStatus.PROCESSING));
        taskRepository.save(task("task-completed", "trace-idle", TaskStatus.COMPLETED));
        invocationRepository.save(invocation("invocation-queued", "task-queued", "trace-stale", InvocationStatus.QUEUED));
        invocationRepository.save(
                invocation("invocation-running", "task-processing", "trace-stale", InvocationStatus.RUNNING));
        invocationRepository.save(
                invocation("invocation-succeeded", "task-completed", "trace-idle", InvocationStatus.SUCCEEDED));

        StartupTaskFailureService.StartupCleanupSummary summary = service.failStaleWork();

        assertThat(summary.failedInvocations()).isEqualTo(2);
        assertThat(summary.failedTasks()).isEqualTo(2);
        assertThat(summary.failedThreads()).isEqualTo(1);
        assertThat(taskRepository.findByTaskId("task-queued").orElseThrow().status())
                .isEqualTo(TaskStatus.FAILED);
        assertThat(taskRepository.findByTaskId("task-processing").orElseThrow().status())
                .isEqualTo(TaskStatus.FAILED);
        assertThat(taskRepository.findByTaskId("task-completed").orElseThrow().status())
                .isEqualTo(TaskStatus.COMPLETED);
        assertThat(invocationRepository.findByInvocationId("invocation-queued").orElseThrow().status())
                .isEqualTo(InvocationStatus.FAILED);
        assertThat(invocationRepository.findByInvocationId("invocation-running").orElseThrow().status())
                .isEqualTo(InvocationStatus.FAILED);
        assertThat(invocationRepository.findByInvocationId("invocation-succeeded").orElseThrow().status())
                .isEqualTo(InvocationStatus.SUCCEEDED);
        assertThat(chatThreadRepository.findByThreadId("thread-stale").orElseThrow().status())
                .isEqualTo(ChatThreadStatus.FAILED);
        assertThat(chatThreadRepository.findByThreadId("thread-idle").orElseThrow().status())
                .isEqualTo(ChatThreadStatus.OPEN);
    }

    @Test
    void marksThreadFailedWhenItHasQueuedTaskEvenIfThreadWasOpen() {
        chatThreadRepository.save(thread("thread-open-with-task", "trace-open", ChatThreadStatus.OPEN));
        taskRepository.save(task("task-open-queued", "trace-open", TaskStatus.QUEUED));

        StartupTaskFailureService.StartupCleanupSummary summary = service.failStaleWork();

        assertThat(summary.failedTasks()).isEqualTo(1);
        assertThat(summary.failedThreads()).isEqualTo(1);
        assertThat(chatThreadRepository.findByThreadId("thread-open-with-task").orElseThrow().status())
                .isEqualTo(ChatThreadStatus.FAILED);
    }

    @Test
    void restoresInterruptedCompactionWithoutChangingGenerationOrSummary() {
        var histories = new InMemoryAgentSessionHistoryRepository();
        Instant now = Instant.now();
        histories.save(new AgentSessionHistory(
                "session-record-1", "user-1", "thread-1", "trace-1", "codex", "codex", "old-session",
                2, AgentSessionHistoryStatus.COMPACTING, "predecessor", "previous summary",
                "message-1", "message-2", "message-3", null, "gpt-5.6", "v1", "TOKEN_THRESHOLD",
                null, now, now, null));
        service.setSessionHistoryRepository(histories);

        service.failStaleWork();
        service.failStaleWork();

        assertThat(histories.findActive("user-1", "thread-1", "codex", "codex")).get().satisfies(history -> {
            assertThat(history.generation()).isEqualTo(2);
            assertThat(history.providerSessionId()).isEqualTo("old-session");
            assertThat(history.startupSummary()).isEqualTo("previous summary");
        });
        assertThat(histories.findCompacting("user-1", "thread-1", "codex", "codex")).isEmpty();
    }

    private static ChatThread thread(String threadId, String traceId, ChatThreadStatus status) {
        Instant now = Instant.now();
        return new ChatThread(threadId, "user-1", "Thread", status, traceId, now, now);
    }

    private static Task task(String taskId, String traceId, TaskStatus status) {
        Instant now = Instant.now();
        return new Task(
                taskId,
                "user-1",
                traceId,
                null,
                status,
                TaskSource.USER,
                0,
                "opencode",
                "context",
                now,
                now);
    }

    private static Invocation invocation(
            String invocationId, String taskId, String traceId, InvocationStatus status) {
        Instant now = Instant.now();
        return new Invocation(invocationId, "user-1", taskId, traceId, "opencode", status, now, null, null);
    }
}
