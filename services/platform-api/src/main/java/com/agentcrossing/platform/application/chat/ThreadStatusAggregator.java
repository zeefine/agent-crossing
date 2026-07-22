package com.agentcrossing.platform.application.chat;

import com.agentcrossing.platform.application.realtime.RealtimeEventTypes;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskRepository;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Thread 状态的唯一写入入口。
 *
 * <p>线程状态不是某一个 task 的镜像：同一个 trace 中可能同时存在规划、并行 task 和 invocation。
 * 因此所有执行路径在状态变化后都调用本服务重新聚合，避免晚到的成功结果覆盖已经发生的失败。</p>
 */
@Service
public class ThreadStatusAggregator {
    private final ChatThreadRepository chatThreadRepository;
    private final TaskRepository taskRepository;
    private final InvocationRepository invocationRepository;
    private final ThreadPlanningQueue threadPlanningQueue;
    private final ChatEventService chatEventService;

    public ThreadStatusAggregator(
            ChatThreadRepository chatThreadRepository,
            TaskRepository taskRepository,
            InvocationRepository invocationRepository,
            ThreadPlanningQueue threadPlanningQueue,
            ChatEventService chatEventService) {
        this.chatThreadRepository = chatThreadRepository;
        this.taskRepository = taskRepository;
        this.invocationRepository = invocationRepository;
        this.threadPlanningQueue = threadPlanningQueue;
        this.chatEventService = chatEventService;
    }

    public Optional<ChatThread> refresh(String userId, String threadId) {
        if (chatThreadRepository == null) {
            return Optional.empty();
        }
        return chatThreadRepository.findByThreadIdAndUserId(threadId, userId)
                .map(this::refresh);
    }

    public Optional<ChatThread> refreshForTrace(String userId, String traceId) {
        if (chatThreadRepository == null) {
            return Optional.empty();
        }
        return chatThreadRepository.findByTraceId(traceId)
                .filter(thread -> thread.userId().equals(userId))
                .map(this::refresh);
    }

    /** Records a planning failure through the same ownership boundary as normal aggregation. */
    public Optional<ChatThread> markPlanningFailed(String userId, String threadId) {
        if (chatThreadRepository == null) {
            return Optional.empty();
        }
        return chatThreadRepository.findByThreadIdAndUserId(threadId, userId)
                .map(thread -> updateIfChanged(thread, ChatThreadStatus.FAILED));
    }

    private ChatThread refresh(ChatThread thread) {
        List<Task> tasks = taskRepository == null
                ? List.of()
                : taskRepository.findByTraceIdAndUserId(thread.traceId(), thread.userId());
        List<Invocation> invocations = invocationRepository == null
                ? List.of()
                : invocationRepository.findByTraceIdAndUserId(thread.traceId(), thread.userId());
        return updateIfChanged(thread, resolveStatus(thread, tasks, invocations));
    }

    private ChatThreadStatus resolveStatus(
            ChatThread thread, List<Task> tasks, List<Invocation> invocations) {
        // 新一轮用户输入已经预留规划时，优先显示当前轮正在处理；旧 trace 的失败不能阻止用户继续追问。
        if (threadPlanningQueue != null && threadPlanningQueue.hasPending(thread.userId(), thread.threadId())) {
            return ChatThreadStatus.RUNNING;
        }
        // 失败是 trace 级终态：并行 sibling 随后成功也不能把它覆盖为 COMPLETED。
        if (tasks.stream().anyMatch(task -> task.status() == TaskStatus.FAILED || task.status() == TaskStatus.BLOCKED)
                || invocations.stream().anyMatch(invocation -> invocation.status() == InvocationStatus.FAILED)) {
            return ChatThreadStatus.FAILED;
        }
        if (tasks.stream().anyMatch(task -> task.status() == TaskStatus.QUEUED || task.status() == TaskStatus.PROCESSING)
                || invocations.stream().anyMatch(invocation -> invocation.status() == InvocationStatus.QUEUED
                        || invocation.status() == InvocationStatus.RUNNING)) {
            return ChatThreadStatus.RUNNING;
        }
        // 没有可观察的失败记录时，保留规划失败写入的 FAILED，直到下一次用户提交重新进入 RUNNING。
        if (thread.status() == ChatThreadStatus.FAILED) {
            return ChatThreadStatus.FAILED;
        }
        if (tasks.isEmpty() && invocations.isEmpty() && thread.status() == ChatThreadStatus.OPEN) {
            return ChatThreadStatus.OPEN;
        }
        return ChatThreadStatus.COMPLETED;
    }

    private ChatThread updateIfChanged(ChatThread thread, ChatThreadStatus nextStatus) {
        if (thread.status() == nextStatus) {
            return thread;
        }
        ChatThread updated = chatThreadRepository.updateStatus(thread.threadId(), nextStatus);
        if (chatEventService != null) {
            chatEventService.publish(updated.threadId(), RealtimeEventTypes.THREAD, updated);
        }
        return updated;
    }
}
