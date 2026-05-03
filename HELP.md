# HELP.md — Notification Service 操作指南

## 環境啟動

```bash
# 1. 啟動所有基礎服務（MySQL / Redis / RocketMQ namesrv / broker / console）
docker compose up -d

# 2. 確認服務狀態（5 個容器全部 running）
docker compose ps

# 3. 啟動 Spring Boot 應用
./mvnw spring-boot:run
```

`init.sql` 會在 MySQL 容器首次啟動時自動執行，建立 `notifications` 表。

---

## 服務埠口

| 服務 | URL / 連線 | 用途 |
|---|---|---|
| Spring Boot 應用 | `http://localhost:8080` | REST API |
| MySQL | `localhost:3306` | DB（taskuser / taskpass / taskdb） |
| Redis | `localhost:6379` | 快取 |
| RocketMQ NameServer | `localhost:9876` | 訊息路由註冊中心 |
| RocketMQ Console | `http://localhost:8088` | RocketMQ 管理介面（topic / consumer 監控） |

---

## API 範例

### 1. 建立通知 (POST)

```bash
curl -i -X POST http://localhost:8080/notifications \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "email",
    "recipient": "alice@example.com",
    "subject": "Welcome",
    "content": "Hi Alice"
  }'
# → 201 Created, body: NotificationResponse JSON
```

### 2. 取得單筆通知 (GET by id)

```bash
curl http://localhost:8080/notifications/1
# → 200 OK, 第一次從 DB 載入並寫入 Redis；第二次直接 Redis 命中
# → 404 Not Found（id 不存在）
```

### 3. 取得最近 10 筆 (GET recent)

```bash
curl http://localhost:8080/notifications/recent
# → 200 OK, 陣列依 createdAt 倒序，最多 10 筆
```

### 4. 更新通知 (PUT)

```bash
curl -i -X PUT http://localhost:8080/notifications/1 \
  -H 'Content-Type: application/json' \
  -d '{"subject":"Updated subject","content":"Updated body"}'
# → 200 OK，Redis 單筆 cache 失效（下次 GET 自動回填）
# → 404 Not Found（id 不存在）
```

### 5. 刪除通知 (DELETE)

```bash
curl -i -X DELETE http://localhost:8080/notifications/1
# → 204 No Content，Redis 同步移除
# → 404 Not Found（id 不存在）
```

### 錯誤情境

```bash
# 缺必要欄位 → 400 + fieldErrors
curl -i -X POST http://localhost:8080/notifications \
  -H 'Content-Type: application/json' -d '{"type":"email"}'

# 路由不匹配 → 404
curl -i http://localhost:8080/notifications/abc
```

---

## 內部驗證指令

### MySQL

```bash
docker exec -it mysql mysql -utaskuser -ptaskpass taskdb
> SELECT id, type, recipient, subject, created_at, updated_at FROM notifications;
> SELECT COUNT(*) FROM notifications;
```

### Redis

```bash
docker exec -it redis redis-cli
> KEYS notification:*
> ZREVRANGE notifications:recent 0 -1 WITHSCORES
> GET notification:1
> TTL notification:1
```

| Key | 型別 | 內容 |
|---|---|---|
| `notification:{id}` | String (JSON) | 完整 Notification，TTL 1 小時 |
| `notifications:recent` | Sorted Set | member = id 字串、score = createdAt epoch ms。內部 buffer 保留最新 20 筆，`GET /notifications/recent` 對外回傳前 10 筆 |
| `notifications:recent:exhausted` | String | DB 已耗盡旗標，TTL 60s。delete 後 buffer 不足時若旗標存在則跳過 refill |

### RocketMQ

- Console UI：`http://localhost:8088`
  - Topic：`notification-topic`（訊息計數）
  - Consumer：`notification-consumer-group`（線上消費者）
- 應用 console log：每筆 POST 由 `NotificationProducer` 異步送訊，consumer 端在 `NotificationConsumer.onMessage` 收到後輸出 `notification received (dispatcher not yet implemented) NotificationMessage=...`（實際派發邏輯尚未實作，僅作為 MQ 鏈路驗證）

