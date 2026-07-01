package com.agentcrossing.platform.domain.task;

public enum TaskSource {
    USER("user"),
    AGENT("agent");

    private final String wireValue;

    TaskSource(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}

