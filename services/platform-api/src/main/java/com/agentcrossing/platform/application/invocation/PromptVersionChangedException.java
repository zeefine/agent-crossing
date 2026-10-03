package com.agentcrossing.platform.application.invocation;

/** A side-effect-free runtime rejection: recover context before retrying with a fresh session. */
public final class PromptVersionChangedException extends RuntimeException {
    public PromptVersionChangedException(String currentPromptVersion) {
        super("Static prompt changed; session context recovery is required (" + currentPromptVersion + ")");
    }
}
