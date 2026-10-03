package com.agentcrossing.platform.domain.message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The exact content delivered to a provider, not a later reread of the mutable message. */
public record ContextMessageReceipt(String messageId, String contentVersion) {
    public static ContextMessageReceipt of(String messageId, String content) {
        try {
            return new ContextMessageReceipt(messageId, HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
