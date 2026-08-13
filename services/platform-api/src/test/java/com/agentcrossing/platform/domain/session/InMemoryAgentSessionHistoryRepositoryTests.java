package com.agentcrossing.platform.domain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryAgentSessionHistoryRepositoryTests {
    private final InMemoryAgentSessionHistoryRepository repository =
            new InMemoryAgentSessionHistoryRepository();

    @Test
    void savesAndReadsSessionGenerations() {
        AgentSessionHistory first = history(
                "session-record-1", "provider-session-1", 1, AgentSessionHistoryStatus.SUPERSEDED, null);
        AgentSessionHistory second = history(
                "session-record-2", "provider-session-2", 2, AgentSessionHistoryStatus.ACTIVE, first.sessionRecordId());

        repository.save(first);
        repository.save(second);

        assertThat(repository.findBySessionRecordId(second.sessionRecordId())).contains(second);
        assertThat(repository.findByProviderSessionId("codex", "provider-session-1")).contains(first);
        assertThat(repository.findByGeneration("user-1", "thread-1", "codex", "codex", 2))
                .contains(second);
        assertThat(repository.findActive("user-1", "thread-1", "codex", "codex"))
                .contains(second);
        assertThat(repository.findByThreadId("user-1", "thread-1", "codex", "codex"))
                .containsExactly(first, second);
    }

    @Test
    void enforcesProviderSessionAndGenerationUniqueness() {
        repository.save(history(
                "session-record-1", "provider-session-1", 1, AgentSessionHistoryStatus.ACTIVE, null));

        assertThatThrownBy(() -> repository.save(history(
                        "session-record-2", "provider-session-1", 2, AgentSessionHistoryStatus.ACTIVE, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.save(history(
                        "session-record-3", "provider-session-3", 1, AgentSessionHistoryStatus.ACTIVE, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletesAllHistoryForUserThread() {
        repository.save(history(
                "session-record-1", "provider-session-1", 1, AgentSessionHistoryStatus.ACTIVE, null));

        repository.deleteByThreadId("user-1", "thread-1");

        assertThat(repository.findByThreadId("user-1", "thread-1", "codex", "codex")).isEmpty();
    }

    private static AgentSessionHistory history(
            String recordId,
            String providerSessionId,
            int generation,
            AgentSessionHistoryStatus status,
            String predecessorId) {
        Instant createdAt = Instant.parse("2026-08-13T03:00:00Z").plusSeconds(generation);
        return new AgentSessionHistory(
                recordId,
                "user-1",
                "thread-1",
                "trace-1",
                "codex",
                "codex",
                providerSessionId,
                generation,
                status,
                predecessorId,
                generation == 1 ? null : "Earlier conversation summary",
                generation == 1 ? null : "message-1",
                generation == 1 ? null : "message-20",
                generation == 1 ? null : "message-21",
                generation == 1 ? null : 300L,
                generation == 1 ? null : "gpt-5.6-codex",
                generation == 1 ? null : "summary-v1",
                generation == 1 ? null : "TOKEN_THRESHOLD",
                status == AgentSessionHistoryStatus.SUPERSEDED ? 100_000L : null,
                createdAt,
                createdAt,
                status == AgentSessionHistoryStatus.SUPERSEDED ? createdAt.plusSeconds(60) : null);
    }
}
