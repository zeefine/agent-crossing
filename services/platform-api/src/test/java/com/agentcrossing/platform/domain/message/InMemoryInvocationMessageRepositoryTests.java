package com.agentcrossing.platform.domain.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.application.invocation.AgentMessageType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryInvocationMessageRepositoryTests {
    @Test
    void existenceTracksInvocationIdentityAndDeletion() {
        var repository = new InMemoryInvocationMessageRepository();
        assertThat(repository.existsByInvocationId("inv")).isFalse();

        repository.save(new InvocationMessage("event", "user", "inv", "task", "trace", "codex",
                AgentMessageType.MESSAGE, "content", null, Instant.now()));
        assertThat(repository.existsByInvocationId("inv")).isTrue();
        assertThat(repository.existsByInvocationId("another-inv")).isFalse();

        repository.deleteByTraceIdAndUserId("trace", "another-user");
        assertThat(repository.existsByInvocationId("inv")).isTrue();
        repository.deleteByTraceIdAndUserId("trace", "user");
        assertThat(repository.existsByInvocationId("inv")).isFalse();
    }
}
