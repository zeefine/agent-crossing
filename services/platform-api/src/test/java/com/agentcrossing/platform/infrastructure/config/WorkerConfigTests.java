package com.agentcrossing.platform.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class WorkerConfigTests {

    @Test
    void configuresBoundedBusinessWorkerQueueWithRejection() {
        ThreadPoolTaskExecutor executor = new WorkerConfig().parallelTaskExecutor();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(executor.getMaxPoolSize());
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(100);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(java.util.concurrent.ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            executor.shutdown();
        }
    }
}
