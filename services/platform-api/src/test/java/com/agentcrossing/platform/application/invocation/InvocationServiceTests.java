package com.agentcrossing.platform.application.invocation;

import com.agentcrossing.platform.support.InvocationServiceTestFactory;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.application.chat.AssistantStreamBuffer;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.context.InMemoryAgentContextCursorRepository;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationRepository;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationUsageRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.invocation.UsagePrecision;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageRole;
import com.agentcrossing.platform.domain.message.ChatMessageStatus;
import com.agentcrossing.platform.domain.message.InMemoryChatMessageRepository;
import com.agentcrossing.platform.domain.message.InMemoryInvocationMessageRepository;
import com.agentcrossing.platform.domain.message.InvocationMessage;
import com.agentcrossing.platform.domain.session.AgentSession;
import com.agentcrossing.platform.domain.session.InMemoryAgentSessionRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskDependencyRepository;
import com.agentcrossing.platform.domain.task.TaskDependency;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class InvocationServiceTests {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingEventsFallBackToAssistantStream(boolean eventRepositoryAvailable) {
        var messages = new InMemoryChatMessageRepository();
        var events = eventRepositoryAvailable ? new InMemoryInvocationMessageRepository() : null;
        var local = invocationService(runtimeClient, events, null, messages, null);
        Boolean missing = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                local, "hasStreamedInvocationMessages", "inv");
        assertThat(missing).isFalse();

        messages.save(new ChatMessage("chat", "thread", ChatMessageRole.ASSISTANT, "stream",
                ChatMessageStatus.STREAMING, "inv", "task", "codex", Instant.now(), Instant.now()));
        Boolean found = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                local, "hasStreamedInvocationMessages", "inv");
        assertThat(found).isTrue();
    }

    @Test
    void missingOptionalRepositoriesMeanNoStreamedMessages() {
        var local = invocationService(runtimeClient, null, null, null, null);
        Boolean exists = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                local, "hasStreamedInvocationMessages", "inv");
        assertThat(exists).isFalse();
    }

    @Test
    void streamedMessageCheckDoesNotLoadEventBodies() {
        var events = new InMemoryInvocationMessageRepository() {
            @Override
            public List<InvocationMessage> findByInvocationId(String invocationId) {
                throw new AssertionError("Existence checks must not load event bodies");
            }
        };
        events.save(new InvocationMessage("event", "user", "inv", "task", "trace", "codex",
                AgentMessageType.MESSAGE, "content", Map.of("payload", "raw"), Instant.now()));
        var local = invocationService(runtimeClient, events, null, null, null);

        Boolean exists = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                local, "hasStreamedInvocationMessages", "inv");

        assertThat(exists).isTrue();
    }

    @Test
    void streamedEventHitSkipsAssistantStreamLookup() {
        var events = new InMemoryInvocationMessageRepository();
        events.save(new InvocationMessage("event", "user", "inv", "task", "trace", "codex",
                AgentMessageType.MESSAGE, "content", null, Instant.now()));
        var messages = new InMemoryChatMessageRepository() {
            @Override
            public Optional<ChatMessage> findAssistantStreamByInvocationId(String invocationId) {
                throw new AssertionError("An event hit must short-circuit the chat lookup");
            }
        };
        var local = invocationService(runtimeClient, events, null, messages, null);

        Boolean exists = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                local, "hasStreamedInvocationMessages", "inv");

        assertThat(exists).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationAfterLastStatusReadCannotBeOverwritten(boolean runtimeFails) {
        var invocations = new InMemoryInvocationRepository();
        AtomicReference<String> runningId = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        var tasks = new InMemoryTaskRepository() {
            @Override
            public Optional<Task> findByTaskId(String taskId) {
                Optional<Task> snapshot = super.findByTaskId(taskId);
                if (runningId.get() != null && reads.incrementAndGet() == (runtimeFails ? 1 : 2)) {
                    // A stop commits after isCanceled has read both rows, but before the terminal write.
                    invocations.updateStatus(runningId.get(), InvocationStatus.CANCELED);
                    super.updateStatus(taskId, TaskStatus.CANCELED);
                }
                return snapshot;
            }
        };
        Task task = task("race-task").withStatus(TaskStatus.PROCESSING);
        tasks.save(task);
        AgentRuntimeClient runtime = request -> {
            runningId.set(request.invocationId());
            if (runtimeFails) {
                throw new IllegalStateException("late runtime failure");
            }
            return new AgentExecutionResult(List.of());
        };
        var local = InvocationServiceTestFactory.create(invocations, tasks, runtime, "http://unused", () -> {},
                new InMemoryInvocationMessageRepository(), null, null, null, null, null,
                new InMemoryTaskDependencyRepository(), null, null);

        Invocation result = local.execute(task);

        assertThat(result.status()).isEqualTo(InvocationStatus.CANCELED);
        assertThat(invocations.findByInvocationId(result.invocationId()).orElseThrow().status())
                .isEqualTo(InvocationStatus.CANCELED);
        assertThat(tasks.findByTaskId(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.CANCELED);
    }

    private final InMemoryInvocationRepository invocationRepository = new InMemoryInvocationRepository();
    private final InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
    private final InMemoryTaskDependencyRepository taskDependencyRepository = new InMemoryTaskDependencyRepository();
    private final InMemoryInvocationUsageRepository invocationUsageRepository =
            new InMemoryInvocationUsageRepository();
    private final FakeRuntimeClient runtimeClient = new FakeRuntimeClient();
    private final InvocationService service = invocationService(runtimeClient);

    @Test
    void createsInvocationRunsRuntimeAndDoesNotParseNaturalLanguageOutput() {
        Task task = task("task-1");
        taskRepository.save(task);
        runtimeClient.result = new AgentExecutionResult(List.of(new AgentMessage(
                "ignored",
                task.taskId(),
                task.traceId(),
                task.agentId(),
                AgentMessageType.MESSAGE,
                "@opencode follow up",
                null,
                Instant.now())));

        Invocation invocation = service.execute(task);

        assertThat(invocation.status()).isEqualTo(InvocationStatus.SUCCEEDED);
        assertThat(invocation.taskId()).isEqualTo(task.taskId());
        assertThat(taskRepository.findByTaskId(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(runtimeClient.lastRequest.invocationId()).isEqualTo(invocation.invocationId());
    }

    @Test
    void persistsNormalizedUsageReturnedByRuntime() {
        Task task = task("task-usage");
        taskRepository.save(task);
        Instant observedAt = Instant.parse("2026-08-13T03:00:00Z");
        runtimeClient.result = new AgentExecutionResult(
                List.of(),
                null,
                true,
                1L,
                "prompt-v1",
                new AgentExecutionUsage(
                        "codex",
                        "gpt-5.6-codex",
                        "session-123",
                        42_000L,
                        18_000L,
                        UsagePrecision.EXACT,
                        18_000L,
                        12_000L,
                        null,
                        null,
                        800L,
                        200L,
                        18_000L,
                        Map.of("input_tokens", 18_000L),
                        "0.75.0",
                        observedAt));

        Invocation invocation = service.execute(task);

        assertThat(invocationUsageRepository.findByInvocationId(invocation.invocationId()))
                .get()
                .satisfies(usage -> {
                    assertThat(usage.provider()).isEqualTo("codex");
                    assertThat(usage.model()).isEqualTo("gpt-5.6-codex");
                    assertThat(usage.providerSessionId()).isEqualTo("session-123");
                    assertThat(usage.totalInputTokens()).isEqualTo(42_000L);
                    assertThat(usage.lastRequestInputTokens()).isEqualTo(18_000L);
                    assertThat(usage.contextInputTokens()).isEqualTo(18_000L);
                    assertThat(usage.usagePrecision()).isEqualTo(UsagePrecision.EXACT);
                    assertThat(usage.observedAt()).isEqualTo(observedAt);
                });
    }

    @ParameterizedTest
    @CsvSource({
            "EXACT,100000,900000,100000,EXACT",
            "EXACT,800000,100000,800000,EXACT",
            "EXACT,0,900000,0,EXACT",
            "EXACT,,800000,800000,EXACT",
            "EXACT,,,,UNKNOWN",
            "TURN_AGGREGATE,,900000,,TURN_AGGREGATE",
            "TURN_AGGREGATE,,,,TURN_AGGREGATE",
            "ESTIMATED,900000,900000,,ESTIMATED",
            "UNKNOWN,,900000,,UNKNOWN"
    })
    void persistsUsageWithoutConfusingAggregateAndReliableContext(
            UsagePrecision precision, Long lastRequest, Long reportedContext,
            Long expectedContext, UsagePrecision expectedPrecision) {
        Task task = task("task-usage-precision");
        taskRepository.save(task);
        runtimeClient.result = new AgentExecutionResult(
                List.of(), null, true, null, "prompt-v1",
                new AgentExecutionUsage(
                        "codex", "gpt-5.6", "session-123",
                        900_000L, lastRequest, precision,
                        900_000L, null, null, null, 100L, null, reportedContext,
                        Map.of("input_tokens", 900_000L), "test", Instant.now()));

        Invocation invocation = service.execute(task);

        assertThat(invocation.status()).isEqualTo(InvocationStatus.SUCCEEDED);
        assertThat(invocationUsageRepository.findByInvocationId(invocation.invocationId()))
                .get().satisfies(usage -> {
                    assertThat(usage.totalInputTokens()).isEqualTo(900_000L);
                    assertThat(usage.inputTokens()).isEqualTo(900_000L);
                    assertThat(usage.lastRequestInputTokens()).isEqualTo(lastRequest);
                    assertThat(usage.contextInputTokens()).isEqualTo(expectedContext);
                    assertThat(usage.usagePrecision()).isEqualTo(expectedPrecision);
                });
    }

    @Test
    void marksInvocationAndTaskFailedAndBlocksSerialDescendantsOnRuntimeFailure() {
        Task head = task("task-a");
        Task child = task("task-b");
        taskRepository.save(head);
        taskRepository.save(child);
        taskDependencyRepository.saveAll(List.of(new TaskDependency("task-a", "task-b")));
        runtimeClient.failure = new RuntimeException("runtime failed");

        InvocationService dependencyAwareService = invocationService(runtimeClient);
        Invocation invocation = dependencyAwareService.execute(head);

        assertThat(invocation.status()).isEqualTo(InvocationStatus.FAILED);
        assertThat(taskRepository.findByTaskId("task-a").orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(taskRepository.findByTaskId("task-b").orElseThrow().status()).isEqualTo(TaskStatus.BLOCKED);
    }

    @Test
    void keepsCancellationWhenRuntimeReturnsAfterUserStoppedTask() {
        Task task = task("task-canceled");
        taskRepository.save(task);
        runtimeClient.result = new AgentExecutionResult(List.of(new AgentMessage(
                "ignored",
                task.taskId(),
                task.traceId(),
                task.agentId(),
                AgentMessageType.MESSAGE,
                "late answer",
                null,
                Instant.now())));
        runtimeClient.onExecute = request -> {
            invocationRepository.updateStatus(request.invocationId(), InvocationStatus.CANCELED);
            taskRepository.updateStatus(request.taskId(), TaskStatus.CANCELED);
        };

        Invocation invocation = service.execute(task);

        assertThat(invocation.status()).isEqualTo(InvocationStatus.CANCELED);
        assertThat(taskRepository.findByTaskId(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.CANCELED);
    }

    @Test
    void marksInvocationAndTaskFailedWhenRuntimeReturnsErrorMessage() {
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        InMemoryChatMessageRepository messageRepository = new InMemoryChatMessageRepository();
        InMemoryInvocationMessageRepository invocationMessageRepository = new InMemoryInvocationMessageRepository();
        Instant now = Instant.now();
        threadRepository.save(new ChatThread(
                "thread-error",
                "anonymous",
                "thread",
                ChatThreadStatus.RUNNING,
                "trace-1",
                now,
                now));
        InvocationService localService = invocationService(
                runtimeClient,
                invocationMessageRepository,
                threadRepository,
                messageRepository,
                null);
        Task task = task("task-error");
        taskRepository.save(task);
        runtimeClient.result = new AgentExecutionResult(List.of(new AgentMessage(
                "ignored",
                task.taskId(),
                task.traceId(),
                task.agentId(),
                AgentMessageType.ERROR,
                "runtime returned error",
                null,
                now)));

        Invocation invocation = localService.execute(task);

        assertThat(invocation.status()).isEqualTo(InvocationStatus.FAILED);
        assertThat(taskRepository.findByTaskId(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(invocationMessageRepository.findByInvocationId(invocation.invocationId()))
                .extracting(message -> message.type())
                .containsExactly(AgentMessageType.ERROR);
        assertThat(messageRepository.findByThreadId("thread-error"))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.status()).isEqualTo(ChatMessageStatus.FAILED);
                    assertThat(message.content()).isEqualTo("runtime returned error");
                });
    }

    @Test
    void savesInvocationMessagesAndAssistantChatMessages() {
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        InMemoryChatMessageRepository messageRepository = new InMemoryChatMessageRepository();
        InMemoryInvocationMessageRepository invocationMessageRepository = new InMemoryInvocationMessageRepository();
        Instant now = Instant.now();
        threadRepository.save(new ChatThread(
                "thread-1",
                "anonymous",
                "thread",
                ChatThreadStatus.RUNNING,
                "trace-1",
                now,
                now));
        InvocationService localService = invocationService(
                runtimeClient,
                invocationMessageRepository,
                threadRepository,
                messageRepository,
                null);
        Task task = task("task-message");
        taskRepository.save(task);
        runtimeClient.result = new AgentExecutionResult(List.of(new AgentMessage(
                "ignored",
                task.taskId(),
                task.traceId(),
                task.agentId(),
                AgentMessageType.MESSAGE,
                "hello from agent",
                null,
                Instant.now())));

        Invocation invocation = localService.execute(task);

        assertThat(invocationMessageRepository.findByInvocationId(invocation.invocationId()))
                .extracting(message -> message.content())
                .containsExactly("hello from agent");
        assertThat(messageRepository.findByThreadId("thread-1"))
                .extracting(message -> message.role())
                .containsExactly(ChatMessageRole.ASSISTANT);
        assertThat(messageRepository.findByThreadId("thread-1"))
                .extracting(ChatMessage::agentId)
                .containsExactly("opencode");
    }

    @Test
    void reconcilesPartialCallbackContentWithAuthoritativeFinalText() {
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        InMemoryChatMessageRepository messageRepository = new InMemoryChatMessageRepository();
        InMemoryInvocationMessageRepository invocationMessageRepository = new InMemoryInvocationMessageRepository();
        Instant now = Instant.now();
        threadRepository.save(new ChatThread(
                "thread-1",
                "anonymous",
                "thread",
                ChatThreadStatus.RUNNING,
                "trace-1",
                now,
                now));
        Task task = task("task-streamed");
        taskRepository.save(task);
        AssistantStreamBuffer streamBuffer = new AssistantStreamBuffer(messageRepository);
        AgentRuntimeClient callbackThenReturnSameMessage = request -> {
            invocationMessageRepository.save(new InvocationMessage(
                    "invocation-message-streamed",
                    request.userId(),
                    request.invocationId(),
                    request.taskId(),
                    request.traceId(),
                    request.agentId(),
                    AgentMessageType.MESSAGE,
                    "claude streamed",
                    null,
                    now));
            Invocation callbackInvocation = new Invocation(
                    request.invocationId(),
                    request.userId(),
                    request.taskId(),
                    request.traceId(),
                    request.agentId(),
                    InvocationStatus.RUNNING,
                    now,
                    now,
                    null);
            streamBuffer.appendChunk(callbackInvocation, "thread-1", "claude ");
            // 第二个分片不足 1KB，会留在内存 buffer 中，专门覆盖终态校准与 drain 的顺序。
            streamBuffer.appendChunk(callbackInvocation, "thread-1", "streamed");
            return new AgentExecutionResult(
                    List.of(new AgentMessage(
                            "returned",
                            request.taskId(),
                            request.traceId(),
                            request.agentId(),
                            AgentMessageType.MESSAGE,
                            "claude streamed once",
                            null,
                            now)),
                    "claude streamed once",
                    true,
                    2L);
        };
        InvocationService localService = InvocationServiceTestFactory.create(
                invocationRepository,
                taskRepository,
                callbackThenReturnSameMessage,
                "http://127.0.0.1:8080/api/callback",
                com.agentcrossing.platform.application.routing.TaskDispatchSignal.NOOP,
                invocationMessageRepository,
                threadRepository,
                messageRepository,
                null,
                null,
                streamBuffer,
                taskDependencyRepository,
                null,
                null);

        Invocation invocation = localService.execute(task);

        assertThat(invocation.status()).isEqualTo(InvocationStatus.SUCCEEDED);
        assertThat(messageRepository.findByThreadId("thread-1"))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.content()).isEqualTo("claude streamed once");
                    assertThat(message.status()).isEqualTo(ChatMessageStatus.COMPLETED);
                });
        assertThat(invocationMessageRepository.findByInvocationId(invocation.invocationId()))
                .extracting(InvocationMessage::content)
                .containsExactly("claude streamed");
    }

    @Test
    void keepsThreadRunningWhenOtherTraceTasksRemainOpen() {
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        Instant now = Instant.now();
        threadRepository.save(new ChatThread(
                "thread-1",
                "anonymous",
                "thread",
                ChatThreadStatus.RUNNING,
                "trace-1",
                now,
                now));
        InvocationService localService = invocationService(
                runtimeClient,
                null,
                threadRepository,
                null,
                null);
        Task current = task("task-current");
        Task queuedChild = new Task(
                "task-child",
                current.userId(),
                current.traceId(),
                current.createdByTaskId(),
                TaskStatus.QUEUED,
                TaskSource.AGENT,
                current.depth() + 1,
                "opencode",
                "child",
                now,
                now);
        taskRepository.save(current);
        taskRepository.save(queuedChild);
        runtimeClient.result = new AgentExecutionResult(List.of(new AgentMessage(
                "ignored",
                current.taskId(),
                current.traceId(),
                current.agentId(),
                AgentMessageType.MESSAGE,
                "done",
                null,
                now)));

        localService.execute(current);

        assertThat(threadRepository.findByTraceId("trace-1").orElseThrow().status())
                .isEqualTo(ChatThreadStatus.RUNNING);
    }

    @Test
    void injectsOnlyIncrementalVisibleChatMessagesAndUpdatesCursorOnSuccess() {
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        InMemoryChatMessageRepository messageRepository = new InMemoryChatMessageRepository();
        InMemoryAgentContextCursorRepository cursorRepository = new InMemoryAgentContextCursorRepository();
        Instant base = Instant.parse("2026-06-22T00:00:00Z");
        threadRepository.save(new ChatThread(
                "thread-1",
                "anonymous",
                "thread",
                ChatThreadStatus.RUNNING,
                "trace-1",
                base,
                base));
        messageRepository.save(chatMessage("message-1", "user", null, null, base.plusSeconds(1)));
        messageRepository.save(chatMessage("message-2", "assistant", "claude-code", "task-claude", base.plusSeconds(2)));
        messageRepository.save(chatMessage("message-3", "assistant", "opencode", "task-own", base.plusSeconds(3)));
        InvocationService localService = invocationService(
                runtimeClient,
                null,
                threadRepository,
                messageRepository,
                new AgentContextService(threadRepository, messageRepository, cursorRepository));
        Task firstTask = task("task-context-1");
        taskRepository.save(firstTask);

        localService.execute(firstTask);

        assertThat(runtimeClient.lastRequest.contextPack().incrementalChatMessages())
                .extracting(IncrementalChatMessage::messageId)
                .containsExactly("message-1", "message-2");
        messageRepository.save(chatMessage("message-4", "user", null, null, base.plusSeconds(4)));
        Task secondTask = task("task-context-2");
        taskRepository.save(secondTask);

        localService.execute(secondTask);

        assertThat(runtimeClient.lastRequest.contextPack().incrementalChatMessages())
                .extracting(IncrementalChatMessage::messageId)
                .containsExactly("message-4");
    }

    @Test
    void succeedsAfterRuntimeSuccess() {
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        InMemoryChatMessageRepository messageRepository = new InMemoryChatMessageRepository();
        InMemoryAgentContextCursorRepository cursorRepository = new InMemoryAgentContextCursorRepository();
        Instant base = Instant.parse("2026-06-22T00:00:00Z");
        threadRepository.save(new ChatThread(
                "thread-1",
                "anonymous",
                "thread",
                ChatThreadStatus.RUNNING,
                "trace-1",
                base,
                base));
        messageRepository.save(chatMessage("message-1", "user", null, null, base.plusSeconds(1)));
        InvocationService localService = invocationService(
                runtimeClient,
                null,
                threadRepository,
                messageRepository,
                new AgentContextService(threadRepository, messageRepository, cursorRepository));
        Task task = task("task-context-failed");
        taskRepository.save(task);
        runtimeClient.result = new AgentExecutionResult(List.of(new AgentMessage(
                "ignored",
                task.taskId(),
                task.traceId(),
                task.agentId(),
                AgentMessageType.MESSAGE,
                "agent output",
                null,
                base.plusSeconds(2))));

        Invocation invocation = localService.execute(task);

        assertThat(invocation.status()).isEqualTo(InvocationStatus.SUCCEEDED);
        assertThat(cursorRepository.find("anonymous", "thread-1", "opencode")).isPresent();
    }

    @Test
    void passesPersistedProviderSessionAndStoresReturnedProviderSession() {
        InMemoryAgentSessionRepository agentSessionRepository = new InMemoryAgentSessionRepository();
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        Instant now = Instant.now();
        agentSessionRepository.save(new AgentSession(
                "anonymous",
                "thread-1",
                "trace-1",
                "opencode",
                "opencode",
                "ses-existing",
                "prompt-v1",
                now,
                now));
        threadRepository.save(new ChatThread(
                "thread-1",
                "anonymous",
                "Session thread",
                ChatThreadStatus.OPEN,
                "trace-1",
                now,
                now));
        InvocationService localService = invocationService(
                runtimeClient,
                null,
                threadRepository,
                null,
                null,
                agentSessionRepository);
        Task task = task("task-session");
        taskRepository.save(task);
        runtimeClient.result = new AgentExecutionResult(List.of(new AgentMessage(
                "ignored",
                task.taskId(),
                task.traceId(),
                task.agentId(),
                AgentMessageType.DONE,
                null,
                Map.of("providerSessionId", "ses-updated", "promptVersion", "prompt-v1"),
                Instant.now())));

        localService.execute(task);

        assertThat(runtimeClient.lastRequest.providerSessionId()).isEqualTo("ses-existing");
        assertThat(runtimeClient.lastRequest.providerPromptVersion()).isEqualTo("prompt-v1");
        assertThat(agentSessionRepository.findByThreadId("anonymous", "thread-1", "opencode", "opencode"))
                .map(AgentSession::providerSessionId)
                .contains("ses-updated");
        assertThat(agentSessionRepository.findByThreadId("anonymous", "thread-1", "opencode", "opencode"))
                .map(AgentSession::threadId)
                .contains("thread-1");
        assertThat(agentSessionRepository.findByThreadId("anonymous", "thread-1", "opencode", "opencode"))
                .map(AgentSession::promptVersion)
                .contains("prompt-v1");
    }

    @Test
    void reusesProviderSessionByThreadOnly() {
        InMemoryAgentSessionRepository agentSessionRepository = new InMemoryAgentSessionRepository();
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        Instant now = Instant.now();
        agentSessionRepository.save(new AgentSession(
                "anonymous",
                "thread-1",
                "trace-from-before-thread-id-lookup",
                "opencode",
                "opencode",
                "ses-thread-existing",
                "prompt-v1",
                now,
                now));
        threadRepository.save(new ChatThread(
                "thread-1",
                "anonymous",
                "Session thread",
                ChatThreadStatus.OPEN,
                "trace-1",
                now,
                now));
        InvocationService localService = invocationService(
                runtimeClient,
                null,
                threadRepository,
                null,
                null,
                agentSessionRepository);
        Task task = task("task-thread-session");
        taskRepository.save(task);
        runtimeClient.result = new AgentExecutionResult(List.of(new AgentMessage(
                "ignored",
                task.taskId(),
                task.traceId(),
                task.agentId(),
                AgentMessageType.DONE,
                null,
                Map.of("providerSessionId", "ses-thread-updated", "promptVersion", "prompt-v1"),
                Instant.now())));

        localService.execute(task);

        assertThat(runtimeClient.lastRequest.providerSessionId()).isEqualTo("ses-thread-existing");
        assertThat(agentSessionRepository.findByThreadId("anonymous", "thread-1", "opencode", "opencode"))
                .map(AgentSession::providerSessionId)
                .contains("ses-thread-updated");
        assertThat(agentSessionRepository.findByThreadId("anonymous", "thread-1", "opencode", "opencode"))
                .map(AgentSession::threadId)
                .contains("thread-1");
    }

    @Test
    void doesNotReuseProviderSessionFromDifferentThread() {
        InMemoryAgentSessionRepository agentSessionRepository = new InMemoryAgentSessionRepository();
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        Instant now = Instant.now();
        agentSessionRepository.save(new AgentSession(
                "anonymous",
                "thread-old",
                "trace-1",
                "opencode",
                "opencode",
                "ses-old-thread",
                "prompt-v1",
                now,
                now));
        threadRepository.save(new ChatThread(
                "thread-1",
                "anonymous",
                "Session thread",
                ChatThreadStatus.OPEN,
                "trace-1",
                now,
                now));
        InvocationService localService = invocationService(
                runtimeClient,
                null,
                threadRepository,
                null,
                null,
                agentSessionRepository);
        Task task = task("task-trace-only-session");
        taskRepository.save(task);
        runtimeClient.result = new AgentExecutionResult(List.of(new AgentMessage(
                "ignored",
                task.taskId(),
                task.traceId(),
                task.agentId(),
                AgentMessageType.DONE,
                null,
                Map.of("providerSessionId", "ses-created-for-thread", "promptVersion", "prompt-v1"),
                Instant.now())));

        localService.execute(task);

        assertThat(runtimeClient.lastRequest.providerSessionId()).isNull();
        assertThat(agentSessionRepository.findByThreadId("anonymous", "thread-1", "opencode", "opencode"))
                .map(AgentSession::providerSessionId)
                .contains("ses-created-for-thread");
    }

    private static Task task(String taskId) {
        Instant now = Instant.now();
        return new Task(
                taskId,
                "anonymous",
                "trace-1",
                null,
                TaskStatus.QUEUED,
                TaskSource.USER,
                0,
                "opencode",
                "context",
                now,
                now);
    }

    private static ChatMessage chatMessage(
            String messageId,
            String role,
            String agentId,
            String taskId,
            Instant createdAt) {
        return new ChatMessage(
                messageId,
                "thread-1",
                "user".equals(role) ? ChatMessageRole.USER : ChatMessageRole.ASSISTANT,
                role + " content",
                ChatMessageStatus.COMPLETED,
                null,
                taskId,
                agentId,
                createdAt,
                createdAt);
    }

    private InvocationService invocationService(AgentRuntimeClient runtimeClient) {
        return invocationService(runtimeClient, null, null, null, null);
    }

    private InvocationService invocationService(
            AgentRuntimeClient runtimeClient,
            InMemoryInvocationMessageRepository invocationMessageRepository,
            InMemoryChatThreadRepository threadRepository,
            InMemoryChatMessageRepository messageRepository,
            AgentContextService agentContextService) {
        return invocationService(
                runtimeClient,
                invocationMessageRepository,
                threadRepository,
                messageRepository,
                agentContextService,
                null);
    }

    private InvocationService invocationService(
            AgentRuntimeClient runtimeClient,
            InMemoryInvocationMessageRepository invocationMessageRepository,
            InMemoryChatThreadRepository threadRepository,
            InMemoryChatMessageRepository messageRepository,
            AgentContextService agentContextService,
            InMemoryAgentSessionRepository agentSessionRepository) {
        return InvocationServiceTestFactory.create(
                invocationRepository,
                taskRepository,
                runtimeClient,
                "http://127.0.0.1:8080/api/callback",
                com.agentcrossing.platform.application.routing.TaskDispatchSignal.NOOP,
                invocationMessageRepository,
                threadRepository,
                messageRepository,
                null,
                null,
                null,
                taskDependencyRepository,
                agentContextService,
                agentSessionRepository,
                invocationUsageRepository);
    }

    private static final class FakeRuntimeClient implements AgentRuntimeClient {
        private AgentExecutionRequest lastRequest;
        private AgentExecutionResult result = new AgentExecutionResult(List.of());
        private RuntimeException failure;
        private Consumer<AgentExecutionRequest> onExecute;

        @Override
        public AgentExecutionResult execute(AgentExecutionRequest request) {
            lastRequest = request;
            if (onExecute != null) {
                onExecute.accept(request);
            }
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

}
