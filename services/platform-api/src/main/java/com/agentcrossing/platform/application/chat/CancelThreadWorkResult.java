package com.agentcrossing.platform.application.chat;

import java.util.List;

/** Result of canceling all non-terminal work currently owned by one chat thread. */
public record CancelThreadWorkResult(
        String threadId,
        List<String> canceledTaskIds,
        List<String> canceledInvocationIds) {
}
