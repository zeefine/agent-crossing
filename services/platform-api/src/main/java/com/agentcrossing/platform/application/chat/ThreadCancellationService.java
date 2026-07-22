package com.agentcrossing.platform.application.chat;

import com.agentcrossing.platform.application.invocation.AgentRuntimeClient;
import com.agentcrossing.platform.application.routing.TaskDispatchSignal;
import com.agentcrossing.platform.application.task.TaskEventService;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskRepository;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final TaskRepository taskRepository;
    private final InvocationRepository invocationRepository;
    private final QuestHub questHub;
    private final ThreadPlanningQueue threadPlanningQueue;
    private final AgentRuntimeClient agentRuntimeClient;
    private final TaskEventService taskEventService;
    private final ThreadStatusAggregator threadStatusAggregator;
    private final TaskDispatchSignal taskDispatchSignal;

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
        this.taskRepository = taskRepository;
        this.invocationRepository = invocationRepository;
        this.questHub = questHub;
        this.threadPlanningQueue = threadPlanningQueue;
        this.agentRuntimeClient = agentRuntimeClient;
        this.taskEventService = taskEventService;
        this.threadStatusAggregator = threadStatusAggregator;
        this.taskDispatchSignal = taskDispatchSignal;
    }

    @Transactional
    public CancelThreadWorkResult cancel(String userId, String threadId) {
        ChatThread thread = chatThreadRepository.findByThreadIdAndUserId(threadId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Chat thread not found: " + threadId));
        threadPlanningQueue.cancel(userId, threadId);

        List<Task> canceledTasks = new ArrayList<>();
        for (Task task : taskRepository.findByTraceIdAndUserId(thread.traceId(), userId)) {
            if (task.status() != TaskStatus.QUEUED && task.status() != TaskStatus.PROCESSING) {
                continue;
            }
            questHub.remove(task.taskId());
            canceledTasks.add(taskRepository.updateStatus(task.taskId(), TaskStatus.CANCELED));
        }

        List<String> runningInvocationIds = new ArrayList<>();
        List<String> canceledInvocationIds = new ArrayList<>();
        for (Invocation invocation : invocationRepository.findByTraceIdAndUserId(thread.traceId(), userId)) {
            if (invocation.status() != InvocationStatus.QUEUED && invocation.status() != InvocationStatus.RUNNING) {
                continue;
            }
            invocationRepository.updateStatus(invocation.invocationId(), InvocationStatus.CANCELED);
            canceledInvocationIds.add(invocation.invocationId());
            if (invocation.status() == InvocationStatus.RUNNING) {
                runningInvocationIds.add(invocation.invocationId());
            }
        }

        Runnable afterCommit = () -> {
            canceledTasks.forEach(taskEventService::publish);
            threadStatusAggregator.refresh(userId, threadId);
            // Runtime cancellation is best-effort. The durable CANCELED state above is the source of truth.
            runningInvocationIds.forEach(agentRuntimeClient::cancel);
            taskDispatchSignal.signal();
            log.info(
                    "Canceled thread work userId={} threadId={} traceId={} tasks={} runningInvocations={}",
                    userId,
                    threadId,
                    thread.traceId(),
                    canceledTasks.size(),
                    runningInvocationIds.size());
        };
        runAfterCommit(afterCommit);
        return new CancelThreadWorkResult(
                threadId,
                canceledTasks.stream().map(Task::taskId).toList(),
                List.copyOf(canceledInvocationIds));
    }

    private static void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
