package com.agentcrossing.platform.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryTaskRepositoryTests {
    private final InMemoryTaskRepository repository = new InMemoryTaskRepository();

    @Test
    void compareAndSetPreservesCanceledRecordAndNeverCreatesMissingTask() {
        repository.save(task("task-1", "trace-1", TaskStatus.PROCESSING));
        assertThat(repository.updateStatusIfCurrent("task-1", Set.of(TaskStatus.QUEUED, TaskStatus.PROCESSING), TaskStatus.CANCELED)).isTrue();
        Task canceled = repository.findByTaskId("task-1").orElseThrow();

        assertThat(repository.updateStatusIfCurrent("task-1", Set.of(TaskStatus.PROCESSING), TaskStatus.COMPLETED)).isFalse();
        assertThat(repository.updateStatus("task-1", TaskStatus.COMPLETED)).isEqualTo(canceled);
        assertThat(repository.updateStatusIfCurrent("missing", Set.of(TaskStatus.PROCESSING), TaskStatus.COMPLETED)).isFalse();
        assertThat(repository.findByTaskId("missing")).isEmpty();
    }

    @Test
    void savesAndFindsTasksByTaskIdTraceIdAndStatus() {
        Task first = task("task-1", "trace-1", TaskStatus.QUEUED);
        Task second = task("task-2", "trace-1", TaskStatus.PROCESSING);
        repository.save(first);
        repository.save(second);

        assertThat(repository.findByTaskId("task-1")).contains(first);
        assertThat(repository.findByTraceId("trace-1")).extracting(Task::taskId).containsExactly("task-1", "task-2");
        assertThat(repository.findByStatus(TaskStatus.PROCESSING)).extracting(Task::taskId).containsExactly("task-2");
    }

    @Test
    void updatesTaskStatus() {
        repository.save(task("task-1", "trace-1", TaskStatus.QUEUED));

        Task updated = repository.updateStatus("task-1", TaskStatus.COMPLETED);

        assertThat(updated.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(updated.updatedAt()).isAfter(updated.createdAt());
        assertThat(repository.findByTaskId("task-1")).contains(updated);
    }

    @Test
    void updateStatusFailsWhenTaskIsMissing() {
        assertThatThrownBy(() -> repository.updateStatus("missing", TaskStatus.FAILED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task not found");
    }

    private static Task task(String taskId, String traceId, TaskStatus status) {
        Instant now = Instant.now();
        return new Task(
                taskId,
                "anonymous",
                traceId,
                null,
                status,
                TaskSource.USER,
                0,
                "opencode",
                "context",
                now,
                now);
    }
}
