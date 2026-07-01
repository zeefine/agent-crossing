package com.agentcrossing.platform.api.dto;

import java.time.Instant;

public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error,
        String timestamp) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now().toString());
    }

    public static <T> ApiResponse<T> failed(String code, String message) {
        return new ApiResponse<>(false, null, new ApiError(code, message), Instant.now().toString());
    }
}