---

## 測試

```bash
./mvnw test
```

涵蓋的 test class（皆為純 unit / Mockito，無需啟動 Redis/MySQL/RocketMQ）：

| 類別 | 範圍 |
|---|---|
| `NotificationControllerTest` | REST 層 5 支 endpoint、validation、404 路徑 |
| `NotificationServiceImplTest` | service 主流程：cache aside、分散式鎖、雙刪、refill 訊號 |
| `NotificationServiceMapperTest` | MapStruct entity ↔ bo ↔ message 對映 |
| `NotificationProducerTest` | RocketMQTemplate 異步送訊與 callback 行為 |
| `NotificationConsumerTest` | consumer onMessage log 行為 |
| `GlobalExceptionHandlerTest` | 各類例外 → HTTP status / body 對映 |
| `NotificationTypeTest` | enum 反序列化大小寫包容 |

---

## 快取架構

```
[App Request]
    ↓
[Redis]                 single key TTL 1h（notification:{id}）
                        recent ZSet 由 Lua 原子 SET+ZADD+ZREMRANGEBYRANK 維護，buffer 20 筆
    ↓ miss              Redisson 分散式鎖 (lock:notification:{id} / lock:notifications:recent:rebuild)
                        防 cache breakdown，鎖內 double-check 後查 DB 並回填
[MySQL]                 Source of Truth
```

### Cache breakdown 防護

- **單筆 (`findById`)**：cache miss 時走 `loadWithCacheBreakdownLock`，用 Redisson `RLock` 對同 id 序列化 DB 查詢；鎖內先 double-check Redis，避免重複回填。
- **列表 (`getRecent`)**：cache 為空時走 `rebuildWithSingleFlight`，用 `lock:notifications:recent:rebuild` 鎖一次重建 ZSet。
- **批次回填 (`backfillWithCacheBreakdownLock`)**：ZSet 命中但部分 single key cache miss 時，用 `lock:notifications:recent:backfill` 序列化批次 DB 查詢與 pipeline 寫回。

### Caffeine fallback（degraded mode 才啟用）

`CacheConfig` 提供兩個 Caffeine cache，**只在以下兩種情況才會被走到**：

| Cache bean | 對應情境 | TTL / Size |
|---|---|---|
| `singleFallbackCache` (AsyncCache) | Redisson `tryLock` timeout 或 Redis 拋 `RedisException` 時，對單筆 GET 與 backfill 提供 process-local single-flight + per-key 結果暫存 | TTL 5s / max 1000 |
| `recentFallbackCache` | `getRecent` 重建鎖 timeout 或 Redis 故障時，對 recent id 列表提供 process-local single-flight | TTL 5s / max 2 |

這兩個 cache 不在主路徑上；正常 Redis 可用時不會被讀寫。設計意圖是**單實例層級的 thundering herd 防護**，避免 Redis 故障窗口內所有 thread 同時打 DB。

### Cache 一致性策略（寫路徑）

- **POST**：DB insert → Lua 原子 `SET single + ZADD recent + ZREMRANGEBYRANK` → 異步發 RocketMQ。任何 cache 寫失敗只 log，不回滾 DB。
- **PUT / DELETE**：採延遲雙刪 — `cacheEvictSingle` 同步刪 → DB 寫 → `delayedEvictScheduler` 延遲 1.5s 再刪一次，緩解「寫 DB 期間有 GET 把舊值寫回 Redis」的 race。
- **DELETE 額外**：`cacheRemoveFromRecent` Lua 在 `ZREM` 後回傳訊號（buffer 仍足 / DB 已耗盡 / 需 refill），訊號 ≥ 0 時觸發異步 `refillRecent` 重建 ZSet 至 20 筆 buffer。
- **Source of Truth**：所有 cache 異常路徑（Redis down / lock timeout / 序列化失敗）一律降級到 DB，永不回傳髒值。
