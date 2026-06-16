package com.cloudvm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Cấu hình ThreadPool cho các tác vụ bất đồng bộ (@Async).
 *
 * Dùng cho luồng khởi tạo EC2 instance chạy ngầm sau khi trả response về user.
 * ThreadPool giúp kiểm soát số lượng tác vụ chạy đồng thời, tránh tạo quá nhiều
 * thread không giới hạn khi có nhiều request thuê máy cùng lúc.
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Value("${async.pool.coreSize:5}")
    private int corePoolSize;

    @Value("${async.pool.maxSize:20}")
    private int maxPoolSize;

    @Value("${async.pool.queueCapacity:100}")
    private int queueCapacity;

    /**
     * Bean executor được @Async sử dụng khi không chỉ định tên bean cụ thể.
     * Tên method "getAsyncExecutor" là convention của AsyncConfigurer.
     */
    @Override
    @Bean(name = "asyncExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("CloudVM-Async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
