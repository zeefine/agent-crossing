package com.agentcrossing.platform.application.invocation;

import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.invocation.InvocationUsage;
import com.agentcrossing.platform.domain.invocation.UsagePrecision;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageRepository;
import com.agentcrossing.platform.domain.message.ChatMessageRole;
import com.agentcrossing.platform.domain.message.ChatMessageStatus;
import com.agentcrossing.platform.domain.message.ContextMessageReceipt;
import com.agentcrossing.platform.domain.session.AgentSession;
import com.agentcrossing.platform.domain.session.AgentSessionHistory;
import com.agentcrossing.platform.domain.session.AgentSessionHistoryRepository;
import com.agentcrossing.platform.domain.session.AgentSessionHistoryStatus;
import com.agentcrossing.platform.domain.session.AgentSessionRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AgentSessionCompressionService {
    private static final Logger log = LoggerFactory.getLogger(AgentSessionCompressionService.class);
    private static final Set<String> BUSINESS_AGENTS = Set.of("codex", "claudecode", "opencode");
    private static final int SESSION_LOCK_STRIPES = 256;
    private final ReentrantLock[] sessionLocks = createSessionLocks();
    private final ChatThreadRepository chatThreadRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final TaskRepository taskRepository;
    private final AgentSessionRepository agentSessionRepository;
    private final AgentSessionHistoryRepository historyRepository;
    private final SessionCompressionClient compressionClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final long contextWindowTokens;
    private final double triggerRatio;
    private final int keepTailUserTurns;
    private final int maxTailMessages;
    private final int maxTailCharacters;
    private final String summaryModel;
    private TransactionTemplate transactionTemplate;

    public AgentSessionCompressionService(
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            TaskRepository taskRepository,
            AgentSessionRepository agentSessionRepository,
            AgentSessionHistoryRepository historyRepository,
            SessionCompressionClient compressionClient,
            ObjectMapper objectMapper,
            @Value("${agent-crossing.session-compression.enabled:true}") boolean enabled,
            @Value("${agent-crossing.session-compression.context-window-tokens:1000000}") long contextWindowTokens,
            @Value("${agent-crossing.session-compression.trigger-ratio:0.8}") double triggerRatio,
            @Value("${agent-crossing.session-compression.keep-tail-user-turns:3}") int keepTailUserTurns,
            @Value("${agent-crossing.session-compression.max-tail-messages:20}") int maxTailMessages,
            @Value("${agent-crossing.session-compression.max-tail-characters:200000}") int maxTailCharacters,
            @Value("${agent-crossing.session-compression.summary-model:gpt-5.6}") String summaryModel) {
        this.chatThreadRepository = chatThreadRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.taskRepository = taskRepository;
        this.agentSessionRepository = agentSessionRepository;
        this.historyRepository = historyRepository;
        this.compressionClient = compressionClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.contextWindowTokens = contextWindowTokens;
        this.triggerRatio = triggerRatio;
        this.keepTailUserTurns = keepTailUserTurns;
        this.maxTailMessages = maxTailMessages;
        this.maxTailCharacters = maxTailCharacters;
        this.summaryModel = summaryModel;
    }

    @Autowired(required = false)
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** The session lock is acquired before the database snapshot and released only after commit. */
    public AgentExecutionSnapshot prepareExecution(Task task, Supplier<AgentExecutionSnapshot> loader) {
        String threadId = findThreadId(task);
        if (threadId == null) {
            return loader.get();
        }
        ReentrantLock lock = sessionLock(task.userId(), threadId, task.agentId(), task.agentId());
        lock.lock();
        try {
            return inTransaction(() -> {
                if (historyRepository.findCompacting(task.userId(), threadId, task.agentId(), task.agentId()).isPresent()) {
                    throw new IllegalStateException("Agent session is still compacting");
                }
                return loader.get();
            });
        } finally {
            lock.unlock();
        }
    }

    /** Non-blocking scheduling check: never hold the global router lock while waiting for a summary. */
    public boolean isCompacting(Task task) {
        String threadId = findThreadId(task);
        return threadId != null && historyRepository
                .findCompacting(task.userId(), threadId, task.agentId(), task.agentId()).isPresent();
    }

    public void rememberSession(Task task, String threadId, AgentSession session) {
        ReentrantLock lock = sessionLock(task.userId(), threadId, task.agentId(), session.provider());
        lock.lock();
        try {
            inTaskTransaction(task, () -> {
                // The upsert serializes MySQL writers; commit both pointer and history before unlocking.
                AgentSession savedSession = agentSessionRepository.save(session);
                if (!rememberHistory(task, threadId, savedSession)) {
                    agentSessionRepository.delete(task.userId(), threadId, task.agentId(), session.provider());
                }
                return null;
            });
        } finally {
            lock.unlock();
        }
    }

    private boolean rememberHistory(Task task, String threadId, AgentSession session) {
        if (!BUSINESS_AGENTS.contains(task.agentId())) {
            return true;
        }
        AgentSessionHistory pending = historyRepository
                .findCreating(task.userId(), threadId, task.agentId(), session.provider())
                .orElse(null);
        if (pending != null) {
            AgentSessionHistory predecessor = pending.predecessorSessionRecordId() == null
                    ? null
                    : historyRepository.findBySessionRecordId(pending.predecessorSessionRecordId()).orElse(null);
            if (predecessor != null && session.providerSessionId().equals(predecessor.providerSessionId())) {
                return false;
            }
            historyRepository.save(pending.activate(session.providerSessionId(), Instant.now()));
            return true;
        }
        AgentSessionHistory active = historyRepository
                .findActive(task.userId(), threadId, task.agentId(), session.provider())
                .orElse(null);
        if (active == null) {
            historyRepository.save(firstActiveHistory(task, threadId, session));
            return true;
        }
        if (active.providerSessionId().equals(session.providerSessionId())) {
            return true;
        }
        Instant now = Instant.now();
        historyRepository.save(active.supersede(null, now));
        historyRepository.save(new AgentSessionHistory(
                "session-record-" + UUID.randomUUID(), task.userId(), threadId, task.traceId(), task.agentId(),
                session.provider(), session.providerSessionId(), active.generation() + 1,
                AgentSessionHistoryStatus.ACTIVE, active.sessionRecordId(), null, null, null, null,
                null, null, null, "PROMPT_VERSION_CHANGED", null, now, now, null));
        return true;
    }

    public void compactIfNeeded(Task task, InvocationUsage usage) {
        if (!shouldCompact(task, usage)) {
            return;
        }
        // REQUIRES_NEW only suspends a caller's transaction; its connection would still be held
        // throughout the remote summary. Require the orchestrator to call after its transaction ends.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Session compression must run outside a database transaction");
        }
        String threadId = findThreadId(task);
        if (threadId == null) {
            return;
        }
        ReentrantLock lock = sessionLock(task.userId(), threadId, task.agentId(), usage.provider());
        lock.lock();
        try {
            compact(task, threadId, new Rotation(usage.provider(), usage.providerSessionId(), usage.contextInputTokens(), false));
        } finally {
            lock.unlock();
        }
    }

    /** Prompt changes require a handoff even when automatic token compaction is disabled or below threshold. */
    public void rotateForPromptChange(Task task, AgentSession rejectedSession) {
        if (!BUSINESS_AGENTS.contains(task.agentId())) {
            throw new IllegalArgumentException("Prompt rotation is only supported for business agents");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Session compression must run outside a database transaction");
        }
        String threadId = findThreadId(task);
        if (threadId == null) {
            throw new DeletedExecutionException(task.taskId());
        }
        ReentrantLock lock = sessionLock(task.userId(), threadId, task.agentId(), rejectedSession.provider());
        lock.lock();
        try {
            compact(task, threadId, new Rotation(rejectedSession.provider(), rejectedSession.providerSessionId(), null, true));
        } finally {
            lock.unlock();
        }
    }

    private record Rotation(String provider, String providerSessionId, Long contextTokens, boolean promptChanged) {
        String reason() { return promptChanged ? "PROMPT_VERSION_CHANGED" : "TOKEN_THRESHOLD"; }
    }

    private void compact(Task task, String threadId, Rotation rotation) {
        CompressionPlan plan = inTaskTransaction(task, () -> prepareCompression(task, threadId, rotation));
        if (plan == null) {
            return;
        }
        try {
            // COMPACTING is already committed; no database connection is held while calling the model.
            // A short/already summarized history needs no model call, but still needs a new generation and tail.
            SessionCompressionResult result = plan.request().messages().isEmpty() ? null : compressionClient.compress(plan.request());
            String summary = result == null ? plan.active().startupSummary() : toJson(result.startupSummary());
            boolean rotated = inTaskTransaction(task, () -> {
                AgentSessionHistory current = historyRepository
                        .findBySessionRecordId(plan.active().sessionRecordId()).orElse(null);
                if (!plan.ownsReservation(current)) {
                    return false; // The thread was deleted or the reserved generation/session changed.
                }
                Instant now = Instant.now();
                AgentSessionHistory pending = new AgentSessionHistory(
                        "session-record-" + UUID.randomUUID(), task.userId(), threadId, task.traceId(), task.agentId(),
                        rotation.provider(), null, current.generation() + 1, AgentSessionHistoryStatus.CREATING,
                        current.sessionRecordId(), summary, plan.compactedStartId(), plan.compactedEndId(),
                        plan.tailFirstId(), null, result == null ? current.summaryModel() : summaryModel,
                        result == null ? current.summaryPromptVersion() : result.summaryPromptVersion(),
                        rotation.reason(), null, now, null, null);
                historyRepository.save(current.supersede(rotation.contextTokens(), now));
                historyRepository.save(pending);
                // The summary covers only these exact snapshots, never a late-completing/revised row.
                chatMessageRepository.acknowledgeSummarizedMessages(task.userId(), threadId, task.agentId(),
                        plan.request().messages().stream()
                                .map(message -> ContextMessageReceipt.of(message.messageId(), message.content()))
                                .toList(), plan.active().startupSummary() == null);
                agentSessionRepository.delete(task.userId(), threadId, task.agentId(), rotation.provider());
                return true;
            });
            if (rotated) {
                log.info("Business session rotated userId={} threadId={} agentId={} generation={} reason={} contextTokens={}",
                        task.userId(), threadId, task.agentId(), plan.active().generation() + 1, rotation.reason(), rotation.contextTokens());
            }
        } catch (RuntimeException | Error failure) {
            try {
                inTaskTransaction(task, () -> {
                    historyRepository.findBySessionRecordId(plan.active().sessionRecordId())
                            .filter(plan::ownsReservation)
                            .ifPresent(history -> historyRepository.save(history.withStatus(AgentSessionHistoryStatus.ACTIVE)));
                    return null;
                });
            } catch (RuntimeException | Error restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
    }

    private CompressionPlan prepareCompression(Task task, String threadId, Rotation rotation) {
        String provider = rotation.provider();
        if (historyRepository.findCreating(task.userId(), threadId, task.agentId(), provider).isPresent()
                || historyRepository.findCompacting(task.userId(), threadId, task.agentId(), provider).isPresent()) {
            return null;
        }
        AgentSession currentSession = agentSessionRepository
                .findByThreadId(task.userId(), threadId, task.agentId(), provider)
                .orElse(null);
        if (currentSession == null || (rotation.providerSessionId() != null
                && !rotation.providerSessionId().equals(currentSession.providerSessionId()))) {
            if (rotation.promptChanged()) {
                throw new IllegalStateException("Provider session changed before prompt rotation");
            }
            return null;
        }
        AgentSessionHistory active = historyRepository
                .findActive(task.userId(), threadId, task.agentId(), provider)
                .orElseGet(() -> historyRepository.save(firstActiveHistory(task, threadId, currentSession)));
        List<ChatMessage> messages = chatMessageRepository.findByThreadId(threadId).stream()
                .filter(message -> message.status() == ChatMessageStatus.COMPLETED)
                .filter(message -> message.role() == ChatMessageRole.USER || message.role() == ChatMessageRole.ASSISTANT)
                .toList();
        int tailStart = tailStart(messages);
        Map<String, String> summarizedVersions = active.startupSummary() == null ? Map.of() : chatMessageRepository
                .findSummarizedContextMessages(task.userId(), threadId, task.agentId()).stream()
                .collect(Collectors.toMap(ContextMessageReceipt::messageId, ContextMessageReceipt::contentVersion));
        // A creation-time boundary would skip early messages completed/revised after the last summary.
        List<ChatMessage> compacted = messages.subList(0, tailStart).stream()
                .filter(message -> !ContextMessageReceipt.of(message.messageId(), message.content()).contentVersion()
                        .equals(summarizedVersions.get(message.messageId())))
                .toList();
        if (compacted.isEmpty() && !rotation.promptChanged()) {
            return null;
        }
        List<SessionCompressionRequest.CompressionMessage> compressionMessages = compacted.stream()
                .map(message -> new SessionCompressionRequest.CompressionMessage(
                        message.messageId(), message.role().wireValue(), message.agentId(), message.taskId(),
                        message.content(), message.createdAt().toString()))
                .toList();
        List<SessionCompressionRequest.CompressionTask> tasks = taskRepository
                .findByTraceIdAndUserId(task.traceId(), task.userId()).stream()
                .map(item -> new SessionCompressionRequest.CompressionTask(
                        item.taskId(), item.agentId(),
                        !rotation.promptChanged() && item.taskId().equals(task.taskId())
                                ? "COMPLETED" : item.status().name(), item.context()))
                .toList();
        SessionCompressionRequest request = new SessionCompressionRequest(
                task.userId(), threadId, task.traceId(), task.agentId(), provider,
                active.generation() + 1, active.startupSummary(), compressionMessages, tasks);
        ChatMessage tailFirst = tailStart < messages.size() ? messages.get(tailStart) : null;
        historyRepository.save(active.withStatus(AgentSessionHistoryStatus.COMPACTING));
        return new CompressionPlan(active, request,
                compacted.isEmpty() ? active.compactedStartMessageId() : compacted.getFirst().messageId(),
                compacted.isEmpty() ? active.compactedEndMessageId() : compacted.getLast().messageId(),
                tailFirst == null ? null : tailFirst.messageId());
    }

    private record CompressionPlan(AgentSessionHistory active, SessionCompressionRequest request,
                                   String compactedStartId, String compactedEndId, String tailFirstId) {
        boolean ownsReservation(AgentSessionHistory current) {
            return current != null
                    && current.status() == AgentSessionHistoryStatus.COMPACTING
                    && current.sessionRecordId().equals(active.sessionRecordId())
                    && current.generation() == active.generation()
                    && current.providerSessionId().equals(active.providerSessionId());
        }
    }

    private String findThreadId(Task task) {
        return chatThreadRepository.findByTraceId(task.traceId())
                .filter(thread -> thread.userId().equals(task.userId()))
                .map(thread -> thread.threadId()).orElse(null);
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate == null ? action.get() : transactionTemplate.execute(status -> action.get());
    }

    private <T> T inTaskTransaction(Task task, Supplier<T> action) {
        // Always session lock -> task lock. The remote summary is outside both the task lock and transaction.
        synchronized (taskRepository) {
            return inTransaction(() -> {
                taskRepository.findByTaskIdForUpdate(task.taskId())
                        .orElseThrow(() -> new DeletedExecutionException(task.taskId()));
                return action.get();
            });
        }
    }

    private boolean shouldCompact(Task task, InvocationUsage usage) {
        return enabled
                && BUSINESS_AGENTS.contains(task.agentId())
                && usage != null
                && usage.usagePrecision() == UsagePrecision.EXACT
                && usage.contextInputTokens() != null
                && usage.contextInputTokens() >= (long) Math.ceil(contextWindowTokens * triggerRatio);
    }

    private AgentSessionHistory firstActiveHistory(Task task, String threadId, AgentSession session) {
        Instant now = Instant.now();
        return new AgentSessionHistory(
                "session-record-" + UUID.randomUUID(), task.userId(), threadId, task.traceId(), task.agentId(),
                session.provider(), session.providerSessionId(), 1, AgentSessionHistoryStatus.ACTIVE,
                null, null, null, null, null, null, null, null, null, null,
                session.createdAt(), session.createdAt(), null);
    }

    private int tailStart(List<ChatMessage> messages) {
        int userTurns = 0;
        int characters = 0;
        int included = 0;
        int start = messages.size();
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            int nextCharacters = characters + message.content().length();
            if (included >= maxTailMessages || (included > 0 && nextCharacters > maxTailCharacters)) {
                break;
            }
            start = index;
            included++;
            characters = nextCharacters;
            if (message.role() == ChatMessageRole.USER && ++userTurns >= keepTailUserTurns) {
                break;
            }
        }
        return start;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize startup summary", exception);
        }
    }

    private ReentrantLock sessionLock(String userId, String threadId, String agentId, String provider) {
        String key = userId + "\n" + threadId + "\n" + agentId + "\n" + provider;
        return sessionLocks[Math.floorMod(key.hashCode(), sessionLocks.length)];
    }

    private static ReentrantLock[] createSessionLocks() {
        ReentrantLock[] locks = new ReentrantLock[SESSION_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }
}
