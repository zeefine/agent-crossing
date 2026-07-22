package com.agentcrossing.platform.application.invocation;

public interface AgentRuntimeClient {
    AgentExecutionResult execute(AgentExecutionRequest request);

    /** Best-effort interruption of an in-flight runtime invocation. */
    default void cancel(String invocationId) {
        // Lightweight test doubles and alternate runtimes may not support remote cancellation yet.
    }
}
