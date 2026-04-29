package com.example.demo.config;

import java.time.Duration;

public final class NotificationConstants {

    private NotificationConstants() {
    }

    public static final String REDIS_SINGLE_KEY_PREFIX = "notification:";

    public static final String REDIS_RECENT_KEY = "notifications:recent";

    public static final String REDIS_RECENT_EXHAUSTED_KEY = "notifications:recent:exhausted";

    public static final Duration REDIS_SINGLE_TTL = Duration.ofHours(1);

    public static final Duration REDIS_RECENT_EXHAUSTED_TTL = Duration.ofSeconds(60);

    public static final int RECENT_FETCH_LIMIT = 10;

    public static final int RECENT_MAX_SIZE = 20;

    public static final int RECENT_REFILL_THRESHOLD = 15;

    /**
     * buffer 仍 ≥ threshold，不需 refill。
     */
    public static final long REFILL_NOT_NEEDED_BUFFER_OK = -1L;

    /**
     * buffer 不夠但 exhausted marker 存在，DB 已耗盡先別 refill。
     */
    public static final long REFILL_NOT_NEEDED_DB_EXHAUSTED = -2L;

    public static final String CACHE_BREAKDOWN_LOCK_PREFIX = "lock:notification:";

    public static final String RECENT_REBUILD_LOCK_KEY = "lock:notifications:recent:rebuild";

    public static final String RECENT_BACKFILL_LOCK_KEY = "lock:notifications:recent:backfill";

    public static final long CACHE_BREAKDOWN_LOCK_WAIT_SECONDS = 1L;

    public static final long CACHE_BREAKDOWN_LOCK_LEASE_SECONDS = 5L;

    public static final Duration FALLBACK_CACHE_TTL = Duration.ofSeconds(5);

    public static final long FALLBACK_CACHE_MAX_SIZE = 1000L;

    public static final String RECENT_FALLBACK_KEY = "recent";

    public static final long DELAYED_DOUBLE_DELETE_MS = 1500L;
}
