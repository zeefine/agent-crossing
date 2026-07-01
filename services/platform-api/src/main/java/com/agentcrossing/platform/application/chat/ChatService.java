package com.agentcrossing.platform.application.chat;

import com.agentcrossing.platform.application.realtime.RealtimeEventTypes;
import com.agentcrossing.platform.application.parser.QuestParserService;
import com.agentcrossing.platform.application.parser.UserInputEnqueueResult;
import com.agentcrossing.platform.application.parser.UserInputParseResult;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.event.EventLogRepository;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageRepository;
import com.agentcrossing.platform.domain.message.ChatMessageRole;
import com.agentcrossing.platform.domain.message.ChatMessageStatus;
import com.agentcrossing.platform.domain.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatService {
    public static final String MASTER_AGENT_ID = "masteragent";
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatThreadRepository chatThreadRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final QuestParserService questParserService;
    private final ChatEventService chatEventService;
    private final EventLogRepository eventLogRepository;
    private final UserRepository userRepository;
    private final Executor chatPlanningExecutor;
    private final TransactionTemplate transactionTemplate;

    public ChatService(
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            QuestParserService questParserService) {
        this(chatThreadRepository, chatMessageRepository, questParserService, null, null, null, Runnable::run, (TransactionTemplate) null);
    }

    public ChatService(
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            QuestParserService questParserService,
            ChatEventService chatEventService) {
        this(chatThreadRepository, chatMessageRepository, questParserService, chatEventService, null, null, Runnable::run, (TransactionTemplate) null);
    }

    @Autowired
    public ChatService(
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            QuestParserService questParserService,
            ChatEventService chatEventService,
            EventLogRepository eventLogRepository,
            UserRepository userRepository,
            @Qualifier("chatPlanningExecutor") Executor chatPlanningExecutor,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        this(
                chatThreadRepository,
                chatMessageRepository,
                questParserService,
                chatEventService,
                eventLogRepository,
                userRepository,
                chatPlanningExecutor,
                transactionManagerProvider.getIfAvailable() == null
                        ? null
                        : new TransactionTemplate(transactionManagerProvider.getIfAvailable()));
    }

    private ChatService(
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            QuestParserService questParserService,
            ChatEventService chatEventService,
            EventLogRepository eventLogRepository,
            UserRepository userRepository,
            Executor chatPlanningExecutor,
            TransactionTemplate transactionTemplate) {
        this.chatThreadRepository = chatThreadRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.questParserService = questParserService;
        this.chatEventService = chatEventService;
        this.eventLogRepository = eventLogRepository;
        this.userRepository = userRepository;
        this.chatPlanningExecutor = chatPlanningExecutor;
        this.transactionTemplate = transactionTemplate;
    }

    public ChatThread createThread(String userId, String title) {
        ensureUser(userId);
        Instant now = Instant.now();
        String safeTitle = normalizeTitle(title);
        ChatThread thread = new ChatThread(
                "thread-" + UUID.randomUUID(),
                userId,
                safeTitle,
                ChatThreadStatus.OPEN,
                "trace-" + UUID.randomUUID(),
                now,
                now);
        ChatThread saved = chatThreadRepository.save(thread);
        publishThread(saved);
        return saved;
    }

    @Transactional
    // 请求线程只落库用户消息和 thread running 状态；MasterAgent 规划在事务提交后异步执行。
    // 这样 POST /messages 不再持有 DB 连接等待 Python parser / CLI 返回，后续 direct answer 或 task
    // 通过 WebSocket 事件回推给前端。
    public ChatSubmitResult submitUserMessage(String userId, String threadId, String content) {
        ChatThread thread = chatThreadRepository
                .findByThreadIdAndUserId(threadId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Chat thread not found: " + threadId));
        Instant now = Instant.now();
        ChatMessage userMessage = chatMessageRepository.save(new ChatMessage(
                "message-" + UUID.randomUUID(),
                thread.threadId(),
                ChatMessageRole.USER,
                content,
                ChatMessageStatus.COMPLETED,
                null,
                null,
                null,
                now,
                now));
        publishChatMessage(userMessage);
        if ("New chat".equals(thread.title())) {
            thread = chatThreadRepository.updateTitle(thread.threadId(), deriveTitle(content));
            publishThread(thread);
        }
        thread = chatThreadRepository.updateStatus(thread.threadId(), ChatThreadStatus.RUNNING);
        publishThread(thread);
        schedulePlanningAfterCommit(userId, thread.threadId(), content);
        return new ChatSubmitResult(thread, userMessage, null, List.of());
    }

    public void deleteThread(String userId, String threadId) {
        ChatThread thread = chatThreadRepository
                .findByThreadIdAndUserId(threadId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Chat thread not found: " + threadId));
        // 先广播 threadDeleted 给当前的 WS 订阅者，让前端立刻把该线程从列表移除。
        // 这条事件本身会写一行 realtime_event；下面 eventLogRepository.deleteByThreadId
        // 会把它也一起清掉，但订阅者那时已经收到了。
        publishThreadDeleted(thread.threadId());
        chatMessageRepository.deleteByThreadId(threadId);
        chatThreadRepository.deleteByThreadId(threadId);
        if (eventLogRepository != null) {
            eventLogRepository.deleteByThreadId(threadId);
        }
        // task / invocation / invocation_message 不级联删：它们以 traceId 为主键，
        // 孤立后已有的 null-safe 查询 (findByTraceId.ifPresent) 会正确 no-op。
    }

    private void ensureUser(String userId) {
        if (userRepository != null) {
            userRepository.ensure(userId);
        }
    }

    private void schedulePlanningAfterCommit(String userId, String threadId, String content) {
        Runnable schedule = () -> chatPlanningExecutor.execute(() -> processUserInput(userId, threadId, content));
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedule.run();
                }
            });
            return;
        }
        schedule.run();
    }

    private void processUserInput(String userId, String threadId, String content) {
        try {
            ChatThread thread = chatThreadRepository
                    .findByThreadIdAndUserId(threadId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("Chat thread not found: " + threadId));
            UserInputParseResult parseResult =
                    questParserService.parseUserInput(userId, thread.threadId(), thread.traceId(), content);
            runInTransaction(() -> savePlanningResult(userId, threadId, thread.traceId(), parseResult));
        } catch (Exception exception) {
            runInTransaction(() -> markPlanningFailed(userId, threadId, exception));
        }
    }

    private void runInTransaction(Runnable action) {
        if (transactionTemplate == null) {
            action.run();
            return;
        }
        transactionTemplate.executeWithoutResult(status -> action.run());
    }

    private void savePlanningResult(String userId, String threadId, String traceId, UserInputParseResult parseResult) {
        ChatThread thread = chatThreadRepository
                .findByThreadIdAndUserId(threadId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Chat thread not found: " + threadId));
        UserInputEnqueueResult enqueueResult =
                questParserService.enqueueParsedUserInput(userId, parseResult, traceId);
        if (enqueueResult.directAnswer() != null) {
            Instant answerTime = Instant.now();
            ChatMessage assistantMessage = chatMessageRepository.save(new ChatMessage(
                    "message-" + UUID.randomUUID(),
                    thread.threadId(),
                    ChatMessageRole.ASSISTANT,
                    enqueueResult.directAnswer(),
                    ChatMessageStatus.COMPLETED,
                    null,
                    null,
                    MASTER_AGENT_ID,
                    answerTime,
                    answerTime));
            publishChatMessage(assistantMessage);
        }
        if (enqueueResult.tasks().isEmpty()) {
            ChatThread completed = chatThreadRepository.updateStatus(thread.threadId(), ChatThreadStatus.COMPLETED);
            publishThread(completed);
        }
    }

    private void markPlanningFailed(String userId, String threadId, Exception exception) {
        ChatThread thread = chatThreadRepository.findByThreadIdAndUserId(threadId, userId).orElse(null);
        if (thread == null) {
            return;
        }
        log.warn("Chat planning failed for thread {}", threadId, exception);
        Instant now = Instant.now();
        ChatMessage failedMessage = chatMessageRepository.save(new ChatMessage(
                "message-" + UUID.randomUUID(),
                thread.threadId(),
                ChatMessageRole.ASSISTANT,
                "任务规划失败，请稍后重试。",
                ChatMessageStatus.FAILED,
                null,
                null,
                MASTER_AGENT_ID,
                now,
                now));
        publishChatMessage(failedMessage);
        ChatThread failed = chatThreadRepository.updateStatus(thread.threadId(), ChatThreadStatus.FAILED);
        publishThread(failed);
    }

    private void publishThread(ChatThread thread) {
        if (chatEventService != null) {
            chatEventService.publish(thread.threadId(), RealtimeEventTypes.THREAD, thread);
        }
    }

    private void publishThreadDeleted(String threadId) {
        if (chatEventService != null) {
            chatEventService.publish(threadId, RealtimeEventTypes.THREAD_DELETED, Map.of("threadId", threadId));
        }
    }

    private void publishChatMessage(ChatMessage message) {
        if (chatEventService != null) {
            chatEventService.publish(message.threadId(), RealtimeEventTypes.CHAT_MESSAGE, message);
        }
    }

    private static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "New chat";
        }
        return title.strip();
    }

    private static String deriveTitle(String content) {
        String normalized = content == null ? "" : content.strip().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return "New chat";
        }
        return normalized.length() <= 48 ? normalized : normalized.substring(0, 48);
    }
}
