package com.collabeditor.execution.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Fixed-size bounded worker pool for asynchronous execution jobs.
 */
@Configuration
public class ExecutionTaskConfig {

    private final ExecutionProperties properties;

    public ExecutionTaskConfig(ExecutionProperties properties) {
        this.properties = properties;
    }

    @Bean
    public ThreadPoolTaskExecutor executionTaskExecutor() {
        validateConfiguredQueue();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("execution-worker-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(8);
        executor.setRejectedExecutionHandler((task, pool) -> {
            throw new TaskRejectedException("Execution queue is full");
        });
        executor.initialize();
        return executor;
    }

    private void validateConfiguredQueue() {
        if (properties.getWorkerCount() != 2) {
            throw new IllegalStateException("app.execution.worker-count must remain 2");
        }
        if (properties.getQueueCapacity() != 8) {
            throw new IllegalStateException("app.execution.queue-capacity must remain 8");
        }
    }
}
