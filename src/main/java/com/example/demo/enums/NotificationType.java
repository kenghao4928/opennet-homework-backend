package com.example.demo.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum NotificationType {
    EMAIL,
    SMS;

    @JsonCreator
    public static NotificationType fromString(String value) {
        return value == null ? null : NotificationType.valueOf(value.toUpperCase());
    }
}
