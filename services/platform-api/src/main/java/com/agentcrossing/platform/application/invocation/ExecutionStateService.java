package com.agentcrossing.platform.application.invocation;

import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskRepository;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short state/result transactions coordinated with thread deletion. Never wrap remote calls. */
@Service
public class ExecutionStateService {
    private static final Set<TaskStatus> ACTIVE_TASKS = Set.of(TaskStatus.QUEUED, TaskStatus.PROCESSING);
    private static final Set<InvocationStatus> ACTIVE_INVOCATIONS = Set.of(InvocationStatus.QUEUED, InvocationStatus.RUNNING);
    private final TaskRepository tasks;
    private final InvocationRepository invocations;
    private TransactionTemplate transactions;

    public ExecutionStateService(TaskRepository tasks, InvocationRepository invocations) {
        this.tasks = tasks;
        this.invocations = invocations;
    }

    @Autowired(required = false)
    void setTransactionManager(PlatformTransactionManager manager) {
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Result finalization may wait for an accepted callback's commit after reading the invocation.
        // Parent row locks provide exclusion; fresh reads must see that callback's newly created message.
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public State start(String taskId, String invocationId) {
        return atomic(() -> {
            State state = read(taskId, invocationId, false);
            if (state.canceled()) {
                return cancelActivePair(taskId, invocationId);
            }
            if (ACTIVE_TASKS.contains(state.task().status())) {
                tasks.updateStatusIfCurrent(taskId, Set.of(TaskStatus.QUEUED), TaskStatus.PROCESSING);
                invocations.updateStatusIfCurrent(invocationId, Set.of(InvocationStatus.QUEUED), InvocationStatus.RUNNING);
            }
            return read(taskId, invocationId, false);
        });
    }

    public <T> T withExistingTask(String taskId, Supplier<T> operation) {
        return atomic(() -> {
            tasks.findByTaskIdForUpdate(taskId).orElseThrow(() -> new DeletedExecutionException(taskId));
            return operation.get();
        });
    }

    public <T> T withExistingExecution(String taskId, String invocationId, Supplier<T> operation) {
        return atomic(() -> {
            read(taskId, invocationId, false);
            return operation.get();
        });
    }

    public State finish(String taskId, String invocationId, InvocationStatus terminal) {
        if (terminal != InvocationStatus.SUCCEEDED && terminal != InvocationStatus.FAILED) {
            throw new IllegalArgumentException("Expected a runtime success or failure");
        }
        try {
            return atomic(() -> {
                State state = read(taskId, invocationId, false);
                if (state.canceled()) {
                    return cancelActivePair(taskId, invocationId);
                }
                if (state.task().status() != TaskStatus.PROCESSING || state.invocation().status() != InvocationStatus.RUNNING) {
                    return state;
                }
                TaskStatus taskTerminal = terminal == InvocationStatus.SUCCEEDED ? TaskStatus.COMPLETED : TaskStatus.FAILED;
                // Match cancellation's lock order: task first, invocation second.
                if (!tasks.updateStatusIfCurrent(taskId, Set.of(TaskStatus.PROCESSING), taskTerminal)) {
                    throw new TransitionConflict();
                }
                try {
                    if (!invocations.updateStatusIfCurrent(invocationId, Set.of(InvocationStatus.RUNNING), terminal)) {
                        throw new TransitionConflict();
                    }
                } catch (RuntimeException failure) {
                    if (transactions == null) {
                        // The memory implementation has no transaction manager; restore our first write only.
                        tasks.updateStatusIfCurrent(taskId, Set.of(taskTerminal), TaskStatus.PROCESSING);
                    }
                    throw failure;
                }
                return read(taskId, invocationId, true);
            });
        } catch (TransitionConflict conflict) {
            // The failed transaction has rolled back. A new snapshot observes the winning stop/completion.
            return atomic(() -> {
                State state = read(taskId, invocationId, false);
                return state.canceled() ? cancelActivePair(taskId, invocationId) : state;
            });
        }
    }

    public State reconcileCancellation(String taskId, String invocationId) {
        return atomic(() -> {
            State state = read(taskId, invocationId, false);
            return state.canceled() ? cancelActivePair(taskId, invocationId) : state;
        });
    }

    public CanceledWork cancelTrace(String userId, String traceId) {
        return atomic(() -> {
            List<Task> canceledTasks = new ArrayList<>();
            for (Task task : tasks.findByTraceIdAndUserId(traceId, userId)) {
                if (ACTIVE_TASKS.contains(task.status())
                        && tasks.updateStatusIfCurrent(task.taskId(), ACTIVE_TASKS, TaskStatus.CANCELED)) {
                    canceledTasks.add(tasks.findByTaskId(task.taskId()).orElseThrow());
                }
            }
            List<Invocation> canceledInvocations = new ArrayList<>();
            for (Invocation invocation : invocations.findByTraceIdAndUserId(traceId, userId)) {
                if (ACTIVE_INVOCATIONS.contains(invocation.status())
                        && invocations.updateStatusIfCurrent(invocation.invocationId(), ACTIVE_INVOCATIONS, InvocationStatus.CANCELED)) {
                    canceledInvocations.add(invocations.findByInvocationId(invocation.invocationId()).orElseThrow());
                }
            }
            return new CanceledWork(List.copyOf(canceledTasks), List.copyOf(canceledInvocations));
        });
    }

    private State cancelActivePair(String taskId, String invocationId) {
        tasks.updateStatusIfCurrent(taskId, ACTIVE_TASKS, TaskStatus.CANCELED);
        invocations.updateStatusIfCurrent(invocationId, ACTIVE_INVOCATIONS, InvocationStatus.CANCELED);
        return read(taskId, invocationId, false);
    }

    private State read(String taskId, String invocationId, boolean changed) {
        Task task = tasks.findByTaskIdForUpdate(taskId).orElseThrow(() -> new DeletedExecutionException(taskId));
        Invocation invocation = invocations.findByInvocationId(invocationId)
                .orElseThrow(() -> new DeletedExecutionException(invocationId));
        if (!invocation.taskId().equals(taskId) || !invocation.userId().equals(task.userId())) {
            throw new IllegalArgumentException("Invocation does not belong to task");
        }
        return new State(task, invocation, changed);
    }

    private <T> T atomic(Supplier<T> operation) {
        // Also coordinates memory-mode completion and cancellation, including manually constructed services.
        // Commit before releasing the monitor; no network calls occur under it.
        synchronized (tasks) {
            return transactions == null ? operation.get() : transactions.execute(status -> operation.get());
        }
    }

    private static final class TransitionConflict extends RuntimeException {}

    public record State(Task task, Invocation invocation, boolean changed) {
        public boolean canceled() {
            return task.status() == TaskStatus.CANCELED || invocation.status() == InvocationStatus.CANCELED;
        }
    }

    public record CanceledWork(List<Task> tasks, List<Invocation> invocations) {}
}
