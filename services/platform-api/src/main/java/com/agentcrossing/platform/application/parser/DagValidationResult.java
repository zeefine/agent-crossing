package com.agentcrossing.platform.application.parser;

import java.util.List;

public record DagValidationResult(List<DagValidationIssue> issues) {
    public DagValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean hasBlockingIssues() {
        return issues.stream()
                .anyMatch(issue -> issue.severity() == DagValidationSeverity.CRITICAL
                        || issue.severity() == DagValidationSeverity.ERROR);
    }

    public String blockingMessage() {
        return issues.stream()
                .filter(issue -> issue.severity() == DagValidationSeverity.CRITICAL
                        || issue.severity() == DagValidationSeverity.ERROR)
                .map(DagValidationIssue::message)
                .findFirst()
                .orElse("DAG validation failed");
    }
}
