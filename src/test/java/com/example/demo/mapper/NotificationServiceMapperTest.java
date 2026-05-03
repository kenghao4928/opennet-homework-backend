package com.example.demo.mapper;

import com.example.demo.bo.NotificationBo;
import com.example.demo.bo.UpdateNotificationBo;
import com.example.demo.dto.NotificationMessage;
import com.example.demo.entity.Notification;
import com.example.demo.enums.NotificationType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationServiceMapperTest {

    private final NotificationServiceMapper mapper = new NotificationServiceMapperImpl();

    @Test
    void toBo_should_copy_all_fields() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T00:00:00Z");
        Notification entity = Notification.builder()
                .id(1L)
                .type(NotificationType.EMAIL)
                .recipient("user@example.com")
                .subject("subject")
                .content("content")
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();

        NotificationBo bo = mapper.toBo(entity);

        assertThat(bo).isNotNull();
        assertThat(bo.getId()).isEqualTo(1L);
        assertThat(bo.getType()).isEqualTo(NotificationType.EMAIL);
        assertThat(bo.getRecipient()).isEqualTo("user@example.com");
        assertThat(bo.getSubject()).isEqualTo("subject");
        assertThat(bo.getContent()).isEqualTo("content");
        assertThat(bo.getCreatedAt()).isEqualTo(createdAt);
        assertThat(bo.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void toEntity_should_copy_business_fields_and_ignore_id_timestamps() {
        // id / createdAt / updatedAt 應由 JPA 管理，mapper 必須忽略
        NotificationBo bo = NotificationBo.builder()
                .id(99L)
                .type(NotificationType.SMS)
                .recipient("0912345678")
                .subject("subject")
                .content("content")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-02T00:00:00Z"))
                .build();

        Notification entity = mapper.toEntity(bo);

        assertThat(entity).isNotNull();
        assertThat(entity.getType()).isEqualTo(NotificationType.SMS);
        assertThat(entity.getRecipient()).isEqualTo("0912345678");
        assertThat(entity.getSubject()).isEqualTo("subject");
        assertThat(entity.getContent()).isEqualTo("content");
        assertThat(entity.getId()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
    }

    @Test
    void toMessage_should_copy_six_fields_without_updatedAt() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        NotificationBo bo = NotificationBo.builder()
                .id(7L)
                .type(NotificationType.EMAIL)
                .recipient("user@example.com")
                .subject("subject")
                .content("content")
                .createdAt(createdAt)
                .updatedAt(Instant.parse("2026-01-02T00:00:00Z"))
                .build();

        NotificationMessage message = mapper.toMessage(bo);

        assertThat(message).isNotNull();
        assertThat(message.getId()).isEqualTo(7L);
        assertThat(message.getType()).isEqualTo(NotificationType.EMAIL);
        assertThat(message.getRecipient()).isEqualTo("user@example.com");
        assertThat(message.getSubject()).isEqualTo("subject");
        assertThat(message.getContent()).isEqualTo("content");
        assertThat(message.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void updateEntity_when_subject_null_should_only_update_content() {
        Notification entity = Notification.builder()
                .id(1L)
                .type(NotificationType.EMAIL)
                .recipient("r")
                .subject("old-s")
                .content("old-c")
                .build();
        UpdateNotificationBo bo = UpdateNotificationBo.builder()
                .subject(null)
                .content("new-c")
                .build();

        mapper.updateEntity(bo, entity);

        assertThat(entity.getSubject()).isEqualTo("old-s");
        assertThat(entity.getContent()).isEqualTo("new-c");
        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getType()).isEqualTo(NotificationType.EMAIL);
        assertThat(entity.getRecipient()).isEqualTo("r");
    }

    @Test
    void updateEntity_when_content_null_should_only_update_subject() {
        Notification entity = Notification.builder()
                .id(1L)
                .type(NotificationType.EMAIL)
                .recipient("r")
                .subject("old-s")
                .content("old-c")
                .build();
        UpdateNotificationBo bo = UpdateNotificationBo.builder()
                .subject("new-s")
                .content(null)
                .build();

        mapper.updateEntity(bo, entity);

        assertThat(entity.getSubject()).isEqualTo("new-s");
        assertThat(entity.getContent()).isEqualTo("old-c");
        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getType()).isEqualTo(NotificationType.EMAIL);
        assertThat(entity.getRecipient()).isEqualTo("r");
    }

    @Test
    void updateEntity_when_both_null_should_not_change_anything() {
        Notification entity = Notification.builder()
                .id(1L)
                .type(NotificationType.EMAIL)
                .recipient("r")
                .subject("old-s")
                .content("old-c")
                .build();
        UpdateNotificationBo bo = UpdateNotificationBo.builder()
                .subject(null)
                .content(null)
                .build();

        mapper.updateEntity(bo, entity);

        assertThat(entity.getSubject()).isEqualTo("old-s");
        assertThat(entity.getContent()).isEqualTo("old-c");
        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getType()).isEqualTo(NotificationType.EMAIL);
        assertThat(entity.getRecipient()).isEqualTo("r");
    }

    @Test
    void updateEntity_when_both_present_should_update_both() {
        Notification entity = Notification.builder()
                .id(1L)
                .type(NotificationType.EMAIL)
                .recipient("r")
                .subject("old-s")
                .content("old-c")
                .build();
        UpdateNotificationBo bo = UpdateNotificationBo.builder()
                .subject("new-s")
                .content("new-c")
                .build();

        mapper.updateEntity(bo, entity);

        assertThat(entity.getSubject()).isEqualTo("new-s");
        assertThat(entity.getContent()).isEqualTo("new-c");
        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getType()).isEqualTo(NotificationType.EMAIL);
        assertThat(entity.getRecipient()).isEqualTo("r");
    }
}
