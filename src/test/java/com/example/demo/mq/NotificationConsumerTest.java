package com.example.demo.mq;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.demo.dto.NotificationMessage;
import com.example.demo.enums.NotificationType;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationConsumerTest {

    private NotificationConsumer consumer;

    @BeforeEach
    void setup() {
        consumer = new NotificationConsumer();
    }

    @Test
    void onMessage_should_not_throw_for_valid_message() {
        NotificationMessage msg = NotificationMessage.builder()
            .id(1L)
            .type(NotificationType.EMAIL)
            .recipient("a@b.com")
            .subject("x")
            .content("y")
            .createdAt(Instant.now())
            .build();

        assertThatCode(() -> consumer.onMessage(msg)).doesNotThrowAnyException();
    }

    @Test
    void onMessage_should_not_throw_for_null_message() {
        // 契約點：消費者收到 null payload 時不應該炸（避免 RocketMQ retry 死循環）
        assertThatCode(() -> consumer.onMessage(null)).doesNotThrowAnyException();
    }
}
