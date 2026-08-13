package com.agentcrossing.platform.infrastructure.runtime;

import com.agentcrossing.platform.application.invocation.SessionCompressionClient;
import com.agentcrossing.platform.application.invocation.SessionCompressionRequest;
import com.agentcrossing.platform.application.invocation.SessionCompressionResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpSessionCompressionClient implements SessionCompressionClient {
    private final RestClient restClient;

    public HttpSessionCompressionClient(@Qualifier("agentRuntimeExecutionRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public SessionCompressionResult compress(SessionCompressionRequest request) {
        SessionCompressionResult response = restClient.post()
                .uri("/api/runtime/compress")
                .body(request)
                .retrieve()
                .body(SessionCompressionResult.class);
        if (response == null || response.startupSummary() == null) {
            throw new IllegalStateException("Agent runtime returned an empty session compression result");
        }
        return response;
    }
}
