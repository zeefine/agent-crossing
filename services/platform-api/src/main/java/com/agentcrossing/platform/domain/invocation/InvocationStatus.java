package com.agentcrossing.platform.domain.invocation;

public enum InvocationStatus {
    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELED("canceled");

    private final String wireValue;

    InvocationStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }
}
