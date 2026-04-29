package com.example.demo.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateNotificationRequest {

    @NotBlank(message = "Subject cannot be blank")
    @Size(max = 255, message = "Subject cannot be longer than 255 characters")
    private String subject;
    @NotBlank(message = "Content cannot be blank")
    @Size(max = 2000, message = "Content cannot be longer than 2000 characters")
    private String content;
}
