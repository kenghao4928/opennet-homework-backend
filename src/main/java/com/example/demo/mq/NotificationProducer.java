package com.example.demo.mq;

import com.example.demo.dto.NotificationMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class NotificationProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final String topic;

    public NotificationProducer(RocketMQTemplate rocketMQTemplate,
        @Value("${notification.messaging.notification.topic}") String topic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.topic = topic;
    }

    public void send(NotificationMessage message) {
        rocketMQTemplate.asyncSend(topic, MessageBuilder.withPayload(message).build(),
            new SendCallback() {
                @Override
                public void onSuccess(SendResult result) {
                    log.debug("notification push sent: id={} status={} msgId={}", message.getId(),
                        result.getSendStatus(), result.getMsgId());
                }

                @Override
                public void onException(Throwable e) {
                    log.warn("notification push failed: id={}", message.getId(), e);
                }
            });
    }
}
