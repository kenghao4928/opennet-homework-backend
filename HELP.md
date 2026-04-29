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
| `notifications:recent` | Sorted Set | member = id 字串、score = createdAt epoch ms（保留最新 10 筆） |

### RocketMQ

- Console UI：`http://localhost:8088`
  - Topic：`notification-topic`（訊息計數）
  - Consumer：`notification-consumer-group`（線上消費者）
- 應用 console log：每筆 POST 對應一行 `[EMAIL] -> ...` 或 `[SMS] -> ...`

---

## 測試

```bash
./mvnw test
# 執行 NotificationServiceTest（12 case）+ NotificationControllerTest（10 case）
# DemoApplicationTests 已 @Disabled（避免 CI 缺 Redis/MySQL/RocketMQ 失敗）
```

---

## 快取分層架構

```
[App Request]
    ↓
[Caffeine L1]   process-local，TTL 30s（單筆）/ 10s（recent 列表）
    ↓ miss     Cache.get(key, loader) 內建 single-flight
[Redis L2]      distributed，single key TTL 1h、recent ZSet 不過期
    ↓ miss     ZSet 含引號 JSON member、Lua 原子 SET+ZADD+TRIM
[MySQL]         Source of Truth
```

### 為何分層

- **Caffeine 防 Cache Breakdown**：`Cache.get(key, loader)` 對同 key 並發只執行一次 loader。100 並發 GET 同 id 在 cache miss 時，DB 只被打 1 次（並發測試已驗證）。
- **Redis 提供跨實例共享**：避免每個實例都從 DB 重建 cache。
- **DB 是 SOT**：所有 cache 失效時降級為直查 DB，永遠正確。

## 跨實例 Cache 主動同步

寫路徑 (POST/PUT/DELETE) commit 後，會發 RocketMQ 廣播訊息到 `notification-cache-sync` topic。所有 instance 的 `CacheSyncConsumer`（BROADCASTING 模式）收到訊息後：

1. 從 DB reload 該 id 最新值（CREATED/UPDATED）；DELETED 直接淘汰
2. 異步執行（獨立 ExecutorService，不阻塞 RocketMQ consumer thread pool）
3. 更新自己的 Caffeine + Redis（idempotent）

效果：**跨實例不一致從 30 秒（TTL 兜底）縮短到 < 1 秒（廣播延遲）**。MQ 故障時退化為 30 秒 TTL 兌現作為 ultimate safety net。

訊息發送在 `TransactionSynchronization.afterCommit` 階段觸發，確保 consumer reload 時 DB 已 commit。

### Consumer Group 命名

`notification-cache-sync-${random.uuid}` — 每個 instance 獨立 group，避免 cluster 模式分流（BROADCASTING 模式下每 instance 都需要收到所有訊息）。

## 設定備註

### MySQL Server 時區

`docker-compose.yaml` 為 mysql service 加了 `TZ: UTC`，確保 `CURRENT_TIMESTAMP(6)` 是 UTC。
搭配 `application.yaml` 中的 `serverTimezone=UTC` + `hibernate.jdbc.time_zone: UTC`，全鏈路時間一律 UTC。
API 回應的 `createdAt / updatedAt` 為 ISO-8601 帶 `Z` 後綴。

### RocketMQ Broker 對外 IP

`broker.conf` 中 `brokerIP1 = 127.0.0.1`：broker 將自己的位址註冊為 `127.0.0.1:10911`，讓 host 上的 Spring Boot 應用能連到 broker（否則 broker 會註冊成 docker 網路內部 IP，連不到）。

### Hibernate `@Generated`

`Notification` entity 的 `createdAt / updatedAt` 由 DB 端 `DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)` 自動填入；entity 用 `@Generated(event = ...)` 在 INSERT/UPDATE 後自動 SELECT 取回 DB 產生的值。

---

## 常見問題

| 症狀 | 原因 | 解法 |
|---|---|---|
| `connect to 192.168.x.x:10911 failed` | Broker 註冊 docker 內網 IP | 確認 `broker.conf` 含 `brokerIP1 = 127.0.0.1`，重啟 broker（`docker compose restart rocketmq-broker`） |
| `Schema-validation: missing column` | init.sql 未執行 | 刪 mysql volume 重建：`docker compose down -v && docker compose up -d` |
| Spring Boot 啟動失敗 connection refused | 容器尚未 ready | `docker compose ps` 等到 mysql/namesrv 顯示 `healthy` 再啟動 |
| 8080 已被占用 | 前次未停乾淨 | `lsof -ti:8080 \| xargs kill -9` |
