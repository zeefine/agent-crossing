package com.agentcrossing.platform.domain.task;

public enum TaskStatus {
    QUEUED("queued"),
    PROCESSING("processing"),
    COMPLETED("completed"),
    FAILED("failed"),
    BLOCKED("blocked"),
    CANCELED("canceled");

    private final String wireValue;

    TaskStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}

