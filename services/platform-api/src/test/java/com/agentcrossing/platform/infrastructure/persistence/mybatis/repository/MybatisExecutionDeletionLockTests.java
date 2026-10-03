package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.TaskMapper;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class MybatisExecutionDeletionLockTests {
    @Test
    void resultAndDeletionUseParentRowLocksIncludingTerminalTasks() throws Exception {
        Configuration config = new Configuration();
        String resource = "mapper/TaskMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        String result = sql(config, "findByTaskIdForUpdate", Map.of("taskId", "task"));
        String deletion = sql(config, "findByTraceIdAndUserIdForUpdate", Map.of("traceId", "trace", "userId", "user"));
        assertThat(result).isEqualTo("SELECT * FROM task WHERE task_id = ? FOR UPDATE");
        assertThat(deletion).contains("WHERE trace_id = ? AND user_id = ?", "ORDER BY task_id ASC FOR UPDATE")
                .doesNotContain("status =", "status IN");
        var mapper = mock(TaskMapper.class);
        var repository = new MybatisTaskRepository(mapper);
        repository.findByTaskIdForUpdate("task");
        repository.findByTraceIdAndUserIdForUpdate("trace", "user");
        verify(mapper).findByTaskIdForUpdate("task");
        verify(mapper).findByTraceIdAndUserIdForUpdate("trace", "user");
        verifyNoMoreInteractions(mapper);
    }

    private String sql(Configuration config, String name, Map<String, ?> params) {
        return config.getMappedStatement(TaskMapper.class.getName() + "." + name)
                .getBoundSql(params).getSql().replaceAll("\\s+", " ").trim();
    }
}
