package com.agentcrossing.platform.infrastructure.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

import com.agentcrossing.platform.application.invocation.AgentContextPack;
import com.agentcrossing.platform.application.invocation.AgentExecutionRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

class HttpAgentRuntimeClientTests {
    @Test
    void translatesNetworkTimeoutForBusinessExecution() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpAgentRuntimeClient client = new HttpAgentRuntimeClient(builder, "http://agent-runtime");

        server.expect(requestTo("http://agent-runtime/api/runtime/execute"))
                .andRespond(request -> {
                    throw new ResourceAccessException("response timeout");
                });

        assertThatThrownBy(() -> client.execute(new AgentExecutionRequest(
                        "invocation-1",
                        "user-1",
                        "task-1",
                        "trace-1",
                        "opencode",
                        "answer",
                        "http://platform/callback",
                        new AgentContextPack(List.of()),
                        null,
                        null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Agent runtime execution timed out or could not be reached")
                .hasMessageContaining("response timeout");

        server.verify();
    }
}
