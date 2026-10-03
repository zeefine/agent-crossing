package com.agentcrossing.platform.application.invocation;

/** The execution's durable parent disappeared; stop without persisting a failure or flushing buffered output. */
public final class DeletedExecutionException extends RuntimeException {
    public DeletedExecutionException(String id) {
        super("Execution deleted: " + id);
    }
}
