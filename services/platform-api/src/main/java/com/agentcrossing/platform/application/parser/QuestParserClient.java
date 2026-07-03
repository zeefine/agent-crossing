package com.agentcrossing.platform.application.parser;

import com.agentcrossing.platform.domain.agent.Agent;
import java.util.List;

public interface QuestParserClient {
    UserInputParseResult parseUserInput(String input, List<Agent> availableAgents);

    default UserInputParseResult parseUserInput(
            String userId,
            String threadId,
            String traceId,
            String input,
            String providerSessionId,
            List<Agent> availableAgents) {
        return parseUserInput(input, availableAgents);
    }
}
