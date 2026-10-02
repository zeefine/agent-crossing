package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.InvocationMapper;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.TaskMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MybatisExecutionStateRepositoryTests {
    @Test
    void invocationStatusUpdateUsesAffectedRowsWithoutUpsertOrRetryingStaleState() {
        var mapper = mock(InvocationMapper.class);
        var repository = new MybatisInvocationRepository(mapper);
        Instant now = Instant.now();
        Invocation running = new Invocation("inv", "user", "task", "trace", "codex", InvocationStatus.RUNNING, now, now, null);
        Invocation canceled = running.withStatus(InvocationStatus.CANCELED);
        when(mapper.findByInvocationId("inv")).thenReturn(running, canceled);
        when(mapper.updateStatusIfCurrent(eq("inv"), eq(Set.of("RUNNING")), eq("SUCCEEDED"), any())).thenReturn(0);

        assertThat(repository.updateStatus("inv", InvocationStatus.SUCCEEDED)).isEqualTo(canceled);
        verify(mapper).updateStatusIfCurrent(eq("inv"), eq(Set.of("RUNNING")), eq("SUCCEEDED"), any());
        verify(mapper, never()).upsert(any());
        when(mapper.updateStatusIfCurrent(eq("other"), eq(Set.of("RUNNING")), eq("FAILED"), any())).thenReturn(1);
        assertThat(repository.updateStatusIfCurrent("other", Set.of(InvocationStatus.RUNNING), InvocationStatus.FAILED)).isTrue();
        assertThat(repository.updateStatusIfCurrent("missing", Set.of(InvocationStatus.RUNNING), InvocationStatus.FAILED)).isFalse();
    }

    @Test
    void taskStatusUpdateUsesAffectedRowsWithoutUpsertOrRetryingStaleState() {
        var mapper = mock(TaskMapper.class);
        var repository = new MybatisTaskRepository(mapper);
        Instant now = Instant.now();
        Task processing = new Task("task", "user", "trace", null, TaskStatus.PROCESSING, TaskSource.USER, 0, "codex", "work", now, now);
        Task canceled = processing.withStatus(TaskStatus.CANCELED);
        when(mapper.findByTaskId("task")).thenReturn(processing, canceled);

        assertThat(repository.updateStatus("task", TaskStatus.COMPLETED)).isEqualTo(canceled);
        verify(mapper).updateStatusIfCurrent(eq("task"), eq(Set.of("PROCESSING")), eq("COMPLETED"), any());
        verify(mapper, never()).upsert(any());
        when(mapper.updateStatusIfCurrent(eq("other"), eq(Set.of("QUEUED", "PROCESSING")), eq("CANCELED"), any())).thenReturn(1);
        assertThat(repository.updateStatusIfCurrent("other", Set.of(TaskStatus.QUEUED, TaskStatus.PROCESSING), TaskStatus.CANCELED)).isTrue();
        assertThat(repository.updateStatusIfCurrent("missing", Set.of(TaskStatus.PROCESSING), TaskStatus.COMPLETED)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Task", "Invocation"})
    void mapperGuardsStatusAndUpdatesOnlyStatusTimestamps(String entity) throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/" + entity + "Mapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        String namespace = entity.equals("Task") ? TaskMapper.class.getName() : InvocationMapper.class.getName();
        String sql = configuration.getMappedStatement(namespace + ".updateStatusIfCurrent").getBoundSql(Map.of(
                "taskId", "task", "invocationId", "inv", "status", "CANCELED",
                "expected", Set.of("QUEUED", "RUNNING"), "now", Instant.now())).getSql().replaceAll("\\s+", " ");
        assertThat(sql).contains("UPDATE", "WHERE", "AND status IN", "?").doesNotContain("INSERT", "ON DUPLICATE", "context_text");
        if (entity.equals("Invocation")) {
            assertThat(sql).contains("COALESCE(started_at", "ELSE started_at", "ELSE completed_at");
        }
    }
}
