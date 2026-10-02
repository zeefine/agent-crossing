package com.agentcrossing.platform.domain.invocation;

import java.time.Instant;
import java.util.Objects;

public record InvocationUsage(
        String invocationId,
        String provider,
        String model,
        String providerSessionId,
        Long totalInputTokens,
        Long lastRequestInputTokens,
        UsagePrecision usagePrecision,
        Long inputTokens,
        Long cachedInputTokens,
        Long cacheCreationInputTokens,
        Long cacheReadInputTokens,
        Long outputTokens,
        Long reasoningOutputTokens,
        Long contextInputTokens,
        Object rawUsageJson,
        String providerCliVersion,
        Instant observedAt) {
    public InvocationUsage {
        Objects.requireNonNull(invocationId, "invocationId must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(usagePrecision, "usagePrecision must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        requireNotBlank(invocationId, "invocationId");
        requireNotBlank(provider, "provider");
        requireNotBlank(model, "model");
        requireNonNegative(totalInputTokens, "totalInputTokens");
        requireNonNegative(lastRequestInputTokens, "lastRequestInputTokens");
        requireNonNegative(inputTokens, "inputTokens");
        requireNonNegative(cachedInputTokens, "cachedInputTokens");
        requireNonNegative(cacheCreationInputTokens, "cacheCreationInputTokens");
        requireNonNegative(cacheReadInputTokens, "cacheReadInputTokens");
        requireNonNegative(outputTokens, "outputTokens");
        requireNonNegative(reasoningOutputTokens, "reasoningOutputTokens");
        requireNonNegative(contextInputTokens, "contextInputTokens");
        // Enforce the same invariant for incoming runtime usage and legacy database rows.
        // Aggregate input remains available for accounting, but cannot measure window occupancy.
        if (usagePrecision == UsagePrecision.EXACT) {
            contextInputTokens = lastRequestInputTokens != null ? lastRequestInputTokens : contextInputTokens;
            if (contextInputTokens == null) {
                usagePrecision = UsagePrecision.UNKNOWN;
            }
        } else {
            contextInputTokens = null;
        }
    }

    private static void requireNotBlank(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireNonNegative(Long value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
