package com.example.demo.exception;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.controller.NotificationController;
import com.example.demo.mapper.NotificationControllerMapperImpl;
import com.example.demo.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
@Import({GlobalExceptionHandler.class, NotificationControllerMapperImpl.class})
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mockMvc;
    @MockBean NotificationService notificationService;

    @Test
    void redis_connection_failure_should_return_503() throws Exception {
        when(notificationService.findById(anyLong()))
            .thenThrow(new RedisConnectionFailureException("redis down"));

        mockMvc.perform(get("/notifications/1"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value(503))
            .andExpect(jsonPath("$.message").value("Cache service temporarily unavailable"));
    }

    @Test
    void redisson_exception_should_return_503() throws Exception {
        when(notificationService.findById(anyLong()))
            .thenThrow(new RedisException("redisson down"));

        mockMvc.perform(get("/notifications/1"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void data_integrity_violation_should_return_409() throws Exception {
        when(notificationService.create(any()))
            .thenThrow(new DataIntegrityViolationException("dup key"));

        String body = """
            {"type":"email","recipient":"a@b.com","subject":"s","content":"c"}
            """;
        mockMvc.perform(post("/notifications").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Data integrity violation"));
    }

    @Test
    void optimistic_lock_failure_should_return_409() throws Exception {
        when(notificationService.delete(anyLong()))
            .thenThrow(new OptimisticLockingFailureException("concurrent update"));

        mockMvc.perform(delete("/notifications/1"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Resource was modified by another transaction"));
    }

    @Test
    void data_access_resource_failure_should_return_503() throws Exception {
        when(notificationService.findById(anyLong()))
            .thenThrow(new DataAccessResourceFailureException("db down"));

        mockMvc.perform(get("/notifications/1"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message").value("Data source temporarily unavailable"));
    }

    @Test
    void unexpected_exception_should_return_500() throws Exception {
        when(notificationService.findById(anyLong()))
            .thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(get("/notifications/1"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    @Test
    void malformed_json_should_return_400() throws Exception {
        String body = "{not-json";
        mockMvc.perform(post("/notifications").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Malformed JSON request body"));
    }

    @Test
    void no_resource_found_should_return_404() throws Exception {
        mockMvc.perform(get("/non-existent-path"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void path_variable_type_mismatch_should_return_404_via_routing() throws Exception {
        // controller path 用 regex \d+ 限定，非數字會 404 而非 400
        mockMvc.perform(get("/notifications/abc"))
            .andExpect(status().isNotFound());
    }
}
