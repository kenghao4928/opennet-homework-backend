package com.example.demo.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationTypeTest {

    @Test
    void fromString_when_null_should_return_null() {
        assertThat(NotificationType.fromString(null)).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "email, EMAIL",
            "sms,   SMS"
    })
    void fromString_when_lowercase_should_return_enum(String input, NotificationType expected) {
        assertThat(NotificationType.fromString(input)).isEqualTo(expected);
    }

    @Test
    void fromString_when_uppercase_should_return_enum() {
        assertThat(NotificationType.fromString("EMAIL")).isEqualTo(NotificationType.EMAIL);
    }

    @Test
    void fromString_when_mixed_case_should_return_enum() {
        assertThat(NotificationType.fromString("EmAiL")).isEqualTo(NotificationType.EMAIL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown"})
    void fromString_when_unknown_value_should_throw_IllegalArgumentException(String input) {
        assertThatThrownBy(() -> NotificationType.fromString(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromString_when_empty_string_should_throw_IllegalArgumentException() {
        assertThatThrownBy(() -> NotificationType.fromString(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
