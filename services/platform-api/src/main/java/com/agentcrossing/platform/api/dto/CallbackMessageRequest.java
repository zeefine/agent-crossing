package com.agentcrossing.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CallbackMessageRequest(
        @NotBlank String invocationId,
        @NotBlank String content,
        boolean stream,
        @Positive Long sequence) {
    public CallbackMessageRequest(String invocationId, String content) {
        this(invocationId, content, false, null);
    }
}
