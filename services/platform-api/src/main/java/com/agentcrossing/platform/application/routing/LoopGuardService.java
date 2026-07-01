package com.agentcrossing.platform.application.routing;

import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class LoopGuardService {
    public static final int MAX_DEPTH = 10;
    public static final int MAX_SELF_TRIGGER_COUNT = 10;

    private final TaskRepository taskRepository;

    public LoopGuardService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public boolean allows(Task candidate) {
        // depth 限制整条派生链路的递归深度，防止 agent 之间无限互相唤醒。
        if (candidate.depth() > MAX_DEPTH) {
            return false;
        }
        if (candidate.createdByTaskId() == null) {
            return true;
        }
        Task sourceTask = taskRepository.findByTaskId(candidate.createdByTaskId()).orElse(null);
        if (sourceTask == null || !Objects.equals(sourceTask.agentId(), candidate.agentId())) {
            return true;
        }
        // 自触发限制只在“来源 agent == 目标 agent”时启用，跨 agent handoff 不算自触发。
        long selfTriggerCount = taskRepository.findByTraceId(candidate.traceId()).stream()
                .filter(task -> candidate.agentId().equals(task.agentId()))
                .filter(task -> task.createdByTaskId() != null)
                .count();
        return selfTriggerCount < MAX_SELF_TRIGGER_COUNT;
    }
}
