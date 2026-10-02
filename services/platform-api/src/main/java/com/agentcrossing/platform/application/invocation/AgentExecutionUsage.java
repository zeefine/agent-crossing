package com.agentcrossing.platform.application.invocation;

import com.agentcrossing.platform.domain.invocation.UsagePrecision;
import java.time.Instant;

/**
 * Token usage normalized by agent-runtime for one provider invocation.
 * Aggregate consumption is stored in totalInputTokens; contextInputTokens is nullable when the
 * current request length is unavailable. EXACT context uses lastRequestInputTokens first.
 *
 * <p>MODEL_SYNC(AgentExecutionUsage): keep this record aligned with
 * contracts/schemas/runtime-event.schema.json and agent_runtime.contracts.models.AgentExecutionUsage.
 */
public record AgentExecutionUsage(
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
    public AgentExecutionUsage {
        usagePrecision = usagePrecision == null ? UsagePrecision.UNKNOWN : usagePrecision;
    }
}
