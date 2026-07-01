package com.agentcrossing.platform.application.parser;

import com.agentcrossing.platform.application.routing.LoopGuardService;
import com.agentcrossing.platform.application.routing.TaskDispatchSignal;
import com.agentcrossing.platform.application.task.TaskEventService;
import com.agentcrossing.platform.domain.agent.AgentRegistry;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.session.AgentSession;
import com.agentcrossing.platform.domain.session.AgentSessionRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskDependency;
import com.agentcrossing.platform.domain.task.TaskDependencyRepository;
import com.agentcrossing.platform.domain.task.TaskRepository;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import com.agentcrossing.platform.domain.user.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class QuestParserService {
    public static final String MASTER_AGENT_ID = "masteragent";
    public static final String MASTER_AGENT_PROVIDER = "claudecode";

    private final QuestParserClient parserClient;
    private final AgentRegistry agentRegistry;
    private final TaskRepository taskRepository;
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
        this.taskDependencyRepository = taskDependencyRepository;
        this.questHub = questHub;
        this.loopGuardService = loopGuardService;
        this.taskDispatchSignal = taskDispatchSignal;
        this.taskEventService = taskEventService;
        this.userRepository = userRepository;
        this.agentSessionRepository = agentSessionRepository;
        this.dagValidator = new DagValidator();
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
        String providerSessionId = findMasterAgentSessionId(userId, threadId, traceId);
        UserInputParseResult result = parserClient.parseUserInput(
                userId,
                threadId,
                traceId,
                input,
                providerSessionId,
                agentRegistry.findAll());
        rememberMasterAgentSession(userId, threadId, traceId, result.providerSessionId());
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
    public List<Task> parseAgentOutputAndEnqueue(Task sourceTask, String output) {
        List<ParsedTask> parsedTasks = parserClient.parseAgentOutput(sourceTask, output, agentRegistry.findAll());
        return appendAgentTasks(sourceTask, parsedTasks);
    }

    @Transactional
    public List<Task> appendAgentTasks(Task sourceTask, List<ParsedTask> parsedTasks) {
        List<ParsedTask> appendTasks = validateAppendPlan(sourceTask, parsedTasks);
        List<Task> accepted = new ArrayList<>();
        List<ParsedTask> acceptedPlans = new ArrayList<>();
        for (ParsedTask parsedTask : appendTasks) {
            // agent 输出产生的是派生任务，必须仍然挂在来源任务的 trace 上。
            if (!agentRegistry.exists(parsedTask.agentId())) {
                continue;
            }
            Task task = toTask(
                    sourceTask.userId(),
                    parsedTask,
                    sourceTask.traceId(),
                    sourceTask.taskId(),
                    TaskSource.AGENT,
                    sourceTask.depth() + 1);
            if (!loopGuardService.allows(task)) {
                continue;
            }
            // Agent 输出生成的新任务统一进入 questHub，由 DAG 依赖判断是否就绪。
            taskRepository.save(task);
            accepted.add(task);
            acceptedPlans.add(parsedTask);
        }
        saveDependencies(acceptedPlans, accepted);
        enqueueAfterCommit(accepted);
        return accepted;
    }

    private void enqueueAfterCommit(List<Task> accepted) {
        if (accepted.isEmpty()) {
            return;
        }
        Runnable enqueue = () -> {
            accepted.forEach(task -> {
                questHub.enqueueLast(task.taskId());
                publishTask(task);
            });
            signalIfAccepted(accepted);
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

    private void ensureUser(String userId) {
        if (userRepository != null) {
            userRepository.ensure(userId);
        }
    }

    private String findMasterAgentSessionId(String userId, String threadId, String traceId) {
        if (agentSessionRepository == null) {
            return null;
        }
        return agentSessionRepository
                .findByThreadId(userId, threadId, MASTER_AGENT_ID, MASTER_AGENT_PROVIDER)
                .map(AgentSession::providerSessionId)
                .orElse(null);
    }

    private void rememberMasterAgentSession(String userId, String threadId, String traceId, String providerSessionId) {
        if (agentSessionRepository == null || providerSessionId == null || providerSessionId.isBlank()) {
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
                    task.context().strip(),
                    dependencies));
        }

        candidates = remapExistingTaskIds(candidates);
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
