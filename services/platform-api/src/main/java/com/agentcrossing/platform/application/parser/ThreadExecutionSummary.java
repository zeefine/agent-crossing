package com.agentcrossing.platform.application.parser;

import java.util.List;
import java.util.Map;

public record ThreadExecutionSummary(
        String threadStatus,
        Map<String, Integer> taskStatusCounts,
        List<TaskSummary> recentTasks,
        List<AgentConclusion> latestAgentConclusions) {
    public ThreadExecutionSummary {
        taskStatusCounts = taskStatusCounts == null ? Map.of() : Map.copyOf(taskStatusCounts);
        recentTasks = recentTasks == null ? List.of() : List.copyOf(recentTasks);
        latestAgentConclusions = latestAgentConclusions == null ? List.of() : List.copyOf(latestAgentConclusions);
    }

    public record TaskSummary(
            String taskId,
            String agentId,
            String status,
            String context,
            String updatedAt) {
    }

    public record AgentConclusion(
            String agentId,
            String taskId,
            String content,
            String createdAt) {
    }
}
