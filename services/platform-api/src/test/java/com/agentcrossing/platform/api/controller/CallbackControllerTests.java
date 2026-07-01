package com.agentcrossing.platform.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.TestAgentRegistries;
import com.agentcrossing.platform.api.dto.CallbackMessageRequest;
import com.agentcrossing.platform.api.dto.TaskResponse;
import com.agentcrossing.platform.application.chat.AssistantStreamBuffer;
import com.agentcrossing.platform.application.chat.ChatEventService;
import com.agentcrossing.platform.application.realtime.SocketManager;
import com.agentcrossing.platform.application.parser.ParsedTask;
import com.agentcrossing.platform.application.parser.QuestParserClient;
import com.agentcrossing.platform.application.parser.QuestParserService;
import com.agentcrossing.platform.application.routing.LoopGuardService;
import com.agentcrossing.platform.domain.agent.Agent;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.event.InMemoryEventLogRepository;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.message.InMemoryChatMessageRepository;
import com.agentcrossing.platform.domain.message.InMemoryInvocationMessageRepository;
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

class CallbackControllerTests {
    @Test
    void callbackMessageParsesAgentOutputIntoQuestHubTasksWithoutToken() {
        InMemoryInvocationRepository invocationRepository = new InMemoryInvocationRepository();
        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
        InMemoryTaskDependencyRepository taskDependencyRepository = new InMemoryTaskDependencyRepository();
        QuestHub questHub = new QuestHub();
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        Task sourceTask = task("task-1");
        taskRepository.save(sourceTask);
        invocationRepository.save(new Invocation(
                "invocation-1",
                "anonymous",
                sourceTask.taskId(),
                sourceTask.traceId(),
                sourceTask.agentId(),
                InvocationStatus.RUNNING,
                Instant.now(),
                Instant.now(),
                null));
        InMemoryChatMessageRepository chatMessageRepository = new InMemoryChatMessageRepository();
        InMemoryInvocationMessageRepository invocationMessageRepository = new InMemoryInvocationMessageRepository();
        CallbackController controller = new CallbackController(
                invocationRepository,
                taskRepository,
                new QuestParserService(
                        new FakeParserClient(),
                        TestAgentRegistries.withDefaultAgent(),
                        taskRepository,
                        taskDependencyRepository,
                        questHub,
                        new LoopGuardService(taskRepository)),
                invocationMessageRepository,
                new InMemoryChatThreadRepository(),
                new AssistantStreamBuffer(chatMessageRepository),
                new ChatEventService(
                        new SocketManager(new ObjectMapper(), eventLogRepository),
                        eventLogRepository),
                taskDependencyRepository);

        List<TaskResponse> created = controller
                .postMessage(new CallbackMessageRequest("invocation-1", "@opencode continue"))
                .data();

        assertThat(created).extracting(TaskResponse::taskId).containsExactly("task-callback");
        assertThat(questHub.snapshot()).containsExactly("task-callback");
    }

    private static Task task(String taskId) {
        Instant now = Instant.now();
        return new Task(
                taskId,
                "anonymous",
                "trace-1",
                null,
                TaskStatus.PROCESSING,
                TaskSource.USER,
                0,
                "opencode",
                "context",
                now,
                now);
    }

    private static final class FakeParserClient implements QuestParserClient {
        @Override
        public com.agentcrossing.platform.application.parser.UserInputParseResult parseUserInput(
                String input, List<Agent> availableAgents) {
            return new com.agentcrossing.platform.application.parser.UserInputParseResult(List.of(), null);
        }

        @Override
        public List<ParsedTask> parseAgentOutput(Task sourceTask, String output, List<Agent> availableAgents) {
            return List.of(new ParsedTask("task-callback", "opencode", "continue", List.of()));
        }
    }
}
