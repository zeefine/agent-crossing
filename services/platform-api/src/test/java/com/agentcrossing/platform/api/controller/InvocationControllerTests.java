package com.agentcrossing.platform.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.api.dto.InvocationResponse;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvocationControllerTests {
    @Test
    void findsInvocationByInvocationIdAndTaskId() {
        InMemoryInvocationRepository repository = new InMemoryInvocationRepository();
        Invocation invocation = new Invocation(
                "invocation-1",
                "anonymous",
                "task-1",
                "trace-1",
                "opencode",
                InvocationStatus.QUEUED,
                Instant.now(),
                null,
                null);
        repository.save(invocation);
        InvocationController controller = new InvocationController(repository);

        InvocationResponse single = controller.getInvocation("anonymous", "invocation-1").data();
        List<InvocationResponse> byTask = controller.getInvocations("anonymous", "task-1").data();

        assertThat(single.invocationId()).isEqualTo("invocation-1");
        assertThat(byTask).extracting(InvocationResponse::invocationId).containsExactly("invocation-1");
    }
}
