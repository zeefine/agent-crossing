package com.agentcrossing.platform.application.routing;

import com.agentcrossing.platform.application.invocation.InvocationService;
import com.agentcrossing.platform.domain.task.Task;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ParallelTaskWorker {
    private static final Logger log = LoggerFactory.getLogger(ParallelTaskWorker.class);
    private final InvocationService invocationService;
    private final Executor parallelTaskExecutor;

    public ParallelTaskWorker(
            InvocationService invocationService,
            @Qualifier("parallelTaskExecutor") Executor parallelTaskExecutor) {
        this.invocationService = invocationService;
        this.parallelTaskExecutor = parallelTaskExecutor;
    }

    /**
     * Returns false only when the bounded executor cannot accept the task. The Router owns the
     * corresponding status rollback and QuestHub requeue.
     */
    public boolean submit(Task task) {
        long submittedAt = System.nanoTime();
        try {
            parallelTaskExecutor.execute(() -> {
                log.info(
                        "agent_crossing_perf event=worker_queue_wait durationMs={} userId={} taskId={} traceId={} agentId={}",
                        elapsedMs(submittedAt),
                        task.userId(),
                        task.taskId(),
                        task.traceId(),
                        task.agentId());
                invocationService.execute(task);
            });
            return true;
        } catch (RejectedExecutionException exception) {
            log.warn(
                    "Business worker rejected task userId={} taskId={} traceId={} agentId={}",
                    task.userId(),
                    task.taskId(),
                    task.traceId(),
                    task.agentId());
            return false;
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
