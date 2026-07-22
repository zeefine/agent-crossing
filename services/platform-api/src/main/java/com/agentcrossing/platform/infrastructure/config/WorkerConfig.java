package com.agentcrossing.platform.infrastructure.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class WorkerConfig {
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor parallelTaskExecutor() {
        int workerCount = Math.max(2, Runtime.getRuntime().availableProcessors());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-worker-");
        executor.setCorePoolSize(workerCount);
        executor.setMaxPoolSize(workerCount);
        executor.setQueueCapacity(100);
        // 队列满时必须让 Router 恢复 QUEUED；CallerRunsPolicy 会把耗时 CLI 执行反压到调度线程。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(destroyMethod = "shutdown")
    public Executor taskDispatchExecutor() {
        return Executors.newSingleThreadExecutor();
    }

    @Bean(destroyMethod = "close")
    public ExecutorService websocketOutboundExecutor() {
        // sendMessage 可能被慢客户端阻塞；每个 session 的 drain 使用独立虚拟线程，避免占住业务线程。
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(destroyMethod = "shutdown")
    public Executor chatPlanningExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("chat-planning-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
