package com.agentcrossing.platform.support;

import com.agentcrossing.platform.application.invocation.AgentRuntimeClient;
import com.agentcrossing.platform.application.invocation.ExecutionStateService;
import com.agentcrossing.platform.application.routing.TaskDispatchSignal;
import com.agentcrossing.platform.application.task.TaskEventService;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.task.TaskRepository;
import com.agentcrossing.platform.application.chat.ThreadCancellationService;
import com.agentcrossing.platform.application.chat.ThreadPlanningQueue;
import com.agentcrossing.platform.application.chat.ThreadStatusAggregator;

public final class ThreadCancellationServiceTestFactory {
    private ThreadCancellationServiceTestFactory() {}

    public static ThreadCancellationService create(
            ChatThreadRepository chatThreadRepository,
            TaskRepository taskRepository,
            InvocationRepository invocationRepository,
            QuestHub questHub,
            ThreadPlanningQueue threadPlanningQueue,
            AgentRuntimeClient agentRuntimeClient,
            TaskEventService taskEventService,
            ThreadStatusAggregator threadStatusAggregator,
            TaskDispatchSignal taskDispatchSignal) {
        return new ThreadCancellationService(chatThreadRepository,
                new ExecutionStateService(taskRepository, invocationRepository), questHub, threadPlanningQueue,
                agentRuntimeClient, taskEventService, threadStatusAggregator, taskDispatchSignal);
    }
}
