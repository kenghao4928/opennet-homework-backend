package com.example.demo.config;

import com.example.demo.bo.NotificationBo;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

    public static final String SINGLE_FALLBACK_CACHE = "singleFallbackCache";

    public static final String RECENT_FALLBACK_CACHE = "recentFallbackCache";

    @Bean(name = SINGLE_FALLBACK_CACHE)
    public AsyncCache<Long, Optional<NotificationBo>> singleFallbackCache(
        @Qualifier(ExecutorConfig.VIRTUAL_THREAD_EXECUTOR) Executor executor) {
        return Caffeine.newBuilder()
            .expireAfterWrite(NotificationConstants.FALLBACK_CACHE_TTL)
            .maximumSize(NotificationConstants.FALLBACK_CACHE_MAX_SIZE)
            .executor(executor)
            .buildAsync();
    }

    @Bean(name = RECENT_FALLBACK_CACHE)
    public Cache<String, List<Long>> recentFallbackCache() {
        return Caffeine.newBuilder()
            .expireAfterWrite(NotificationConstants.FALLBACK_CACHE_TTL)
            .maximumSize(2L)
            .build();
    }
}
