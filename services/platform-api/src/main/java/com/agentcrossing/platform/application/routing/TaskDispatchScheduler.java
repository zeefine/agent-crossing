package com.agentcrossing.platform.application.routing;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class TaskDispatchScheduler implements TaskDispatchSignal {
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
        if (!draining.compareAndSet(false, true)) {
            rescheduleRequested.set(true);
            return;
        }
        taskDispatchExecutor.execute(this::drain);
    }

    private void drain() {
        try {
            QuestRouterService questRouterService = questRouterServiceProvider.getObject();
            while (questRouterService.processNext().isPresent()) {
                // 持续 drain，直到队列为空或当前队头 agent 正忙。
            }
        } finally {
            draining.set(false);
            if (rescheduleRequested.getAndSet(false)) {
                signal();
            }
        }
    }
}
