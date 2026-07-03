package com.agentcrossing.platform.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.api.dto.CallbackMessageRequest;
import com.agentcrossing.platform.api.dto.TaskResponse;
import com.agentcrossing.platform.application.chat.AssistantStreamBuffer;
import com.agentcrossing.platform.application.chat.ChatEventService;
import com.agentcrossing.platform.application.realtime.SocketManager;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.event.InMemoryEventLogRepository;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.message.InMemoryChatMessageRepository;
import com.agentcrossing.platform.domain.message.InMemoryInvocationMessageRepository;
import com.agentcrossing.platform.domain.queue.QuestHub;
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
    void callbackMessageOnlyPersistsAndPublishesContentWithoutParsingTasks() {
        InMemoryInvocationRepository invocationRepository = new InMemoryInvocationRepository();
        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
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
                invocationMessageRepository,
                new InMemoryChatThreadRepository(),
                new AssistantStreamBuffer(chatMessageRepository),
                new ChatEventService(
                        new SocketManager(new ObjectMapper(), eventLogRepository),
                        eventLogRepository));

        List<TaskResponse> created = controller
                .postMessage(new CallbackMessageRequest("invocation-1", "@opencode continue"))
                .data();

        assertThat(created).isEmpty();
        assertThat(questHub.snapshot()).isEmpty();
        assertThat(invocationMessageRepository.findByInvocationId("invocation-1"))
                .singleElement()
                .satisfies(message -> assertThat(message.content()).isEqualTo("@opencode continue"));
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
}
