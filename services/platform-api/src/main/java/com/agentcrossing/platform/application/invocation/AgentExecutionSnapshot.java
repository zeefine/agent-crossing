package com.agentcrossing.platform.application.invocation;

import com.agentcrossing.platform.domain.session.AgentSession;

/** Session pointer and prompt context read from the same session generation. */
public record AgentExecutionSnapshot(AgentContextPack contextPack, AgentSession providerSession) {}
