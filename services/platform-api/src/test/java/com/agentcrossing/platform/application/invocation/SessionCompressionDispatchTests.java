package com.agentcrossing.platform.application.invocation;

import com.agentcrossing.platform.support.InvocationServiceTestFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agentcrossing.platform.application.chat.AssistantStreamBuffer;
import com.agentcrossing.platform.application.chat.ThreadPlanningQueue;
import com.agentcrossing.platform.application.chat.ThreadStatusAggregator;
import com.agentcrossing.platform.application.routing.ParallelTaskWorker;
import com.agentcrossing.platform.application.routing.QuestRouterService;
import com.agentcrossing.platform.application.task.TaskEventService;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.context.InMemoryAgentContextCursorRepository;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationRepository;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationUsageRepository;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.invocation.UsagePrecision;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageRole;
import com.agentcrossing.platform.domain.message.ChatMessageStatus;
import com.agentcrossing.platform.domain.message.InMemoryChatMessageRepository;
import com.agentcrossing.platform.domain.message.InMemoryInvocationMessageRepository;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.session.AgentSession;
import com.agentcrossing.platform.domain.session.InMemoryAgentSessionHistoryRepository;
import com.agentcrossing.platform.domain.session.InMemoryAgentSessionRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskDependencyRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SessionCompressionDispatchTests {
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void blocksNextDispatchUntilCompressionFinishesAndThenUsesMatchingContext(
            boolean failCompression, boolean cancelDuringCompression)
            throws Exception {
        var tasks = new InMemoryTaskRepository();
        var invocations = new InMemoryInvocationRepository();
        var threads = new InMemoryChatThreadRepository();
        var messages = new InMemoryChatMessageRepository();
        var sessions = new InMemoryAgentSessionRepository();
        var histories = new InMemoryAgentSessionHistoryRepository();
        var dependencies = new InMemoryTaskDependencyRepository();
        var contextService = new AgentContextService(
                threads, messages, new InMemoryAgentContextCursorRepository(), null, histories);
        Instant base = Instant.parse("2026-08-13T03:00:00Z");
        threads.save(new ChatThread("thread-1", "user-1", "Compression", ChatThreadStatus.RUNNING,
                "trace-1", base, base));
        for (int i = 1; i <= 8; i++) {
            messages.save(new ChatMessage("message-" + i, "thread-1",
                    i % 2 == 1 ? ChatMessageRole.USER : ChatMessageRole.ASSISTANT,
                    "content-" + i, ChatMessageStatus.COMPLETED, null, null,
                    i % 2 == 1 ? null : "codex", base.plusSeconds(i), base.plusSeconds(i)));
        }
        CountDownLatch compressing = new CountDownLatch(1);
        CountDownLatch finishCompression = new CountDownLatch(1);
        var compression = new AgentSessionCompressionService(
                threads, messages, tasks, sessions, histories, request -> {
                    compressing.countDown();
                    try {
                        if (!finishCompression.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Test did not release compression");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    if (failCompression) {
                        throw new IllegalStateException("Summary provider unavailable");
                    }
                    return new SessionCompressionResult(Map.of("schemaVersion", 1, "objective", "continue"), "v1");
                }, new ObjectMapper(), true, 1_000_000, 0.8, 3, 20, 200_000, "gpt-5.6");
        Task firstTask = task("task-1", TaskStatus.PROCESSING, base);
        Task nextTask = task("task-2", TaskStatus.QUEUED, base);
        tasks.save(firstTask);
        tasks.save(nextTask);
        compression.rememberSession(firstTask, "thread-1", new AgentSession(
                "user-1", "thread-1", "trace-1", "codex", "codex", "old-session", "v1", base, base));
        List<AgentExecutionRequest> requests = new ArrayList<>();
        AtomicInteger signals = new AtomicInteger();
        var service = InvocationServiceTestFactory.create(
                invocations, tasks, request -> {
                    requests.add(request);
                    String sessionId = request.providerSessionId() == null ? "new-session" : request.providerSessionId();
                    return new AgentExecutionResult(List.of(new AgentMessage(
                            request.invocationId(), request.taskId(), request.traceId(), request.agentId(),
                            AgentMessageType.DONE, null, Map.of("providerSessionId", sessionId), Instant.now())),
                            "answer", true, null, "v1", requests.size() > 1 ? null : new AgentExecutionUsage(
                                    "codex", "gpt-5.6", sessionId, 900_000L, 800_000L, UsagePrecision.EXACT,
                                    900_000L, null, null, null, 100L, null, 800_000L, Map.of(), "test", base));
                }, "http://unused", signals::incrementAndGet, new InMemoryInvocationMessageRepository(),
                threads, messages, null, null, new AssistantStreamBuffer(messages), dependencies,
                contextService, sessions, new InMemoryInvocationUsageRepository(), compression,
                new ThreadStatusAggregator(threads, tasks, invocations, new ThreadPlanningQueue(Runnable::run), null));
        QuestHub queue = new QuestHub();
        queue.enqueue(nextTask.taskId());
        List<Runnable> scheduled = new ArrayList<>();
        var router = new QuestRouterService(queue, tasks, dependencies, invocations,
                new ParallelTaskWorker(service, scheduled::add), mock(TaskEventService.class), compression);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var execution = executor.submit(() -> service.execute(firstTask));
            try {
                assertThat(compressing.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(router.processNext()).isEmpty();
                assertThat(scheduled).isEmpty();
                assertThat(tasks.findByTaskId(firstTask.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.PROCESSING);
                assertThat(invocations.findAll().getFirst().status()).isEqualTo(InvocationStatus.RUNNING);
                assertThat(histories.findByGeneration("user-1", "thread-1", "codex", "codex", 1))
                        .get().satisfies(history -> assertThat(history.status().name()).isEqualTo("COMPACTING"));
                if (cancelDuringCompression) {
                    tasks.updateStatus(firstTask.taskId(), TaskStatus.CANCELED);
                    invocations.updateStatus(invocations.findAll().getFirst().invocationId(), InvocationStatus.CANCELED);
                    assertThat(router.processNext()).isEmpty();
                    assertThat(scheduled).isEmpty();
                }
                Task other = new Task("other-agent-task", "user-1", "trace-1", null, TaskStatus.QUEUED,
                        TaskSource.USER, 0, "claudecode", "unrelated", base, base);
                tasks.save(other);
                queue.enqueue(other.taskId());
                assertThat(router.processNext()).map(Task::taskId).contains(other.taskId());
                // Another agent can be dispatched while this session's summary is blocked.
                scheduled.clear();
            } finally {
                finishCompression.countDown();
            }
            assertThat(execution.get(3, TimeUnit.SECONDS).status())
                    .isEqualTo(cancelDuringCompression ? InvocationStatus.CANCELED : InvocationStatus.SUCCEEDED);
        }
        assertThat(signals.get()).isEqualTo(1);
        assertThat(router.processNext()).map(Task::taskId).contains(nextTask.taskId());
        scheduled.getFirst().run();
        AgentExecutionRequest next = requests.get(1);
        if (failCompression) {
            assertThat(next.providerSessionId()).isEqualTo("old-session");
            assertThat(next.contextPack().startupSummary()).isNull();
        } else {
            assertThat(next.providerSessionId()).isNull();
            assertThat(next.contextPack().startupSummary()).contains("continue");
            assertThat(next.contextPack().incrementalChatMessages()).isNotEmpty();
        }
    }

    private static Task task(String id, TaskStatus status, Instant now) {
        return new Task(id, "user-1", "trace-1", null, status, TaskSource.USER, 0, "codex", "continue", now, now);
    }
}
