package com.agentcrossing.platform.application.invocation;

import java.util.List;

public record AgentContextPack(
        List<IncrementalChatMessage> incrementalChatMessages,
        List<AvailableAgentContext> availableAgents,
        String startupSummary) {
    public AgentContextPack {
        incrementalChatMessages = incrementalChatMessages == null ? List.of() : List.copyOf(incrementalChatMessages);
        availableAgents = availableAgents == null ? List.of() : List.copyOf(availableAgents);
    }

    public AgentContextPack(List<IncrementalChatMessage> incrementalChatMessages) {
        this(incrementalChatMessages, List.of(), null);
    }

    public AgentContextPack(
            List<IncrementalChatMessage> incrementalChatMessages,
            List<AvailableAgentContext> availableAgents) {
        this(incrementalChatMessages, availableAgents, null);
    }
}
