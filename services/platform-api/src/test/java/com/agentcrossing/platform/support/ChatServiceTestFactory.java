package com.agentcrossing.platform.support;

import com.agentcrossing.platform.application.chat.ChatEventService;
import com.agentcrossing.platform.application.chat.ChatService;
import com.agentcrossing.platform.application.chat.ThreadPlanningQueue;
import com.agentcrossing.platform.application.chat.ThreadStatusAggregator;
import com.agentcrossing.platform.application.parser.QuestParserService;
import com.agentcrossing.platform.domain.context.AgentContextCursorRepository;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.event.EventLogRepository;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.message.ChatMessageRepository;
import com.agentcrossing.platform.domain.message.InvocationMessageRepository;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.session.AgentSessionRepository;
import com.agentcrossing.platform.domain.task.TaskCreationRepository;
import com.agentcrossing.platform.domain.task.TaskDependencyRepository;
import com.agentcrossing.platform.domain.task.TaskRepository;
import com.agentcrossing.platform.domain.user.UserRepository;
import java.util.concurrent.Executor;
import org.springframework.transaction.PlatformTransactionManager;

/** Compatibility defaults live in tests, not in the Spring service. */
public final class ChatServiceTestFactory {
    private ChatServiceTestFactory() {}

    public static ChatService create(
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            QuestParserService questParserService) {
        return create(
                chatThreadRepository,
                chatMessageRepository,
                questParserService,
                null);
    }

    public static ChatService create(
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            QuestParserService questParserService,
            ChatEventService chatEventService) {
        return create(
                chatThreadRepository,
                chatMessageRepository,
                questParserService,
                chatEventService,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Runnable::run,
                (PlatformTransactionManager) null);
    }

    public static ChatService create(
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            QuestParserService questParserService,
            ChatEventService chatEventService,
            EventLogRepository eventLogRepository,
            UserRepository userRepository,
            InvocationMessageRepository invocationMessageRepository,
            InvocationRepository invocationRepository,
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            AgentContextCursorRepository agentContextCursorRepository,
            AgentSessionRepository agentSessionRepository,
            QuestHub questHub,
            Executor chatPlanningExecutor,
            PlatformTransactionManager transactionManager) {
        return create(
                chatThreadRepository,
                chatMessageRepository,
                questParserService,
                chatEventService,
                eventLogRepository,
                userRepository,
                invocationMessageRepository,
                invocationRepository,
                taskRepository,
                null,
                taskDependencyRepository,
                agentContextCursorRepository,
                agentSessionRepository,
                questHub,
                chatPlanningExecutor,
                transactionManager);
    }

    public static ChatService create(
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            QuestParserService questParserService,
            ChatEventService chatEventService,
            EventLogRepository eventLogRepository,
            UserRepository userRepository,
            InvocationMessageRepository invocationMessageRepository,
            InvocationRepository invocationRepository,
            TaskRepository taskRepository,
            TaskCreationRepository taskCreationRepository,
            TaskDependencyRepository taskDependencyRepository,
            AgentContextCursorRepository agentContextCursorRepository,
            AgentSessionRepository agentSessionRepository,
            QuestHub questHub,
            Executor chatPlanningExecutor,
            PlatformTransactionManager transactionManager) {
        var queue = new ThreadPlanningQueue(chatPlanningExecutor);
        var aggregator = new ThreadStatusAggregator(
                chatThreadRepository, taskRepository, invocationRepository, queue, chatEventService);
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        if (transactionManager != null) {
            beans.registerSingleton("transactionManager", transactionManager);
        }
        return new ChatService(
                chatThreadRepository, chatMessageRepository, questParserService, chatEventService,
                eventLogRepository, userRepository, invocationMessageRepository, invocationRepository,
                taskRepository, taskCreationRepository, taskDependencyRepository, agentContextCursorRepository,
                agentSessionRepository, questHub, chatPlanningExecutor, queue, aggregator,
                beans.getBeanProvider(PlatformTransactionManager.class));
    }
}
