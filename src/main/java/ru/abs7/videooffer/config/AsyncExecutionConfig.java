package ru.abs7.videooffer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncExecutionConfig {

    @Bean(name = "videoProcessingExecutor")
    public ThreadPoolTaskExecutor videoProcessingExecutor(
            @Value("${app.concurrency.video.global-workers:3}") int workers) {
        int poolSize = Math.max(1, workers);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        // Fair queuing is handled by TenantFairVideoScheduler. Keep only a tiny
        // executor-side buffer so tasks cannot build a second, invisible queue.
        executor.setQueueCapacity(poolSize);
        executor.setThreadNamePrefix("video-processing-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Bean(name = "notificationExecutor")
    public ThreadPoolTaskExecutor notificationExecutor(
            @Value("${app.concurrency.notification.core-workers:2}") int coreWorkers,
            @Value("${app.concurrency.notification.max-workers:4}") int maxWorkers,
            @Value("${app.concurrency.notification.queue-capacity:500}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, coreWorkers));
        executor.setMaxPoolSize(Math.max(Math.max(1, coreWorkers), maxWorkers));
        executor.setQueueCapacity(Math.max(50, queueCapacity));
        executor.setThreadNamePrefix("bitrix-notification-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }

    @Bean(name = "systemAsyncExecutor")
    public ThreadPoolTaskExecutor systemAsyncExecutor(
            @Value("${app.concurrency.system.core-workers:2}") int coreWorkers,
            @Value("${app.concurrency.system.max-workers:4}") int maxWorkers,
            @Value("${app.concurrency.system.queue-capacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, coreWorkers));
        executor.setMaxPoolSize(Math.max(Math.max(1, coreWorkers), maxWorkers));
        executor.setQueueCapacity(Math.max(20, queueCapacity));
        executor.setThreadNamePrefix("system-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
