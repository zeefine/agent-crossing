package com.agentcrossing.platform.api.controller;

import com.agentcrossing.platform.api.dto.ApiResponse;
import com.agentcrossing.platform.api.dto.ChatMessageResponse;
import com.agentcrossing.platform.api.dto.ChatThreadResponse;
import com.agentcrossing.platform.api.dto.CreateChatThreadRequest;
import com.agentcrossing.platform.api.dto.InvocationMessageResponse;
import com.agentcrossing.platform.api.dto.SubmitChatMessageRequest;
import com.agentcrossing.platform.api.dto.SubmitChatMessageResponse;
import com.agentcrossing.platform.application.auth.CurrentUserResolver;
import com.agentcrossing.platform.application.chat.ChatEventService;
import com.agentcrossing.platform.application.chat.ChatService;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.message.ChatMessageRepository;
import com.agentcrossing.platform.domain.message.InvocationMessageRepository;
import com.agentcrossing.platform.domain.task.TaskDependencyRepository;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService chatService;
    private final ChatThreadRepository chatThreadRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final InvocationMessageRepository invocationMessageRepository;
    private final ChatEventService chatEventService;
    private final TaskDependencyRepository taskDependencyRepository;
    private final CurrentUserResolver currentUserResolver;

    public ChatController(
            ChatService chatService,
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            InvocationMessageRepository invocationMessageRepository,
            ChatEventService chatEventService,
            TaskDependencyRepository taskDependencyRepository) {
        this(
                chatService,
                chatThreadRepository,
                chatMessageRepository,
                invocationMessageRepository,
                chatEventService,
                taskDependencyRepository,
                new CurrentUserResolver());
    }

    @Autowired
    public ChatController(
            ChatService chatService,
            ChatThreadRepository chatThreadRepository,
            ChatMessageRepository chatMessageRepository,
            InvocationMessageRepository invocationMessageRepository,
            ChatEventService chatEventService,
            TaskDependencyRepository taskDependencyRepository,
            CurrentUserResolver currentUserResolver) {
        this.chatService = chatService;
        this.chatThreadRepository = chatThreadRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.invocationMessageRepository = invocationMessageRepository;
        this.chatEventService = chatEventService;
        this.taskDependencyRepository = taskDependencyRepository;
        this.currentUserResolver = currentUserResolver;
    }

    @PostMapping("/threads")
    public ApiResponse<ChatThreadResponse> createThread(
            @RequestHeader(value = CurrentUserResolver.USER_ID_HEADER, required = false) String userId,
            @RequestBody(required = false) CreateChatThreadRequest request) {
        String title = request == null ? null : request.title();
        return ApiResponse.ok(ChatThreadResponse.from(chatService.createThread(currentUser(userId), title)));
    }

    @GetMapping("/threads")
    public ApiResponse<List<ChatThreadResponse>> getThreads(
            @RequestHeader(value = CurrentUserResolver.USER_ID_HEADER, required = false) String userId) {
        return ApiResponse.ok(chatThreadRepository.findAllByUserId(currentUser(userId)).stream()
                .map(ChatThreadResponse::from)
                .toList());
    }

    @GetMapping("/threads/{threadId}")
    public ApiResponse<ChatThreadResponse> getThread(
            @RequestHeader(value = CurrentUserResolver.USER_ID_HEADER, required = false) String userId,
            @PathVariable String threadId) {
        return ApiResponse.ok(ChatThreadResponse.from(findThread(currentUser(userId), threadId)));
    }

    @DeleteMapping("/threads/{threadId}")
    public ApiResponse<Void> deleteThread(
            @RequestHeader(value = CurrentUserResolver.USER_ID_HEADER, required = false) String userId,
            @PathVariable String threadId) {
        chatService.deleteThread(currentUser(userId), threadId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/threads/{threadId}/messages")
    public ApiResponse<SubmitChatMessageResponse> submitMessage(
            @RequestHeader(value = CurrentUserResolver.USER_ID_HEADER, required = false) String userId,
            @PathVariable String threadId,
            @Valid @RequestBody SubmitChatMessageRequest request) {
        return ApiResponse.ok(SubmitChatMessageResponse.from(
                chatService.submitUserMessage(currentUser(userId), threadId, request.content()), taskDependencyRepository));
    }

    @GetMapping("/threads/{threadId}/messages")
    public ApiResponse<List<ChatMessageResponse>> getMessages(
            @RequestHeader(value = CurrentUserResolver.USER_ID_HEADER, required = false) String userId,
            @PathVariable String threadId) {
        findThread(currentUser(userId), threadId);
        return ApiResponse.ok(chatMessageRepository.findByThreadId(threadId).stream()
                .map(ChatMessageResponse::from)
                .toList());
    }

    @GetMapping("/threads/{threadId}/invocation-messages")
    public ApiResponse<List<InvocationMessageResponse>> getInvocationMessages(
            @RequestHeader(value = CurrentUserResolver.USER_ID_HEADER, required = false) String userId,
            @PathVariable String threadId) {
        String currentUserId = currentUser(userId);
        ChatThread thread = findThread(currentUserId, threadId);
        return ApiResponse.ok(invocationMessageRepository.findByTraceIdAndUserId(thread.traceId(), currentUserId).stream()
                .map(InvocationMessageResponse::from)
                .toList());
    }

    // 保留 SSE 事件流接口用于调试、排障或备用接入；Web 前端正式实时链路统一走 WebSocket。
    @GetMapping(value = "/threads/{threadId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(
            @RequestHeader(value = CurrentUserResolver.USER_ID_HEADER, required = false) String userId,
            @PathVariable String threadId,
            @RequestParam(value = "lastEventId", required = false) Long lastEventId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader) {
        findThread(currentUser(userId), threadId);
        return chatEventService.subscribe(threadId, resolveLastEventId(lastEventId, lastEventIdHeader));
    }

    private ChatThread findThread(String userId, String threadId) {
        return chatThreadRepository
                .findByThreadIdAndUserId(threadId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Chat thread not found: " + threadId));
    }

    private String currentUser(String userId) {
        return currentUserResolver.fromHeader(userId).userId();
    }

    private static long resolveLastEventId(Long requestParam, String headerValue) {
        if (requestParam != null) {
            return requestParam;
        }
        if (headerValue == null || headerValue.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(headerValue.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
