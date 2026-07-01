package com.agentcrossing.platform.application.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.application.chat.ChatEventService;
import com.agentcrossing.platform.application.realtime.SocketManager;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.event.InMemoryEventLogRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.InMemoryTaskDependencyRepository;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TaskEventServiceTests {
    @Test
    void publishesTaskEventWhenTraceMapsToKnownThread() {
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        ChatEventService chatEventService =
                new ChatEventService(new SocketManager(new ObjectMapper(), eventLogRepository), eventLogRepository);
        TaskEventService taskEventService = new TaskEventService(
                threadRepository, chatEventService, new InMemoryTaskDependencyRepository());
        threadRepository.save(new ChatThread(
                "thread-1",
                "anonymous",
                "known",
                ChatThreadStatus.RUNNING,
                "trace-1",
                Instant.now(),
                Instant.now()));

        taskEventService.publish(task("task-known", "trace-1"));

        assertThat(eventLogRepository.findAfter("thread-1", 0L, 10))
                .extracting(event -> event.type())
                .containsExactly("task");
    }

    @Test
    void ignoresUnknownTraceWithoutCreatingEvent() {
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        ChatEventService chatEventService =
                new ChatEventService(new SocketManager(new ObjectMapper(), eventLogRepository), eventLogRepository);
        TaskEventService taskEventService = new TaskEventService(
                threadRepository, chatEventService, new InMemoryTaskDependencyRepository());

        taskEventService.publish(task("task-unknown", "trace-missing"));

        assertThat(eventLogRepository.findAfter("thread-missing", 0L, 10)).isEmpty();
    }

    private static Task task(String taskId, String traceId) {
        Instant now = Instant.now();
        return new Task(
                taskId,
                "anonymous",
                traceId,
                null,
                TaskStatus.QUEUED,
                TaskSource.USER,
                0,
                "opencode",
                "context",
                now,
                now);
    }
}
