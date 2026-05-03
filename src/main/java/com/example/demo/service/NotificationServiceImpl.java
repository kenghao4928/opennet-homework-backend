package com.example.demo.service;

import com.example.demo.bo.NotificationBo;
import com.example.demo.bo.UpdateNotificationBo;
import com.example.demo.config.CacheConfig;
import com.example.demo.config.ExecutorConfig;
import com.example.demo.config.NotificationConstants;
import com.example.demo.entity.Notification;
import com.example.demo.mapper.NotificationServiceMapper;
import com.example.demo.mq.NotificationProducer;
import com.example.demo.repository.NotificationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    /*
     * 新建通知的原子寫入：單筆 cache 寫入 + 加入 ZSet + 修剪 ZSet + 清除 exhausted 旗標。
     * KEYS[1] = notifications:recent             （ZSet，最近通知列表）
     * KEYS[2] = notifications:recent:exhausted   （DB 耗盡旗標）
     * KEYS[3] = notification:{id}                （單筆 cache key）
     * ARGV[1] = createdAt epochMillis            （ZSet score）
     * ARGV[2] = id                               （ZSet member）
     * ARGV[3] = -(RECENT_MAX_SIZE + 1) = -21     （ZREMRANGEBYRANK 截斷點，保留最新 20 筆）
     * ARGV[4] = NotificationBo JSON              （單筆 cache value）
     * ARGV[5] = REDIS_SINGLE_TTL 秒數            （單筆 cache TTL）
     */
    private static final String ADD_TO_RECENT_LUA = """
        redis.call('SET', KEYS[3], ARGV[4], 'EX', ARGV[5])
        redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
        redis.call('ZREMRANGEBYRANK', KEYS[1], 0, ARGV[3])
        redis.call('DEL', KEYS[2])
        return 1
        """;

    private static final RedisScript<Long> ADD_TO_RECENT_SCRIPT = new DefaultRedisScript<>(
        ADD_TO_RECENT_LUA, Long.class);

    /*
     * 刪除通知的 ZSet 移除 + 回傳「是否需要 refill」訊號。
     * KEYS[1] = notifications:recent              （ZSet）
     * KEYS[2] = notifications:recent:exhausted    （DB 耗盡旗標）
     * ARGV[1] = id                                （要從 ZSet 移除的 member）
     * ARGV[2] = RECENT_REFILL_THRESHOLD = 15      （buffer 低於此值才需 refill）
     * ARGV[3] = REFILL_NOT_NEEDED_BUFFER_OK  = -1 （buffer 仍足夠，不需 refill）
     * ARGV[4] = REFILL_NOT_NEEDED_DB_EXHAUSTED = -2（DB 已耗盡，不需 refill）
     * 回傳：-1 = buffer 足夠；-2 = DB 已耗盡；>=0 = 現存 size，需觸發 refillRecent。
     */
    private static final String REMOVE_FROM_RECENT_LUA = """
        redis.call('ZREM', KEYS[1], ARGV[1])
        local size = redis.call('ZCARD', KEYS[1])
        if size >= tonumber(ARGV[2]) then
          return tonumber(ARGV[3])
        end
        if redis.call('EXISTS', KEYS[2]) == 1 then
          return tonumber(ARGV[4])
        end
        return size
        """;

    private static final RedisScript<Long> REMOVE_FROM_RECENT_SCRIPT = new DefaultRedisScript<>(
        REMOVE_FROM_RECENT_LUA, Long.class);

    /*
     * 重建/補充 ZSet 的原子操作：批次 ZADD + 修剪 ZSet。
     * KEYS[1]    = notifications:recent           （ZSet）
     * ARGV[1]    = -(RECENT_MAX_SIZE + 1) = -21   （ZREMRANGEBYRANK 截斷點）
     * ARGV[2..n] = score, member, score, member… （依序為 epochMillis 與 id 的成對序列）
     */
    private static final String ADD_ALL_TO_RECENT_LUA = """
        redis.call('ZADD', KEYS[1], unpack(ARGV, 2))
        redis.call('ZREMRANGEBYRANK', KEYS[1], 0, ARGV[1])
        return 1
        """;

    private static final RedisScript<Long> ADD_ALL_TO_RECENT_SCRIPT = new DefaultRedisScript<>(
        ADD_ALL_TO_RECENT_LUA, Long.class);

    private final NotificationRepository notificationRepository;
    private final NotificationServiceMapper notificationMapper;
    private final NotificationProducer notificationProducer;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;

    @Qualifier(ExecutorConfig.VIRTUAL_THREAD_EXECUTOR)
    private final Executor virtualThreadExecutor;

    @Qualifier(ExecutorConfig.DELAYED_EVICT_SCHEDULER)
    private final ScheduledExecutorService delayedEvictScheduler;

    @Qualifier(CacheConfig.SINGLE_FALLBACK_CACHE)
    private final AsyncCache<Long, Optional<NotificationBo>> singleFallbackCache;

    @Qualifier(CacheConfig.RECENT_FALLBACK_CACHE)
    private final Cache<String, List<Long>> recentFallbackCache;

    @Lazy
    private final NotificationService self;

    @Override
    public NotificationBo create(NotificationBo input) {
        NotificationBo bo = self.dbCreate(input);
        try {
            cacheAddToRecent(bo);
        } catch (RuntimeException e) {
            log.error("cacheAddToRecent failed for new notification id={}", bo.getId(), e);
        }
        try {
            notificationProducer.send(notificationMapper.toMessage(bo));
        } catch (RuntimeException e) {
            log.error("producer send failed id={}", bo.getId(), e);
        }
        return bo;
    }

    @Override
    public Optional<NotificationBo> findById(Long id) {
        Optional<NotificationBo> hit = cacheFindById(id);
        if (hit.isPresent()) {
            return hit;
        }
        return loadWithCacheBreakdownLock(id);
    }

    @Override
    public List<NotificationBo> getRecent() {
        List<Long> ids = cacheGetRecentIds();
        if (ids.isEmpty()) {
            ids = rebuildWithSingleFlight();
            if (ids.isEmpty()) {
                return List.of();
            }
        }
        return mapToBos(ids, resolveWithDbBackfill(ids));
    }

    @Override
    public Optional<NotificationBo> update(Long id, UpdateNotificationBo updateBo) {
        try {
            cacheEvictSingle(id);
        } catch (RuntimeException e) {
            log.error("cacheEvictSingle failed before update id={}", id, e);
        }
        Optional<NotificationBo> bo = self.dbUpdate(id, updateBo);
        bo.ifPresent(updated -> scheduleSecondEvict(updated.getId()));
        return bo;
    }

    @Override
    public boolean delete(Long id) {
        try {
            cacheEvictSingle(id);
        } catch (RuntimeException e) {
            log.error("cacheEvictSingle failed before delete id={}", id, e);
        }
        if (!self.dbDelete(id)) {
            return false;
        }
        long signal;
        try {
            signal = cacheRemoveFromRecent(id);
        } catch (RuntimeException e) {
            log.error("cacheRemoveFromRecent failed id={}", id, e);
            signal = NotificationConstants.REFILL_NOT_NEEDED_BUFFER_OK;
        }
        scheduleSecondEvict(id);
        if (signal >= 0L) {
            virtualThreadExecutor.execute(this::refillRecent);
        }
        return true;
    }

    private void scheduleSecondEvict(Long id) {
        delayedEvictScheduler.schedule(() -> virtualThreadExecutor.execute(() -> {
            try {
                cacheEvictSingle(id);
            } catch (RuntimeException e) {
                log.error("delayed second evict failed id={}", id, e);
            }
        }), NotificationConstants.DELAYED_DOUBLE_DELETE_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NotificationBo dbCreate(NotificationBo input) {
        Notification entity = notificationMapper.toEntity(input);
        return notificationMapper.toBo(notificationRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationBo> dbFindById(Long id) {
        return notificationRepository.findById(id).map(notificationMapper::toBo);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, NotificationBo> dbFindByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return notificationRepository.findAllById(ids).stream().collect(
            Collectors.toMap(Notification::getId, notificationMapper::toBo, (a, b) -> a,
                LinkedHashMap::new));
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationBo> dbFindRecent(int limit) {
        return notificationRepository.findByOrderByCreatedAtDesc(PageRequest.of(0, limit)).stream()
            .map(notificationMapper::toBo).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<NotificationBo> dbUpdate(Long id, UpdateNotificationBo updateBo) {
        return notificationRepository.findById(id).map(entity -> {
            notificationMapper.updateEntity(updateBo, entity);
            return notificationMapper.toBo(notificationRepository.saveAndFlush(entity));
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean dbDelete(Long id) {
        if (!notificationRepository.existsById(id)) {
            return false;
        }
        notificationRepository.deleteById(id);
        return true;
    }


    private Optional<NotificationBo> loadWithCacheBreakdownLock(Long id) {
        RLock lock = redissonClient.getLock(NotificationConstants.CACHE_BREAKDOWN_LOCK_PREFIX + id);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(NotificationConstants.CACHE_BREAKDOWN_LOCK_WAIT_SECONDS,
                NotificationConstants.CACHE_BREAKDOWN_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug(
                    "findById fallback=lock-timeout id={}, instance single-flight via Caffeine",
                    id);
                return loadFromDbWithSingleFlight(id);
            }
            Optional<NotificationBo> doubleCheck = cacheFindById(id);
            if (doubleCheck.isPresent()) {
                return doubleCheck;
            }
            Optional<NotificationBo> fromDb = self.dbFindById(id);
            fromDb.ifPresent(this::cachePutSingle);
            return fromDb;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("findById interrupted id={}", id, e);
            return Optional.empty();
        } catch (RedisException | DataAccessException e) {
            log.warn("findById fallback=redis-down id={}, instance single-flight via Caffeine", id,
                e);
            return loadFromDbWithSingleFlight(id);
        } catch (RuntimeException e) {
            log.error("findById failed id={}, returning empty as last-resort fallback", id, e);
            return Optional.empty();
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Optional<NotificationBo> loadFromDbWithSingleFlight(Long id) {
        return singleFallbackCache.synchronous().get(id, k -> {
            try {
                Optional<NotificationBo> retry = cacheFindById(k);
                if (retry.isPresent()) {
                    return retry;
                }
            } catch (RedisException | DataAccessException e) {
                // Redis 仍故障，直接走 DB
                log.debug("findById single-flight retry cache failed id={}, falling through to DB",
                    k, e);
            }
            return self.dbFindById(k);
        });
    }

    private List<NotificationBo> mapToBos(List<Long> ids, Map<Long, NotificationBo> resolved) {
        return ids.stream().map(resolved::get).filter(Objects::nonNull)
            .limit(NotificationConstants.RECENT_FETCH_LIMIT).toList();
    }

    private Map<Long, NotificationBo> resolveWithDbBackfill(List<Long> ids) {
        Map<Long, NotificationBo> resolved = cacheMultiGet(ids);
        if (resolved.size() == ids.size()) {
            return resolved;
        }
        List<Long> missingIds = ids.stream().filter(id -> !resolved.containsKey(id)).toList();
        Map<Long, NotificationBo> dbHits = backfillWithCacheBreakdownLock(missingIds);
        Map<Long, NotificationBo> merged = new LinkedHashMap<>(resolved);
        merged.putAll(dbHits);
        return merged;
    }

    private Map<Long, NotificationBo> backfillWithCacheBreakdownLock(List<Long> missingIds) {
        RLock lock = redissonClient.getLock(NotificationConstants.RECENT_BACKFILL_LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(NotificationConstants.CACHE_BREAKDOWN_LOCK_WAIT_SECONDS,
                NotificationConstants.CACHE_BREAKDOWN_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug(
                    "backfill fallback=lock-timeout ids={}, instance single-flight via singleFallbackCache",
                    missingIds);
                return loadFromSingleFallbackCache(missingIds);
            }
            return doBackfill(missingIds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("backfill interrupted ids={}", missingIds, e);
            return Map.of();
        } catch (RedisException e) {
            log.warn(
                "backfill fallback=redis-down ids={}, instance single-flight via singleFallbackCache",
                missingIds, e);
            return loadFromSingleFallbackCache(missingIds);
        } catch (DataAccessException e) {
            log.error("backfill DB failed ids={}, returning partial cache hits", missingIds, e);
            return Map.of();
        } catch (RuntimeException e) {
            log.error("backfill failed ids={}, returning partial cache hits", missingIds, e);
            return Map.of();
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 拿到 distributed lock 後的批次 backfill：雙檢 cache、批次 DB、pipeline 寫回。
     */
    private Map<Long, NotificationBo> doBackfill(List<Long> missingIds) {
        Map<Long, NotificationBo> recheck = cacheMultiGet(missingIds);
        List<Long> stillMissing = missingIds.stream().filter(id -> !recheck.containsKey(id))
            .toList();
        if (stillMissing.isEmpty()) {
            return recheck;
        }
        Map<Long, NotificationBo> fromDb = self.dbFindByIds(stillMissing);
        if (!fromDb.isEmpty()) {
            try {
                cachePutMany(fromDb);
            } catch (RedisException e) {
                log.warn("backfill cachePutMany failed, results still returned", e);
            }
        }
        Map<Long, NotificationBo> merged = new LinkedHashMap<>(recheck);
        merged.putAll(fromDb);
        return merged;
    }

    /**
     * Redis lock timeout 或 Redis 故障時，走 singleFallbackCache（與 findById fallback 共用 cache）。
     * 底層為 AsyncCache：對每個 missing key 以 placeholder CompletableFuture 做 per-key single-flight，
     * 後到的 thread 會等已存在的 future 完成；尚未出現過的 keys 才會合併呼叫 batchLoadForFallback 一次。
     * loader 內先嘗試從 Redis 撈取，仍 missing 的才批次查 DB。
     * findById 路徑寫回的 fallback 條目也會在這裡命中，避免重複 DB 查詢。
     */
    private Map<Long, NotificationBo> loadFromSingleFallbackCache(List<Long> missingIds) {
        Map<Long, Optional<NotificationBo>> hits = singleFallbackCache.synchronous()
            .getAll(missingIds, this::batchLoadForFallback);
        Map<Long, NotificationBo> result = new LinkedHashMap<>();
        for (Long id : missingIds) {
            Optional<NotificationBo> opt = hits.get(id);
            if (opt != null && opt.isPresent()) {
                result.put(id, opt.get());
            }
        }
        return result;
    }

    private Map<Long, Optional<NotificationBo>> batchLoadForFallback(Set<? extends Long> keys) {
        List<Long> ids = new ArrayList<>(keys);
        Map<Long, NotificationBo> fromCache;
        try {
            fromCache = cacheMultiGet(ids);
        } catch (RedisException e) {
            log.debug("fallback retry cache failed ids={}, falling through to DB", ids, e);
            fromCache = Map.of();
        }
        Map<Long, NotificationBo> resolved = new LinkedHashMap<>(fromCache);
        List<Long> stillMissing = ids.stream().filter(id -> !resolved.containsKey(id)).toList();
        if (!stillMissing.isEmpty()) {
            try {
                resolved.putAll(self.dbFindByIds(stillMissing));
            } catch (DataAccessException e) {
                log.error("fallback batch DB failed ids={}, returning Caffeine-only results",
                    stillMissing, e);
            }
        }
        Map<Long, Optional<NotificationBo>> result = new LinkedHashMap<>();
        for (Long id : ids) {
            result.put(id, Optional.ofNullable(resolved.get(id)));
        }
        return result;
    }

    private List<Long> rebuildWithSingleFlight() {
        RLock lock = redissonClient.getLock(NotificationConstants.RECENT_REBUILD_LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(NotificationConstants.CACHE_BREAKDOWN_LOCK_WAIT_SECONDS,
                NotificationConstants.CACHE_BREAKDOWN_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug(
                    "getRecent fallback=rebuild-lock-timeout, instance single-flight via Caffeine");
                return loadRecentFromDbWithSingleFlight();
            }
            List<Long> doubleCheck = cacheGetRecentIds();
            if (!doubleCheck.isEmpty()) {
                return doubleCheck;
            }
            return rebuildIdsAndWriteCache();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("getRecent interrupted", e);
            return List.of();
        } catch (RedisException | DataAccessException e) {
            log.warn("getRecent fallback=redis-down, instance single-flight via Caffeine", e);
            return loadRecentFromDbWithSingleFlight();
        } catch (Exception e) {
            log.error("getRecent failed, returning empty as last-resort fallback", e);
            return List.of();
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private List<Long> loadRecentFromDbWithSingleFlight() {
        return recentFallbackCache.get(NotificationConstants.RECENT_FALLBACK_KEY, k -> {
            try {
                List<Long> retry = cacheGetRecentIds();
                if (!retry.isEmpty()) {
                    return retry;
                }
            } catch (RedisException | DataAccessException e) {
                // Redis 仍故障，直接走 DB
                log.debug("getRecent single-flight retry cache failed, falling through to DB", e);
            }
            return loadRecentIdsFromDb();
        });
    }

    private List<Long> rebuildIdsAndWriteCache() {
        List<NotificationBo> bos = self.dbFindRecent(NotificationConstants.RECENT_MAX_SIZE);
        if (bos.isEmpty()) {
            return List.of();
        }
        cacheAddAllToRecent(bos);
        return bos.stream().map(NotificationBo::getId).toList();
    }

    private List<Long> loadRecentIdsFromDb() {
        return self.dbFindRecent(NotificationConstants.RECENT_FETCH_LIMIT).stream()
            .map(NotificationBo::getId).toList();
    }

    private void refillRecent() {
        RLock lock = redissonClient.getLock(NotificationConstants.RECENT_REBUILD_LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0L, NotificationConstants.CACHE_BREAKDOWN_LOCK_LEASE_SECONDS,
                TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("Refill skipped: another holder already refilling");
                return;
            }
            List<NotificationBo> bos = self.dbFindRecent(NotificationConstants.RECENT_MAX_SIZE);
            if (bos.isEmpty()) {
                cacheMarkExhausted();
                return;
            }
            cacheAddAllToRecent(bos);
            if (bos.size() < NotificationConstants.RECENT_MAX_SIZE) {
                cacheMarkExhausted();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Refill interrupted; aborting this round");
        } catch (RuntimeException e) {
            log.error("Refill failed; ZSet buffer may stay below threshold until next delete "
                + "with signal>=0 triggers another attempt", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Optional<NotificationBo> cacheFindById(Long id) {
        String json = redisTemplate.opsForValue().get(singleKey(id));
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(decode(json));
    }

    private void cachePutSingle(NotificationBo bo) {
        redisTemplate.opsForValue()
            .set(singleKey(bo.getId()), encode(bo), NotificationConstants.REDIS_SINGLE_TTL);
    }

    private void cachePutMany(Map<Long, NotificationBo> bos) {
        if (bos.isEmpty()) {
            return;
        }
        redisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(@NonNull RedisOperations operations) {
                bos.forEach((id, bo) -> operations.opsForValue()
                    .set(singleKey(id), encode(bo), NotificationConstants.REDIS_SINGLE_TTL));
                return null;
            }
        });
    }

    private void cacheEvictSingle(Long id) {
        redisTemplate.delete(singleKey(id));
    }

    private long cacheRemoveFromRecent(Long id) {
        Long result = redisTemplate.execute(REMOVE_FROM_RECENT_SCRIPT,
            List.of(NotificationConstants.REDIS_RECENT_KEY,
                NotificationConstants.REDIS_RECENT_EXHAUSTED_KEY), id.toString(),
            String.valueOf(NotificationConstants.RECENT_REFILL_THRESHOLD),
            String.valueOf(NotificationConstants.REFILL_NOT_NEEDED_BUFFER_OK),
            String.valueOf(NotificationConstants.REFILL_NOT_NEEDED_DB_EXHAUSTED));
        return result == null ? NotificationConstants.REFILL_NOT_NEEDED_BUFFER_OK : result;
    }


    private void cacheAddToRecent(NotificationBo bo) {
        redisTemplate.execute(ADD_TO_RECENT_SCRIPT, List.of(NotificationConstants.REDIS_RECENT_KEY,
                NotificationConstants.REDIS_RECENT_EXHAUSTED_KEY, singleKey(bo.getId())),
            String.valueOf(bo.getCreatedAt().toEpochMilli()), bo.getId().toString(),
            String.valueOf(-(NotificationConstants.RECENT_MAX_SIZE + 1L)), encode(bo),
            String.valueOf(NotificationConstants.REDIS_SINGLE_TTL.toSeconds()));
    }

    private List<Long> cacheGetRecentIds() {
        Set<String> raw = redisTemplate.opsForZSet()
            .reverseRange(NotificationConstants.REDIS_RECENT_KEY, 0,
                NotificationConstants.RECENT_FETCH_LIMIT - 1L);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                ids.add(Long.valueOf(s));
            } catch (NumberFormatException e) {
                log.warn("invalid id in recent ZSet, skipping: {}", s);
            }
        }
        return ids;
    }

    private Map<Long, NotificationBo> cacheMultiGet(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<String> keys = ids.stream().map(this::singleKey).toList();
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) {
            return Map.of();
        }
        Map<Long, NotificationBo> result = new LinkedHashMap<>();
        for (int i = 0; i < ids.size() && i < values.size(); i++) {
            String json = values.get(i);
            if (json != null) {
                result.put(ids.get(i), decode(json));
            }
        }
        return result;
    }

    private void cacheAddAllToRecent(List<NotificationBo> bos) {
        if (bos.isEmpty()) {
            return;
        }
        List<String> args = new ArrayList<>(1 + bos.size() * 2);
        args.add(String.valueOf(-(NotificationConstants.RECENT_MAX_SIZE + 1L)));
        for (NotificationBo bo : bos) {
            args.add(String.valueOf(bo.getCreatedAt().toEpochMilli()));
            args.add(bo.getId().toString());
        }
        redisTemplate.execute(ADD_ALL_TO_RECENT_SCRIPT,
            List.of(NotificationConstants.REDIS_RECENT_KEY), args.toArray());
    }

    private void cacheMarkExhausted() {
        redisTemplate.opsForValue().set(NotificationConstants.REDIS_RECENT_EXHAUSTED_KEY, "1",
            NotificationConstants.REDIS_RECENT_EXHAUSTED_TTL);
    }

    private String singleKey(Long id) {
        return NotificationConstants.REDIS_SINGLE_KEY_PREFIX + id;
    }

    private String encode(NotificationBo bo) {
        try {
            return objectMapper.writeValueAsString(bo);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize NotificationBo id=" + bo.getId(),
                e);
        }
    }

    private NotificationBo decode(String json) {
        try {
            return objectMapper.readValue(json, NotificationBo.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize NotificationBo", e);
        }
    }
}
