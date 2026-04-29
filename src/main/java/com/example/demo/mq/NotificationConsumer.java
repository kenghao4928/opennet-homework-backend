package com.example.demo.mq;

import com.example.demo.dto.NotificationMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RocketMQMessageListener(topic = "${notification.messaging.notification.topic}", consumerGroup = "${notification.messaging.notification.consumer-group}")
public class NotificationConsumer implements RocketMQListener<NotificationMessage> {

    @Override
    public void onMessage(NotificationMessage msg) {
        log.info("notification received (dispatcher not yet implemented) NotificationMessage={} ",
            msg);
    }
}
