package com.agentcrossing.platform.domain.task;

/** Read-only scheduling advice, not a reservation. A QUEUED conditional update must still win before dispatch. */
public record TaskDispatchSnapshot(String taskId, boolean dependencyBlocked, boolean dependencyWaiting,
                                   boolean agentBusy, boolean sessionCompacting) {}
