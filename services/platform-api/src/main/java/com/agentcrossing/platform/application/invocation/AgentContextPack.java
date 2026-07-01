package com.agentcrossing.platform.application.invocation;

import java.util.List;

public record AgentContextPack(List<IncrementalChatMessage> incrementalChatMessages) {
    public AgentContextPack {
        incrementalChatMessages = incrementalChatMessages == null ? List.of() : List.copyOf(incrementalChatMessages);
    }
}
