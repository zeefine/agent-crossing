package com.agentcrossing.platform.application.chat;

import com.agentcrossing.platform.application.invocation.AgentRuntimeClient;
import com.agentcrossing.platform.application.invocation.ExecutionStateService;
import com.agentcrossing.platform.application.routing.TaskDispatchSignal;
import com.agentcrossing.platform.application.task.TaskEventService;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Owns user-initiated stop requests for a chat thread.
 *
 * <p>The database transition is committed before the runtime cancellation request is sent. A late CLI result
 * therefore observes the durable {@code CANCELED} state and cannot turn the task back into success.</p>
 */
@Service
public class ThreadCancellationService {
    private static final Logger log = LoggerFactory.getLogger(ThreadCancellationService.class);

    private final ChatThreadRepository chatThreadRepository;
    private final QuestHub questHub;
    private final ThreadPlanningQueue threadPlanningQueue;
    private final AgentRuntimeClient agentRuntimeClient;
    private final TaskEventService taskEventService;
    private final ThreadStatusAggregator threadStatusAggregator;
    private final TaskDispatchSignal taskDispatchSignal;
    private ExecutionStateService executionStateService;

    public ThreadCancellationService(
            ChatThreadRepository chatThreadRepository,
            TaskRepository taskRepository,
            InvocationRepository invocationRepository,
            QuestHub questHub,
            ThreadPlanningQueue threadPlanningQueue,
            AgentRuntimeClient agentRuntimeClient,
            TaskEventService taskEventService,
            ThreadStatusAggregator threadStatusAggregator,
            TaskDispatchSignal taskDispatchSignal) {
        this.chatThreadRepository = chatThreadRepository;
        this.questHub = questHub;
        this.threadPlanningQueue = threadPlanningQueue;
        this.agentRuntimeClient = agentRuntimeClient;
        this.taskEventService = taskEventService;
        this.threadStatusAggregator = threadStatusAggregator;
        this.taskDispatchSignal = taskDispatchSignal;
        this.executionStateService = new ExecutionStateService(taskRepository, invocationRepository);
    }

    @Autowired
    void setExecutionStateService(ExecutionStateService executionStateService) {
        this.executionStateService = executionStateService;
    }

    public CancelThreadWorkResult cancel(String userId, String threadId) {
        ChatThread thread = chatThreadRepository.findByThreadIdAndUserId(threadId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Chat thread not found: " + threadId));
        threadPlanningQueue.cancel(userId, threadId);

        ExecutionStateService.CanceledWork canceled = executionStateService.cancelTrace(userId, thread.traceId());
        List<Task> canceledTasks = canceled.tasks();
        canceledTasks.forEach(task -> questHub.remove(task.taskId()));
        List<String> canceledInvocationIds = canceled.invocations().stream().map(Invocation::invocationId).toList();

        Runnable afterCommit = () -> {
            canceledTasks.forEach(taskEventService::publish);
            threadStatusAggregator.refresh(userId, threadId);
            // Runtime cancellation is best-effort. The durable CANCELED state above is the source of truth.
            canceledInvocationIds.forEach(agentRuntimeClient::cancel);
            taskDispatchSignal.signal();
            log.info(
                    "Canceled thread work userId={} threadId={} traceId={} tasks={} canceledInvocations={}",
                    userId,
                    threadId,
                    thread.traceId(),
                    canceledTasks.size(),
                    canceledInvocationIds.size());
        };
        afterCommit.run();
        return new CancelThreadWorkResult(
                threadId,
                canceledTasks.stream().map(Task::taskId).toList(),
                List.copyOf(canceledInvocationIds));
    }

}
