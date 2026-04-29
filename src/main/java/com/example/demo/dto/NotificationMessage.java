package com.example.demo.dto;

import com.example.demo.enums.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMessage implements Serializable {

    private Long id;
    private NotificationType type;
    private String recipient;
    private String subject;
    private String content;
    private Instant createdAt;
}
