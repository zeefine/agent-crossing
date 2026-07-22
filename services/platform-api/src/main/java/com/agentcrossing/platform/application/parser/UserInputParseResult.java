package com.agentcrossing.platform.application.parser;

import java.util.List;

public record UserInputParseResult(
        List<ParsedTask> tasks,
        String directAnswer,
        String providerSessionId,
        String promptVersion) {
    public UserInputParseResult {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        directAnswer = directAnswer == null || directAnswer.isBlank() ? null : directAnswer.strip();
        providerSessionId = providerSessionId == null || providerSessionId.isBlank() ? null : providerSessionId.strip();
        promptVersion = promptVersion == null || promptVersion.isBlank() ? null : promptVersion.strip();
    }

    public UserInputParseResult(List<ParsedTask> tasks, String directAnswer, String providerSessionId) {
        this(tasks, directAnswer, providerSessionId, null);
    }

    public UserInputParseResult(List<ParsedTask> tasks, String directAnswer) {
        this(tasks, directAnswer, null, null);
    }
}
