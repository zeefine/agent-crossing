package com.agentcrossing.platform.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CallbackMessageRequest(@NotBlank String invocationId, @NotBlank String content, boolean stream) {
    public CallbackMessageRequest(String invocationId, String content) {
        this(invocationId, content, false);
    }
}
