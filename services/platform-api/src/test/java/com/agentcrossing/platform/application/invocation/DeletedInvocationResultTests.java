package com.agentcrossing.platform.application.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.agentcrossing.platform.application.chat.AssistantStreamBuffer;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.context.InMemoryAgentContextCursorRepository;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationRepository;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationUsageRepository;
import com.agentcrossing.platform.domain.invocation.UsagePrecision;
import com.agentcrossing.platform.domain.message.InMemoryChatMessageRepository;
import com.agentcrossing.platform.domain.message.InMemoryInvocationMessageRepository;
import com.agentcrossing.platform.domain.session.AgentSession;
import com.agentcrossing.platform.domain.session.InMemoryAgentSessionRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskDependencyRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import com.agentcrossing.platform.support.ChatServiceTestFactory;
import com.agentcrossing.platform.support.InvocationServiceTestFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

class DeletedInvocationResultTests {
    @Test
    void staleQueuedTaskCannotRecreateInvocationOrStartRuntimeAfterDeletion() {
        var tasks = new InMemoryTaskRepository();
        var invocations = new InMemoryInvocationRepository();
        var service = InvocationServiceTestFactory.create(
                invocations, tasks, request -> { throw new AssertionError("runtime must not start"); },
                "http://unused", () -> {}, new InMemoryInvocationMessageRepository(),
                new InMemoryChatThreadRepository(), new InMemoryChatMessageRepository(), null, null,
                null, new InMemoryTaskDependencyRepository(), null, null, new InMemoryInvocationUsageRepository());
        Instant now = Instant.now();
        Task deleted = new Task("deleted", "alice", "trace", null, TaskStatus.PROCESSING,
                TaskSource.USER, 0, "codex", "work", now, now);
        assertThat(service.execute(deleted).status())
                .isEqualTo(com.agentcrossing.platform.domain.invocation.InvocationStatus.CANCELED);
        assertThat(invocations.findByTraceIdAndUserId("trace", "alice")).isEmpty();
    }

    @ParameterizedTest(name = "runtimeFails={0}, pendingStreamTail={1}")
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void deletionDuringRuntimeStopsLateFinalizationWithoutRecreatingData(
            boolean runtimeFails, boolean pendingStreamTail) {
        var threads = new InMemoryChatThreadRepository();
        var messages = new InMemoryChatMessageRepository();
        var invocations = new InMemoryInvocationRepository();
        var usage = new InMemoryInvocationUsageRepository();
        var events = new InMemoryInvocationMessageRepository();
        var tasks = new InMemoryTaskRepository();
        var dependencies = new InMemoryTaskDependencyRepository();
        var cursors = new InMemoryAgentContextCursorRepository();
        var sessions = new InMemoryAgentSessionRepository();
        var buffer = new AssistantStreamBuffer(messages);
        var chat = ChatServiceTestFactory.create(
                threads, messages, null, null, null, null, events, invocations,
                tasks, dependencies, cursors, sessions, null, Runnable::run, null);
        ReflectionTestUtils.setField(chat, "invocationUsageRepository", usage);

        Instant now = Instant.parse("2026-10-03T01:00:00Z");
        Task task = new Task("task-delete", "alice", "trace-delete", null,
                TaskStatus.PROCESSING, TaskSource.USER, 0, "codex", "work", now, now);
        tasks.save(task);
        threads.save(new ChatThread("thread-delete", "alice", "Delete while running",
                ChatThreadStatus.RUNNING, task.traceId(), now, now));
        sessions.save(new AgentSession("alice", "thread-delete", task.traceId(), "codex", "codex",
                "old-provider-session", "prompt-v1", now, now));
        AtomicReference<String> invocationId = new AtomicReference<>();
        AgentRuntimeClient runtime = request -> {
            invocationId.set(request.invocationId());
            if (pendingStreamTail) {
                var running = invocations.findByInvocationId(request.invocationId()).orElseThrow();
                buffer.appendChunk(running, "thread-delete", "partial");
                buffer.appendChunk(running, "thread-delete", " pending tail");
                assertThat(messages.findByThreadId("thread-delete")).singleElement()
                        .satisfies(message -> assertThat(message.content()).isEqualTo("partial"));
            }
            // The real deletion path removes both the invocation and its owning task before HTTP returns.
            chat.deleteThread("alice", "thread-delete");
            assertThat(invocations.findByInvocationId(request.invocationId())).isEmpty();
            assertThat(tasks.findByTaskId(task.taskId())).isEmpty();
            if (runtimeFails) {
                throw new IllegalStateException("late runtime transport failure");
            }
            return new AgentExecutionResult(
                    List.of(new AgentMessage(request.invocationId(), task.taskId(), task.traceId(), "codex",
                            AgentMessageType.DONE, "", Map.of("providerSessionId", "late-provider-session"), now)),
                    "Complete reply arriving after deletion", true, 2L, "prompt-v1",
                    new AgentExecutionUsage("codex", "gpt-5.6", "late-provider-session", 1200L, 1000L,
                            UsagePrecision.EXACT, 1000L, null, null, null, 200L, null, 1000L,
                            Map.of("input_tokens", 1000L), "test-cli", now));
        };
        var service = InvocationServiceTestFactory.create(
                invocations, tasks, runtime, "http://unused", () -> {}, events, threads, messages,
                null, null, buffer, dependencies, null, sessions, usage);

        // Keep all state assertions independent: an exception must not obscure recreated orphan rows.
        assertAll(
                () -> assertDoesNotThrow(() -> service.execute(task)),
                () -> assertThat(invocationId.get()).isNotNull(),
                () -> assertThat(threads.findByThreadIdAndUserId("thread-delete", "alice")).isEmpty(),
                () -> assertThat(tasks.findByTaskId(task.taskId())).isEmpty(),
                () -> assertThat(invocations.findByInvocationId(invocationId.get())).isEmpty(),
                () -> assertThat(usage.findByInvocationId(invocationId.get())).isEmpty(),
                () -> assertThat(events.findByInvocationId(invocationId.get())).isEmpty(),
                () -> assertThat(messages.findByThreadId("thread-delete")).isEmpty(),
                () -> assertThat(sessions.findByThreadId("alice", "thread-delete", "codex", "codex")).isEmpty());
        // Final cleanup must discard the tail, not merely postpone its eventual flush.
        buffer.drain(invocationId.get());
        assertThat(messages.findByThreadId("thread-delete")).isEmpty();
    }
}
