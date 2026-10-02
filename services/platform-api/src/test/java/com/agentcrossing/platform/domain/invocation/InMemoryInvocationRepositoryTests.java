package com.agentcrossing.platform.domain.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryInvocationRepositoryTests {
    private final InMemoryInvocationRepository repository = new InMemoryInvocationRepository();

    @Test
    void compareAndSetPreservesCanceledRecordAndNeverCreatesMissingInvocation() {
        repository.save(invocation("invocation-1", "task-1"));
        assertThat(repository.updateStatusIfCurrent("invocation-1", Set.of(InvocationStatus.QUEUED), InvocationStatus.RUNNING)).isTrue();
        assertThat(repository.updateStatusIfCurrent("invocation-1", Set.of(InvocationStatus.RUNNING), InvocationStatus.CANCELED)).isTrue();
        Invocation canceled = repository.findByInvocationId("invocation-1").orElseThrow();

        assertThat(repository.updateStatusIfCurrent("invocation-1", Set.of(InvocationStatus.RUNNING), InvocationStatus.SUCCEEDED)).isFalse();
        assertThat(repository.updateStatus("invocation-1", InvocationStatus.FAILED)).isEqualTo(canceled);
        assertThat(repository.updateStatusIfCurrent("missing", Set.of(InvocationStatus.RUNNING), InvocationStatus.SUCCEEDED)).isFalse();
        assertThat(repository.findByInvocationId("missing")).isEmpty();
        assertThat(canceled.startedAt()).isNotNull();
        assertThat(canceled.completedAt()).isNotNull();
    }

    @Test
    void savesAndFindsInvocationsByInvocationIdAndTaskId() {
        Invocation first = invocation("invocation-1", "task-1");
        Invocation second = invocation("invocation-2", "task-1");
        repository.save(first);
        repository.save(second);

        assertThat(repository.findByInvocationId("invocation-1")).contains(first);
        assertThat(repository.findByTaskId("task-1"))
                .extracting(Invocation::invocationId)
                .containsExactly("invocation-1", "invocation-2");
    }

    @Test
    void updatesInvocationStatusAndTimestamps() {
        repository.save(invocation("invocation-1", "task-1"));

        Invocation running = repository.updateStatus("invocation-1", InvocationStatus.RUNNING);
        Invocation succeeded = repository.updateStatus("invocation-1", InvocationStatus.SUCCEEDED);

        assertThat(running.status()).isEqualTo(InvocationStatus.RUNNING);
        assertThat(running.startedAt()).isNotNull();
        assertThat(succeeded.status()).isEqualTo(InvocationStatus.SUCCEEDED);
        assertThat(succeeded.completedAt()).isNotNull();
    }

    @Test
    void findsRunningInvocationsByAgentId() {
        repository.save(invocation("invocation-1", "task-1", "opencode", InvocationStatus.QUEUED));
        repository.save(invocation("invocation-2", "task-2", "opencode", InvocationStatus.RUNNING));
        repository.save(invocation("invocation-3", "task-3", "claude-code", InvocationStatus.RUNNING));
        repository.save(invocation("invocation-4", "task-4", "opencode", InvocationStatus.SUCCEEDED));

        assertThat(repository.findRunningByAgentId("opencode"))
                .extracting(Invocation::invocationId)
                .containsExactly("invocation-2");
    }

    @Test
    void updateStatusFailsWhenInvocationIsMissing() {
        assertThatThrownBy(() -> repository.updateStatus("missing", InvocationStatus.FAILED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invocation not found");
    }

    private static Invocation invocation(String invocationId, String taskId) {
        return invocation(invocationId, taskId, "opencode", InvocationStatus.QUEUED);
    }

    private static Invocation invocation(
            String invocationId, String taskId, String agentId, InvocationStatus status) {
        Instant now = Instant.now();
        Instant startedAt = status == InvocationStatus.RUNNING ? now : null;
        Instant completedAt = status == InvocationStatus.SUCCEEDED
                        || status == InvocationStatus.FAILED
                        || status == InvocationStatus.CANCELED
                ? now
                : null;
        return new Invocation(invocationId, "anonymous", taskId, "trace-1", agentId, status, now, startedAt, completedAt);
    }
}
