package com.agentcrossing.platform.infrastructure.parser;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.agentcrossing.platform.application.parser.ThreadExecutionSummary;
import com.agentcrossing.platform.application.parser.UserInputParseResult;
import com.agentcrossing.platform.domain.agent.Agent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

class HttpQuestParserClientTests {
    @Test
    void sendsThreadExecutionSummaryToMasterAgentRuntime() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpQuestParserClient client = new HttpQuestParserClient(builder, "http://agent-runtime");
        ThreadExecutionSummary summary = new ThreadExecutionSummary(
                "running",
                Map.of("completed", 1),
                List.of(new ThreadExecutionSummary.TaskSummary(
                        "task-1",
                        "opencode",
                        "completed",
                        "分析连接池",
                        "2026-07-17T10:00:00Z")),
                List.of(new ThreadExecutionSummary.AgentConclusion(
                        "opencode",
                        "task-1",
                        "连接池配置需要调整。",
                        "2026-07-17T10:00:01Z")));

        server.expect(requestTo("http://agent-runtime/api/parser/user-input"))
                .andExpect(content().json("""
                        {
                          "userId": "user-1",
                          "threadId": "thread-1",
                          "traceId": "trace-1",
                          "input": "继续分析",
                          "providerSessionId": null,
                          "providerPromptVersion": "prompt-v1",
                          "availableAgents": [{"agentId": "opencode"}],
                          "threadExecutionSummary": {
                            "threadStatus": "running",
                            "taskStatusCounts": {"completed": 1},
                            "recentTasks": [{"taskId": "task-1", "status": "completed"}],
                            "latestAgentConclusions": [{"agentId": "opencode", "content": "连接池配置需要调整。"}]
                          }
                        }
                        """, false))
                .andRespond(withSuccess(
                        "{\"tasks\":[],\"directAnswer\":\"收到\",\"providerSessionId\":\"claude-new\",\"promptVersion\":\"prompt-v2\"}",
                        MediaType.APPLICATION_JSON));

        UserInputParseResult result = client.parseUserInput(
                "user-1",
                "thread-1",
                "trace-1",
                "继续分析",
                null,
                "prompt-v1",
                List.of(new Agent("opencode", "OpenCode")),
                summary);

        org.assertj.core.api.Assertions.assertThat(result.providerSessionId()).isEqualTo("claude-new");
        org.assertj.core.api.Assertions.assertThat(result.promptVersion()).isEqualTo("prompt-v2");
        server.verify();
    }

    @Test
    void translatesFastApiRuntimeErrorsForUserInputParsing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpQuestParserClient client = new HttpQuestParserClient(builder, "http://agent-runtime");

        server.expect(requestTo("http://agent-runtime/api/parser/user-input"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "detail": {
                                    "code": "MASTER_AGENT_NO_TASK_PLAN",
                                    "message": "MasterAgent exited without calling submit_task_plan."
                                  }
                                }
                                """));

        assertThatThrownBy(() -> client.parseUserInput("分析项目", List.of(new Agent("opencode", "OpenCode"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent runtime failed to parse user input")
                .hasMessageContaining("MASTER_AGENT_NO_TASK_PLAN")
                .hasMessageContaining("MasterAgent exited without calling submit_task_plan.");

        server.verify();
    }

    @Test
    void translatesNetworkTimeoutForUserInputParsing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpQuestParserClient client = new HttpQuestParserClient(builder, "http://agent-runtime");

        server.expect(requestTo("http://agent-runtime/api/parser/user-input"))
                .andRespond(request -> {
                    throw new ResourceAccessException("response timeout");
                });

        assertThatThrownBy(() -> client.parseUserInput("分析项目", List.of(new Agent("opencode", "OpenCode"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent runtime timed out or could not be reached")
                .hasMessageContaining("response timeout");

        server.verify();
    }
}
