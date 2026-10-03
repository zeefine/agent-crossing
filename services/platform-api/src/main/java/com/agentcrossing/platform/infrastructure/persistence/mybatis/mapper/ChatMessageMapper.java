package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ContextMessageReceipt;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChatMessageMapper {
    void upsert(ChatMessage message);

    List<ChatMessage> findByThreadId(@Param("threadId") String threadId);

    List<ChatMessage> findLatestAgentConclusions(@Param("threadId") String threadId,
            @Param("excludedAgentId") String excludedAgentId, @Param("limit") int limit);

    List<ChatMessage> findUnacknowledgedVisibleMessages(
            @Param("userId") String userId,
            @Param("threadId") String threadId,
            @Param("currentAgentId") String currentAgentId,
            @Param("limit") int limit);

    void acknowledgeContextMessages(@Param("userId") String userId, @Param("threadId") String threadId,
            @Param("agentId") String agentId, @Param("receipts") List<ContextMessageReceipt> receipts,
            @Param("summarized") boolean summarized);

    List<ContextMessageReceipt> findSummarizedContextMessages(@Param("userId") String userId,
            @Param("threadId") String threadId, @Param("agentId") String agentId);

    void clearSummarizedContextMessages(@Param("userId") String userId,
            @Param("threadId") String threadId, @Param("agentId") String agentId);

    ChatMessage findAssistantStreamByInvocationId(@Param("invocationId") String invocationId);

    void deleteByThreadId(@Param("threadId") String threadId);
}
