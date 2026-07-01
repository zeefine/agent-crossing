package com.agentcrossing.platform.application.chat;

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
import com.agentcrossing.platform.domain.message.InMemoryChatMessageRepository;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.session.AgentSession;
import com.agentcrossing.platform.domain.session.InMemoryAgentSessionRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskDependencyRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import java.time.Instant;
import java.util.List;
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
                now,
                now));
        parserClient.directAnswer = "继续回答。";
        parserClient.providerSessionId = "claude-new";

        sessionAwareChatService.submitUserMessage("user-1", thread.threadId(), "继续");

        assertThat(parserClient.capturedProviderSessionId).isEqualTo("claude-old");
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
    }

    private static final class FakeParserClient implements QuestParserClient {
        private String directAnswer;
        private String providerSessionId;
        private String capturedProviderSessionId;

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
                    List.of(), directAnswer, this.providerSessionId);
        }

        @Override
        public List<ParsedTask> parseAgentOutput(Task sourceTask, String output, List<Agent> availableAgents) {
            return List.of();
        }
    }
}
