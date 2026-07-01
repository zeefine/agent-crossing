package com.agentcrossing.platform.application.parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DagValidator {
    public DagValidationResult validate(List<ParsedTask> tasks) {
        Map<String, ParsedTask> tasksById = new LinkedHashMap<>();
        for (ParsedTask task : tasks) {
            tasksById.put(task.taskId(), task);
        }

        List<DagValidationIssue> issues = new ArrayList<>();
        if (hasCycle(tasksById)) {
            issues.add(new DagValidationIssue(
                    DagValidationSeverity.CRITICAL,
                    "DAG 中存在环",
                    "移除形成环的依赖边"));
        }

        List<String> orphans = findOrphans(tasksById);
        if (!orphans.isEmpty()) {
            issues.add(new DagValidationIssue(
                    DagValidationSeverity.WARNING,
                    "存在孤立节点: " + orphans,
                    "确认这些节点是否应该连接到其他节点"));
        }

        List<String> redundantEdges = findRedundantEdges(tasksById);
        if (!redundantEdges.isEmpty()) {
            issues.add(new DagValidationIssue(
                    DagValidationSeverity.INFO,
                    "存在冗余依赖边: " + redundantEdges,
                    "移除冗余边以简化图"));
        }

        return new DagValidationResult(issues);
    }

    private boolean hasCycle(Map<String, ParsedTask> tasksById) {
        Map<String, VisitColor> colors = new HashMap<>();
        for (String taskId : tasksById.keySet()) {
            colors.put(taskId, VisitColor.WHITE);
        }
        for (String taskId : tasksById.keySet()) {
            if (colors.get(taskId) == VisitColor.WHITE && hasCycleFrom(taskId, tasksById, colors)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycleFrom(
            String taskId,
            Map<String, ParsedTask> tasksById,
            Map<String, VisitColor> colors) {
        colors.put(taskId, VisitColor.GRAY);
        for (String parentTaskId : tasksById.get(taskId).dependsOn()) {
            if (!tasksById.containsKey(parentTaskId)) {
                continue;
            }
            VisitColor parentColor = colors.get(parentTaskId);
            if (parentColor == VisitColor.GRAY) {
                return true;
            }
            if (parentColor == VisitColor.WHITE && hasCycleFrom(parentTaskId, tasksById, colors)) {
                return true;
            }
        }
        colors.put(taskId, VisitColor.BLACK);
        return false;
    }

    private List<String> findOrphans(Map<String, ParsedTask> tasksById) {
        if (tasksById.size() <= 1) {
            return List.of();
        }
        Set<String> hasIncomingOrOutgoing = new HashSet<>();
        for (ParsedTask task : tasksById.values()) {
            if (!task.dependsOn().isEmpty()) {
                hasIncomingOrOutgoing.add(task.taskId());
            }
            for (String parentTaskId : task.dependsOn()) {
                if (tasksById.containsKey(parentTaskId)) {
                    hasIncomingOrOutgoing.add(parentTaskId);
                }
            }
        }
        return tasksById.keySet().stream()
                .filter(taskId -> !hasIncomingOrOutgoing.contains(taskId))
                .toList();
    }

    private List<String> findRedundantEdges(Map<String, ParsedTask> tasksById) {
        List<String> redundant = new ArrayList<>();
        for (ParsedTask child : tasksById.values()) {
            for (String parentTaskId : child.dependsOn()) {
                if (!tasksById.containsKey(parentTaskId)) {
                    continue;
                }
                if (isReachableWithoutEdge(parentTaskId, child.taskId(), parentTaskId, child.taskId(), tasksById)) {
                    redundant.add(parentTaskId + " -> " + child.taskId());
                }
            }
        }
        return redundant;
    }

    private boolean isReachableWithoutEdge(
            String startTaskId,
            String targetTaskId,
            String removedParentTaskId,
            String removedChildTaskId,
            Map<String, ParsedTask> tasksById) {
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(startTaskId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            for (String childTaskId : childrenOf(current, tasksById)) {
                if (current.equals(removedParentTaskId) && childTaskId.equals(removedChildTaskId)) {
                    continue;
                }
                if (childTaskId.equals(targetTaskId)) {
                    return true;
                }
                queue.addLast(childTaskId);
            }
        }
        return false;
    }

    private List<String> childrenOf(String parentTaskId, Map<String, ParsedTask> tasksById) {
        return tasksById.values().stream()
                .filter(task -> task.dependsOn().contains(parentTaskId))
                .map(ParsedTask::taskId)
                .toList();
    }

    private enum VisitColor {
        WHITE,
        GRAY,
        BLACK
    }
}
