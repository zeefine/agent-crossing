package com.agentcrossing.platform.api.controller;

import com.agentcrossing.platform.api.dto.ApiResponse;
import com.agentcrossing.platform.api.dto.CallbackMessageRequest;
import com.agentcrossing.platform.api.dto.TaskResponse;
import com.agentcrossing.platform.application.chat.AssistantStreamBuffer;
import com.agentcrossing.platform.application.chat.ChatEventService;
import com.agentcrossing.platform.application.invocation.AgentMessageType;
import com.agentcrossing.platform.application.realtime.RealtimeEventTypes;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.InvocationMessage;
import com.agentcrossing.platform.domain.message.InvocationMessageRepository;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/callback")
public class CallbackController {
    private static final Logger log = LoggerFactory.getLogger(CallbackController.class);
    private final InvocationRepository invocationRepository;
    private final InvocationMessageRepository invocationMessageRepository;
    private final ChatThreadRepository chatThreadRepository;
    private final AssistantStreamBuffer assistantStreamBuffer;
    private final ChatEventService chatEventService;

    public CallbackController(
            InvocationRepository invocationRepository,
            InvocationMessageRepository invocationMessageRepository,
            ChatThreadRepository chatThreadRepository,
            AssistantStreamBuffer assistantStreamBuffer,
            ChatEventService chatEventService) {
        this.invocationRepository = invocationRepository;
        this.invocationMessageRepository = invocationMessageRepository;
        this.chatThreadRepository = chatThreadRepository;
        this.assistantStreamBuffer = assistantStreamBuffer;
        this.chatEventService = chatEventService;
    }

    @PostMapping("/messages")
    @Transactional
    // 进入事务的写入：invocation_message INSERT（每分片）+ chat_message INSERT/UPDATE
    //（仅 AssistantStreamBuffer 达到 1KB 阈值或首次建流式消息时触发）。
    // realtime_event 走 publishTransient，不写 realtime_event 持久化行；事务 afterCommit 后才广播到实时通道。
    public ApiResponse<List<TaskResponse>> postMessage(@Valid @RequestBody CallbackMessageRequest request) {
        long startedAt = System.nanoTime();
        Invocation invocation = invocationRepository.findByInvocationId(request.invocationId())
                .orElseThrow(() -> new IllegalArgumentException("Invocation not found: " + request.invocationId()));
        if (invocation.status() == InvocationStatus.CANCELED) {
            // 停止已落库后，CLI 可能仍在收尾并回写尾分片；这些内容不能复活已取消的任务。
            return ApiResponse.ok(List.of());
        }
        saveAndPublishMessage(invocation, request);
        log.info(
                "agent_crossing_perf event=callback_message durationMs={} invocationId={} taskId={} traceId={} agentId={} contentChars={}",
                elapsedMs(startedAt),
                invocation.invocationId(),
                invocation.taskId(),
                invocation.traceId(),
                invocation.agentId(),
                request.content() == null ? 0 : request.content().length());
        return ApiResponse.ok(List.of());
    }

    private void saveAndPublishMessage(Invocation invocation, CallbackMessageRequest request) {
        String content = request.content();
        Long sequence = request.sequence();
        // invocation_message 仍然每分片 INSERT 一行——这是 Developer log 的"原始事件流"粒度，调试时要看。
        InvocationMessage invocationMessage = new InvocationMessage(
                "invocation-message-" + UUID.randomUUID(),
                invocation.userId(),
                invocation.invocationId(),
                invocation.taskId(),
                invocation.traceId(),
                invocation.agentId(),
                AgentMessageType.MESSAGE,
                content,
                null,
                sequence,
                Instant.now());
        if (!invocationMessageRepository.saveIfAbsent(invocationMessage)) {
            return;
        }
        chatThreadRepository.findByTraceId(invocation.traceId()).ifPresent(thread -> {
            // chat_message 走 AssistantStreamBuffer：累积到 1KB UTF-8 才 UPDATE 一次。
            // 返回的 ChatMessage 总是含最新累积内容，方便直接 publish 给 WS 显示。
            ChatMessage chatMessage = assistantStreamBuffer.appendChunk(invocation, thread.threadId(), content);
            // 流式分片走 transient 通道：不进 realtime_event，省每条 callback 2 行 realtime_event INSERT。
            // 终态（任务完成 / 失败、thread 状态变化）的 publish 仍走持久化 publish，断线重连仍可补发。
            // 详见 spec/v1.1.md §2.5 + ChatEventService.publishTransient javadoc。
            chatEventService.publishTransient(thread.threadId(), RealtimeEventTypes.INVOCATION_MESSAGE, invocationMessage);
            chatEventService.publishTransient(thread.threadId(), RealtimeEventTypes.CHAT_MESSAGE, chatMessage);
        });
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
