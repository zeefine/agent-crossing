package com.agentcrossing.platform.application.routing;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TaskDispatchScheduler implements TaskDispatchSignal {
    private static final Logger log = LoggerFactory.getLogger(TaskDispatchScheduler.class);
    private final ObjectProvider<QuestRouterService> questRouterServiceProvider;
    private final Executor taskDispatchExecutor;
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicBoolean rescheduleRequested = new AtomicBoolean(false);

    public TaskDispatchScheduler(
            ObjectProvider<QuestRouterService> questRouterServiceProvider,
            @Qualifier("taskDispatchExecutor") Executor taskDispatchExecutor) {
        this.questRouterServiceProvider = questRouterServiceProvider;
        this.taskDispatchExecutor = taskDispatchExecutor;
    }

    @Override
    public void signal() {
        long signaledAt = System.nanoTime();
        if (!draining.compareAndSet(false, true)) {
            rescheduleRequested.set(true);
            return;
        }
        taskDispatchExecutor.execute(() -> drain(signaledAt));
    }

    private void drain(long signaledAt) {
        long startedAt = System.nanoTime();
        int dispatched = 0;
        try {
            log.info(
                    "agent_crossing_perf event=router_signal_wait durationMs={}",
                    elapsedMs(signaledAt));
            QuestRouterService questRouterService = questRouterServiceProvider.getObject();
            while (questRouterService.processNext().isPresent()) {
                // 持续 drain，直到队列为空或当前队头 agent 正忙。
                dispatched++;
            }
        } finally {
            log.info(
                    "agent_crossing_perf event=router_drain durationMs={} dispatched={} rescheduleRequested={}",
                    elapsedMs(startedAt),
                    dispatched,
                    rescheduleRequested.get());
            draining.set(false);
            if (rescheduleRequested.getAndSet(false)) {
                signal();
            }
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
