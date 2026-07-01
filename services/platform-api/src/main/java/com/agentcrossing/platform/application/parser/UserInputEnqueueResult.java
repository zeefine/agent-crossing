package com.agentcrossing.platform.application.parser;

import com.agentcrossing.platform.domain.task.Task;
import java.util.List;

public record UserInputEnqueueResult(List<Task> tasks, String directAnswer) {
    public UserInputEnqueueResult {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        directAnswer = directAnswer == null || directAnswer.isBlank() ? null : directAnswer.strip();
    }
}
