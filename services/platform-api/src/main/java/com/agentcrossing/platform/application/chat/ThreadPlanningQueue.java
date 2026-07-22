package com.agentcrossing.platform.application.chat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 按 userId + threadId 串行执行 MasterAgent 规划。
 *
 * <p>同一线程的规划以 CompletableFuture 链接，后续规划不会占住 executor 线程等待前序任务；
 * 不同线程仍可由 chatPlanningExecutor 并发处理。</p>
 */
@Component
public final class ThreadPlanningQueue {
    private final Executor executor;
    private final ConcurrentMap<ThreadKey, CompletableFuture<Void>> tailsByThread = new ConcurrentHashMap<>();
    private final ConcurrentMap<ThreadKey, AtomicLong> generationsByThread = new ConcurrentHashMap<>();
    private final ConcurrentMap<ReservationKey, AtomicInteger> pendingCountsByReservation = new ConcurrentHashMap<>();

    public ThreadPlanningQueue(@Qualifier("chatPlanningExecutor") Executor executor) {
        this.executor = executor;
    }

    long reserve(String userId, String threadId) {
        ThreadKey key = new ThreadKey(userId, threadId);
        long generation = currentGeneration(key);
        pendingCountsByReservation
                .computeIfAbsent(new ReservationKey(key, generation), ignored -> new AtomicInteger())
                .incrementAndGet();
        return generation;
    }

    void enqueueReserved(
            String userId,
            String threadId,
            long reservationGeneration,
            Runnable planning,
            Consumer<Throwable> onUnexpectedFailure,
            Runnable onQueueIdle) {
        ThreadKey key = new ThreadKey(userId, threadId);
        AtomicBoolean reservationCompleted = new AtomicBoolean(false);
        Runnable wrappedPlanning = () -> {
            try {
                // cancel 会推进 generation。已经开始或尚未开始的旧规划可以自然结束，但不能再落库创建任务。
                if (isCurrentGeneration(key, reservationGeneration)) {
                    planning.run();
                }
            } finally {
                notifyQueueIdleIfNeeded(key, reservationGeneration, reservationCompleted, onQueueIdle);
            }
        };
        CompletableFuture<Void> current = tailsByThread.compute(key, (ignored, previous) -> {
            CompletableFuture<Void> predecessor = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((unused, failure) -> null);
            return predecessor.thenRunAsync(wrappedPlanning, executor);
        });
        current.whenComplete((unused, failure) -> {
            tailsByThread.remove(key, current);
            // executor 拒绝任务或 Future 被取消时 wrappedPlanning 不会运行，需在这里兜底释放预留名额。
            notifyQueueIdleIfNeeded(key, reservationGeneration, reservationCompleted, onQueueIdle);
            if (failure != null && isCurrentGeneration(key, reservationGeneration)) {
                onUnexpectedFailure.accept(failure);
            }
        });
    }

    boolean cancelReservation(String userId, String threadId, long reservationGeneration) {
        return completeReservation(new ThreadKey(userId, threadId), reservationGeneration);
    }

    // 保留给同 package 的旧测试辅助调用；生产提交路径始终携带 reservation generation。
    boolean cancelReservation(String userId, String threadId) {
        ThreadKey key = new ThreadKey(userId, threadId);
        return completeReservation(key, currentGeneration(key));
    }

    boolean hasPendingAfterCurrent(String userId, String threadId) {
        ThreadKey key = new ThreadKey(userId, threadId);
        AtomicInteger count = pendingCountsByReservation.get(new ReservationKey(key, currentGeneration(key)));
        return count != null && count.get() > 1;
    }

    boolean hasPending(String userId, String threadId) {
        ThreadKey key = new ThreadKey(userId, threadId);
        AtomicInteger count = pendingCountsByReservation.get(new ReservationKey(key, currentGeneration(key)));
        return count != null && count.get() > 0;
    }

    /** Invalidates queued and in-flight planning results without blocking the next user turn. */
    void cancel(String userId, String threadId) {
        ThreadKey key = new ThreadKey(userId, threadId);
        generationsByThread.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }

    void remove(String userId, String threadId) {
        ThreadKey key = new ThreadKey(userId, threadId);
        tailsByThread.remove(key);
        generationsByThread.remove(key);
        pendingCountsByReservation.keySet().removeIf(reservationKey -> reservationKey.threadKey().equals(key));
    }

    boolean isCurrent(String userId, String threadId, long generation) {
        return isCurrentGeneration(new ThreadKey(userId, threadId), generation);
    }

    private long currentGeneration(ThreadKey key) {
        return generationsByThread.computeIfAbsent(key, ignored -> new AtomicLong()).get();
    }

    private boolean isCurrentGeneration(ThreadKey key, long generation) {
        return currentGeneration(key) == generation;
    }

    private boolean completeReservation(ThreadKey key, long generation) {
        ReservationKey reservationKey = new ReservationKey(key, generation);
        AtomicInteger count = pendingCountsByReservation.get(reservationKey);
        if (count == null) {
            return false;
        }
        if (count.decrementAndGet() == 0) {
            pendingCountsByReservation.remove(reservationKey, count);
            return isCurrentGeneration(key, generation);
        }
        return false;
    }

    private void notifyQueueIdleIfNeeded(
            ThreadKey key,
            long reservationGeneration,
            AtomicBoolean completed,
            Runnable onQueueIdle) {
        if (completed.compareAndSet(false, true)) {
            if (completeReservation(key, reservationGeneration)) {
                onQueueIdle.run();
            }
        }
    }

    private record ThreadKey(String userId, String threadId) {
    }

    private record ReservationKey(ThreadKey threadKey, long generation) {
    }
}
