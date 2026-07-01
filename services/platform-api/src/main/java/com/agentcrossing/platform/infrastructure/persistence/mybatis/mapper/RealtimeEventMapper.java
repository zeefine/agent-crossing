package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import com.agentcrossing.platform.domain.event.RealtimeEvent;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RealtimeEventMapper {
    void insert(RealtimeEventInsertParam event);

    List<RealtimeEvent> findAfter(
            @Param("threadId") String threadId,
            @Param("lastEventId") long lastEventId,
            @Param("limit") int limit);

    void deleteByThreadId(@Param("threadId") String threadId);
}
