package com.agentcrossing.platform.infrastructure.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentcrossing.platform.application.parser.ParsedTask;
import com.agentcrossing.platform.application.parser.QuestParserClient;
import com.agentcrossing.platform.application.parser.UserInputParseResult;
import com.agentcrossing.platform.domain.agent.Agent;
import com.agentcrossing.platform.domain.task.Task;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;

@Component
public class HttpQuestParserClient implements QuestParserClient {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestClient restClient;

    public HttpQuestParserClient(
            RestClient.Builder restClientBuilder,
            @Value("${agent-crossing.agent-runtime.base-url}") String agentRuntimeBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(agentRuntimeBaseUrl).build();
    }

    @Override
    public UserInputParseResult parseUserInput(String input, List<Agent> availableAgents) {
        return parseUserInput(null, null, null, input, null, availableAgents);
    }

    @Override
    public UserInputParseResult parseUserInput(
            String userId,
            String threadId,
            String traceId,
            String input,
            String providerSessionId,
            List<Agent> availableAgents) {
        try {
            UserInputParseResponse response = restClient.post()
                    .uri("/api/parser/user-input")
                    .body(new UserInputParseRequest(
                            userId, threadId, traceId, input, providerSessionId, toAgentCards(availableAgents)))
                    .retrieve()
                    .body(UserInputParseResponse.class);
            return response == null ? new UserInputParseResult(List.of(), null) : response.toResult();
        } catch (RestClientResponseException exception) {
            throw translateRuntimeError("parse user input", exception);
        }
    }

    @Override
    public List<ParsedTask> parseAgentOutput(Task sourceTask, String output, List<Agent> availableAgents) {
        try {
            AgentOutputParseResponse response = restClient.post()
                    .uri("/api/parser/agent-output")
                    .body(new AgentOutputParseRequest(
                            sourceTask.taskId(), sourceTask.agentId(), output, toAgentIds(availableAgents)))
                    .retrieve()
                    .body(AgentOutputParseResponse.class);
            return response == null ? List.of() : response.toParsedTasks();
        } catch (RestClientResponseException exception) {
            throw translateRuntimeError("parse agent output", exception);
        }
    }

    private static IllegalArgumentException translateRuntimeError(String operation, RestClientResponseException exception) {
        RuntimeError error = parseRuntimeError(exception.getResponseBodyAsString());
        String code = error.code() == null || error.code().isBlank() ? "RUNTIME_HTTP_" + exception.getStatusCode().value() : error.code();
        String message = error.message() == null || error.message().isBlank()
                ? exception.getStatusText()
                : error.message();
        return new IllegalArgumentException("Agent runtime failed to " + operation + " [" + code + "]: " + message, exception);
    }

    private static RuntimeError parseRuntimeError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return RuntimeError.empty();
        }
        try {
            FastApiErrorBody body = OBJECT_MAPPER.readValue(responseBody, FastApiErrorBody.class);
            if (body.detail() != null) {
                return new RuntimeError(body.detail().code(), body.detail().message());
            }
            if (body.error() != null) {
                return new RuntimeError(body.error().code(), body.error().message());
            }
        } catch (Exception ignored) {
            // Non-JSON runtime failures still get translated with HTTP status information.
        }
        return RuntimeError.empty();
    }

    private static List<String> toAgentIds(List<Agent> agents) {
        return agents.stream().map(Agent::agentId).toList();
    }

    private static List<AvailableAgentCard> toAgentCards(List<Agent> agents) {
        return agents.stream().map(AvailableAgentCard::from).toList();
    }

    private record UserInputParseRequest(
            String userId,
            String threadId,
            String traceId,
            String input,
            String providerSessionId,
            List<AvailableAgentCard> availableAgents) {
    }

    private record AvailableAgentCard(
            String agentId,
            String displayName,
            String role,
            List<String> capabilities,
            List<String> tools) {
        private static AvailableAgentCard from(Agent agent) {
            return new AvailableAgentCard(
                    agent.agentId(),
                    agent.displayName(),
                    agent.role(),
                    agent.capabilities(),
                    agent.tools());
        }
    }

    private record AgentOutputParseRequest(
            String sourceTaskId, String sourceAgentId, String output, List<String> availableAgentIds) {
    }

    private record UserInputParseResponse(List<ParsedTaskDto> tasks, String directAnswer, String providerSessionId) {
        UserInputParseResult toResult() {
            return new UserInputParseResult(
                    tasks == null ? List.of() : tasks.stream().map(ParsedTaskDto::toParsedTask).toList(),
                    directAnswer,
                    providerSessionId);
        }
    }

    private record AgentOutputParseResponse(List<ParsedTaskDto> tasks) {
        List<ParsedTask> toParsedTasks() {
            return tasks == null ? List.of() : tasks.stream().map(ParsedTaskDto::toParsedTask).toList();
        }
    }

    private record ParsedTaskDto(String taskId, String agentId, String context, List<String> dependsOn) {
        ParsedTask toParsedTask() {
            return new ParsedTask(taskId, agentId, context, dependsOn);
        }
    }

    private record RuntimeError(String code, String message) {
        private static RuntimeError empty() {
            return new RuntimeError(null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FastApiErrorBody(RuntimeErrorDetail detail, RuntimeErrorDetail error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RuntimeErrorDetail(String code, String message) {
    }
}
