package com.example.demo.mapper;

import com.example.demo.bo.NotificationBo;
import com.example.demo.bo.UpdateNotificationBo;
import com.example.demo.vo.CreateNotificationRequest;
import com.example.demo.vo.NotificationResponse;
import com.example.demo.vo.UpdateNotificationRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring")
public interface NotificationControllerMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    NotificationBo toBo(CreateNotificationRequest request);

    UpdateNotificationBo toUpdateBo(UpdateNotificationRequest request);

    NotificationResponse toResponse(NotificationBo bo);
}
