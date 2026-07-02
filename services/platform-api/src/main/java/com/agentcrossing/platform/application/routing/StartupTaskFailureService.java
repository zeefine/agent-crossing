package com.agentcrossing.platform.application.routing;

import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskRepository;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class StartupTaskFailureService {
    private static final Logger log = LoggerFactory.getLogger(StartupTaskFailureService.class);

    private final TaskRepository taskRepository;
    private final InvocationRepository invocationRepository;
    private final ChatThreadRepository chatThreadRepository;

    public StartupTaskFailureService(
            TaskRepository taskRepository,
            InvocationRepository invocationRepository,
            ChatThreadRepository chatThreadRepository) {
        this.taskRepository = taskRepository;
        this.invocationRepository = invocationRepository;
        this.chatThreadRepository = chatThreadRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failStaleWorkOnStartup() {
        StartupCleanupSummary summary = failStaleWork();
        if (summary.hasChanges()) {
            log.warn(
                    "Marked stale startup work as failed: invocations={}, tasks={}, threads={}",
                    summary.failedInvocations(),
                    summary.failedTasks(),
                    summary.failedThreads());
        }
    }

    StartupCleanupSummary failStaleWork() {
        Set<String> affectedTraceIds = new HashSet<>();

        int failedInvocations = failInvocations(affectedTraceIds);
        int failedTasks = failTasks(affectedTraceIds);
        int failedThreads = failThreads(affectedTraceIds);

        return new StartupCleanupSummary(failedInvocations, failedTasks, failedThreads);
    }

    private int failInvocations(Set<String> affectedTraceIds) {
        List<Invocation> staleInvocations = invocationRepository.findAll().stream()
                .filter(invocation -> invocation.status() == InvocationStatus.QUEUED
                        || invocation.status() == InvocationStatus.RUNNING)
                .toList();
        staleInvocations.forEach(invocation -> {
            invocationRepository.updateStatus(invocation.invocationId(), InvocationStatus.FAILED);
            affectedTraceIds.add(invocation.traceId());
        });
        return staleInvocations.size();
    }

    private int failTasks(Set<String> affectedTraceIds) {
        List<Task> staleTasks = taskRepository.findAll().stream()
                .filter(task -> task.status() == TaskStatus.QUEUED || task.status() == TaskStatus.PROCESSING)
                .toList();
        staleTasks.forEach(task -> {
            taskRepository.updateStatus(task.taskId(), TaskStatus.FAILED);
            affectedTraceIds.add(task.traceId());
        });
        return staleTasks.size();
    }

    private int failThreads(Set<String> affectedTraceIds) {
        Set<String> failedThreadIds = new HashSet<>();
        chatThreadRepository.findAll().stream()
                .filter(thread -> thread.status() == ChatThreadStatus.RUNNING || affectedTraceIds.contains(thread.traceId()))
                .forEach(thread -> failThread(thread, failedThreadIds));
        return failedThreadIds.size();
    }

    private void failThread(ChatThread thread, Set<String> failedThreadIds) {
        if (thread.status() == ChatThreadStatus.FAILED) {
            failedThreadIds.add(thread.threadId());
            return;
        }
        chatThreadRepository.updateStatus(thread.threadId(), ChatThreadStatus.FAILED);
        failedThreadIds.add(thread.threadId());
    }

    record StartupCleanupSummary(int failedInvocations, int failedTasks, int failedThreads) {
        boolean hasChanges() {
            return failedInvocations > 0 || failedTasks > 0 || failedThreads > 0;
        }
    }
}
