package com.example.demo.mapper;

import com.example.demo.bo.NotificationBo;
import com.example.demo.bo.UpdateNotificationBo;
import com.example.demo.vo.CreateNotificationRequest;
import com.example.demo.vo.NotificationResponse;
import com.example.demo.vo.UpdateNotificationRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Controller 層 mapper：純粹 VO ↔ BO 雙向轉換。
 * unmappedTargetPolicy = ERROR：VO/BO 任一邊新增欄位但忘了同步時會在編譯期報錯。
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface NotificationControllerMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    NotificationBo toBo(CreateNotificationRequest request);

    UpdateNotificationBo toUpdateBo(UpdateNotificationRequest request);

    NotificationResponse toResponse(NotificationBo bo);
}
