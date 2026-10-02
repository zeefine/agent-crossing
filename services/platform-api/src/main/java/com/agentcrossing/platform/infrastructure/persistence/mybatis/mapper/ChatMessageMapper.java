package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import com.agentcrossing.platform.domain.message.ChatMessage;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChatMessageMapper {
    void upsert(ChatMessage message);

    List<ChatMessage> findByThreadId(@Param("threadId") String threadId);

    List<ChatMessage> findLatestAgentConclusions(@Param("threadId") String threadId,
            @Param("excludedAgentId") String excludedAgentId, @Param("limit") int limit);

    List<ChatMessage> findVisibleMessagesAfterCursor(
            @Param("threadId") String threadId,
            @Param("currentAgentId") String currentAgentId,
            @Param("lastInjectedCreatedAt") Instant lastInjectedCreatedAt,
            @Param("lastInjectedMessageId") String lastInjectedMessageId,
            @Param("limit") int limit);

    ChatMessage findAssistantStreamByInvocationId(@Param("invocationId") String invocationId);

    void deleteByThreadId(@Param("threadId") String threadId);
}
