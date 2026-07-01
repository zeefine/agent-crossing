package com.agentcrossing.platform.application.invocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.TestAgentRegistries;
import com.agentcrossing.platform.application.parser.ParsedTask;
import com.agentcrossing.platform.application.parser.QuestParserClient;
import com.agentcrossing.platform.application.parser.QuestParserService;
import com.agentcrossing.platform.application.routing.LoopGuardService;
import com.agentcrossing.platform.domain.agent.Agent;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.context.InMemoryAgentContextCursorRepository;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageRole;
import com.agentcrossing.platform.domain.message.ChatMessageStatus;
import com.agentcrossing.platform.domain.message.InMemoryChatMessageRepository;
import com.agentcrossing.platform.domain.message.InMemoryInvocationMessageRepository;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.session.AgentSession;
import com.agentcrossing.platform.domain.session.InMemoryAgentSessionRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskDependencyRepository;
import com.agentcrossing.platform.domain.task.TaskDependency;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InvocationServiceTests {
    private final InMemoryInvocationRepository invocationRepository = new InMemoryInvocationRepository();
    private final InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
    private final InMemoryTaskDependencyRepository taskDependencyRepository = new InMemoryTaskDependencyRepository();
    private final QuestHub questHub = new QuestHub();
    private final FakeRuntimeClient runtimeClient = new FakeRuntimeClient();
    private final FakeParserClient parserClient = new FakeParserClient();
    private final InvocationService service = invocationService(runtimeClient, parserService(parserClient));

    @Test
    void createsInvocationRunsRuntimeCompletesTaskAndParsesOutput() {
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
        parserClient.agentTasks = List.of(new ParsedTask("task-child", "opencode", "follow up", List.of()));

        Invocation invocation = service.execute(task);

        assertThat(invocation.status()).isEqualTo(InvocationStatus.SUCCEEDED);
        assertThat(invocation.taskId()).isEqualTo(task.taskId());
        assertThat(taskRepository.findByTaskId(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(questHub.snapshot()).containsExactly("task-child");
        assertThat(runtimeClient.lastRequest.invocationId()).isEqualTo(invocation.invocationId());
    }

    @Test
    void marksInvocationAndTaskFailedAndBlocksSerialDescendantsOnRuntimeFailure() {
        Task head = task("task-a");
        Task child = task("task-b");
        taskRepository.save(head);
        taskRepository.save(child);
        taskDependencyRepository.saveAll(List.of(new TaskDependency("task-a", "task-b")));
        runtimeClient.failure = new RuntimeException("runtime failed");

        InvocationService dependencyAwareService = invocationService(runtimeClient, parserService(parserClient));
        Invocation invocation = dependencyAwareService.execute(head);

        assertThat(invocation.status()).isEqualTo(InvocationStatus.FAILED);
        assertThat(taskRepository.findByTaskId("task-a").orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(taskRepository.findByTaskId("task-b").orElseThrow().status()).isEqualTo(TaskStatus.BLOCKED);
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
                parserService(parserClient),
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
        assertThat(parserClient.parseAgentOutputCalls).isZero();
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
                parserService(parserClient),
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
                parserService(parserClient),
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
    void doesNotUpdateContextCursorWhenPostRuntimeProcessingFails() {
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
        FakeParserClient failingParserClient = new FakeParserClient();
        failingParserClient.agentOutputFailure = new RuntimeException("parser failed");
        InvocationService localService = invocationService(
                runtimeClient,
                parserService(failingParserClient),
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

        assertThat(invocation.status()).isEqualTo(InvocationStatus.FAILED);
        assertThat(cursorRepository.find("anonymous", "thread-1", "opencode")).isEmpty();
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
                parserService(parserClient),
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
                Map.of("providerSessionId", "ses-updated"),
                Instant.now())));

        localService.execute(task);

        assertThat(runtimeClient.lastRequest.providerSessionId()).isEqualTo("ses-existing");
        assertThat(agentSessionRepository.findByThreadId("anonymous", "thread-1", "opencode", "opencode"))
                .map(AgentSession::providerSessionId)
                .contains("ses-updated");
        assertThat(agentSessionRepository.findByThreadId("anonymous", "thread-1", "opencode", "opencode"))
                .map(AgentSession::threadId)
                .contains("thread-1");
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
                parserService(parserClient),
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
                Map.of("providerSessionId", "ses-thread-updated"),
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
                parserService(parserClient),
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
                Map.of("providerSessionId", "ses-created-for-thread"),
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

    private QuestParserService parserService(QuestParserClient parserClient) {
        return new QuestParserService(
                parserClient,
                TestAgentRegistries.withDefaultAgent(),
                taskRepository,
                taskDependencyRepository,
                questHub,
                new LoopGuardService(taskRepository));
    }

    private InvocationService invocationService(AgentRuntimeClient runtimeClient, QuestParserService parserService) {
        return invocationService(runtimeClient, parserService, null, null, null, null);
    }

    private InvocationService invocationService(
            AgentRuntimeClient runtimeClient,
            QuestParserService parserService,
            InMemoryInvocationMessageRepository invocationMessageRepository,
            InMemoryChatThreadRepository threadRepository,
            InMemoryChatMessageRepository messageRepository,
            AgentContextService agentContextService) {
        return invocationService(
                runtimeClient,
                parserService,
                invocationMessageRepository,
                threadRepository,
                messageRepository,
                agentContextService,
                null);
    }

    private InvocationService invocationService(
            AgentRuntimeClient runtimeClient,
            QuestParserService parserService,
            InMemoryInvocationMessageRepository invocationMessageRepository,
            InMemoryChatThreadRepository threadRepository,
            InMemoryChatMessageRepository messageRepository,
            AgentContextService agentContextService,
            InMemoryAgentSessionRepository agentSessionRepository) {
        return new InvocationService(
                invocationRepository,
                taskRepository,
                runtimeClient,
                parserService,
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
                agentSessionRepository);
    }

    private static final class FakeRuntimeClient implements AgentRuntimeClient {
        private AgentExecutionRequest lastRequest;
        private AgentExecutionResult result = new AgentExecutionResult(List.of());
        private RuntimeException failure;

        @Override
        public AgentExecutionResult execute(AgentExecutionRequest request) {
            lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private static final class FakeParserClient implements QuestParserClient {
        private List<ParsedTask> agentTasks = new ArrayList<>();
        private RuntimeException agentOutputFailure;
        private int parseAgentOutputCalls;

        @Override
        public com.agentcrossing.platform.application.parser.UserInputParseResult parseUserInput(
                String input, List<Agent> availableAgents) {
            return new com.agentcrossing.platform.application.parser.UserInputParseResult(List.of(), null);
        }

        @Override
        public List<ParsedTask> parseAgentOutput(Task sourceTask, String output, List<Agent> availableAgents) {
            parseAgentOutputCalls++;
            if (agentOutputFailure != null) {
                throw agentOutputFailure;
            }
            return agentTasks;
        }
    }
}
