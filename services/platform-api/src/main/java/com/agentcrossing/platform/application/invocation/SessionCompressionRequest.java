package com.agentcrossing.platform.application.invocation;

import java.util.List;

public record SessionCompressionRequest(
        String userId,
        String threadId,
        String traceId,
        String agentId,
        String provider,
        int generation,
        String previousStartupSummary,
        List<CompressionMessage> messages,
        List<CompressionTask> tasks) {
    public record CompressionMessage(
            String messageId, String role, String agentId, String taskId, String content, String createdAt) {}

    public record CompressionTask(String taskId, String agentId, String status, String context) {}
}
