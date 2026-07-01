package com.agentcrossing.platform.infrastructure.parser;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.agentcrossing.platform.domain.agent.Agent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpQuestParserClientTests {
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
}
