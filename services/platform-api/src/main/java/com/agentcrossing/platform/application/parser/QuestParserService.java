package com.agentcrossing.platform.application.parser;

import com.agentcrossing.platform.application.routing.LoopGuardService;
import com.agentcrossing.platform.application.routing.TaskDispatchSignal;
import com.agentcrossing.platform.application.task.TaskEventService;
import com.agentcrossing.platform.domain.agent.AgentRegistry;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.session.AgentSession;
import com.agentcrossing.platform.domain.session.AgentSessionRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskCreation;
import com.agentcrossing.platform.domain.task.TaskCreationRepository;
import com.agentcrossing.platform.domain.task.TaskDependency;
import com.agentcrossing.platform.domain.task.TaskDependencyRepository;
import com.agentcrossing.platform.domain.task.TaskRepository;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import com.agentcrossing.platform.domain.user.UserRepository;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class QuestParserService {
    public static final String MASTER_AGENT_ID = "masteragent";
    public static final String MASTER_AGENT_PROVIDER = "claudecode";
    private static final String SELF_ORCHESTRATION_CONTRACT_START = "[Self-Orchestration Contract]";
    private static final String SELF_ORCHESTRATION_CONTRACT_END = "[/Self-Orchestration Contract]";
    private static final Logger log = LoggerFactory.getLogger(QuestParserService.class);

    private final QuestParserClient parserClient;
    private final AgentRegistry agentRegistry;
    private final TaskRepository taskRepository;
    private final TaskCreationRepository taskCreationRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final QuestHub questHub;
    private final LoopGuardService loopGuardService;
    private final TaskDispatchSignal taskDispatchSignal;
    private final TaskEventService taskEventService;
    private final UserRepository userRepository;
    private final AgentSessionRepository agentSessionRepository;
    private final DagValidator dagValidator;

    @Autowired
    public QuestParserService(
            QuestParserClient parserClient,
            AgentRegistry agentRegistry,
            TaskRepository taskRepository,
            TaskCreationRepository taskCreationRepository,
            TaskDependencyRepository taskDependencyRepository,
            QuestHub questHub,
            LoopGuardService loopGuardService,
            TaskDispatchSignal taskDispatchSignal,
            TaskEventService taskEventService,
            UserRepository userRepository,
            AgentSessionRepository agentSessionRepository) {
        this.parserClient = parserClient;
        this.agentRegistry = agentRegistry;
        this.taskRepository = taskRepository;
        this.taskCreationRepository = taskCreationRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.questHub = questHub;
        this.loopGuardService = loopGuardService;
        this.taskDispatchSignal = taskDispatchSignal;
        this.taskEventService = taskEventService;
        this.userRepository = userRepository;
        this.agentSessionRepository = agentSessionRepository;
        this.dagValidator = new DagValidator();
    }

    /** Compatibility constructor for focused tests without a Spring-managed idempotency repository. */
    public QuestParserService(
            QuestParserClient parserClient,
            AgentRegistry agentRegistry,
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            QuestHub questHub,
            LoopGuardService loopGuardService,
            TaskDispatchSignal taskDispatchSignal,
            TaskEventService taskEventService,
            UserRepository userRepository,
            AgentSessionRepository agentSessionRepository) {
        this(
                parserClient,
                agentRegistry,
                taskRepository,
                new com.agentcrossing.platform.domain.task.InMemoryTaskCreationRepository(),
                taskDependencyRepository,
                questHub,
                loopGuardService,
                taskDispatchSignal,
                taskEventService,
                userRepository,
                agentSessionRepository);
    }

    public QuestParserService(
            QuestParserClient parserClient,
            AgentRegistry agentRegistry,
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            QuestHub questHub,
            LoopGuardService loopGuardService,
            TaskDispatchSignal taskDispatchSignal,
            TaskEventService taskEventService) {
        this(
                parserClient,
                agentRegistry,
                taskRepository,
                new com.agentcrossing.platform.domain.task.InMemoryTaskCreationRepository(),
                taskDependencyRepository,
                questHub,
                loopGuardService,
                taskDispatchSignal,
                taskEventService,
                null,
                null);
    }

    public QuestParserService(
            QuestParserClient parserClient,
            AgentRegistry agentRegistry,
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            QuestHub questHub,
            LoopGuardService loopGuardService) {
        this(
                parserClient,
                agentRegistry,
                taskRepository,
                new com.agentcrossing.platform.domain.task.InMemoryTaskCreationRepository(),
                taskDependencyRepository,
                questHub,
                loopGuardService,
                TaskDispatchSignal.NOOP,
                null,
                null,
                null);
    }

    @Transactional
    public List<Task> parseUserInputAndEnqueue(String userId, String input, String traceId) {
        return parseUserInputAndEnqueueWithResult(userId, input, traceId).tasks();
    }

    @Transactional
    public UserInputEnqueueResult parseUserInputAndEnqueueWithResult(String userId, String input, String traceId) {
        return enqueueParsedUserInput(userId, parserClient.parseUserInput(input, agentRegistry.findAll()), traceId);
    }

    public UserInputParseResult parseUserInput(String userId, String threadId, String traceId, String input) {
        return parseUserInput(userId, threadId, traceId, input, null);
    }

    public UserInputParseResult parseUserInput(
            String userId,
            String threadId,
            String traceId,
            String input,
            ThreadExecutionSummary threadExecutionSummary) {
        AgentSession providerSession = findMasterAgentSession(userId, threadId);
        UserInputParseResult result = parserClient.parseUserInput(
                userId,
                threadId,
                traceId,
                input,
                providerSession == null ? null : providerSession.providerSessionId(),
                providerSession == null ? null : providerSession.promptVersion(),
                agentRegistry.findAll(),
                threadExecutionSummary);
        rememberMasterAgentSession(
                userId,
                threadId,
                traceId,
                result.providerSessionId(),
                result.promptVersion());
        return result;
    }

    @Transactional
    public UserInputEnqueueResult enqueueParsedUserInput(String userId, UserInputParseResult parseResult, String traceId) {
        ensureUser(userId);
        List<ParsedTask> parsedTasks = validateUserPlan(parseResult.tasks());
        List<Task> accepted = new ArrayList<>();
        List<ParsedTask> acceptedPlans = new ArrayList<>();
        for (ParsedTask parsedTask : parsedTasks) {
            // v1.0 约定未知 agent 直接丢弃，不进入队列，也不产生错误。
            if (!agentRegistry.exists(parsedTask.agentId())) {
                continue;
            }
            Task task = toTask(userId, parsedTask, traceId, null, TaskSource.USER, 0);
            taskRepository.save(task);
            accepted.add(task);
            acceptedPlans.add(parsedTask);
        }
        saveDependencies(acceptedPlans, accepted);
        enqueueAfterCommit(accepted);
        return new UserInputEnqueueResult(accepted, parseResult.directAnswer());
    }

    @Transactional
    public List<Task> appendAgentTasks(Task sourceTask, List<ParsedTask> parsedTasks) {
        return appendAgentTasks(sourceTask, parsedTasks, legacyIdempotencyKey(sourceTask, parsedTasks));
    }

    /**
     * Appends tasks exactly once per {@code sourceTaskId + clientTaskId}.
     *
     * <p>The caller-provided task ids are client ids, not global primary keys. Persisting this
     * mapping lets a lost MCP response be retried safely and makes concurrent snapshot/create
     * calls converge on the same task.
     */
    @Transactional
    public List<Task> appendAgentTasks(Task sourceTask, List<ParsedTask> parsedTasks, String idempotencyKey) {
        List<ParsedTask> appendTasks = validateAppendPlan(sourceTask, parsedTasks);
        if (appendTasks.isEmpty()) {
            return List.of();
        }
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey, sourceTask, appendTasks);
        Map<String, String> taskIdsByClientTaskId = new LinkedHashMap<>();
        for (ParsedTask appendTask : appendTasks) {
            taskIdsByClientTaskId.put(appendTask.taskId(), generatedAppendTaskId(sourceTask.taskId(), appendTask.taskId()));
        }

        List<Task> accepted = new ArrayList<>();
        List<Task> newlyCreated = new ArrayList<>();
        List<ParsedTask> newlyCreatedPlans = new ArrayList<>();
        for (ParsedTask parsedTask : appendTasks) {
            // agent 输出产生的是派生任务，必须仍然挂在来源任务的 trace 上。
            if (!agentRegistry.exists(parsedTask.agentId())) {
                continue;
            }
            String clientTaskId = parsedTask.taskId();
            String taskId = taskIdsByClientTaskId.get(clientTaskId);
            ParsedTask resolvedPlan = new ParsedTask(
                    taskId,
                    parsedTask.agentId(),
                    parsedTask.context(),
                    resolvedAppendDependencies(parsedTask.dependsOn(), taskIdsByClientTaskId));
            Task task = toTask(
                    sourceTask.userId(),
                    resolvedPlan,
                    sourceTask.traceId(),
                    sourceTask.taskId(),
                    TaskSource.AGENT,
                    sourceTask.depth() + 1);
            if (!loopGuardService.allows(task)) {
                continue;
            }
            TaskCreation requestedCreation = new TaskCreation(
                    sourceTask.taskId(),
                    clientTaskId,
                    taskId,
                    sourceTask.userId(),
                    sourceTask.traceId(),
                    normalizedIdempotencyKey,
                    Instant.now());
            if (taskCreationRepository.saveIfAbsent(requestedCreation)) {
                // Agent 输出生成的新任务统一进入 questHub，由 DAG 依赖判断是否就绪。
                taskRepository.save(task);
                accepted.add(task);
                newlyCreated.add(task);
                newlyCreatedPlans.add(resolvedPlan);
                continue;
            }
            TaskCreation existingCreation = taskCreationRepository
                    .findBySourceTaskIdAndClientTaskId(sourceTask.taskId(), clientTaskId)
                    .orElseThrow(() -> new IllegalStateException("Task creation mapping disappeared: " + clientTaskId));
            Task existingTask = taskRepository.findByTaskId(existingCreation.taskId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Task creation mapping points to a missing task: " + existingCreation.taskId()));
            accepted.add(existingTask);
        }
        saveDependencies(newlyCreatedPlans, newlyCreated);
        enqueueAfterCommit(newlyCreated);
        return accepted;
    }

    private void enqueueAfterCommit(List<Task> accepted) {
        if (accepted.isEmpty()) {
            return;
        }
        Runnable enqueue = () -> {
            long startedAt = System.nanoTime();
            accepted.forEach(task -> {
                questHub.enqueueLast(task.taskId());
                publishTask(task);
            });
            signalIfAccepted(accepted);
            log.info(
                    "agent_crossing_perf event=questhub_enqueue durationMs={} tasks={} taskIds={}",
                    elapsedMs(startedAt),
                    accepted.size(),
                    accepted.stream().map(Task::taskId).toList());
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue.run();
                }
            });
            return;
        }
        enqueue.run();
    }

    private void signalIfAccepted(List<Task> accepted) {
        if (!accepted.isEmpty()) {
            taskDispatchSignal.signal();
        }
    }

    private void publishTask(Task task) {
        if (taskEventService != null) {
            taskEventService.publish(task);
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private void ensureUser(String userId) {
        if (userRepository != null) {
            userRepository.ensure(userId);
        }
    }

    private AgentSession findMasterAgentSession(String userId, String threadId) {
        if (agentSessionRepository == null) {
            return null;
        }
        return agentSessionRepository
                .findByThreadId(userId, threadId, MASTER_AGENT_ID, MASTER_AGENT_PROVIDER)
                .orElse(null);
    }

    private void rememberMasterAgentSession(
            String userId,
            String threadId,
            String traceId,
            String providerSessionId,
            String promptVersion) {
        if (agentSessionRepository == null
                || providerSessionId == null
                || providerSessionId.isBlank()
                || promptVersion == null
                || promptVersion.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        Instant createdAt = agentSessionRepository
                .findByThreadId(userId, threadId, MASTER_AGENT_ID, MASTER_AGENT_PROVIDER)
                .map(AgentSession::createdAt)
                .orElse(now);
        agentSessionRepository.save(new AgentSession(
                userId,
                threadId,
                traceId,
                MASTER_AGENT_ID,
                MASTER_AGENT_PROVIDER,
                providerSessionId,
                promptVersion,
                createdAt,
                now));
    }

    private List<ParsedTask> validateUserPlan(List<ParsedTask> parsedTasks) {
        Map<String, Integer> taskIdCounts = new HashMap<>();
        for (ParsedTask task : parsedTasks) {
            if (task.taskId() != null && !task.taskId().isBlank()) {
                taskIdCounts.merge(task.taskId().strip(), 1, Integer::sum);
            }
        }
        Map<String, ParsedTask> candidates = new LinkedHashMap<>();
        for (ParsedTask task : parsedTasks) {
            if (task.taskId() == null
                    || task.agentId() == null
                    || task.context() == null) {
                continue;
            }
            String taskId = task.taskId().strip();
            if (taskId.isBlank()
                    || taskIdCounts.getOrDefault(taskId, 0) != 1
                    || task.agentId().isBlank()
                    || task.context().isBlank()
                    || !agentRegistry.exists(task.agentId().strip())) {
                continue;
            }
            candidates.put(taskId, new ParsedTask(
                    taskId,
                    task.agentId().strip(),
                    task.context().strip(),
                    normalizeDependencies(task.dependsOn())));
        }

        candidates = remapExistingTaskIds(candidates);
        boolean changed;
        do {
            Set<String> candidateIds = Set.copyOf(candidates.keySet());
            changed = candidates.entrySet().removeIf(entry -> entry.getValue().dependsOn().stream()
                    .anyMatch(parentTaskId -> parentTaskId.equals(entry.getKey())
                            || !candidateIds.contains(parentTaskId)));
        } while (changed);

        ensureValidDag(List.copyOf(candidates.values()));
        return List.copyOf(candidates.values());
    }

    private List<ParsedTask> validateAppendPlan(Task sourceTask, List<ParsedTask> parsedTasks) {
        if (parsedTasks == null || parsedTasks.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> taskIdCounts = new HashMap<>();
        for (ParsedTask task : parsedTasks) {
            if (task.taskId() != null && !task.taskId().isBlank()) {
                taskIdCounts.merge(task.taskId().strip(), 1, Integer::sum);
            }
        }

        Map<String, ParsedTask> candidates = new LinkedHashMap<>();
        for (ParsedTask task : parsedTasks) {
            if (task.taskId() == null || task.agentId() == null || task.context() == null) {
                continue;
            }
            String taskId = task.taskId().strip();
            if (taskId.isBlank()
                    || taskIdCounts.getOrDefault(taskId, 0) != 1
                    || task.agentId().isBlank()
                    || task.context().isBlank()
                    || !agentRegistry.exists(task.agentId().strip())) {
                continue;
            }
            List<String> dependencies = normalizeAppendDependencies(sourceTask.taskId(), task.dependsOn());
            candidates.put(taskId, new ParsedTask(
                    taskId,
                    task.agentId().strip(),
                    inheritSelfOrchestrationContract(sourceTask.context(), task.context()),
                    dependencies));
        }

        boolean changed;
        do {
            Set<String> candidateIds = Set.copyOf(candidates.keySet());
            changed = candidates.entrySet().removeIf(entry -> entry.getValue().dependsOn().stream()
                    .anyMatch(parentTaskId -> parentTaskId.equals(entry.getKey())
                            || (!candidateIds.contains(parentTaskId)
                                    && !isValidAppendExistingDependency(sourceTask, parentTaskId))));
        } while (changed);

        ensureValidDag(List.copyOf(projectCandidateDependencies(candidates).values()));
        return List.copyOf(candidates.values());
    }

    /**
     * 自组织互动的完成条件由根任务定义，后续 agent 只能追加任务，不能在接力时弱化条件。
     * 因此平台会移除模型生成的契约副本，并始终附加来源任务中的权威版本。
     */
    private static String inheritSelfOrchestrationContract(String sourceContext, String childContext) {
        String normalizedChildContext = childContext.strip();
        String sourceContract = extractSelfOrchestrationContract(sourceContext);
        if (sourceContract == null) {
            return normalizedChildContext;
        }
        String childWithoutContract = removeSelfOrchestrationContract(normalizedChildContext);
        if (childWithoutContract.isBlank()) {
            return sourceContract;
        }
        return childWithoutContract + "\n\n" + sourceContract;
    }

    private static String extractSelfOrchestrationContract(String context) {
        if (context == null || context.isBlank()) {
            return null;
        }
        int start = context.indexOf(SELF_ORCHESTRATION_CONTRACT_START);
        int end = context.lastIndexOf(SELF_ORCHESTRATION_CONTRACT_END);
        if (start < 0 || end < start) {
            return null;
        }
        return context.substring(start, end + SELF_ORCHESTRATION_CONTRACT_END.length()).strip();
    }

    private static String removeSelfOrchestrationContract(String context) {
        int start = context.indexOf(SELF_ORCHESTRATION_CONTRACT_START);
        int end = context.lastIndexOf(SELF_ORCHESTRATION_CONTRACT_END);
        if (start < 0 || end < start) {
            return context;
        }
        return (context.substring(0, start)
                        + context.substring(end + SELF_ORCHESTRATION_CONTRACT_END.length()))
                .strip();
    }

    private Map<String, ParsedTask> remapExistingTaskIds(Map<String, ParsedTask> candidates) {
        Map<String, String> idRemaps = new HashMap<>();
        Set<String> reservedIds = new HashSet<>(candidates.keySet());
        for (String taskId : candidates.keySet()) {
            if (taskRepository.findByTaskId(taskId).isPresent()) {
                String nextTaskId = allocateTaskId(taskId, reservedIds);
                idRemaps.put(taskId, nextTaskId);
                reservedIds.add(nextTaskId);
            }
        }
        if (idRemaps.isEmpty()) {
            return candidates;
        }

        Map<String, ParsedTask> remappedCandidates = new LinkedHashMap<>();
        for (ParsedTask task : candidates.values()) {
            String taskId = idRemaps.getOrDefault(task.taskId(), task.taskId());
            List<String> dependsOn = task.dependsOn().stream()
                    .map(parentTaskId -> idRemaps.getOrDefault(parentTaskId, parentTaskId))
                    .toList();
            remappedCandidates.put(taskId, new ParsedTask(taskId, task.agentId(), task.context(), dependsOn));
        }
        return remappedCandidates;
    }

    private static List<String> resolvedAppendDependencies(
            List<String> dependencies, Map<String, String> taskIdsByClientTaskId) {
        return dependencies.stream()
                .map(parentTaskId -> taskIdsByClientTaskId.getOrDefault(parentTaskId, parentTaskId))
                .toList();
    }

    private static String normalizeIdempotencyKey(
            String idempotencyKey, Task sourceTask, List<ParsedTask> appendTasks) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyKey.strip();
        }
        return "legacy-" + sha256(sourceTask.taskId() + "\u0000"
                + appendTasks.stream().map(ParsedTask::taskId).sorted().collect(java.util.stream.Collectors.joining("\u0000")));
    }

    private static String legacyIdempotencyKey(Task sourceTask, List<ParsedTask> parsedTasks) {
        return normalizeIdempotencyKey(null, sourceTask, parsedTasks == null ? List.of() : parsedTasks);
    }

    private static String generatedAppendTaskId(String sourceTaskId, String clientTaskId) {
        return "agent-task-" + sha256(sourceTaskId + "\u0000" + clientTaskId);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String allocateTaskId(String baseTaskId, Set<String> reservedIds) {
        String prefix = baseTaskId.isBlank() ? "task" : baseTaskId;
        String candidate;
        do {
            candidate = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        } while (reservedIds.contains(candidate) || taskRepository.findByTaskId(candidate).isPresent());
        return candidate;
    }

    private static List<String> normalizeAppendDependencies(String sourceTaskId, List<String> dependencies) {
        List<String> normalized = normalizeDependencies(dependencies);
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return List.of(sourceTaskId);
    }

    private boolean isValidAppendExistingDependency(Task sourceTask, String parentTaskId) {
        return taskRepository.findByTaskId(parentTaskId)
                .filter(task -> task.userId().equals(sourceTask.userId()))
                .filter(task -> task.traceId().equals(sourceTask.traceId()))
                .isPresent();
    }

    private static Map<String, ParsedTask> projectCandidateDependencies(Map<String, ParsedTask> candidates) {
        Set<String> candidateIds = Set.copyOf(candidates.keySet());
        Map<String, ParsedTask> projected = new LinkedHashMap<>();
        candidates.forEach((taskId, task) -> projected.put(taskId, new ParsedTask(
                task.taskId(),
                task.agentId(),
                task.context(),
                task.dependsOn().stream().filter(candidateIds::contains).toList())));
        return projected;
    }

    private static List<String> normalizeDependencies(List<String> dependencies) {
        if (dependencies == null) {
            return List.of();
        }
        return dependencies.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private void ensureValidDag(List<ParsedTask> tasks) {
        DagValidationResult result = dagValidator.validate(tasks);
        if (result.hasBlockingIssues()) {
            throw new IllegalArgumentException(result.blockingMessage());
        }
    }

    private void saveDependencies(List<ParsedTask> parsedTasks, List<Task> acceptedTasks) {
        Set<String> acceptedTaskIds = acceptedTasks.stream().map(Task::taskId).collect(java.util.stream.Collectors.toSet());
        List<TaskDependency> dependencies = parsedTasks.stream()
                .flatMap(task -> task.dependsOn().stream()
                        .filter(parentTaskId -> acceptedTaskIds.contains(parentTaskId)
                                || taskRepository.findByTaskId(parentTaskId).isPresent())
                        .map(parentTaskId -> new TaskDependency(parentTaskId, task.taskId())))
                .toList();
        taskDependencyRepository.saveAll(dependencies);
    }

    private static Task toTask(
            String userId,
            ParsedTask parsedTask,
            String traceId,
            String createdByTaskId,
            TaskSource source,
            int depth) {
        Instant now = Instant.now();
        return new Task(
                parsedTask.taskId(),
                userId,
                traceId,
                createdByTaskId,
                TaskStatus.QUEUED,
                source,
                depth,
                parsedTask.agentId(),
                parsedTask.context(),
                now,
                now);
    }
}
