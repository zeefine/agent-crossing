package com.agentcrossing.platform.infrastructure.runtime;

import com.agentcrossing.platform.application.invocation.AgentExecutionRequest;
import com.agentcrossing.platform.application.invocation.AgentExecutionResult;
import com.agentcrossing.platform.application.invocation.AgentMessage;
import com.agentcrossing.platform.application.invocation.AgentMessageType;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpAgentRuntimeClient implements com.agentcrossing.platform.application.invocation.AgentRuntimeClient {
    private final RestClient restClient;

    public HttpAgentRuntimeClient(
            RestClient.Builder restClientBuilder,
            @Value("${agent-crossing.agent-runtime.base-url}") String agentRuntimeBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(agentRuntimeBaseUrl).build();
    }

    @Override
    public AgentExecutionResult execute(AgentExecutionRequest request) {
        AgentExecutionResponse response = restClient.post()
                .uri("/api/runtime/execute")
                .body(request)
                .retrieve()
                .body(AgentExecutionResponse.class);
        return new AgentExecutionResult(response == null ? List.of() : response.toAgentMessages());
    }

    private record AgentExecutionResponse(List<AgentMessageDto> messages) {
        List<AgentMessage> toAgentMessages() {
            return messages == null ? List.of() : messages.stream().map(AgentMessageDto::toAgentMessage).toList();
        }
    }

    private record AgentMessageDto(
            String invocationId,
            String taskId,
            String traceId,
            String agentId,
            String type,
            String content,
            Object raw,
            Instant createdAt) {
        AgentMessage toAgentMessage() {
            return new AgentMessage(
                    invocationId,
                    taskId,
                    traceId,
                    agentId,
                    toType(type),
                    content,
                    raw,
                    createdAt == null ? Instant.now() : createdAt);
        }

        private static AgentMessageType toType(String value) {
            if ("textDelta".equals(value)) {
                return AgentMessageType.TEXT_DELTA;
            }
            if ("message".equals(value)) {
                return AgentMessageType.MESSAGE;
            }
            if ("done".equals(value)) {
                return AgentMessageType.DONE;
            }
            if ("error".equals(value)) {
                return AgentMessageType.ERROR;
            }
            throw new IllegalArgumentException("Unknown agent message type: " + value);
        }
    }
}

