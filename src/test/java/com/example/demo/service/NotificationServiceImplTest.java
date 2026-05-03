package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.demo.bo.NotificationBo;
import com.example.demo.bo.UpdateNotificationBo;
import com.example.demo.config.NotificationConstants;
import com.example.demo.dto.NotificationMessage;
import com.example.demo.entity.Notification;
import com.example.demo.enums.NotificationType;
import com.example.demo.mapper.NotificationServiceMapper;
import com.example.demo.mq.NotificationProducer;
import com.example.demo.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.mockito.ArgumentCaptor;
import java.util.HashMap;
import java.util.function.Function;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceImplTest {

    @Mock NotificationRepository notificationRepository;
    @Mock NotificationServiceMapper notificationMapper;
    @Mock NotificationProducer notificationProducer;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ObjectMapper objectMapper;
    @Mock RedissonClient redissonClient;
    @Mock Executor virtualThreadExecutor;
    @Mock ScheduledExecutorService delayedEvictScheduler;
    @Mock AsyncCache<Long, Optional<NotificationBo>> singleFallbackCache;
    @Mock Cache<String, List<Long>> recentFallbackCache;
    @Mock NotificationService self;

    @Mock RLock rLock;
    @Mock ValueOperations<String, String> valueOps;
    @Mock ZSetOperations<String, String> zSetOps;

    NotificationServiceImpl service;

    private NotificationBo sampleBo(Long id) {
        return new NotificationBo(id, NotificationType.EMAIL, "a@b.com", "subj", "body",
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private String singleKey(Long id) {
        return NotificationConstants.REDIS_SINGLE_KEY_PREFIX + id;
    }

    @BeforeEach
    void setupCommonStubs() {
        // 明確 constructor 注入，避免 @InjectMocks 對 Executor / ScheduledExecutorService 的 by-type 歧義
        service = new NotificationServiceImpl(
            notificationRepository, notificationMapper, notificationProducer,
            redisTemplate, objectMapper, redissonClient,
            virtualThreadExecutor, delayedEvictScheduler,
            singleFallbackCache, recentFallbackCache, self);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        lenient().when(redissonClient.getLock(any(String.class))).thenReturn(rLock);

        // virtualThreadExecutor.execute(Runnable) — 同步執行讓測試能驗證 refill 觸發
        lenient().doAnswer(inv -> {
            Runnable r = inv.getArgument(0);
            r.run();
            return null;
        }).when(virtualThreadExecutor).execute(any(Runnable.class));
    }

    // ==========================================================
    // create
    // ==========================================================

    @Test
    void create_should_call_dbCreate_then_cacheAddToRecent_then_send() throws Exception {
        NotificationBo input = sampleBo(null);
        NotificationBo saved = sampleBo(1L);
        NotificationMessage msg = new NotificationMessage();

        when(self.dbCreate(input)).thenReturn(saved);
        when(objectMapper.writeValueAsString(saved)).thenReturn("{json}");
        when(notificationMapper.toMessage(saved)).thenReturn(msg);

        NotificationBo result = service.create(input);

        assertThat(result).isEqualTo(saved);
        verify(self).dbCreate(input);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
        verify(notificationProducer).send(msg);
    }

    @Test
    void create_when_cacheAddToRecent_throws_should_still_send_and_return() throws Exception {
        NotificationBo input = sampleBo(null);
        NotificationBo saved = sampleBo(1L);
        NotificationMessage msg = new NotificationMessage();

        when(self.dbCreate(input)).thenReturn(saved);
        when(objectMapper.writeValueAsString(saved)).thenReturn("{json}");
        when(notificationMapper.toMessage(saved)).thenReturn(msg);
        doThrow(new RedisException("redis down")).when(redisTemplate).execute(
            any(RedisScript.class), anyList(), any(Object[].class));

        NotificationBo result = service.create(input);

        assertThat(result).isEqualTo(saved);
        verify(notificationProducer).send(msg);
    }

    @Test
    void create_when_producer_throws_should_still_return() throws Exception {
        NotificationBo input = sampleBo(null);
        NotificationBo saved = sampleBo(1L);
        NotificationMessage msg = new NotificationMessage();

        when(self.dbCreate(input)).thenReturn(saved);
        when(objectMapper.writeValueAsString(saved)).thenReturn("{json}");
        when(notificationMapper.toMessage(saved)).thenReturn(msg);
        doThrow(new RuntimeException("mq down")).when(notificationProducer).send(msg);

        NotificationBo result = service.create(input);

        assertThat(result).isEqualTo(saved);
    }

    // ==========================================================
    // findById
    // ==========================================================

    @Test
    void findById_when_cache_hit_should_return_without_lock_or_db() throws Exception {
        Long id = 1L;
        NotificationBo bo = sampleBo(id);

        when(valueOps.get(singleKey(id))).thenReturn("{json}");
        when(objectMapper.readValue("{json}", NotificationBo.class)).thenReturn(bo);

        Optional<NotificationBo> result = service.findById(id);

        assertThat(result).contains(bo);
        verify(redissonClient, never()).getLock(any(String.class));
        verify(self, never()).dbFindById(anyLong());
    }

    @Test
    void findById_when_cache_miss_and_lock_acquired_should_load_from_db_and_writeback()
        throws Exception {
        Long id = 1L;
        NotificationBo bo = sampleBo(id);

        when(valueOps.get(singleKey(id))).thenReturn(null);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(self.dbFindById(id)).thenReturn(Optional.of(bo));
        when(objectMapper.writeValueAsString(bo)).thenReturn("{json}");

        Optional<NotificationBo> result = service.findById(id);

        assertThat(result).contains(bo);
        verify(self).dbFindById(id);
        verify(valueOps).set(eq(singleKey(id)), eq("{json}"), eq(NotificationConstants.REDIS_SINGLE_TTL));
        verify(rLock).unlock();
    }

    @Test
    void findById_when_lock_acquired_doublecheck_hits_cache_should_return_without_db()
        throws Exception {
        Long id = 1L;
        NotificationBo bo = sampleBo(id);

        // 第一次 cacheFindById 回 null（cache miss），雙檢時回 hit（其他 thread 寫回了）
        when(valueOps.get(singleKey(id))).thenReturn(null, "{json}");
        when(objectMapper.readValue("{json}", NotificationBo.class)).thenReturn(bo);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        Optional<NotificationBo> result = service.findById(id);

        assertThat(result).contains(bo);
        verify(self, never()).dbFindById(anyLong());
        verify(rLock).unlock();
    }

    @Test
    void findById_when_lock_timeout_should_use_caffeine_fallback() throws Exception {
        Long id = 1L;
        NotificationBo bo = sampleBo(id);

        when(valueOps.get(singleKey(id))).thenReturn(null);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        com.github.benmanes.caffeine.cache.Cache<Long, Optional<NotificationBo>> syncView = mock(
            com.github.benmanes.caffeine.cache.Cache.class);
        when(singleFallbackCache.synchronous()).thenReturn(syncView);
        when(syncView.get(eq(id), any())).thenReturn(Optional.of(bo));

        Optional<NotificationBo> result = service.findById(id);

        assertThat(result).contains(bo);
        verify(rLock, never()).unlock();
    }

    @Test
    void findById_when_redis_exception_inside_lock_should_use_caffeine_fallback()
        throws Exception {
        Long id = 1L;
        NotificationBo bo = sampleBo(id);

        // 第一次 cacheFindById 回 null（外層入口 miss），進 loadWithCacheBreakdownLock 後雙檢拋
        when(valueOps.get(singleKey(id))).thenReturn(null).thenThrow(new RedisException("down"));
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        com.github.benmanes.caffeine.cache.Cache<Long, Optional<NotificationBo>> syncView = mock(
            com.github.benmanes.caffeine.cache.Cache.class);
        when(singleFallbackCache.synchronous()).thenReturn(syncView);
        when(syncView.get(eq(id), any())).thenReturn(Optional.of(bo));

        Optional<NotificationBo> result = service.findById(id);

        assertThat(result).contains(bo);
        verify(rLock).unlock();
    }

    @Test
    void findById_when_initial_cache_call_throws_should_propagate_for_handler() {
        Long id = 1L;

        when(valueOps.get(singleKey(id))).thenThrow(new RedisException("redis down"));

        // 設計：findById 入口的 cacheFindById 沒包 catch，異常 propagate 到 GlobalExceptionHandler 回 503
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.findById(id))
            .isInstanceOf(RedisException.class);
    }

    @Test
    void findById_when_interrupted_should_recover_flag_and_return_empty() throws Exception {
        Long id = 1L;

        when(valueOps.get(singleKey(id))).thenReturn(null);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
            .thenThrow(new InterruptedException("test"));

        try {
            Optional<NotificationBo> result = service.findById(id);
            assertThat(result).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // 清除 interrupt flag，避免影響後續測試
            Thread.interrupted();
        }
    }

    @Test
    void findById_when_unexpected_runtime_exception_should_return_empty_as_last_resort()
        throws Exception {
        Long id = 1L;
        NotificationBo bo = sampleBo(id);

        when(valueOps.get(singleKey(id))).thenReturn(null);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(self.dbFindById(id)).thenReturn(Optional.of(bo));
        // encode 失敗 → IllegalStateException 從 cachePutSingle 拋出
        when(objectMapper.writeValueAsString(bo))
            .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("test") {});

        Optional<NotificationBo> result = service.findById(id);

        assertThat(result).isEmpty();
        verify(rLock).unlock();
    }

    // ==========================================================
    // update
    // ==========================================================

    @Test
    void update_should_evict_then_dbUpdate_then_schedule_second_evict() {
        Long id = 1L;
        UpdateNotificationBo updateBo = new UpdateNotificationBo("new subj", "new body");
        NotificationBo updated = sampleBo(id);

        when(self.dbUpdate(id, updateBo)).thenReturn(Optional.of(updated));

        Optional<NotificationBo> result = service.update(id, updateBo);

        assertThat(result).contains(updated);
        verify(redisTemplate).delete(singleKey(id));
        verify(self).dbUpdate(id, updateBo);
        verify(delayedEvictScheduler).schedule(any(Runnable.class),
            eq(NotificationConstants.DELAYED_DOUBLE_DELETE_MS), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void update_when_cacheEvict_throws_should_still_dbUpdate() {
        Long id = 1L;
        UpdateNotificationBo updateBo = new UpdateNotificationBo("new subj", "new body");
        NotificationBo updated = sampleBo(id);

        when(redisTemplate.delete(singleKey(id))).thenThrow(new RedisException("redis down"));
        when(self.dbUpdate(id, updateBo)).thenReturn(Optional.of(updated));

        Optional<NotificationBo> result = service.update(id, updateBo);

        assertThat(result).contains(updated);
        verify(self).dbUpdate(id, updateBo);
    }

    @Test
    void update_when_dbUpdate_returns_empty_should_not_schedule_second_evict() {
        Long id = 99L;
        UpdateNotificationBo updateBo = new UpdateNotificationBo("new subj", "new body");

        when(self.dbUpdate(id, updateBo)).thenReturn(Optional.empty());

        Optional<NotificationBo> result = service.update(id, updateBo);

        assertThat(result).isEmpty();
        verify(delayedEvictScheduler, never()).schedule(any(Runnable.class), anyLong(),
            any(TimeUnit.class));
    }

    // ==========================================================
    // delete
    // ==========================================================

    @Test
    void delete_when_dbDelete_false_should_return_false_without_remove() {
        Long id = 99L;

        when(self.dbDelete(id)).thenReturn(false);

        boolean result = service.delete(id);

        assertThat(result).isFalse();
        verify(redisTemplate).delete(singleKey(id));
        verify(self).dbDelete(id);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(),
            any(Object[].class));
    }

    @Test
    void delete_when_signal_buffer_ok_should_not_refill() {
        Long id = 1L;

        when(self.dbDelete(id)).thenReturn(true);
        doReturn(NotificationConstants.REFILL_NOT_NEEDED_BUFFER_OK).when(redisTemplate)
            .execute(any(RedisScript.class), anyList(), any(Object[].class));

        boolean result = service.delete(id);

        assertThat(result).isTrue();
        verify(virtualThreadExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void delete_when_signal_db_exhausted_should_not_refill() {
        Long id = 1L;

        when(self.dbDelete(id)).thenReturn(true);
        doReturn(NotificationConstants.REFILL_NOT_NEEDED_DB_EXHAUSTED).when(redisTemplate)
            .execute(any(RedisScript.class), anyList(), any(Object[].class));

        boolean result = service.delete(id);

        assertThat(result).isTrue();
        verify(virtualThreadExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void delete_when_signal_positive_should_trigger_refill() throws Exception {
        Long id = 1L;

        when(self.dbDelete(id)).thenReturn(true);
        doAnswer(inv -> 10L).when(redisTemplate).execute(any(RedisScript.class), anyList(),
            any(Object[].class));
        // refill 內 tryLock(0) 取不到（已被其他 instance 佔用，refillRecent 直接 skip return）
        when(rLock.tryLock(eq(0L), anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(rLock.isHeldByCurrentThread()).thenReturn(false);

        boolean result = service.delete(id);

        assertThat(result).isTrue();
        verify(virtualThreadExecutor, atLeastOnce()).execute(any(Runnable.class));
    }

    @Test
    void delete_when_cacheRemoveFromRecent_throws_should_treat_as_buffer_ok() {
        Long id = 1L;

        when(self.dbDelete(id)).thenReturn(true);
        doThrow(new RedisException("redis down")).when(redisTemplate).execute(
            any(RedisScript.class), anyList(), any(Object[].class));

        boolean result = service.delete(id);

        assertThat(result).isTrue();
        verify(virtualThreadExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void delete_when_cacheEvictSingle_throws_should_still_dbDelete() {
        Long id = 1L;

        when(redisTemplate.delete(singleKey(id))).thenThrow(new RedisException("redis down"));
        when(self.dbDelete(id)).thenReturn(true);
        doReturn(NotificationConstants.REFILL_NOT_NEEDED_BUFFER_OK).when(redisTemplate)
            .execute(any(RedisScript.class), anyList(), any(Object[].class));

        boolean result = service.delete(id);

        assertThat(result).isTrue();
        verify(self).dbDelete(id);
    }

    // ==========================================================
    // getRecent
    // ==========================================================

    @Test
    void getRecent_when_zset_has_ids_all_cached_should_return_without_db() throws Exception {
        NotificationBo bo1 = sampleBo(1L);
        NotificationBo bo2 = sampleBo(2L);

        Set<String> rawIds = new LinkedHashSet<>();
        rawIds.add("1");
        rawIds.add("2");
        when(zSetOps.reverseRange(eq(NotificationConstants.REDIS_RECENT_KEY), eq(0L),
            anyLong())).thenReturn(rawIds);
        when(valueOps.multiGet(anyList())).thenReturn(List.of("{1}", "{2}"));
        when(objectMapper.readValue("{1}", NotificationBo.class)).thenReturn(bo1);
        when(objectMapper.readValue("{2}", NotificationBo.class)).thenReturn(bo2);

        List<NotificationBo> result = service.getRecent();

        assertThat(result).containsExactly(bo1, bo2);
        verify(self, never()).dbFindByIds(anyList());
        verify(self, never()).dbFindRecent(anyInt());
    }

    @Test
    void getRecent_when_zset_empty_should_rebuild_from_db() throws Exception {
        NotificationBo bo1 = sampleBo(1L);

        when(zSetOps.reverseRange(eq(NotificationConstants.REDIS_RECENT_KEY), eq(0L), anyLong()))
            .thenReturn(Set.of());
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(self.dbFindRecent(NotificationConstants.RECENT_MAX_SIZE)).thenReturn(List.of(bo1));
        when(valueOps.multiGet(anyList())).thenReturn(List.of("{1}"));
        when(objectMapper.readValue("{1}", NotificationBo.class)).thenReturn(bo1);

        List<NotificationBo> result = service.getRecent();

        assertThat(result).containsExactly(bo1);
        verify(self).dbFindRecent(NotificationConstants.RECENT_MAX_SIZE);
        verify(rLock).unlock();
    }

    @Test
    void getRecent_when_zset_empty_and_db_also_empty_should_return_empty_list() throws Exception {
        when(zSetOps.reverseRange(eq(NotificationConstants.REDIS_RECENT_KEY), eq(0L), anyLong()))
            .thenReturn(Set.of());
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(self.dbFindRecent(NotificationConstants.RECENT_MAX_SIZE)).thenReturn(List.of());

        List<NotificationBo> result = service.getRecent();

        assertThat(result).isEmpty();
        verify(rLock).unlock();
    }

    @Test
    void getRecent_should_filter_orphan_ids_from_dirty_zset() throws Exception {
        NotificationBo bo1 = sampleBo(1L);

        Set<String> rawIds = new LinkedHashSet<>();
        rawIds.add("1");
        rawIds.add("999");  // orphan: ZSet 有但 cache + DB 都沒
        when(zSetOps.reverseRange(eq(NotificationConstants.REDIS_RECENT_KEY), eq(0L), anyLong()))
            .thenReturn(rawIds);
        // multiGet 第一次呼叫帶 [notification:1, notification:999] 回 [json, null]
        // doBackfill 第二次呼叫帶 [notification:999] 回 [null]
        when(valueOps.multiGet(anyList())).thenAnswer(inv -> {
            List<String> keys = inv.getArgument(0);
            return keys.stream()
                .map(k -> "notification:1".equals(k) ? "{1}" : null)
                .toList();
        });
        when(objectMapper.readValue("{1}", NotificationBo.class)).thenReturn(bo1);
        // backfill: 999 走 DB 也找不到
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(self.dbFindByIds(List.of(999L))).thenReturn(Map.of());

        List<NotificationBo> result = service.getRecent();

        assertThat(result).containsExactly(bo1);
    }

    @Test
    void getRecent_should_skip_invalid_id_in_zset_without_throwing() throws Exception {
        NotificationBo bo1 = sampleBo(1L);

        Set<String> rawIds = new LinkedHashSet<>();
        rawIds.add("1");
        rawIds.add("not-a-number");  // 髒資料
        when(zSetOps.reverseRange(eq(NotificationConstants.REDIS_RECENT_KEY), eq(0L), anyLong()))
            .thenReturn(rawIds);
        when(valueOps.multiGet(anyList())).thenReturn(List.of("{1}"));
        when(objectMapper.readValue("{1}", NotificationBo.class)).thenReturn(bo1);

        List<NotificationBo> result = service.getRecent();

        assertThat(result).containsExactly(bo1);
    }

    @Test
    void getRecent_when_rebuild_lock_timeout_should_use_caffeine_fallback() throws Exception {
        NotificationBo bo1 = sampleBo(1L);

        when(zSetOps.reverseRange(eq(NotificationConstants.REDIS_RECENT_KEY), eq(0L), anyLong()))
            .thenReturn(Set.of());
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(recentFallbackCache.get(eq(NotificationConstants.RECENT_FALLBACK_KEY), any()))
            .thenReturn(List.of(1L));
        when(valueOps.multiGet(anyList())).thenReturn(List.of("{1}"));
        when(objectMapper.readValue("{1}", NotificationBo.class)).thenReturn(bo1);

        List<NotificationBo> result = service.getRecent();

        assertThat(result).containsExactly(bo1);
    }

    @Test
    void getRecent_when_redis_exception_during_rebuild_should_use_caffeine_fallback_for_ids()
        throws Exception {
        NotificationBo bo1 = sampleBo(1L);

        // 1. cacheGetRecentIds 回空 → 進 rebuildWithSingleFlight
        when(zSetOps.reverseRange(eq(NotificationConstants.REDIS_RECENT_KEY), eq(0L), anyLong()))
            .thenReturn(Set.of());
        // 2. tryLock 拋 RedisException → catch 走 loadRecentFromDbWithSingleFlight
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
            .thenThrow(new RedisException("redis down"));
        // 3. recentFallbackCache 從 caffeine 拿到 ids（loader 已被其他 thread 跑過）
        when(recentFallbackCache.get(eq(NotificationConstants.RECENT_FALLBACK_KEY), any()))
            .thenReturn(List.of(1L));
        // 4. 拿到 ids 後 resolveWithDbBackfill → cacheMultiGet 回正常結果
        // （此測試聚焦 rebuild fallback 路徑，假設後續 cacheMultiGet 已恢復可用）
        when(valueOps.multiGet(anyList())).thenReturn(List.of("{1}"));
        when(objectMapper.readValue("{1}", NotificationBo.class)).thenReturn(bo1);

        List<NotificationBo> result = service.getRecent();

        assertThat(result).containsExactly(bo1);
        verify(recentFallbackCache).get(eq(NotificationConstants.RECENT_FALLBACK_KEY), any());
    }

    @Test
    void getRecent_when_unexpected_exception_should_return_empty_as_last_resort() throws Exception {
        when(zSetOps.reverseRange(eq(NotificationConstants.REDIS_RECENT_KEY), eq(0L), anyLong()))
            .thenReturn(Set.of());
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(self.dbFindRecent(anyInt())).thenThrow(new IllegalStateException("unexpected"));

        List<NotificationBo> result = service.getRecent();

        assertThat(result).isEmpty();
        verify(rLock).unlock();
    }

    // ==========================================================
    // 區塊 A: refillRecent markExhausted 分支 (P0-3)
    // ==========================================================

    @Test
    void delete_when_dbFindRecent_empty_should_markExhausted_and_skip_addAll() throws Exception {
        Long id = 1L;

        when(self.dbDelete(id)).thenReturn(true);
        // cacheRemoveFromRecent 回 10L (>=0 觸發 refill)
        doReturn(10L).when(redisTemplate).execute(any(RedisScript.class), anyList(),
            any(Object[].class));
        when(rLock.tryLock(eq(0L), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(self.dbFindRecent(NotificationConstants.RECENT_MAX_SIZE)).thenReturn(List.of());

        boolean result = service.delete(id);

        assertThat(result).isTrue();
        // markExhausted 被呼叫
        verify(valueOps).set(eq(NotificationConstants.REDIS_RECENT_EXHAUSTED_KEY), eq("1"),
            eq(NotificationConstants.REDIS_RECENT_EXHAUSTED_TTL));
        // execute 只在 cacheRemoveFromRecent 呼叫過一次（沒有 addAll）
        verify(redisTemplate, times(1)).execute(any(RedisScript.class), anyList(),
            any(Object[].class));
    }

    @Test
    void delete_when_dbFindRecent_partial_should_addAll_then_markExhausted() throws Exception {
        Long id = 1L;

        when(self.dbDelete(id)).thenReturn(true);
        doReturn(10L).when(redisTemplate).execute(any(RedisScript.class), anyList(),
            any(Object[].class));
        when(rLock.tryLock(eq(0L), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        List<NotificationBo> partial = new ArrayList<>();
        for (long i = 1; i <= 5; i++) {
            partial.add(sampleBo(i));
        }
        when(self.dbFindRecent(NotificationConstants.RECENT_MAX_SIZE)).thenReturn(partial);
        when(objectMapper.writeValueAsString(any(NotificationBo.class))).thenReturn("{json}");

        boolean result = service.delete(id);

        assertThat(result).isTrue();
        // execute 兩次：cacheRemoveFromRecent + cacheAddAllToRecent
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(),
            any(Object[].class));
        // markExhausted 也被呼叫（partial < RECENT_MAX_SIZE）
        verify(valueOps).set(eq(NotificationConstants.REDIS_RECENT_EXHAUSTED_KEY), eq("1"),
            eq(NotificationConstants.REDIS_RECENT_EXHAUSTED_TTL));
    }

    @Test
    void delete_when_dbFindRecent_full_should_addAll_only_no_markExhausted() throws Exception {
        Long id = 1L;

        when(self.dbDelete(id)).thenReturn(true);
        doReturn(10L).when(redisTemplate).execute(any(RedisScript.class), anyList(),
            any(Object[].class));
        when(rLock.tryLock(eq(0L), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        List<NotificationBo> full = new ArrayList<>();
        for (long i = 1; i <= NotificationConstants.RECENT_MAX_SIZE; i++) {
            full.add(sampleBo(i));
        }
        when(self.dbFindRecent(NotificationConstants.RECENT_MAX_SIZE)).thenReturn(full);
        when(objectMapper.writeValueAsString(any(NotificationBo.class))).thenReturn("{json}");

        boolean result = service.delete(id);

        assertThat(result).isTrue();
        // execute 兩次：remove + addAll
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(),
            any(Object[].class));
        // markExhausted 從未被呼叫（剛好 RECENT_MAX_SIZE）
        verify(valueOps, never()).set(eq(NotificationConstants.REDIS_RECENT_EXHAUSTED_KEY),
            any(String.class), any(java.time.Duration.class));
    }

    @Test
    void delete_when_refill_interrupted_should_restore_interrupt_flag() throws Exception {
        Long id = 1L;

        when(self.dbDelete(id)).thenReturn(true);
        doReturn(10L).when(redisTemplate).execute(any(RedisScript.class), anyList(),
            any(Object[].class));
        when(rLock.tryLock(eq(0L), anyLong(), any(TimeUnit.class)))
            .thenThrow(new InterruptedException("test"));

        try {
            boolean result = service.delete(id);

            assertThat(result).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // 清除 interrupt flag，避免污染後續測試
            Thread.interrupted();
        }
    }

    // ==========================================================
    // 區塊 B: loadFromSingleFallbackCache batchLoadForFallback (P0-4)
    // ==========================================================

    @Test
    void getRecent_when_backfill_lock_timeout_should_call_singleFallbackCache_getAll()
        throws Exception {
        NotificationBo bo1 = sampleBo(1L);
        NotificationBo bo2 = sampleBo(2L);
        NotificationBo bo3 = sampleBo(3L);

        Set<String> rawIds = new LinkedHashSet<>();
        rawIds.add("1");
        rawIds.add("2");
        rawIds.add("3");
        when(zSetOps.reverseRange(eq(NotificationConstants.REDIS_RECENT_KEY), eq(0L), anyLong()))
            .thenReturn(rawIds);
        // 外層 cacheMultiGet：1 hit, 2/3 miss
        when(valueOps.multiGet(anyList())).thenReturn(java.util.Arrays.asList("{1}", null, null));
        when(objectMapper.readValue("{1}", NotificationBo.class)).thenReturn(bo1);

        // backfill 拿不到 lock → 走 loadFromSingleFallbackCache
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        com.github.benmanes.caffeine.cache.Cache<Long, Optional<NotificationBo>> syncView = mock(
            com.github.benmanes.caffeine.cache.Cache.class);
        when(singleFallbackCache.synchronous()).thenReturn(syncView);

        Map<Long, Optional<NotificationBo>> getAllResult = new LinkedHashMap<>();
        getAllResult.put(2L, Optional.of(bo2));
        getAllResult.put(3L, Optional.of(bo3));
        when(syncView.getAll(eq(List.of(2L, 3L)), any(Function.class))).thenReturn(getAllResult);

        List<NotificationBo> result = service.getRecent();

        assertThat(result).containsExactly(bo1, bo2, bo3);
        verify(singleFallbackCache, atLeastOnce()).synchronous();
        // self.dbFindByIds 不會被直接呼叫（getAll 已直接回結果）
        verify(self, never()).dbFindByIds(anyList());
    }

    @Test
    void batchLoadForFallback_internal_should_use_db_when_cache_miss() throws Exception {
        NotificationBo bo1 = sampleBo(1L);
        NotificationBo bo2 = sampleBo(2L);
        NotificationBo bo3 = sampleBo(3L);

        Set<String> rawIds = new LinkedHashSet<>();
        rawIds.add("1");
        rawIds.add("2");
        rawIds.add("3");
        when(zSetOps.reverseRange(eq(NotificationConstants.REDIS_RECENT_KEY), eq(0L), anyLong()))
            .thenReturn(rawIds);
        // 兩次 multiGet：第一次外層 cacheMultiGet 回 [hit, null, null]，第二次 loader 內 cacheMultiGet 回 [null, null]
        when(valueOps.multiGet(anyList())).thenReturn(
            java.util.Arrays.asList("{1}", null, null),
            java.util.Arrays.asList(null, null));
        when(objectMapper.readValue("{1}", NotificationBo.class)).thenReturn(bo1);

        // backfill 拿不到 lock
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        com.github.benmanes.caffeine.cache.Cache<Long, Optional<NotificationBo>> syncView = mock(
            com.github.benmanes.caffeine.cache.Cache.class);
        when(singleFallbackCache.synchronous()).thenReturn(syncView);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Function> loaderCaptor = ArgumentCaptor.forClass(Function.class);
        // getAll 回空（service 最終 result 不是測試重點，這裡只在意 loader）
        when(syncView.getAll(eq(List.of(2L, 3L)), loaderCaptor.capture()))
            .thenReturn(Map.of(2L, Optional.empty(), 3L, Optional.empty()));

        // loader 內走 dbFindByIds
        Map<Long, NotificationBo> dbResult = new LinkedHashMap<>();
        dbResult.put(2L, bo2);
        dbResult.put(3L, bo3);
        when(self.dbFindByIds(List.of(2L, 3L))).thenReturn(dbResult);

        service.getRecent();

        // 取出 loader 並手動 apply 模擬 caffeine 內部呼叫
        @SuppressWarnings("unchecked")
        Function<Iterable<? extends Long>, Map<Long, Optional<NotificationBo>>> loader =
            loaderCaptor.getValue();
        Set<Long> keys = new LinkedHashSet<>();
        keys.add(2L);
        keys.add(3L);
        Map<Long, Optional<NotificationBo>> loaderResult = loader.apply(keys);

        assertThat(loaderResult.get(2L)).contains(bo2);
        assertThat(loaderResult.get(3L)).contains(bo3);
        verify(self).dbFindByIds(List.of(2L, 3L));
    }

    // ==========================================================
    // 區塊 C: dbFindByIds LinkedHashMap merge (P1-5)
    // ==========================================================

    @Test
    void dbFindByIds_when_repository_returns_duplicate_ids_should_keep_first() {
        Notification entityA = new Notification();
        entityA.setId(1L);
        Notification entityB = new Notification();
        entityB.setId(1L);
        NotificationBo boA = sampleBo(1L);
        NotificationBo boB = sampleBo(1L);

        when(notificationRepository.findAllById(List.of(1L, 2L)))
            .thenReturn(List.of(entityA, entityB));
        when(notificationMapper.toBo(entityA)).thenReturn(boA);
        when(notificationMapper.toBo(entityB)).thenReturn(boB);

        Map<Long, NotificationBo> result = service.dbFindByIds(List.of(1L, 2L));

        // 第一個 entity 對應的 bo 勝出 (a, b) -> a
        assertThat(result).hasSize(1);
        assertThat(result.get(1L)).isSameAs(boA);
    }

    @Test
    void dbFindByIds_should_preserve_insertion_order_in_LinkedHashMap() {
        Notification e3 = new Notification();
        e3.setId(3L);
        Notification e1 = new Notification();
        e1.setId(1L);
        Notification e2 = new Notification();
        e2.setId(2L);
        NotificationBo bo3 = sampleBo(3L);
        NotificationBo bo1 = sampleBo(1L);
        NotificationBo bo2 = sampleBo(2L);

        when(notificationRepository.findAllById(List.of(3L, 1L, 2L)))
            .thenReturn(List.of(e3, e1, e2));
        when(notificationMapper.toBo(e3)).thenReturn(bo3);
        when(notificationMapper.toBo(e1)).thenReturn(bo1);
        when(notificationMapper.toBo(e2)).thenReturn(bo2);

        Map<Long, NotificationBo> result = service.dbFindByIds(List.of(3L, 1L, 2L));

        assertThat(result).containsExactly(
            Map.entry(3L, bo3), Map.entry(1L, bo1), Map.entry(2L, bo2));
    }

    @Test
    void dbFindByIds_when_ids_empty_should_return_empty_map_without_query() {
        Map<Long, NotificationBo> result = service.dbFindByIds(List.of());

        assertThat(result).isEmpty();
        verify(notificationRepository, never()).findAllById(anyList());
    }

    // ==========================================================
    // 區塊 D: cachePutMany pipeline session callback (P1-8)
    // ==========================================================

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void getRecent_when_backfill_db_hits_should_cachePutMany_with_per_id_set() throws Exception {
        NotificationBo bo1 = sampleBo(1L);
        NotificationBo bo2 = sampleBo(2L);

        Set<String> rawIds = new LinkedHashSet<>();
        rawIds.add("1");
        rawIds.add("2");
        when(zSetOps.reverseRange(eq(NotificationConstants.REDIS_RECENT_KEY), eq(0L), anyLong()))
            .thenReturn(rawIds);
        // 兩次 multiGet 都回 [hit, null] 讓 doBackfill 走 stillMissing=[2L]
        when(valueOps.multiGet(anyList())).thenReturn(
            java.util.Arrays.asList("{1}", null),
            java.util.Arrays.asList((String) null));
        when(objectMapper.readValue("{1}", NotificationBo.class)).thenReturn(bo1);

        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(self.dbFindByIds(List.of(2L))).thenReturn(Map.of(2L, bo2));
        when(objectMapper.writeValueAsString(bo2)).thenReturn("{2}");

        ArgumentCaptor<SessionCallback> callbackCaptor = ArgumentCaptor.forClass(
            SessionCallback.class);

        service.getRecent();

        verify(redisTemplate).executePipelined(callbackCaptor.capture());

        // 取出 callback 並注入 mock RedisOperations 驗證 set 行為
        SessionCallback<?> callback = callbackCaptor.getValue();
        RedisOperations mockOps = mock(RedisOperations.class);
        ValueOperations mockValueOps = mock(ValueOperations.class);
        when(mockOps.opsForValue()).thenReturn(mockValueOps);

        callback.execute(mockOps);

        verify(mockValueOps, times(1)).set(eq("notification:2"), eq("{2}"),
            eq(NotificationConstants.REDIS_SINGLE_TTL));
    }

    // ==========================================================
    // 區塊 E: getRecent reverseRange 回 null 分支 (P1-9)
    // ==========================================================

    @Test
    void getRecent_when_zset_reverseRange_returns_null_should_treat_as_empty_and_rebuild()
        throws Exception {
        // reverseRange 回 null（注意：不是 empty Set）
        when(zSetOps.reverseRange(eq(NotificationConstants.REDIS_RECENT_KEY), eq(0L), anyLong()))
            .thenReturn(null);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(self.dbFindRecent(NotificationConstants.RECENT_MAX_SIZE)).thenReturn(List.of());

        List<NotificationBo> result = service.getRecent();

        assertThat(result).isEmpty();
        // 證明走了 rebuild 路徑而不是 NPE
        verify(self).dbFindRecent(NotificationConstants.RECENT_MAX_SIZE);
    }

    // ==========================================================
    // 區塊 F: backfillWithCacheBreakdownLock DataAccessException catch (P1-?)
    // ==========================================================

    @Test
    void getRecent_when_backfill_db_throws_DataAccessException_should_return_partial_cache_hits()
        throws Exception {
        NotificationBo bo1 = sampleBo(1L);

        // zSet 回三個 ids
        Set<String> rawIds = new LinkedHashSet<>();
        rawIds.add("1");
        rawIds.add("2");
        rawIds.add("3");
        when(zSetOps.reverseRange(eq(NotificationConstants.REDIS_RECENT_KEY), eq(0L), anyLong()))
            .thenReturn(rawIds);

        // 第一次 multiGet：id=1 hit, 2/3 miss → 進 backfill
        // 第二次 multiGet（doBackfill 雙檢）：still miss → 走 dbFindByIds
        when(valueOps.multiGet(anyList())).thenReturn(
            java.util.Arrays.asList("{1}", null, null),
            java.util.Arrays.asList(null, null));
        when(objectMapper.readValue("{1}", NotificationBo.class)).thenReturn(bo1);

        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        // dbFindByIds 拋 DataAccessException 子類，觸發 catch (DataAccessException)
        when(self.dbFindByIds(eq(List.of(2L, 3L))))
            .thenThrow(new QueryTimeoutException("DB timeout"));

        List<NotificationBo> result = service.getRecent();

        // 只剩 cache hit 的 bo1
        assertThat(result).containsExactly(bo1);
        assertThat(result).hasSize(1);

        // 確認真的進 doBackfill
        verify(self, times(1)).dbFindByIds(List.of(2L, 3L));
        // 確認 finally 釋放 lock
        verify(rLock).unlock();
    }
}
