package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.InvocationMessageMapper;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class MybatisInvocationMessageRepositoryTests {
    @Test
    void existenceUsesBooleanMapperQueryForBothHitAndMiss() {
        var mapper = mock(InvocationMessageMapper.class);
        var repository = new MybatisInvocationMessageRepository(mapper);
        when(mapper.existsByInvocationId("hit")).thenReturn(true);

        assertThat(repository.existsByInvocationId("hit")).isTrue();
        assertThat(repository.existsByInvocationId("miss")).isFalse();
        verify(mapper).existsByInvocationId("hit");
        verify(mapper).existsByInvocationId("miss");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void existenceSqlReturnsOnlyBooleanWithoutLoadingOrSortingEventBodies() throws Exception {
        Configuration config = new Configuration();
        String resource = "mapper/InvocationMessageMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        var statement = config.getMappedStatement(InvocationMessageMapper.class.getName() + ".existsByInvocationId");
        var boundSql = statement.getBoundSql(Map.of("invocationId", "inv"));
        assertThat(boundSql.getSql().replaceAll("\\s+", " ").trim())
                .isEqualTo("SELECT EXISTS ( SELECT 1 FROM invocation_message WHERE invocation_id = ? )");
        assertThat(boundSql.getParameterMappings()).singleElement()
                .satisfies(parameter -> assertThat(parameter.getProperty()).isEqualTo("invocationId"));
        assertThat(statement.getResultMaps()).singleElement()
                .satisfies(result -> assertThat(result.getType()).isEqualTo(Boolean.class));
    }
}
