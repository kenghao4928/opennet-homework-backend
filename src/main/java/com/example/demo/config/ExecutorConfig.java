package com.example.demo.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class ExecutorConfig {

    public static final String VIRTUAL_THREAD_EXECUTOR = "virtualThreadExecutor";

    public static final String DELAYED_EVICT_SCHEDULER = "delayedEvictScheduler";

    @Bean(name = VIRTUAL_THREAD_EXECUTOR)
    public Executor virtualThreadExecutor() {
        return Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("vt-", 0).factory());
    }

    @Bean(name = DELAYED_EVICT_SCHEDULER, destroyMethod = "shutdown")
    public ScheduledExecutorService delayedEvictScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "notification-delayed-evict");
            t.setDaemon(true);
            return t;
        });
    }
}
