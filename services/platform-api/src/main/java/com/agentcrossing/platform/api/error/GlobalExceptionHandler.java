package com.agentcrossing.platform.api.error;

import com.agentcrossing.platform.api.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Request validation failed");
        log.debug("Validation failed: {}", message);
        return ResponseEntity.badRequest().body(ApiResponse.failed("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        log.debug("Constraint violation: {}", exception.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.failed("VALIDATION_ERROR", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException exception) {
        // 业务侧的 "Chat thread not found" 之类用 IllegalArgumentException 抛出，
        // 这是 expected 4xx，不需要打 ERROR 栈；但留 debug 日志方便排查。
        log.debug("Bad request: {}", exception.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.failed("BAD_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        // 必须打完整栈，否则 500 在 production 完全无法排查（之前 bug B 的教训）。
        log.error("Unexpected server error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failed("INTERNAL_ERROR", "Unexpected server error"));
    }
}

