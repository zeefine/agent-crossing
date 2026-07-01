package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import com.agentcrossing.platform.domain.chat.ChatThread;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChatThreadMapper {
    void upsert(ChatThread thread);

    ChatThread findByThreadId(@Param("threadId") String threadId);

    ChatThread findByThreadIdAndUserId(@Param("threadId") String threadId, @Param("userId") String userId);

    ChatThread findByTraceId(@Param("traceId") String traceId);

    void deleteByThreadId(@Param("threadId") String threadId);

    List<ChatThread> findAll();

    List<ChatThread> findAllByUserId(@Param("userId") String userId);
}
