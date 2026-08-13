package com.agentcrossing.platform.infrastructure.runtime;

import com.agentcrossing.platform.application.invocation.AgentExecutionRequest;
import com.agentcrossing.platform.application.invocation.AgentExecutionResult;
import com.agentcrossing.platform.application.invocation.AgentExecutionUsage;
import com.agentcrossing.platform.application.invocation.AgentMessage;
import com.agentcrossing.platform.application.invocation.AgentMessageType;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class HttpAgentRuntimeClient implements com.agentcrossing.platform.application.invocation.AgentRuntimeClient {
    private static final Logger log = LoggerFactory.getLogger(HttpAgentRuntimeClient.class);
    private final RestClient restClient;

    @Autowired
    public HttpAgentRuntimeClient(@Qualifier("agentRuntimeExecutionRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    HttpAgentRuntimeClient(RestClient.Builder restClientBuilder, String agentRuntimeBaseUrl) {
        this(restClientBuilder.baseUrl(agentRuntimeBaseUrl).build());
    }

    @Override
    public AgentExecutionResult execute(AgentExecutionRequest request) {
        AgentExecutionResponse response;
        try {
            response = restClient.post()
                    .uri("/api/runtime/execute")
                    .body(request)
                    .retrieve()
                    .body(AgentExecutionResponse.class);
        } catch (ResourceAccessException exception) {
            throw new IllegalStateException(
                    "Agent runtime execution timed out or could not be reached: " + exception.getMessage(), exception);
        }
        return response == null
                ? new AgentExecutionResult(List.of())
                : new AgentExecutionResult(
                        response.toAgentMessages(),
                        response.finalText(),
                        response.streamCompleted(),
                        response.lastSequence(),
                        response.promptVersion(),
                        response.usage());
    }

    @Override
    public void cancel(String invocationId) {
        try {
            restClient.post()
                    .uri("/api/runtime/invocations/{invocationId}/cancel", invocationId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            // 平台已持久化 CANCELED；runtime 不可达时只影响能否主动杀掉 CLI，不能回滚用户的停止操作。
            log.warn("Failed to forward cancellation to agent runtime invocationId={}", invocationId, exception);
        }
    }

    private record AgentExecutionResponse(
            List<AgentMessageDto> messages,
            String finalText,
            boolean streamCompleted,
            Long lastSequence,
            String promptVersion,
            AgentExecutionUsage usage) {
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
