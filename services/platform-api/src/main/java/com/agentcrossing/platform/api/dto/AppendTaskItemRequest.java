package com.agentcrossing.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record AppendTaskItemRequest(
        @NotBlank String taskId,
        @NotBlank String agentId,
        @NotBlank String context,
        List<String> dependsOn) {}
