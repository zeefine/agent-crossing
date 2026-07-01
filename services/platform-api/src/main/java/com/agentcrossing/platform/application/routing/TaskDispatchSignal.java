package com.agentcrossing.platform.application.routing;

public interface TaskDispatchSignal {
    TaskDispatchSignal NOOP = () -> {};

    void signal();
}
