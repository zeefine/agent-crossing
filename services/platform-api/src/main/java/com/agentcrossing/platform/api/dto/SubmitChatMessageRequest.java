package com.agentcrossing.platform.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SubmitChatMessageRequest(@NotBlank String content) {
}
