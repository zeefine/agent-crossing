package com.agentcrossing.platform.application.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.invocation.*;
import com.agentcrossing.platform.domain.message.InMemoryChatMessageRepository;
import com.agentcrossing.platform.domain.task.*;
import com.agentcrossing.platform.support.ChatServiceTestFactory;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ExecutionDeletionCoordinationTests {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionAndResultWritesStaySerializedThroughCommit(boolean deletionFirst) throws Exception {
        var tasks = new InMemoryTaskRepository() {
            @Override public java.util.Optional<Task> findByTaskIdForUpdate(String id) {
                assertThat(Thread.holdsLock(this)).isTrue();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return super.findByTaskIdForUpdate(id);
            }
            @Override public java.util.List<Task> findByTraceIdAndUserIdForUpdate(String trace, String user) {
                assertThat(Thread.holdsLock(this)).isTrue();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
                return super.findByTraceIdAndUserIdForUpdate(trace, user);
            }
        };
        var invocations = new InMemoryInvocationRepository();
        var usage = new InMemoryInvocationUsageRepository();
        var threads = new InMemoryChatThreadRepository();
        Instant now = Instant.now();
        // Terminal parents must also be locked: success can commit just before usage/event cleanup.
        tasks.save(new Task("task", "user", "trace", null, TaskStatus.COMPLETED,
                TaskSource.USER, 0, "codex", "work", now, now));
        invocations.save(new Invocation("inv", "user", "task", "trace", "codex",
                InvocationStatus.SUCCEEDED, now, now, now));
        threads.save(new ChatThread("thread", "user", "title", ChatThreadStatus.COMPLETED, "trace", now, now));
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        AtomicBoolean firstCommit = new AtomicBoolean(true);
        var manager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
                assertThat(Thread.holdsLock(tasks)).isTrue();
                assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            }
            @Override protected void doCommit(DefaultTransactionStatus status) {
                assertThat(Thread.holdsLock(tasks)).isTrue();
                if (firstCommit.getAndSet(false)) {
                    committing.countDown();
                    await(allowCommit);
                }
            }
            @Override protected void doRollback(DefaultTransactionStatus status) {}
        };
        var state = new ExecutionStateService(tasks, invocations);
        state.setTransactionManager(manager);
        var chat = ChatServiceTestFactory.create(threads, new InMemoryChatMessageRepository(),
                null, null, null, null, null, invocations, tasks, null, null, null, null, Runnable::run, manager);
        ReflectionTestUtils.setField(chat, "invocationUsageRepository", usage);
        AtomicInteger writes = new AtomicInteger();
        Runnable write = () -> {
            try {
                state.withExistingExecution("task", "inv", () -> {
                    writes.incrementAndGet();
                    return usage.save(new InvocationUsage("inv", "codex", "gpt-5.6", null,
                            100L, 100L, UsagePrecision.EXACT, 100L, null, null, null, 10L, null,
                            100L, null, null, now));
                });
                assertThat(deletionFirst).isFalse();
            } catch (DeletedExecutionException deleted) {
                assertThat(deletionFirst).isTrue();
            }
        };
        Runnable delete = () -> chat.deleteThread("user", "thread");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(deletionFirst ? delete : write);
            try {
                assertThat(committing.await(3, TimeUnit.SECONDS)).isTrue();
                CountDownLatch secondStarted = new CountDownLatch(1);
                var second = executor.submit(() -> {
                    secondStarted.countDown();
                    (deletionFirst ? write : delete).run();
                });
                assertThat(secondStarted.await(3, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                allowCommit.countDown();
                first.get(3, TimeUnit.SECONDS);
                second.get(3, TimeUnit.SECONDS);
            } finally {
                allowCommit.countDown();
            }
        }
        assertThat(writes.get()).isEqualTo(deletionFirst ? 0 : 1);
        assertThat(usage.findByInvocationId("inv")).isEmpty();
        assertThat(invocations.findByInvocationId("inv")).isEmpty();
        assertThat(tasks.findByTaskId("task")).isEmpty();
        assertThat(threads.findByThreadIdAndUserId("thread", "user")).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
