CREATE TABLE IF NOT EXISTS notifications (
    id          BIGINT        NOT NULL AUTO_INCREMENT  COMMENT '通知唯一識別碼，自動遞增',
    type        VARCHAR(16)   NOT NULL                 COMMENT '通知類型：EMAIL 或 SMS',
    recipient   VARCHAR(255)  NOT NULL                 COMMENT '收件人（email 地址或手機號碼）',
    subject     VARCHAR(255)  NOT NULL                 COMMENT '通知主旨/標題',
    content     VARCHAR(2000) NOT NULL                 COMMENT '通知內容本文，最長 2000 字',
    created_at  DATETIME(6)   NOT NULL  DEFAULT CURRENT_TIMESTAMP(6)                                COMMENT '建立時間',
    updated_at  DATETIME(6)   NOT NULL  DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '最後更新時間',
    PRIMARY KEY (id),
    KEY idx_notifications_created_at (created_at DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '通知服務主表';
