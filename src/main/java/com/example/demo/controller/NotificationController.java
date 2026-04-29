package com.example.demo.controller;

import com.example.demo.bo.NotificationBo;
import com.example.demo.bo.UpdateNotificationBo;
import com.example.demo.exception.ErrorResponse;
import com.example.demo.mapper.NotificationControllerMapper;
import com.example.demo.service.NotificationService;
import com.example.demo.vo.CreateNotificationRequest;
import com.example.demo.vo.NotificationResponse;
import com.example.demo.vo.UpdateNotificationRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationControllerMapper controllerMapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NotificationResponse create(@Valid @RequestBody CreateNotificationRequest req) {
        NotificationBo bo = controllerMapper.toBo(req);
        return controllerMapper.toResponse(notificationService.create(bo));
    }

    @GetMapping("/recent")
    public List<NotificationResponse> recent() {
        return notificationService.getRecent().stream().map(controllerMapper::toResponse).toList();
    }

    @GetMapping("/{id:\\d+}")
    public ResponseEntity<Object> getById(@PathVariable Long id, HttpServletRequest req) {
        return notificationService.findById(id)
            .<ResponseEntity<Object>>map(bo -> ResponseEntity.ok(controllerMapper.toResponse(bo)))
            .orElseGet(() -> notFound(id, req));
    }

    @PutMapping("/{id:\\d+}")
    public ResponseEntity<Object> update(@PathVariable Long id,
        @Valid @RequestBody UpdateNotificationRequest body, HttpServletRequest req) {
        UpdateNotificationBo updateBo = controllerMapper.toUpdateBo(body);
        return notificationService.update(id, updateBo)
            .<ResponseEntity<Object>>map(bo -> ResponseEntity.ok(controllerMapper.toResponse(bo)))
            .orElseGet(() -> notFound(id, req));
    }

    @DeleteMapping("/{id:\\d+}")
    public ResponseEntity<Object> delete(@PathVariable Long id, HttpServletRequest req) {
        return notificationService.delete(id) ? ResponseEntity.noContent().build()
            : notFound(id, req);
    }

    private ResponseEntity<Object> notFound(Long id, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.of(HttpStatus.NOT_FOUND, "Notification not found: " + id,
            req.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).<Object>body(body);
    }
}
