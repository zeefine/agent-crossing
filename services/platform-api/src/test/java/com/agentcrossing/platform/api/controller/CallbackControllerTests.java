package com.agentcrossing.platform.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.agentcrossing.platform.api.dto.CallbackMessageRequest;
import com.agentcrossing.platform.api.dto.TaskResponse;
import com.agentcrossing.platform.application.chat.AssistantStreamBuffer;
import com.agentcrossing.platform.application.chat.ChatEventService;
import com.agentcrossing.platform.application.realtime.SocketManager;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class CallbackControllerTests {
    @Test
    void waitingCallbackStartsFreshTransactionOnlyAfterAcquiringStreamLock() throws Exception {
        var invocations = new InMemoryInvocationRepository();
        var messages = new InMemoryChatMessageRepository();
        var events = new InMemoryInvocationMessageRepository();
        var publisher = mock(ChatEventService.class);
        var buffer = new AssistantStreamBuffer(messages);
        var controller = new CallbackController(invocations, events, new InMemoryChatThreadRepository(), buffer, publisher);
        Instant now = Instant.now();
        invocations.save(new Invocation("inv", "user", "task", "trace", "codex", InvocationStatus.RUNNING, now, now, null));
        AtomicInteger begins = new AtomicInteger();
        controller.setTransactionManager(new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { begins.incrementAndGet(); }
            @Override protected void doCommit(DefaultTransactionStatus status) {}
            @Override protected void doRollback(DefaultTransactionStatus status) {}
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            var callback = buffer.withInvocationLock("inv", () -> {
                CountDownLatch started = new CountDownLatch(1);
                var pending = worker.submit(() -> {
                    started.countDown();
                    return controller.postMessage(new CallbackMessageRequest("inv", "late"));
                });
                try {
                    assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    throw new AssertionError(exception);
                }
                assertThatThrownBy(() -> pending.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(begins.get()).isZero();
                invocations.updateStatus("inv", InvocationStatus.SUCCEEDED);
                return pending;
            });
            assertThat(callback.get(3, TimeUnit.SECONDS).data()).isEmpty();
        }
        assertThat(begins.get()).isEqualTo(1);
        assertThat(events.findByInvocationId("inv")).isEmpty();
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @EnumSource(value = InvocationStatus.class, names = {"SUCCEEDED", "FAILED", "CANCELED"})
    void ignoresEveryTerminalInvocationWithoutCreatingMessagesOrPublishingEvents(InvocationStatus terminal) {
        var invocations = new InMemoryInvocationRepository();
        var threads = new InMemoryChatThreadRepository();
        var messages = new InMemoryChatMessageRepository();
        var events = new InMemoryInvocationMessageRepository();
        var publisher = mock(ChatEventService.class);
        Instant now = Instant.now();
        invocations.save(new Invocation("inv", "user", "task", "trace", "codex", terminal, now, now, now));
        threads.save(new ChatThread("thread", "user", "thread", ChatThreadStatus.COMPLETED, "trace", now, now));
        var buffer = new AssistantStreamBuffer(messages);
        var controller = new CallbackController(invocations, events, threads, buffer, publisher);

        assertThat(controller.postMessage(new CallbackMessageRequest("inv", "late chunk", true, 99L)).data()).isEmpty();
        buffer.drain("inv");

        assertThat(events.findByInvocationId("inv")).isEmpty();
        assertThat(messages.findByThreadId("thread")).isEmpty();
        verifyNoInteractions(publisher);
    }

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

    @Test
    void duplicateCallbackSequenceIsIdempotent() {
        InMemoryInvocationRepository invocationRepository = new InMemoryInvocationRepository();
        Invocation invocation = new Invocation(
                "invocation-1",
                "anonymous",
                "task-1",
                "trace-1",
                "claudecode",
                InvocationStatus.RUNNING,
                Instant.now(),
                Instant.now(),
                null);
        invocationRepository.save(invocation);
        InMemoryInvocationMessageRepository invocationMessageRepository = new InMemoryInvocationMessageRepository();
        InMemoryEventLogRepository eventLogRepository = new InMemoryEventLogRepository();
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        threadRepository.save(new ChatThread(
                "thread-1",
                "anonymous",
                "thread",
                ChatThreadStatus.RUNNING,
                "trace-1",
                Instant.now(),
                Instant.now()));
        InMemoryChatMessageRepository chatMessageRepository = new InMemoryChatMessageRepository();
        CallbackController controller = new CallbackController(
                invocationRepository,
                invocationMessageRepository,
                threadRepository,
                new AssistantStreamBuffer(chatMessageRepository),
                new ChatEventService(new SocketManager(new ObjectMapper(), eventLogRepository), eventLogRepository));

        CallbackMessageRequest callback = new CallbackMessageRequest("invocation-1", "hello", true, 1L);
        controller.postMessage(callback);
        controller.postMessage(callback);

        assertThat(invocationMessageRepository.findByInvocationId("invocation-1"))
                .singleElement()
                .satisfies(message -> assertThat(message.sequence()).isEqualTo(1L));
        assertThat(chatMessageRepository.findByThreadId("thread-1"))
                .singleElement()
                .satisfies(message -> assertThat(message.content()).isEqualTo("hello"));
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
