package com.agentcrossing.platform.domain.session;

import java.time.Instant;
import java.util.Objects;

public record AgentSessionHistory(
        String sessionRecordId,
        String userId,
        String threadId,
        String traceId,
        String agentId,
        String provider,
        String providerSessionId,
        int generation,
        AgentSessionHistoryStatus status,
        String predecessorSessionRecordId,
        String startupSummary,
        String compactedStartMessageId,
        String compactedEndMessageId,
        String keepTailFromMessageId,
        Long summaryTokens,
        String summaryModel,
        String summaryPromptVersion,
        String rotationReason,
        Long finalContextInputTokens,
        Instant createdAt,
        Instant activatedAt,
        Instant supersededAt) {
    public AgentSessionHistory {
        requireNotBlank(sessionRecordId, "sessionRecordId");
        requireNotBlank(userId, "userId");
        requireNotBlank(threadId, "threadId");
        requireNotBlank(traceId, "traceId");
        requireNotBlank(agentId, "agentId");
        requireNotBlank(provider, "provider");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (status != AgentSessionHistoryStatus.CREATING) {
            requireNotBlank(providerSessionId, "providerSessionId");
            Objects.requireNonNull(activatedAt, "activatedAt must not be null");
        }
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be at least 1");
        }
        if (sessionRecordId.equals(predecessorSessionRecordId)) {
            throw new IllegalArgumentException("predecessorSessionRecordId must not reference itself");
        }
        requireNonNegative(summaryTokens, "summaryTokens");
        requireNonNegative(finalContextInputTokens, "finalContextInputTokens");
    }

    private static void requireNotBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public AgentSessionHistory activate(String nextProviderSessionId, Instant now) {
        return new AgentSessionHistory(
                sessionRecordId, userId, threadId, traceId, agentId, provider, nextProviderSessionId,
                generation, AgentSessionHistoryStatus.ACTIVE, predecessorSessionRecordId,
                startupSummary, compactedStartMessageId, compactedEndMessageId, keepTailFromMessageId,
                summaryTokens, summaryModel, summaryPromptVersion, rotationReason, finalContextInputTokens,
                createdAt, now, null);
    }

    public AgentSessionHistory supersede(Long finalTokens, Instant now) {
        return new AgentSessionHistory(
                sessionRecordId, userId, threadId, traceId, agentId, provider, providerSessionId,
                generation, AgentSessionHistoryStatus.SUPERSEDED, predecessorSessionRecordId,
                startupSummary, compactedStartMessageId, compactedEndMessageId, keepTailFromMessageId,
                summaryTokens, summaryModel, summaryPromptVersion, rotationReason, finalTokens,
                createdAt, activatedAt, now);
    }

    public AgentSessionHistory withStatus(AgentSessionHistoryStatus nextStatus) {
        return new AgentSessionHistory(
                sessionRecordId, userId, threadId, traceId, agentId, provider, providerSessionId,
                generation, nextStatus, predecessorSessionRecordId,
                startupSummary, compactedStartMessageId, compactedEndMessageId, keepTailFromMessageId,
                summaryTokens, summaryModel, summaryPromptVersion, rotationReason, finalContextInputTokens,
                createdAt, activatedAt, supersededAt);
    }

    private static void requireNonNegative(Long value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
