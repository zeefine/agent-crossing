package com.agentcrossing.platform.application.routing;

import com.agentcrossing.platform.application.invocation.InvocationService;
import com.agentcrossing.platform.domain.task.Task;
import java.util.concurrent.Executor;
import org.springframework.stereotype.Component;

@Component
public class ParallelTaskWorker {
    private final InvocationService invocationService;
    private final Executor parallelTaskExecutor;

    public ParallelTaskWorker(InvocationService invocationService, Executor parallelTaskExecutor) {
        this.invocationService = invocationService;
        this.parallelTaskExecutor = parallelTaskExecutor;
    }

    public void submit(Task task) {
        parallelTaskExecutor.execute(() -> invocationService.execute(task));
    }
}

