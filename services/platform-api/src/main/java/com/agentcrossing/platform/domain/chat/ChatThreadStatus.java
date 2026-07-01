package com.agentcrossing.platform.domain.chat;

public enum ChatThreadStatus {
    OPEN("open"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELED("canceled");

    private final String wireValue;

    ChatThreadStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
