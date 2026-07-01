package com.agentcrossing.platform.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.TestAgentRegistries;
import com.agentcrossing.platform.api.dto.AgentCardResponse;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentControllerTests {
    @Test
    void reportsAgentRunningWhenInvocationIsRunning() {
        InMemoryInvocationRepository invocationRepository = new InMemoryInvocationRepository();
        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
        invocationRepository.save(new Invocation(
                "invocation-1",
                "anonymous",
                "task-1",
                "trace-1",
                "opencode",
                InvocationStatus.RUNNING,
                Instant.now(),
                Instant.now(),
                null));
        AgentController controller =
                new AgentController(TestAgentRegistries.withDefaultAgent(), invocationRepository, taskRepository);

        List<AgentCardResponse> agents = controller.getAgents("anonymous").data();

        assertThat(agents).hasSize(1);
        assertThat(agents.getFirst().agentId()).isEqualTo("opencode");
        assertThat(agents.getFirst().role()).contains("CLI coding agent");
        assertThat(agents.getFirst().capabilities()).contains("implementation");
        assertThat(agents.getFirst().tools()).contains("opencode-cli");
        assertThat(agents.getFirst().status()).isEqualTo("running");
        assertThat(agents.getFirst().runningInvocations()).isEqualTo(1);
    }
}
