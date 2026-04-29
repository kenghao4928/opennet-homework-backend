package com.example.demo.mapper;

import com.example.demo.bo.NotificationBo;
import com.example.demo.bo.UpdateNotificationBo;
import com.example.demo.dto.NotificationMessage;
import com.example.demo.entity.Notification;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * Service 層 mapper：Entity ↔ BO 與 BO → MQ DTO。
 * unmappedTargetPolicy = ERROR：entity/BO 新增欄位但忘了同步 mapping 時會在編譯期報錯，
 * 避免欄位默默變 null。確實無對應的 target 欄位（id、timestamps 等）需顯式 @Mapping(ignore=true)。
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface NotificationServiceMapper {

    NotificationBo toBo(Notification entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Notification toEntity(NotificationBo bo);

    NotificationMessage toMessage(NotificationBo bo);

    /**
     * Partial update：仰賴 Hibernate dirty checking 直接套用部分欄位到 managed entity 上。
     * id/type/recipient/timestamps 一律不可被 update 覆寫；NULL 欄位忽略以支援只傳部分欄位的 PATCH-like 更新。
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "recipient", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntity(UpdateNotificationBo updateBo, @MappingTarget Notification entity);
}
