package com.agentcrossing.platform.application.parser;

import java.util.Objects;

public record DagValidationIssue(
        DagValidationSeverity severity,
        String message,
        String fix) {
    public DagValidationIssue {
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(fix, "fix must not be null");
    }
}
