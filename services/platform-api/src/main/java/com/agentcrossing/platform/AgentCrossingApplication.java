package com.agentcrossing.platform;

import com.agentcrossing.platform.infrastructure.config.AgentCatalogProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AgentCatalogProperties.class)
public class AgentCrossingApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentCrossingApplication.class, args);
    }
}
