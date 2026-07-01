package com.agentcrossing.platform.domain.chat;

import java.util.List;
import java.util.Optional;

public interface ChatThreadRepository {
    ChatThread save(ChatThread thread);

    Optional<ChatThread> findByThreadId(String threadId);

    Optional<ChatThread> findByThreadIdAndUserId(String threadId, String userId);

    Optional<ChatThread> findByTraceId(String traceId);

    ChatThread updateStatus(String threadId, ChatThreadStatus status);

    ChatThread updateTitle(String threadId, String title);

    /** 硬删除指定 thread；chat_message / realtime_event 的级联由对应 repository 各自负责。 */
    void deleteByThreadId(String threadId);

    List<ChatThread> findAll();

    List<ChatThread> findAllByUserId(String userId);
}
