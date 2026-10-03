package com.agentcrossing.platform.support;

import com.agentcrossing.platform.application.chat.AssistantStreamBuffer;
import com.agentcrossing.platform.application.chat.ChatEventService;
import com.agentcrossing.platform.application.chat.ThreadPlanningQueue;
import com.agentcrossing.platform.application.chat.ThreadStatusAggregator;
import com.agentcrossing.platform.application.invocation.AgentContextService;
import com.agentcrossing.platform.application.invocation.AgentRuntimeClient;
import com.agentcrossing.platform.application.invocation.AgentSessionCompressionService;
import com.agentcrossing.platform.application.invocation.ExecutionStateService;
import com.agentcrossing.platform.application.invocation.InvocationService;
import com.agentcrossing.platform.application.routing.TaskDispatchSignal;
import com.agentcrossing.platform.application.task.TaskEventService;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.invocation.InvocationUsageRepository;
import com.agentcrossing.platform.domain.message.ChatMessageRepository;
import com.agentcrossing.platform.domain.message.InvocationMessageRepository;
import com.agentcrossing.platform.domain.session.AgentSessionRepository;
import com.agentcrossing.platform.domain.task.TaskDependencyRepository;
import com.agentcrossing.platform.domain.task.TaskRepository;

/** Test-only defaults; production services receive fully assembled dependencies. */
public final class InvocationServiceTestFactory {
    private InvocationServiceTestFactory() {}

    public static InvocationService create(
            InvocationRepository invocationRepository,
            TaskRepository taskRepository,
            AgentRuntimeClient agentRuntimeClient,
            String callbackBaseUrl,
            TaskDispatchSignal taskDispatchSignal,
            InvocationMessageRepository invocationMessageRepository,
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            ChatEventService chatEventService,
            TaskEventService taskEventService,
            AssistantStreamBuffer assistantStreamBuffer,
            TaskDependencyRepository taskDependencyRepository,
            AgentContextService agentContextService,
            AgentSessionRepository agentSessionRepository) {
        return create(
                invocationRepository,
                taskRepository,
                agentRuntimeClient,
                callbackBaseUrl,
                taskDispatchSignal,
                invocationMessageRepository,
                chatThreadRepository,
                chatMessageRepository,
                chatEventService,
                taskEventService,
                assistantStreamBuffer,
                taskDependencyRepository,
                agentContextService,
                agentSessionRepository,
                null);
    }

    public static InvocationService create(
            InvocationRepository invocationRepository,
            TaskRepository taskRepository,
            AgentRuntimeClient agentRuntimeClient,
            String callbackBaseUrl,
            TaskDispatchSignal taskDispatchSignal,
            InvocationMessageRepository invocationMessageRepository,
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            ChatEventService chatEventService,
            TaskEventService taskEventService,
            AssistantStreamBuffer assistantStreamBuffer,
            TaskDependencyRepository taskDependencyRepository,
            AgentContextService agentContextService,
            AgentSessionRepository agentSessionRepository,
            InvocationUsageRepository invocationUsageRepository) {
        return create(
                invocationRepository,
                taskRepository,
                agentRuntimeClient,
                callbackBaseUrl,
                taskDispatchSignal,
                invocationMessageRepository,
                chatThreadRepository,
                chatMessageRepository,
                chatEventService,
                taskEventService,
                assistantStreamBuffer,
                taskDependencyRepository,
                agentContextService,
                agentSessionRepository,
                invocationUsageRepository,
                null,
                new ThreadStatusAggregator(
                        chatThreadRepository,
                        taskRepository,
                        invocationRepository,
                        new ThreadPlanningQueue(Runnable::run),
                        chatEventService));
    }

    public static InvocationService create(
            InvocationRepository invocationRepository,
            TaskRepository taskRepository,
            AgentRuntimeClient agentRuntimeClient,
            String callbackBaseUrl,
            TaskDispatchSignal taskDispatchSignal,
            InvocationMessageRepository invocationMessageRepository,
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            ChatEventService chatEventService,
            TaskEventService taskEventService,
            AssistantStreamBuffer assistantStreamBuffer,
            TaskDependencyRepository taskDependencyRepository,
            AgentContextService agentContextService,
            AgentSessionRepository agentSessionRepository,
            InvocationUsageRepository invocationUsageRepository,
            AgentSessionCompressionService sessionCompressionService,
            ThreadStatusAggregator threadStatusAggregator) {
        return new InvocationService(
                invocationRepository,
                taskRepository,
                agentRuntimeClient,
                callbackBaseUrl,
                taskDispatchSignal,
                invocationMessageRepository,
                chatThreadRepository,
                chatMessageRepository,
                chatEventService,
                taskEventService,
                assistantStreamBuffer,
                taskDependencyRepository,
                agentContextService,
                agentSessionRepository,
                invocationUsageRepository,
                sessionCompressionService,
                threadStatusAggregator,
                new ExecutionStateService(taskRepository, invocationRepository));
    }
}
