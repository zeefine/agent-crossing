package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.agentcrossing.platform.domain.task.*;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.TaskMapper;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.ChatMessageMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class MybatisBoundedQueryTests {
    @Test
    void summaryRepositoriesUseBoundedAndAggregateMapperQueries() {
        var taskMapper = mock(TaskMapper.class);
        var messageMapper = mock(ChatMessageMapper.class);
        var tasks = new MybatisTaskRepository(taskMapper);
        var messages = new MybatisChatMessageRepository(messageMapper);
        when(taskMapper.countByStatusForTrace("trace", "user"))
                .thenReturn(List.of(new TaskStatusCount(TaskStatus.COMPLETED, 200)));

        assertThat(tasks.countByStatusForTrace("trace", "user")).containsExactly(new TaskStatusCount(TaskStatus.COMPLETED, 200));
        tasks.findRecentByTraceIdAndUserId("trace", "user", 12);
        messages.findLatestAgentConclusions("thread", "masteragent", 6);
        verify(taskMapper).findRecentByTraceIdAndUserId("trace", "user", 12);
        verify(messageMapper).findLatestAgentConclusions("thread", "masteragent", 6);
        verify(taskMapper, never()).findByTraceIdAndUserId(anyString(), anyString());
        verify(messageMapper, never()).findByThreadId(anyString());
    }

    @Test
    void emptyBatchesAndNonpositiveLimitsDoNotReachDatabase() {
        var taskMapper = mock(TaskMapper.class);
        var messageMapper = mock(ChatMessageMapper.class);
        var tasks = new MybatisTaskRepository(taskMapper);
        var messages = new MybatisChatMessageRepository(messageMapper);
        assertThat(tasks.findDispatchSnapshots(List.of())).isEmpty();
        assertThat(tasks.findRecentByTraceIdAndUserId("trace", "user", 0)).isEmpty();
        assertThat(messages.findLatestAgentConclusions("thread", "masteragent", -1)).isEmpty();
        verifyNoInteractions(taskMapper, messageMapper);
    }

    @Test
    void sqlPreservesTenantScopeOrderingLimitsAndSchedulingGuards() throws Exception {
        Configuration config = new Configuration();
        for (String name : List.of("Task", "ChatMessage")) {
            String resource = "mapper/" + name + "Mapper.xml";
            try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        var parameters = Map.of("traceId", "trace", "userId", "user", "threadId", "thread",
                "excludedAgentId", "masteragent", "limit", 12, "taskIds", List.of("a", "b"));
        assertThat(sql(config, TaskMapper.class, "countByStatusForTrace", parameters))
                .contains("COUNT(*)", "trace_id = ? AND user_id = ?", "GROUP BY status");
        assertThat(sql(config, TaskMapper.class, "findRecentByTraceIdAndUserId", parameters))
                .contains("trace_id = ? AND user_id = ?", "ORDER BY updated_at DESC, created_at ASC, task_id ASC", "LIMIT ?");
        assertThat(sql(config, ChatMessageMapper.class, "findLatestAgentConclusions", parameters))
                .contains("ROW_NUMBER() OVER (PARTITION BY agent_id ORDER BY created_at DESC, message_id ASC)",
                        "thread_id = ?", "role = 'ASSISTANT'", "status = 'COMPLETED'", "agent_id != ?",
                        "WHERE agent_rank = 1", "LIMIT ?", "latest.message_id = message.message_id")
                .doesNotContain("SELECT chat_message.*");
        assertThat(sql(config, TaskMapper.class, "findDispatchSnapshots", parameters))
                .contains("LEFT JOIN task parent", "parent.task_id IS NULL", "'FAILED', 'BLOCKED', 'CANCELED'",
                        "parent.status != 'COMPLETED'", "i.user_id = candidate.user_id", "i.agent_id = candidate.agent_id",
                        "i.status = 'RUNNING'", "running.status = 'PROCESSING'", "running.user_id = candidate.user_id",
                        "h.status = 'COMPACTING'", "h.provider = candidate.agent_id", "thread.user_id = candidate.user_id",
                        "candidate.status = 'QUEUED'", "candidate.task_id IN ( ? , ? )")
                .doesNotContain("SELECT *", "context_text");
    }

    private static String sql(Configuration config, Class<?> mapper, String statement, Map<String, ?> params) {
        return config.getMappedStatement(mapper.getName() + "." + statement)
                .getBoundSql(params).getSql().replaceAll("\\s+", " ").trim();
    }
}
