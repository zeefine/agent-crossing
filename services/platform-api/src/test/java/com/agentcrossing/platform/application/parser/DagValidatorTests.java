package com.agentcrossing.platform.application.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DagValidatorTests {
    private final DagValidator validator = new DagValidator();

    @Test
    void detectsCycleAsBlockingIssue() {
        DagValidationResult result = validator.validate(List.of(
                task("task-a", "task-c"),
                task("task-b", "task-a"),
                task("task-c", "task-b")));

        assertThat(result.hasBlockingIssues()).isTrue();
        assertThat(result.issues())
                .extracting(DagValidationIssue::severity)
                .contains(DagValidationSeverity.CRITICAL);
    }

    @Test
    void detectsOrphansAndRedundantEdgesAsNonBlockingIssues() {
        DagValidationResult result = validator.validate(List.of(
                task("task-a"),
                task("task-b", "task-a"),
                task("task-c", "task-a", "task-b"),
                task("task-orphan")));

        assertThat(result.hasBlockingIssues()).isFalse();
        assertThat(result.issues())
                .extracting(DagValidationIssue::severity)
                .contains(DagValidationSeverity.WARNING, DagValidationSeverity.INFO);
        assertThat(result.issues())
                .extracting(DagValidationIssue::message)
                .anySatisfy(message -> assertThat(message).contains("task-orphan"))
                .anySatisfy(message -> assertThat(message).contains("task-a -> task-c"));
    }

    private static ParsedTask task(String taskId, String... dependsOn) {
        return new ParsedTask(taskId, "opencode", "context for " + taskId, List.of(dependsOn));
    }
}
