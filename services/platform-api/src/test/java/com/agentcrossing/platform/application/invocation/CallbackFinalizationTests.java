package com.agentcrossing.platform.application.invocation;

import com.agentcrossing.platform.support.InvocationServiceTestFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.agentcrossing.platform.api.controller.CallbackController;
import com.agentcrossing.platform.api.dto.CallbackMessageRequest;
import com.agentcrossing.platform.application.chat.AssistantStreamBuffer;
import com.agentcrossing.platform.application.chat.ChatEventService;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationRepository;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageStatus;
import com.agentcrossing.platform.domain.message.InMemoryChatMessageRepository;
import com.agentcrossing.platform.domain.message.InMemoryInvocationMessageRepository;
import com.agentcrossing.platform.domain.message.InvocationMessage;
import com.agentcrossing.platform.domain.task.InMemoryTaskDependencyRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class CallbackFinalizationTests {
    @Test
    void callbackRetainsStreamLockUntilItsTransactionCommits() throws Exception {
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        var fixture = new Fixture(new InMemoryChatMessageRepository(), new InMemoryInvocationMessageRepository(), false);
        var manager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
            @Override protected void doCommit(DefaultTransactionStatus status) {
                committing.countDown();
                await(allowCommit);
            }
            @Override protected void doRollback(DefaultTransactionStatus status) {}
        };
        ReflectionTestUtils.invokeMethod(fixture.controller, "setTransactionManager", manager);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var execution = workers.submit(() -> fixture.service.execute(fixture.task));
            try {
                assertThat(fixture.runtimeStarted.await(3, TimeUnit.SECONDS)).isTrue();
                var callback = workers.submit(() -> fixture.controller.postMessage(fixture.callback("partial")));
                assertThat(committing.await(3, TimeUnit.SECONDS)).isTrue();
                fixture.allowRuntimeReturn.countDown();
                assertThatThrownBy(() -> execution.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                allowCommit.countDown();
                callback.get(3, TimeUnit.SECONDS);
                assertThat(execution.get(3, TimeUnit.SECONDS).status()).isEqualTo(InvocationStatus.SUCCEEDED);
            } finally {
                allowCommit.countDown();
                fixture.allowRuntimeReturn.countDown();
            }
        }
        assertThat(fixture.messages.findByThreadId("thread")).singleElement().satisfies(message -> {
            assertThat(message.content()).isEqualTo("authoritative answer");
            assertThat(message.status()).isEqualTo(ChatMessageStatus.COMPLETED);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finalizationWaitsForCallbackThatAlreadyPassedStatusCheck(boolean failure) throws Exception {
        CountDownLatch callbackAccepted = new CountDownLatch(1);
        CountDownLatch allowCallbackWrite = new CountDownLatch(1);
        var events = new InMemoryInvocationMessageRepository() {
            @Override
            public boolean saveIfAbsent(InvocationMessage message) {
                callbackAccepted.countDown();
                await(allowCallbackWrite);
                return super.saveIfAbsent(message);
            }
        };
        var fixture = new Fixture(new InMemoryChatMessageRepository(), events, failure);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var execution = workers.submit(() -> fixture.service.execute(fixture.task));
            try {
                assertThat(fixture.runtimeStarted.await(3, TimeUnit.SECONDS)).isTrue();
                var callback = workers.submit(() -> fixture.controller.postMessage(fixture.callback("partial")));
                assertThat(callbackAccepted.await(3, TimeUnit.SECONDS)).isTrue();
                fixture.allowRuntimeReturn.countDown();
                assertThatThrownBy(() -> execution.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                allowCallbackWrite.countDown();
                callback.get(3, TimeUnit.SECONDS);
                assertThat(execution.get(3, TimeUnit.SECONDS).status())
                        .isEqualTo(failure ? InvocationStatus.FAILED : InvocationStatus.SUCCEEDED);
                fixture.controller.postMessage(fixture.callback("late"));
                assertThat(events.findByInvocationId(fixture.invocationId)).hasSize(1);
                assertThat(fixture.messages.findByThreadId("thread")).singleElement().satisfies(message -> {
                    assertThat(message.status()).isEqualTo(failure ? ChatMessageStatus.FAILED : ChatMessageStatus.COMPLETED);
                    assertThat(message.content()).isEqualTo(failure ? "partialruntime failed" : "authoritative answer");
                });
            } finally {
                allowCallbackWrite.countDown();
                fixture.allowRuntimeReturn.countDown();
            }
        }
    }

    @Test
    void rejectsCallbacksAfterFinalResponseStartsEvenWhileInvocationIsStillRunning() throws Exception {
        CountDownLatch finalWrite = new CountDownLatch(1);
        CountDownLatch allowFinalWrite = new CountDownLatch(1);
        var messages = new InMemoryChatMessageRepository() {
            @Override
            public ChatMessage save(ChatMessage message) {
                if (message.status() == ChatMessageStatus.COMPLETED) {
                    finalWrite.countDown();
                    await(allowFinalWrite);
                }
                return super.save(message);
            }
        };
        var fixture = new Fixture(messages, new InMemoryInvocationMessageRepository(), false);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var execution = workers.submit(() -> fixture.service.execute(fixture.task));
            try {
                assertThat(fixture.runtimeStarted.await(3, TimeUnit.SECONDS)).isTrue();
                fixture.allowRuntimeReturn.countDown();
                assertThat(finalWrite.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(fixture.invocations.findByInvocationId(fixture.invocationId).orElseThrow().status())
                        .isEqualTo(InvocationStatus.RUNNING);
                fixture.controller.postMessage(fixture.callback("late"));
                assertThat(fixture.events.findByInvocationId(fixture.invocationId)).isEmpty();
            } finally {
                allowFinalWrite.countDown();
                fixture.allowRuntimeReturn.countDown();
            }
            execution.get(3, TimeUnit.SECONDS);
        }
        assertThat(messages.findByThreadId("thread")).singleElement().satisfies(message -> {
            assertThat(message.content()).isEqualTo("authoritative answer");
            assertThat(message.status()).isEqualTo(ChatMessageStatus.COMPLETED);
        });
    }

    private static final class Fixture {
        final InMemoryInvocationRepository invocations = new InMemoryInvocationRepository();
        final InMemoryChatMessageRepository messages;
        final InMemoryInvocationMessageRepository events;
        final CountDownLatch runtimeStarted = new CountDownLatch(1);
        final CountDownLatch allowRuntimeReturn = new CountDownLatch(1);
        final Task task;
        final InvocationService service;
        final CallbackController controller;
        volatile String invocationId;

        Fixture(InMemoryChatMessageRepository messages, InMemoryInvocationMessageRepository events, boolean failure) {
            this.messages = messages;
            this.events = events;
            var tasks = new InMemoryTaskRepository();
            var threads = new InMemoryChatThreadRepository();
            var buffer = new AssistantStreamBuffer(messages);
            var publisher = mock(ChatEventService.class);
            Instant now = Instant.now();
            task = new Task("task", "user", "trace", null, TaskStatus.PROCESSING, TaskSource.USER, 0, "codex", "work", now, now);
            tasks.save(task);
            threads.save(new ChatThread("thread", "user", "thread", ChatThreadStatus.RUNNING, "trace", now, now));
            controller = new CallbackController(invocations, events, threads, buffer, publisher);
            service = InvocationServiceTestFactory.create(invocations, tasks, request -> {
                invocationId = request.invocationId();
                runtimeStarted.countDown();
                await(allowRuntimeReturn);
                if (failure) {
                    throw new IllegalStateException("runtime failed");
                }
                return new AgentExecutionResult(List.of(), "authoritative answer", true, null, "v1");
            }, "http://unused", () -> {}, events, threads, messages, publisher, null, buffer,
                    new InMemoryTaskDependencyRepository(), null, null);
        }

        CallbackMessageRequest callback(String content) {
            return new CallbackMessageRequest(invocationId, content);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Test did not release callback/finalization");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
