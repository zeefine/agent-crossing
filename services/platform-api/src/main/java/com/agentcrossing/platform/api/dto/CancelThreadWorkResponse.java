package com.agentcrossing.platform.api.dto;

import com.agentcrossing.platform.application.chat.CancelThreadWorkResult;
import java.util.List;

public record CancelThreadWorkResponse(
        String threadId,
        List<String> canceledTaskIds,
        List<String> canceledInvocationIds) {
    public static CancelThreadWorkResponse from(CancelThreadWorkResult result) {
        return new CancelThreadWorkResponse(
                result.threadId(),
                result.canceledTaskIds(),
                result.canceledInvocationIds());
    }
}
