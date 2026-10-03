package com.agentcrossing.platform.infrastructure.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.agentcrossing.platform.application.invocation.AgentContextPack;
import com.agentcrossing.platform.application.invocation.AgentExecutionRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

class HttpAgentRuntimeClientTests {
    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "{\"detail\":\"PROMPT_VERSION_CHANGED\"}",
            "{\"detail\":{\"code\":\"OTHER_CONFLICT\",\"currentPromptVersion\":\"v2\"}}",
            "{\"detail\":{\"code\":\"PROMPT_VERSION_CHANGED\",\"currentPromptVersion\":\" \"}}"})
    void arbitraryConflictIsNotClassifiedAsSafePromptRetry(String body) {
        RestClient.Builder builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new HttpAgentRuntimeClient(builder, "http://agent-runtime");
        server.expect(requestTo("http://agent-runtime/api/runtime/execute"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body(body));
        assertThatThrownBy(() -> client.execute(request())).isInstanceOf(HttpClientErrorException.Conflict.class);
        server.verify();
    }

    @Test
    void mapsNormalizedUsageFromRuntimeResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpAgentRuntimeClient client = new HttpAgentRuntimeClient(builder, "http://agent-runtime");
        server.expect(requestTo("http://agent-runtime/api/runtime/execute"))
                .andRespond(withSuccess(
                        """
                        {
                          "messages": [],
                          "usage": {
                            "provider": "codex",
                            "model": "gpt-5.6-codex",
                            "providerSessionId": "session-1",
                            "totalInputTokens": 25000,
                            "lastRequestInputTokens": 12000,
                            "usagePrecision": "EXACT",
                            "inputTokens": 12000,
                            "cachedInputTokens": 8000,
                            "outputTokens": 500,
                            "reasoningOutputTokens": 100,
                            "contextInputTokens": 12000,
                            "rawUsageJson": {"input_tokens": 12000},
                            "providerCliVersion": "0.75.0",
                            "observedAt": "2026-08-13T03:00:00Z"
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        var result = client.execute(request());

        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().contextInputTokens()).isEqualTo(12_000L);
        assertThat(result.usage().cachedInputTokens()).isEqualTo(8_000L);
        assertThat(result.usage().rawUsageJson()).isNotNull();
        server.verify();
    }

    @Test
    void translatesNetworkTimeoutForBusinessExecution() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpAgentRuntimeClient client = new HttpAgentRuntimeClient(builder, "http://agent-runtime");

        server.expect(requestTo("http://agent-runtime/api/runtime/execute"))
                .andRespond(request -> {
                    throw new ResourceAccessException("response timeout");
                });

        assertThatThrownBy(() -> client.execute(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Agent runtime execution timed out or could not be reached")
                .hasMessageContaining("response timeout");

        server.verify();
    }

    private static AgentExecutionRequest request() {
        return new AgentExecutionRequest(
                "invocation-1",
                "user-1",
                "task-1",
                "trace-1",
                "opencode",
                "answer",
                "http://platform/callback",
                new AgentContextPack(List.of()),
                null,
                null);
    }
}
