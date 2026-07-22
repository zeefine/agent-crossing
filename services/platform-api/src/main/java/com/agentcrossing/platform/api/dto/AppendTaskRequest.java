package com.agentcrossing.platform.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AppendTaskRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        @NotEmpty List<@Valid AppendTaskItemRequest> tasks) {
    /** Backward-compatible direct Java construction; HTTP requests must supply idempotencyKey. */
    public AppendTaskRequest(List<@Valid AppendTaskItemRequest> tasks) {
        this("legacy-direct-request", tasks);
    }
}
