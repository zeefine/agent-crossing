package com.agentcrossing.platform.application.parser;

import java.util.List;

public record UserInputParseResult(List<ParsedTask> tasks, String directAnswer, String providerSessionId) {
    public UserInputParseResult {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        directAnswer = directAnswer == null || directAnswer.isBlank() ? null : directAnswer.strip();
        providerSessionId = providerSessionId == null || providerSessionId.isBlank() ? null : providerSessionId.strip();
    }

    public UserInputParseResult(List<ParsedTask> tasks, String directAnswer) {
        this(tasks, directAnswer, null);
    }
}
