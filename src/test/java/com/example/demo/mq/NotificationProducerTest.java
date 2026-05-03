package com.example.demo.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.demo.dto.NotificationMessage;
import com.example.demo.enums.NotificationType;
import java.time.Instant;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;

@ExtendWith(MockitoExtension.class)
class NotificationProducerTest {

    @Mock RocketMQTemplate rocketMQTemplate;

    NotificationProducer producer;

    private ListAppender<ILoggingEvent> appender;
    private Logger producerLogger;
    private Level originalLevel;

    @BeforeEach
    void setup() {
        producer = new NotificationProducer(rocketMQTemplate, "test-topic");

        producerLogger = (Logger) LoggerFactory.getLogger(NotificationProducer.class);
        originalLevel = producerLogger.getLevel();
        producerLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        producerLogger.addAppender(appender);
    }

    @AfterEach
    void teardownLogCapture() {
        producerLogger.detachAppender(appender);
        producerLogger.setLevel(originalLevel);
    }

    private NotificationMessage sampleMessage() {
        return new NotificationMessage(1L, NotificationType.EMAIL, "a@b.com", "subj", "body",
            Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void send_should_invoke_asyncSend_with_configured_topic() {
        producer.send(sampleMessage());

        verify(rocketMQTemplate).asyncSend(eq("test-topic"), any(Message.class),
            any(SendCallback.class));
    }

    @Test
    void onSuccess_callback_should_log_debug_with_msgId_and_status() {
        producer.send(sampleMessage());

        ArgumentCaptor<SendCallback> captor = ArgumentCaptor.forClass(SendCallback.class);
        verify(rocketMQTemplate).asyncSend(any(String.class), any(Message.class), captor.capture());

        SendResult result = mock(SendResult.class);
        when(result.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(result.getMsgId()).thenReturn("msg-123");

        // 只驗證 callback 觸發後產生的 log，避免 send() 期間其他 log 干擾
        appender.list.clear();
        captor.getValue().onSuccess(result);

        assertThat(appender.list).isNotEmpty();
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(event.getFormattedMessage())
            .contains("notification push sent")
            .contains("msg-123")
            .contains("SEND_OK")
            .contains("id=1");
    }

    @Test
    void onException_callback_should_log_warn_with_throwable_and_id() {
        producer.send(sampleMessage());

        ArgumentCaptor<SendCallback> captor = ArgumentCaptor.forClass(SendCallback.class);
        verify(rocketMQTemplate).asyncSend(any(String.class), any(Message.class), captor.capture());

        appender.list.clear();
        captor.getValue().onException(new RuntimeException("broker timeout"));

        assertThat(appender.list).isNotEmpty();
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
            .contains("notification push failed")
            .contains("id=1");
        // 關鍵：證明 throwable 真的有被傳給 logger（防止有人改成 log.warn(msg) 漏掉 e）
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(event.getThrowableProxy().getMessage()).contains("broker timeout");
    }
}
