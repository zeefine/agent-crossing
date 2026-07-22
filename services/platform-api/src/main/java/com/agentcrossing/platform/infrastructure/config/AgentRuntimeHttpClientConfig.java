package com.agentcrossing.platform.infrastructure.config;

import java.time.Duration;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AgentRuntimeHttpClientConfig {
    @Bean(name = "agentRuntimeHttpClient", destroyMethod = "close")
    public CloseableHttpClient agentRuntimeHttpClient(
            @Value("${agent-crossing.agent-runtime.http.connect-timeout}") Duration connectTimeout,
            @Value("${agent-crossing.agent-runtime.http.connection-request-timeout}")
                    Duration connectionRequestTimeout,
            @Value("${agent-crossing.agent-runtime.http.business-response-timeout}")
                    Duration businessResponseTimeout,
            @Value("${agent-crossing.agent-runtime.http.max-connections}") int maxConnections,
            @Value("${agent-crossing.agent-runtime.http.max-connections-per-route}")
                    int maxConnectionsPerRoute) {
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(connectionRequestTimeout, "connection-request-timeout");
        requirePositive(businessResponseTimeout, "business-response-timeout");
        if (maxConnections < 1 || maxConnectionsPerRoute < 1) {
            throw new IllegalArgumentException("Agent runtime HTTP connection pool limits must be positive");
        }

        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnectionsPerRoute)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.of(connectTimeout))
                .setConnectionRequestTimeout(Timeout.of(connectionRequestTimeout))
                .setResponseTimeout(Timeout.of(businessResponseTimeout))
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();
    }

    @Bean(name = "agentRuntimeExecutionRestClient")
    public RestClient agentRuntimeExecutionRestClient(
            RestClient.Builder restClientBuilder,
            @Qualifier("agentRuntimeHttpClient") CloseableHttpClient httpClient,
            @Value("${agent-crossing.agent-runtime.base-url}") String baseUrl,
            @Value("${agent-crossing.agent-runtime.http.connect-timeout}") Duration connectTimeout,
            @Value("${agent-crossing.agent-runtime.http.connection-request-timeout}")
                    Duration connectionRequestTimeout,
            @Value("${agent-crossing.agent-runtime.http.business-response-timeout}")
                    Duration responseTimeout) {
        return restClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(httpClient, connectTimeout, connectionRequestTimeout, responseTimeout))
                .build();
    }

    @Bean(name = "agentRuntimeParserRestClient")
    public RestClient agentRuntimeParserRestClient(
            RestClient.Builder restClientBuilder,
            @Qualifier("agentRuntimeHttpClient") CloseableHttpClient httpClient,
            @Value("${agent-crossing.agent-runtime.base-url}") String baseUrl,
            @Value("${agent-crossing.agent-runtime.http.connect-timeout}") Duration connectTimeout,
            @Value("${agent-crossing.agent-runtime.http.connection-request-timeout}")
                    Duration connectionRequestTimeout,
            @Value("${agent-crossing.agent-runtime.http.parser-response-timeout}")
                    Duration responseTimeout) {
        return restClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(httpClient, connectTimeout, connectionRequestTimeout, responseTimeout))
                .build();
    }

    private static ClientHttpRequestFactory requestFactory(
            CloseableHttpClient httpClient,
            Duration connectTimeout,
            Duration connectionRequestTimeout,
            Duration responseTimeout) {
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(connectionRequestTimeout, "connection-request-timeout");
        requirePositive(responseTimeout, "response-timeout");
        return new ResponseTimeoutRequestFactory(
                httpClient, connectTimeout, connectionRequestTimeout, responseTimeout);
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("Agent runtime HTTP " + property + " must be positive");
        }
    }

    private static final class ResponseTimeoutRequestFactory extends HttpComponentsClientHttpRequestFactory {
        private final Duration responseTimeout;

        private ResponseTimeoutRequestFactory(
                CloseableHttpClient httpClient,
                Duration connectTimeout,
                Duration connectionRequestTimeout,
                Duration responseTimeout) {
            super(httpClient);
            this.responseTimeout = responseTimeout;
            setConnectTimeout(connectTimeout);
            setConnectionRequestTimeout(connectionRequestTimeout);
        }

        @Override
        protected RequestConfig createRequestConfig(Object client) {
            RequestConfig baseConfig = super.createRequestConfig(client);
            return RequestConfig.copy(baseConfig == null ? RequestConfig.DEFAULT : baseConfig)
                    .setResponseTimeout(Timeout.of(responseTimeout))
                    .build();
        }
    }
}
