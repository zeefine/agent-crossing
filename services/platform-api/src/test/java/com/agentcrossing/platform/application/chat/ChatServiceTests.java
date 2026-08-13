package com.agentcrossing.platform.application.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.TestAgentRegistries;
import com.agentcrossing.platform.application.parser.ParsedTask;
import com.agentcrossing.platform.application.parser.QuestParserClient;
import com.agentcrossing.platform.application.parser.QuestParserService;
import com.agentcrossing.platform.application.parser.ThreadExecutionSummary;
import com.agentcrossing.platform.application.routing.LoopGuardService;
import com.agentcrossing.platform.domain.context.AgentContextCursor;
import com.agentcrossing.platform.domain.context.InMemoryAgentContextCursorRepository;
import com.agentcrossing.platform.domain.agent.Agent;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.event.InMemoryEventLogRepository;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationRepository;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationUsageRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.invocation.InvocationUsage;
import com.agentcrossing.platform.domain.invocation.UsagePrecision;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageRole;
import com.agentcrossing.platform.domain.message.ChatMessageStatus;
import com.agentcrossing.platform.domain.message.InMemoryInvocationMessageRepository;
import com.agentcrossing.platform.domain.message.InMemoryChatMessageRepository;
import com.agentcrossing.platform.domain.message.InvocationMessage;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.session.AgentSession;
import com.agentcrossing.platform.domain.session.InMemoryAgentSessionRepository;
import com.agentcrossing.platform.domain.task.TaskDependency;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import com.agentcrossing.platform.domain.task.InMemoryTaskDependencyRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.application.invocation.AgentMessageType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChatServiceTests {
    private final InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
    private final InMemoryTaskDependencyRepository taskDependencyRepository = new InMemoryTaskDependencyRepository();
    private final QuestHub questHub = new QuestHub();
    private final InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
    private final InMemoryChatMessageRepository messageRepository = new InMemoryChatMessageRepository();
    private final FakeParserClient parserClient = new FakeParserClient();
    private final QuestParserService parserService = new QuestParserService(
            parserClient,
            TestAgentRegistries.withDefaultAgent(),
            taskRepository,
            taskDependencyRepository,
            questHub,
            new LoopGuardService(taskRepository));
    private final ChatService chatService = new ChatService(threadRepository, messageRepository, parserService);

    @Test
    void submitsUserMessageBeforeSavingDirectAnswerAsynchronously() {
        ChatThread thread = chatService.createThread("user-1", "New chat");
        parserClient.directAnswer = "我是 MasterAgent。";

        ChatSubmitResult result = chatService.submitUserMessage("user-1", thread.threadId(), "你有哪些队友");

        assertThat(result.tasks()).isEmpty();
        assertThat(result.assistantMessage()).isNull();
        assertThat(result.thread().status()).isEqualTo(ChatThreadStatus.RUNNING);
        assertThat(threadRepository.findByThreadId(thread.threadId()).orElseThrow().status())
                .isEqualTo(ChatThreadStatus.COMPLETED);
        assertThat(questHub.snapshot()).isEmpty();
        assertThat(messageRepository.findByThreadId(thread.threadId())).hasSize(2);
        assertThat(messageRepository.findByThreadId(thread.threadId()).getLast().content()).isEqualTo("我是 MasterAgent。");
        assertThat(messageRepository.findByThreadId(thread.threadId()).getLast().agentId())
                .isEqualTo(ChatService.MASTER_AGENT_ID);
    }

    @Test
    void serializesMasterAgentPlanningForSameUserAndThread() throws Exception {
        InMemoryTaskRepository localTaskRepository = new InMemoryTaskRepository();
        InMemoryTaskDependencyRepository localDependencyRepository = new InMemoryTaskDependencyRepository();
        QuestHub localQuestHub = new QuestHub();
        SerialParserClient serialParserClient = new SerialParserClient();
        QuestParserService localParserService = new QuestParserService(
                serialParserClient,
                TestAgentRegistries.withDefaultAgent(),
                localTaskRepository,
                localDependencyRepository,
                localQuestHub,
                new LoopGuardService(localTaskRepository));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ChatService serialChatService = new ChatService(
                threadRepository,
                messageRepository,
                localParserService,
                null,
                null,
                null,
                null,
                null,
                localTaskRepository,
                localDependencyRepository,
                null,
                null,
                localQuestHub,
                executor,
                (org.springframework.transaction.support.TransactionTemplate) null);
        ChatThread thread = serialChatService.createThread("user-1", "New chat");

        serialChatService.submitUserMessage("user-1", thread.threadId(), "第一条");
        assertThat(serialParserClient.firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        serialChatService.submitUserMessage("user-1", thread.threadId(), "第二条");

        assertThat(serialParserClient.secondStarted.await(200, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(serialParserClient.maximumActive.get()).isEqualTo(1);

        serialParserClient.releaseFirst.countDown();
        assertThat(serialParserClient.secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();

        assertThat(serialParserClient.inputs).containsExactly("第一条", "第二条");
        assertThat(serialParserClient.maximumActive.get()).isEqualTo(1);
        assertThat(threadRepository.findByThreadId(thread.threadId()).orElseThrow().status())
                .isEqualTo(ChatThreadStatus.COMPLETED);
    }

    @Test
    void keepsThreadRunningWhenCurrentPlanningCreatesNoTasksButTraceHasOpenTasks() {
        ChatService taskAwareChatService = taskAwareChatService(parserService, Runnable::run);
        ChatThread thread = taskAwareChatService.createThread("user-1", "New chat");
        Instant now = Instant.now();
        taskRepository.save(new Task(
                "task-still-running",
                "user-1",
                thread.traceId(),
                null,
                TaskStatus.PROCESSING,
                TaskSource.USER,
                0,
                "opencode",
                "continue running",
                now,
                now));
        parserClient.directAnswer = "当前输入不需要新任务。";

        taskAwareChatService.submitUserMessage("user-1", thread.threadId(), "补充说明");

        assertThat(threadRepository.findByThreadId(thread.threadId()).orElseThrow().status())
                .isEqualTo(ChatThreadStatus.RUNNING);
    }

    @Test
    void injectsBoundedTaskStateAndLatestAgentConclusionsIntoMasterAgentPlanning() {
        ChatService taskAwareChatService = taskAwareChatService(parserService, Runnable::run);
        ChatThread thread = taskAwareChatService.createThread("user-1", "New chat");
        Instant now = Instant.now();
        taskRepository.save(new Task(
                "task-finished",
                "user-1",
                thread.traceId(),
                null,
                TaskStatus.COMPLETED,
                TaskSource.USER,
                0,
                "opencode",
                "分析数据库连接池",
                now.minusSeconds(2),
                now.minusSeconds(1)));
        messageRepository.save(new ChatMessage(
                "message-agent-conclusion",
                thread.threadId(),
                ChatMessageRole.ASSISTANT,
                "连接池配置是当前性能瓶颈。",
                ChatMessageStatus.COMPLETED,
                null,
                "task-finished",
                "opencode",
                now.minusSeconds(1),
                now.minusSeconds(1)));
        messageRepository.save(new ChatMessage(
                "message-agent-old-conclusion",
                thread.threadId(),
                ChatMessageRole.ASSISTANT,
                "这条旧结论不应重复注入。",
                ChatMessageStatus.COMPLETED,
                null,
                "task-finished",
                "opencode",
                now.minusSeconds(3),
                now.minusSeconds(3)));
        parserClient.directAnswer = "收到。";

        taskAwareChatService.submitUserMessage("user-1", thread.threadId(), "继续优化");

        ThreadExecutionSummary summary = parserClient.capturedThreadExecutionSummary;
        assertThat(summary).isNotNull();
        assertThat(summary.taskStatusCounts()).containsEntry("completed", 1);
        assertThat(summary.recentTasks()).singleElement().satisfies(task -> {
            assertThat(task.taskId()).isEqualTo("task-finished");
            assertThat(task.context()).isEqualTo("分析数据库连接池");
        });
        assertThat(summary.latestAgentConclusions()).singleElement().satisfies(conclusion -> {
            assertThat(conclusion.agentId()).isEqualTo("opencode");
            assertThat(conclusion.taskId()).isEqualTo("task-finished");
            assertThat(conclusion.content()).isEqualTo("连接池配置是当前性能瓶颈。");
        });
    }

    @Test
    void reusesAndStoresMasterAgentProviderSession() {
        InMemoryAgentSessionRepository agentSessionRepository = new InMemoryAgentSessionRepository();
        QuestParserService sessionAwareParserService = new QuestParserService(
                parserClient,
                TestAgentRegistries.withDefaultAgent(),
                taskRepository,
                taskDependencyRepository,
                questHub,
                new LoopGuardService(taskRepository),
                com.agentcrossing.platform.application.routing.TaskDispatchSignal.NOOP,
                null,
                null,
                agentSessionRepository);
        ChatService sessionAwareChatService = new ChatService(threadRepository, messageRepository, sessionAwareParserService);
        ChatThread thread = sessionAwareChatService.createThread("user-1", "New chat");
        Instant now = Instant.now();
        agentSessionRepository.save(new AgentSession(
                "user-1",
                thread.threadId(),
                "trace-from-before-thread-id-lookup",
                QuestParserService.MASTER_AGENT_ID,
                QuestParserService.MASTER_AGENT_PROVIDER,
                "claude-old",
                "prompt-v1",
                now,
                now));
        parserClient.directAnswer = "继续回答。";
        parserClient.providerSessionId = "claude-new";
        parserClient.promptVersion = "prompt-v1";

        sessionAwareChatService.submitUserMessage("user-1", thread.threadId(), "继续");

        assertThat(parserClient.capturedProviderSessionId).isEqualTo("claude-old");
        assertThat(parserClient.capturedProviderPromptVersion).isEqualTo("prompt-v1");
        assertThat(agentSessionRepository
                        .findByThreadId(
                                "user-1",
                                thread.threadId(),
                                QuestParserService.MASTER_AGENT_ID,
                                QuestParserService.MASTER_AGENT_PROVIDER)
                        .orElseThrow()
                        .providerSessionId())
                .isEqualTo("claude-new");
        assertThat(agentSessionRepository
                        .findByThreadId(
                                "user-1",
                                thread.threadId(),
                                QuestParserService.MASTER_AGENT_ID,
                                QuestParserService.MASTER_AGENT_PROVIDER)
                        .orElseThrow()
                        .threadId())
                .isEqualTo(thread.threadId());
        assertThat(agentSessionRepository
                        .findByThreadId(
                                "user-1",
                                thread.threadId(),
                                QuestParserService.MASTER_AGENT_ID,
                                QuestParserService.MASTER_AGENT_PROVIDER)
                        .orElseThrow()
                        .promptVersion())
                .isEqualTo("prompt-v1");
    }

    @Test
    void deleteThreadRemovesTraceScopedRuntimeDataAndThreadScopedState() {
        InMemoryInvocationMessageRepository invocationMessageRepository = new InMemoryInvocationMessageRepository();
        InMemoryInvocationRepository invocationRepository = new InMemoryInvocationRepository();
        InMemoryInvocationUsageRepository invocationUsageRepository = new InMemoryInvocationUsageRepository();
        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
        InMemoryTaskDependencyRepository taskDependencyRepository = new InMemoryTaskDependencyRepository();
        InMemoryAgentContextCursorRepository cursorRepository = new InMemoryAgentContextCursorRepository();
        InMemoryAgentSessionRepository agentSessionRepository = new InMemoryAgentSessionRepository();
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        QuestHub questHub = new QuestHub();
        ChatService cascadingChatService = new ChatService(
                threadRepository,
                messageRepository,
                parserService,
                null,
                eventLogRepository,
                null,
                invocationMessageRepository,
                invocationRepository,
                taskRepository,
                taskDependencyRepository,
                cursorRepository,
                agentSessionRepository,
                questHub,
                Runnable::run,
                (org.springframework.transaction.support.TransactionTemplate) null);
        cascadingChatService.setInvocationUsageRepository(invocationUsageRepository);
        ChatThread thread = cascadingChatService.createThread("user-1", "delete me");
        Instant now = Instant.now();
        Task queuedTask = new Task(
                "task-delete-1",
                "user-1",
                thread.traceId(),
                null,
                TaskStatus.QUEUED,
                TaskSource.USER,
                0,
                "opencode",
                "first",
                now,
                now);
        Task processingTask = new Task(
                "task-delete-2",
                "user-1",
                thread.traceId(),
                queuedTask.taskId(),
                TaskStatus.PROCESSING,
                TaskSource.AGENT,
                1,
                "claudecode",
                "second",
                now,
                now);
        taskRepository.save(queuedTask);
        taskRepository.save(processingTask);
        taskDependencyRepository.saveAll(List.of(new TaskDependency(queuedTask.taskId(), processingTask.taskId())));
        questHub.enqueue(queuedTask.taskId());
        questHub.enqueue(processingTask.taskId());
        invocationRepository.save(new Invocation(
                "inv-delete-1",
                "user-1",
                queuedTask.taskId(),
                thread.traceId(),
                "opencode",
                InvocationStatus.RUNNING,
                now,
                now,
                null));
        invocationUsageRepository.save(new InvocationUsage(
                "inv-delete-1", "opencode", "gpt-5.6", "ses-delete",
                100L, 100L, UsagePrecision.EXACT, 100L, null, null, null,
                10L, null, 100L, Map.of("input_tokens", 100L), "test", now));
        invocationMessageRepository.save(new InvocationMessage(
                "inv-msg-delete-1",
                "user-1",
                "inv-delete-1",
                queuedTask.taskId(),
                thread.traceId(),
                "opencode",
                AgentMessageType.TEXT_DELTA,
                "chunk",
                null,
                now));
        messageRepository.save(new ChatMessage(
                "chat-msg-delete-1",
                thread.threadId(),
                ChatMessageRole.USER,
                "hello",
                ChatMessageStatus.COMPLETED,
                null,
                null,
                null,
                now,
                now));
        cursorRepository.save(new AgentContextCursor("user-1", thread.threadId(), "opencode", now, "m1", now));
        agentSessionRepository.save(new AgentSession(
                "user-1",
                thread.threadId(),
                thread.traceId(),
                "opencode",
                "opencode",
                "ses-delete",
                "prompt-v1",
                now,
                now));
        eventLogRepository.save(thread.threadId(), "chatMessage", "payload");

        cascadingChatService.deleteThread("user-1", thread.threadId());

        assertThat(threadRepository.findByThreadId(thread.threadId())).isEmpty();
        assertThat(messageRepository.findByThreadId(thread.threadId())).isEmpty();
        assertThat(invocationMessageRepository.findByTraceIdAndUserId(thread.traceId(), "user-1")).isEmpty();
        assertThat(invocationRepository.findByTraceIdAndUserId(thread.traceId(), "user-1")).isEmpty();
        assertThat(invocationUsageRepository.findByInvocationId("inv-delete-1")).isEmpty();
        assertThat(taskRepository.findByTraceIdAndUserId(thread.traceId(), "user-1")).isEmpty();
        assertThat(taskDependencyRepository.findChildTaskIds(queuedTask.taskId())).isEmpty();
        assertThat(taskDependencyRepository.findParentTaskIds(processingTask.taskId())).isEmpty();
        assertThat(cursorRepository.find("user-1", thread.threadId(), "opencode")).isEmpty();
        assertThat(agentSessionRepository.findByThreadId("user-1", thread.threadId(), "opencode", "opencode")).isEmpty();
        assertThat(eventLogRepository.findAfter(thread.threadId(), 0, 10)).isEmpty();
        assertThat(questHub.snapshot()).doesNotContain(queuedTask.taskId(), processingTask.taskId());
    }

    private ChatService taskAwareChatService(QuestParserService service, Executor executor) {
        return new ChatService(
                threadRepository,
                messageRepository,
                service,
                null,
                null,
                null,
                null,
                null,
                taskRepository,
                taskDependencyRepository,
                null,
                null,
                questHub,
                executor,
                (org.springframework.transaction.support.TransactionTemplate) null);
    }

    private static final class FakeParserClient implements QuestParserClient {
        private String directAnswer;
        private String providerSessionId;
        private String promptVersion;
        private String capturedProviderSessionId;
        private String capturedProviderPromptVersion;
        private ThreadExecutionSummary capturedThreadExecutionSummary;

        @Override
        public com.agentcrossing.platform.application.parser.UserInputParseResult parseUserInput(
                String input, List<Agent> availableAgents) {
            return new com.agentcrossing.platform.application.parser.UserInputParseResult(List.of(), directAnswer);
        }

        @Override
        public com.agentcrossing.platform.application.parser.UserInputParseResult parseUserInput(
                String userId,
                String threadId,
                String traceId,
                String input,
                String providerSessionId,
                List<Agent> availableAgents) {
            capturedProviderSessionId = providerSessionId;
            return new com.agentcrossing.platform.application.parser.UserInputParseResult(
                    List.of(), directAnswer, this.providerSessionId, this.promptVersion);
        }

        @Override
        public com.agentcrossing.platform.application.parser.UserInputParseResult parseUserInput(
                String userId,
                String threadId,
                String traceId,
                String input,
                String providerSessionId,
                String providerPromptVersion,
                List<Agent> availableAgents,
                ThreadExecutionSummary threadExecutionSummary) {
            capturedProviderSessionId = providerSessionId;
            capturedProviderPromptVersion = providerPromptVersion;
            capturedThreadExecutionSummary = threadExecutionSummary;
            return new com.agentcrossing.platform.application.parser.UserInputParseResult(
                    List.of(), directAnswer, this.providerSessionId, this.promptVersion);
        }

        @Override
        public com.agentcrossing.platform.application.parser.UserInputParseResult parseUserInput(
                String userId,
                String threadId,
                String traceId,
                String input,
                String providerSessionId,
                List<Agent> availableAgents,
                ThreadExecutionSummary threadExecutionSummary) {
            capturedThreadExecutionSummary = threadExecutionSummary;
            return parseUserInput(userId, threadId, traceId, input, providerSessionId, availableAgents);
        }

    }

    private static final class SerialParserClient implements QuestParserClient {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch secondStarted = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximumActive = new AtomicInteger();
        private final List<String> inputs = new CopyOnWriteArrayList<>();

        @Override
        public com.agentcrossing.platform.application.parser.UserInputParseResult parseUserInput(
                String input, List<Agent> availableAgents) {
            return parse(input);
        }

        @Override
        public com.agentcrossing.platform.application.parser.UserInputParseResult parseUserInput(
                String userId,
                String threadId,
                String traceId,
                String input,
                String providerSessionId,
                List<Agent> availableAgents) {
            return parse(input);
        }

        private com.agentcrossing.platform.application.parser.UserInputParseResult parse(String input) {
            int call = calls.incrementAndGet();
            int activeCount = active.incrementAndGet();
            maximumActive.accumulateAndGet(activeCount, Math::max);
            inputs.add(input);
            try {
                if (call == 1) {
                    firstStarted.countDown();
                    if (!releaseFirst.await(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("first planning was not released");
                    }
                } else {
                    secondStarted.countDown();
                }
                return new com.agentcrossing.platform.application.parser.UserInputParseResult(
                        List.of(),
                        "answer-" + call);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("planning interrupted", exception);
            } finally {
                active.decrementAndGet();
            }
        }
    }
}
