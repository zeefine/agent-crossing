package com.agentcrossing.platform.domain.message;

public enum ChatMessageRole {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    private final String wireValue;

    ChatMessageRole(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
