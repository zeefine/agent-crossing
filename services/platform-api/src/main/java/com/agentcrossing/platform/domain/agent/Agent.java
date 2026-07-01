package com.agentcrossing.platform.domain.agent;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record Agent(String agentId, String displayName, String role, List<String> capabilities, List<String> tools) {
    private static final Pattern AGENT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    public Agent(String agentId, String displayName) {
        this(agentId, displayName, "", List.of(), List.of());
    }

    public Agent {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        role = role == null ? "" : role;
        capabilities = normalizeList(capabilities);
        tools = normalizeList(tools);
        if (!AGENT_ID_PATTERN.matcher(agentId).matches()) {
            throw new IllegalArgumentException("agentId must contain only letters, numbers, underscores, or hyphens");
        }
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }
}
