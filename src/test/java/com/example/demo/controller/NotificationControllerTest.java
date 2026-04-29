package com.example.demo.controller;

import com.example.demo.bo.NotificationBo;
import com.example.demo.bo.UpdateNotificationBo;
import com.example.demo.enums.NotificationType;
import com.example.demo.exception.GlobalExceptionHandler;
import com.example.demo.mapper.NotificationControllerMapperImpl;
import com.example.demo.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@Import({GlobalExceptionHandler.class, NotificationControllerMapperImpl.class})
class NotificationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean NotificationService facade;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private NotificationBo sampleBo(Long id) {
        return new NotificationBo(
                id,
                NotificationType.EMAIL,
                "a@b.com",
                "subj",
                "body",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void post_should_return_201_with_body() throws Exception {
        when(facade.create(any(NotificationBo.class))).thenReturn(sampleBo(1L));
        String body = """
                {"type":"email","recipient":"a@b.com","subject":"subj","content":"body"}
                """;

        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.type").value("EMAIL"));
    }

    @Test
    void post_should_return_400_when_validation_fails() throws Exception {
        String body = """
                {"type":"email"}
                """;
        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists());
    }

    @Test
    void getById_should_return_200() throws Exception {
        when(facade.findById(1L)).thenReturn(Optional.of(sampleBo(1L)));

        mockMvc.perform(get("/notifications/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void getById_should_return_404_when_not_found() throws Exception {
        when(facade.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/notifications/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Notification not found: 99"))
                .andExpect(jsonPath("$.path").value("/notifications/99"));
    }

    @Test
    void getRecent_should_return_200_with_array() throws Exception {
        when(facade.getRecent()).thenReturn(List.of(sampleBo(1L), sampleBo(2L)));

        mockMvc.perform(get("/notifications/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void put_should_return_200_with_updated_body() throws Exception {
        when(facade.update(eq(1L), any(UpdateNotificationBo.class)))
                .thenReturn(Optional.of(sampleBo(1L)));
        String body = """
                {"subject":"s","content":"c"}
                """;
        mockMvc.perform(put("/notifications/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void put_should_return_404_when_not_found() throws Exception {
        when(facade.update(eq(99L), any())).thenReturn(Optional.empty());
        String body = """
                {"subject":"s","content":"c"}
                """;
        mockMvc.perform(put("/notifications/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Notification not found: 99"));
    }

    @Test
    void put_should_return_400_when_validation_fails() throws Exception {
        String body = """
                {"subject":"","content":""}
                """;
        mockMvc.perform(put("/notifications/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists());
    }

    @Test
    void delete_should_return_204() throws Exception {
        when(facade.delete(1L)).thenReturn(true);

        mockMvc.perform(delete("/notifications/1"))
                .andExpect(status().isNoContent());
        verify(facade).delete(1L);
    }

    @Test
    void delete_should_return_404_when_not_found() throws Exception {
        when(facade.delete(99L)).thenReturn(false);

        mockMvc.perform(delete("/notifications/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Notification not found: 99"));
    }
}
