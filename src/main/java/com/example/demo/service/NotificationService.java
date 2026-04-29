package com.example.demo.service;

import com.example.demo.bo.NotificationBo;
import com.example.demo.bo.UpdateNotificationBo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 通知模組對外契約：合併原 NotificationFacade（編排）、NotificationCacheService（Redis L2）、
 * NotificationPersistenceService（DB）。所有 cache 寫入降為實作內 private method，
 * 鎖（cache breakdown / recent rebuild）、cache、DB 三段協作落在同一 unit-of-work。
 *
 * 對外 use-case 一律進出 BO，VO ↔ BO 由 controller 層處理；service 與 HTTP 表現層解耦。
 *
 * dbXxx 系列方法為 internal — 僅供實作內 self-invocation 觸發 @Transactional proxy 使用，
 * 外部 caller 一律走上面 5 個 use-case method。
 */
public interface NotificationService {

    NotificationBo create(NotificationBo bo);

    Optional<NotificationBo> findById(Long id);

    List<NotificationBo> getRecent();

    Optional<NotificationBo> update(Long id, UpdateNotificationBo updateBo);

    boolean delete(Long id);

    NotificationBo dbCreate(NotificationBo bo);

    Optional<NotificationBo> dbFindById(Long id);

    Map<Long, NotificationBo> dbFindByIds(List<Long> ids);

    List<NotificationBo> dbFindRecent(int limit);

    Optional<NotificationBo> dbUpdate(Long id, UpdateNotificationBo updateBo);

    boolean dbDelete(Long id);
}
