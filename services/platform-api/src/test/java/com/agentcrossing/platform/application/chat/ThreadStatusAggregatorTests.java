package com.agentcrossing.platform.application.chat;

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
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ThreadStatusAggregatorTests {
    private final InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
    private final InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
    private final InMemoryInvocationRepository invocationRepository = new InMemoryInvocationRepository();
    private final ThreadPlanningQueue planningQueue = new ThreadPlanningQueue(Runnable::run);
    private final ThreadStatusAggregator aggregator = new ThreadStatusAggregator(
            threadRepository,
            taskRepository,
            invocationRepository,
            planningQueue,
            null);

    @Test
    void keepsThreadFailedWhenALateParallelSiblingSucceeds() {
        ChatThread thread = saveThread(ChatThreadStatus.RUNNING);
        taskRepository.save(task("task-a", thread.traceId(), TaskStatus.FAILED));
        taskRepository.save(task("task-b", thread.traceId(), TaskStatus.COMPLETED));

        aggregator.refresh(thread.userId(), thread.threadId());

        assertThat(threadRepository.findByThreadId(thread.threadId()).orElseThrow().status())
                .isEqualTo(ChatThreadStatus.FAILED);
    }

    @Test
    void keepsThreadRunningWhileMasterAgentPlanningIsReservedEvenAfterEarlierFailure() {
        ChatThread thread = saveThread(ChatThreadStatus.FAILED);
        taskRepository.save(task("task-failed", thread.traceId(), TaskStatus.FAILED));
        planningQueue.reserve(thread.userId(), thread.threadId());

        aggregator.refresh(thread.userId(), thread.threadId());

        assertThat(threadRepository.findByThreadId(thread.threadId()).orElseThrow().status())
                .isEqualTo(ChatThreadStatus.RUNNING);

        planningQueue.cancelReservation(thread.userId(), thread.threadId());
        aggregator.refresh(thread.userId(), thread.threadId());
        assertThat(threadRepository.findByThreadId(thread.threadId()).orElseThrow().status())
                .isEqualTo(ChatThreadStatus.FAILED);
    }

    @Test
    void keepsThreadRunningWhileAnInvocationIsStillActive() {
        ChatThread thread = saveThread(ChatThreadStatus.COMPLETED);
        taskRepository.save(task("task-completed", thread.traceId(), TaskStatus.COMPLETED));
        invocationRepository.save(new Invocation(
                "invocation-running",
                thread.userId(),
                "task-completed",
                thread.traceId(),
                "opencode",
                InvocationStatus.RUNNING,
                Instant.now(),
                null,
                null));

        aggregator.refresh(thread.userId(), thread.threadId());

        assertThat(threadRepository.findByThreadId(thread.threadId()).orElseThrow().status())
                .isEqualTo(ChatThreadStatus.RUNNING);
    }

    private ChatThread saveThread(ChatThreadStatus status) {
        Instant now = Instant.now();
        return threadRepository.save(new ChatThread(
                "thread-1",
                "user-1",
                "Thread",
                status,
                "trace-1",
                now,
                now));
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
}
