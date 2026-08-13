package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentcrossing.platform.domain.invocation.InvocationUsage;
import com.agentcrossing.platform.domain.invocation.UsagePrecision;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.InvocationUsageMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class MybatisInvocationUsageRepositoryTests {
    private final InvocationUsageMapper mapper = mock(InvocationUsageMapper.class);
    private final MybatisInvocationUsageRepository repository = new MybatisInvocationUsageRepository(mapper);

    @Test
    void upsertsAndFindsUsageByInvocationId() {
        InvocationUsage usage = usage();
        when(mapper.findByInvocationId(usage.invocationId())).thenReturn(usage);

        assertThat(repository.save(usage)).isSameAs(usage);
        assertThat(repository.findByInvocationId(usage.invocationId())).contains(usage);

        verify(mapper).upsert(usage);
        verify(mapper).findByInvocationId(usage.invocationId());
    }

    @Test
    void deletesUsageByInvocationIds() {
        repository.deleteByInvocationIds(List.of("invocation-1", "invocation-2"));

        verify(mapper).deleteByInvocationIds(List.of("invocation-1", "invocation-2"));
    }

    @Test
    void invocationUsageMapperXmlCanBeParsed() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/InvocationUsageMapper.xml";

        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }

        String namespace = InvocationUsageMapper.class.getName();
        assertThat(configuration.hasStatement(namespace + ".upsert")).isTrue();
        assertThat(configuration.hasStatement(namespace + ".findByInvocationId")).isTrue();
        assertThat(configuration.hasStatement(namespace + ".deleteByInvocationIds")).isTrue();
    }

    private static InvocationUsage usage() {
        return new InvocationUsage(
                "invocation-usage-test",
                "claudecode",
                "claude-sonnet-4-5",
                "session-usage-test",
                30_000L,
                12_000L,
                UsagePrecision.TURN_AGGREGATE,
                12_000L,
                null,
                2_000L,
                8_000L,
                600L,
                null,
                12_000L,
                Map.of("input_tokens", 12_000L),
                "2.1.0",
                Instant.parse("2026-08-13T03:00:00Z"));
    }
}
