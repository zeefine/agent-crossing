package com.agentcrossing.platform.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.TestAgentRegistries;
import com.agentcrossing.platform.api.dto.ApiResponse;
import com.agentcrossing.platform.api.dto.ChatMessageResponse;
import com.agentcrossing.platform.api.dto.ChatThreadResponse;
import com.agentcrossing.platform.api.dto.CreateChatThreadRequest;
import com.agentcrossing.platform.api.dto.SubmitChatMessageRequest;
import com.agentcrossing.platform.api.dto.SubmitChatMessageResponse;
import com.agentcrossing.platform.application.chat.ChatEventService;
import com.agentcrossing.platform.application.realtime.SocketManager;
import com.agentcrossing.platform.application.chat.ChatService;
import com.agentcrossing.platform.application.parser.ParsedTask;
import com.agentcrossing.platform.application.parser.QuestParserClient;
import com.agentcrossing.platform.application.parser.QuestParserService;
import com.agentcrossing.platform.application.routing.LoopGuardService;
import com.agentcrossing.platform.domain.agent.Agent;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.event.InMemoryEventLogRepository;
import com.agentcrossing.platform.domain.message.InMemoryChatMessageRepository;
import com.agentcrossing.platform.domain.message.InMemoryInvocationMessageRepository;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.task.InMemoryTaskDependencyRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ChatControllerTests {
    private final InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
    private final InMemoryTaskDependencyRepository taskDependencyRepository = new InMemoryTaskDependencyRepository();
    private final QuestHub questHub = new QuestHub();
    private final InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
    private final InMemoryChatMessageRepository messageRepository = new InMemoryChatMessageRepository();
    private final InMemoryInvocationMessageRepository invocationMessageRepository = new InMemoryInvocationMessageRepository();
    private final InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
    private final ChatEventService chatEventService =
            new ChatEventService(
                    new SocketManager(new ObjectMapper(), eventLogRepository),
                    eventLogRepository);
    private final ChatController controller;

    ChatControllerTests() {
        QuestParserService parserService = new QuestParserService(
                new FakeParserClient(),
                TestAgentRegistries.withDefaultAgent(),
                taskRepository,
                taskDependencyRepository,
                questHub,
                new LoopGuardService(taskRepository));
        ChatService chatService = new ChatService(threadRepository, messageRepository, parserService, chatEventService);
        controller = new ChatController(
                chatService,
                threadRepository,
                messageRepository,
                invocationMessageRepository,
                chatEventService,
                taskDependencyRepository);
    }

    @Test
    void createsThreadAndSubmitsMessageThroughTaskQueue() {
        ApiResponse<ChatThreadResponse> created = controller.createThread("anonymous", new CreateChatThreadRequest(null));
        String threadId = created.data().threadId();

        ApiResponse<SubmitChatMessageResponse> submitted =
                controller.submitMessage("anonymous", threadId, new SubmitChatMessageRequest("build a plan"));

        assertThat(submitted.data().thread().title()).isEqualTo("build a plan");
        assertThat(submitted.data().thread().status()).isEqualTo("running");
        assertThat(submitted.data().tasks()).isEmpty();
        // 一个 thread 只维护一个 trace：用户后续输入都是同一任务上下文的继续。
        assertThat(submitted.data().thread().traceId()).isEqualTo(created.data().traceId());
        assertThat(taskRepository.findByTaskId("task-chat").orElseThrow().traceId())
                .isEqualTo(submitted.data().thread().traceId());
        assertThat(questHub.snapshot()).containsExactly("task-chat");
        List<ChatMessageResponse> messages = controller.getMessages("anonymous", threadId).data();
        assertThat(messages).extracting(ChatMessageResponse::role).containsExactly("user");
        assertThat(messages).extracting(ChatMessageResponse::agentId).containsExactly((String) null);
        assertThat(controller.getThreads("anonymous").data()).hasSize(1);
    }

    @Test
    void createsSseEmitterForExistingThread() {
        String threadId = controller.createThread("anonymous", new CreateChatThreadRequest("events")).data().threadId();

        assertThat(controller.streamEvents("anonymous", threadId, null, null)).isNotNull();
    }

    private static final class FakeParserClient implements QuestParserClient {
        @Override
        public com.agentcrossing.platform.application.parser.UserInputParseResult parseUserInput(
                String input, List<Agent> availableAgents) {
            return new com.agentcrossing.platform.application.parser.UserInputParseResult(
                    List.of(new ParsedTask("task-chat", "opencode", input, List.of())), null);
        }

        @Override
        public List<ParsedTask> parseAgentOutput(Task sourceTask, String output, List<Agent> availableAgents) {
            return List.of();
        }
    }
}
