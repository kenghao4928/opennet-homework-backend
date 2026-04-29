package com.example.demo.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.redisson.client.RedisException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
        HttpServletRequest request) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
            .forEach(fe -> fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse.of(HttpStatus.BAD_REQUEST, "Validation failed", request.getRequestURI(),
                fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(HttpMessageNotReadableException ex,
        HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed JSON request body", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
        HttpServletRequest request) {
        String message = String.format("Parameter '%s' has invalid value '%s'", ex.getName(),
            ex.getValue());
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex,
        HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    /**
     * Redis 連線失敗或 Redisson 與 Redis 通訊異常一律回 503，告訴前端 cache layer 暫時不可用，
     * 不嘗試 DB fallback。Spring Data Redis 與 Redisson 用兩個獨立的例外型別，需各自處理。
     */
    @ExceptionHandler({RedisConnectionFailureException.class, RedisException.class})
    public ResponseEntity<ErrorResponse> handleCacheUnavailable(RuntimeException ex,
        HttpServletRequest request) {
        log.error("Cache layer unavailable processing request {} {}", request.getMethod(),
            request.getRequestURI(), ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Cache service temporarily unavailable",
            request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex,
        HttpServletRequest request) {
        log.warn("Data integrity violation on request {} {}", request.getMethod(),
            request.getRequestURI(), ex);
        return build(HttpStatus.CONFLICT, "Data integrity violation", request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex,
        HttpServletRequest request) {
        log.warn("Optimistic locking failure on request {} {}", request.getMethod(),
            request.getRequestURI(), ex);
        return build(HttpStatus.CONFLICT, "Resource was modified by another transaction", request);
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ErrorResponse> handleDataSourceUnavailable(
        DataAccessResourceFailureException ex, HttpServletRequest request) {
        log.error("Data source unavailable processing request {} {}", request.getMethod(),
            request.getRequestURI(), ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Data source temporarily unavailable",
            request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex,
        HttpServletRequest request) {
        log.error("Unexpected error processing request {} {}", request.getMethod(),
            request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message,
        HttpServletRequest request) {
        return ResponseEntity.status(status)
            .body(ErrorResponse.of(status, message, request.getRequestURI()));
    }
}
