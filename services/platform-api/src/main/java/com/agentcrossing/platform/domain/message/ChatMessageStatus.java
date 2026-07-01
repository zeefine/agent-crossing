package com.agentcrossing.platform.domain.message;

public enum ChatMessageStatus {
    CREATED("created"),
    STREAMING("streaming"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String wireValue;

    ChatMessageStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
