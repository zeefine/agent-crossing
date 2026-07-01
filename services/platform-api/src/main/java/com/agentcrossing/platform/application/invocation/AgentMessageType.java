package com.agentcrossing.platform.application.invocation;

public enum AgentMessageType {
    TEXT_DELTA("textDelta"),
    MESSAGE("message"),
    DONE("done"),
    ERROR("error");

    private final String wireValue;

    AgentMessageType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}

