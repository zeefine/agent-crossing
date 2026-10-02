package com.agentcrossing.platform.application.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcrossing.platform.domain.invocation.*;
import com.agentcrossing.platform.domain.task.*;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ExecutionStateServiceTests {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void firstTerminalTransitionWinsWithoutMixedStates(boolean cancellationWins) throws Exception {
        var tasks = new InMemoryTaskRepository();
        CountDownLatch firstWrite = new CountDownLatch(1);
        CountDownLatch allowSecondWrite = new CountDownLatch(1);
        InvocationStatus winner = cancellationWins ? InvocationStatus.CANCELED : InvocationStatus.SUCCEEDED;
        var invocations = new InMemoryInvocationRepository() {
            @Override
            public boolean updateStatusIfCurrent(String id, Set<InvocationStatus> expected, InvocationStatus status) {
                if (status == winner) {
                    firstWrite.countDown();
                    await(allowSecondWrite);
                }
                return super.updateStatusIfCurrent(id, expected, status);
            }
        };
        seed(tasks, invocations);
        // Independently constructed services still share the memory-mode transition boundary.
        var completion = new ExecutionStateService(tasks, invocations);
        var cancellation = new ExecutionStateService(tasks, invocations);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> cancellationWins
                    ? cancellation.cancelTrace("user", "trace")
                    : completion.finish("task", "inv", InvocationStatus.SUCCEEDED));
            try {
                assertThat(firstWrite.await(3, TimeUnit.SECONDS)).isTrue();
                CountDownLatch secondStarted = new CountDownLatch(1);
                var second = executor.submit(() -> {
                    secondStarted.countDown();
                    return cancellationWins ? completion.finish("task", "inv", InvocationStatus.SUCCEEDED)
                            : cancellation.cancelTrace("user", "trace");
                });
                assertThat(secondStarted.await(3, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                allowSecondWrite.countDown();
                first.get(3, TimeUnit.SECONDS);
                second.get(3, TimeUnit.SECONDS);
            } finally {
                allowSecondWrite.countDown();
            }
        }
        assertThat(invocations.findByInvocationId("inv").orElseThrow().status()).isEqualTo(winner);
        assertThat(tasks.findByTaskId("task").orElseThrow().status())
                .isEqualTo(cancellationWins ? TaskStatus.CANCELED : TaskStatus.COMPLETED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedSecondWriteRollsBackFirstWrite(boolean transactional) {
        var tasks = new InMemoryTaskRepository();
        var invocations = new InMemoryInvocationRepository() {
            @Override
            public boolean updateStatusIfCurrent(String id, Set<InvocationStatus> expected, InvocationStatus status) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isEqualTo(transactional);
                throw new IllegalStateException("database write failed");
            }
        };
        seed(tasks, invocations);
        var service = new ExecutionStateService(tasks, invocations);
        AtomicInteger rollbacks = new AtomicInteger();
        Task originalTask = tasks.findByTaskId("task").orElseThrow();
        if (transactional) {
            service.setTransactionManager(new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
                @Override protected void doCommit(DefaultTransactionStatus status) { throw new AssertionError("must roll back"); }
                @Override protected void doRollback(DefaultTransactionStatus status) {
                    rollbacks.incrementAndGet();
                    tasks.save(originalTask);
                }
            });
        }
        assertThatThrownBy(() -> service.finish("task", "inv", InvocationStatus.SUCCEEDED))
                .isInstanceOf(IllegalStateException.class).hasMessage("database write failed");

        assertThat(tasks.findByTaskId("task").orElseThrow().status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(invocations.findByInvocationId("inv").orElseThrow().status()).isEqualTo(InvocationStatus.RUNNING);
        assertThat(rollbacks.get()).isEqualTo(transactional ? 1 : 0);
    }

    @Test
    void lostInvocationCompareAndSetCannotLeaveCompletedTask() {
        var tasks = new InMemoryTaskRepository();
        var invocations = new InMemoryInvocationRepository() {
            @Override
            public boolean updateStatusIfCurrent(String id, Set<InvocationStatus> expected, InvocationStatus status) {
                if (status == InvocationStatus.SUCCEEDED) {
                    super.updateStatusIfCurrent(id, expected, InvocationStatus.CANCELED);
                }
                return super.updateStatusIfCurrent(id, expected, status);
            }
        };
        seed(tasks, invocations);

        var state = new ExecutionStateService(tasks, invocations).finish("task", "inv", InvocationStatus.SUCCEEDED);

        assertThat(state.changed()).isFalse();
        assertThat(state.invocation().status()).isEqualTo(InvocationStatus.CANCELED);
        assertThat(state.task().status()).isEqualTo(TaskStatus.CANCELED);
    }

    @Test
    void lostTaskCompareAndSetPreventsSuccessWrite() {
        var tasks = new InMemoryTaskRepository() {
            @Override
            public boolean updateStatusIfCurrent(String id, Set<TaskStatus> expected, TaskStatus status) {
                if (status == TaskStatus.COMPLETED) {
                    super.updateStatusIfCurrent(id, expected, TaskStatus.CANCELED);
                }
                return super.updateStatusIfCurrent(id, expected, status);
            }
        };
        var invocations = new InMemoryInvocationRepository();
        seed(tasks, invocations);

        var state = new ExecutionStateService(tasks, invocations).finish("task", "inv", InvocationStatus.SUCCEEDED);

        assertThat(state.changed()).isFalse();
        assertThat(state.invocation().status()).isEqualTo(InvocationStatus.CANCELED);
        assertThat(state.task().status()).isEqualTo(TaskStatus.CANCELED);
    }

    private static void seed(InMemoryTaskRepository tasks, InMemoryInvocationRepository invocations) {
        Instant now = Instant.now();
        tasks.save(new Task("task", "user", "trace", null, TaskStatus.PROCESSING, TaskSource.USER,
                0, "codex", "work", now, now));
        invocations.save(new Invocation("inv", "user", "task", "trace", "codex", InvocationStatus.RUNNING, now, now, null));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Test did not release state transition");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
