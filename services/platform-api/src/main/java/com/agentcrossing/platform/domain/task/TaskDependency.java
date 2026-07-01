package com.agentcrossing.platform.domain.task;

import java.util.Objects;

public record TaskDependency(String parentTaskId, String childTaskId) {
    public TaskDependency {
        Objects.requireNonNull(parentTaskId, "parentTaskId must not be null");
        Objects.requireNonNull(childTaskId, "childTaskId must not be null");
        if (parentTaskId.equals(childTaskId)) {
            throw new IllegalArgumentException("A task cannot depend on itself");
        }
    }
}
