package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentcrossing.platform.domain.session.AgentSessionHistory;
import com.agentcrossing.platform.domain.session.AgentSessionHistoryStatus;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.AgentSessionHistoryMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class MybatisAgentSessionHistoryRepositoryTests {
    private final AgentSessionHistoryMapper mapper = mock(AgentSessionHistoryMapper.class);
    private final MybatisAgentSessionHistoryRepository repository =
            new MybatisAgentSessionHistoryRepository(mapper);

    @Test
    void delegatesHistoryWritesAndReadsToMapper() {
        AgentSessionHistory history = history();
        when(mapper.findBySessionRecordId(history.sessionRecordId())).thenReturn(history);
        when(mapper.findByProviderSessionId(history.provider(), history.providerSessionId())).thenReturn(history);
        when(mapper.findByGeneration("user-1", "thread-1", "codex", "codex", 2)).thenReturn(history);
        when(mapper.findActive("user-1", "thread-1", "codex", "codex", "ACTIVE"))
                .thenReturn(history);
        when(mapper.findByThreadId("user-1", "thread-1", "codex", "codex"))
                .thenReturn(List.of(history));
        when(mapper.updateBySessionRecordId(history)).thenReturn(0);

        assertThat(repository.save(history)).isSameAs(history);
        assertThat(repository.findBySessionRecordId(history.sessionRecordId())).contains(history);
        assertThat(repository.findByProviderSessionId(history.provider(), history.providerSessionId()))
                .contains(history);
        assertThat(repository.findByGeneration("user-1", "thread-1", "codex", "codex", 2))
                .contains(history);
        assertThat(repository.findActive("user-1", "thread-1", "codex", "codex"))
                .contains(history);
        assertThat(repository.findByThreadId("user-1", "thread-1", "codex", "codex"))
                .containsExactly(history);

        repository.deleteByThreadId("user-1", "thread-1");

        verify(mapper).updateBySessionRecordId(history);
        verify(mapper).insert(history);
        verify(mapper).deleteByThreadId("user-1", "thread-1");
    }

    @Test
    void updatesExistingRecordWithoutInserting() {
        AgentSessionHistory history = history();
        when(mapper.updateBySessionRecordId(history)).thenReturn(1);

        assertThat(repository.save(history)).isSameAs(history);

        verify(mapper).updateBySessionRecordId(history);
        verify(mapper, never()).insert(history);
    }

    @Test
    void readsCompactingHistoryAndRestoresInterruptedCompactions() {
        AgentSessionHistory compacting = history().withStatus(AgentSessionHistoryStatus.COMPACTING);
        when(mapper.findActive("user-1", "thread-1", "codex", "codex", "COMPACTING")).thenReturn(compacting);
        when(mapper.restoreInterruptedCompactions()).thenReturn(1);

        assertThat(repository.findCompacting("user-1", "thread-1", "codex", "codex")).contains(compacting);
        assertThat(repository.restoreInterruptedCompactions()).isEqualTo(1);
    }

    @Test
    void doesNotHideConflictsOnOtherUniqueKeys() {
        AgentSessionHistory history = history();
        DuplicateKeyException conflict = new DuplicateKeyException("generation conflict");
        when(mapper.updateBySessionRecordId(history)).thenReturn(0);
        doThrow(conflict).when(mapper).insert(history);

        assertThatThrownBy(() -> repository.save(history)).isSameAs(conflict);

        verify(mapper).insert(history);
        verify(mapper, org.mockito.Mockito.times(2)).updateBySessionRecordId(history);
    }

    @Test
    void agentSessionHistoryMapperXmlCanBeParsed() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/AgentSessionHistoryMapper.xml";

        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }

        String namespace = AgentSessionHistoryMapper.class.getName();
        assertThat(configuration.hasStatement(namespace + ".insert")).isTrue();
        assertThat(configuration.hasStatement(namespace + ".updateBySessionRecordId")).isTrue();
        assertThat(configuration.hasStatement(namespace + ".findBySessionRecordId")).isTrue();
        assertThat(configuration.hasStatement(namespace + ".findByProviderSessionId")).isTrue();
        assertThat(configuration.hasStatement(namespace + ".findByGeneration")).isTrue();
        assertThat(configuration.hasStatement(namespace + ".findActive")).isTrue();
        assertThat(configuration.hasStatement(namespace + ".findCreating")).isTrue();
        assertThat(configuration.hasStatement(namespace + ".findByThreadId")).isTrue();
        assertThat(configuration.hasStatement(namespace + ".deleteByThreadId")).isTrue();
        assertThat(configuration.hasStatement(namespace + ".restoreInterruptedCompactions")).isTrue();
    }

    private static AgentSessionHistory history() {
        Instant createdAt = Instant.parse("2026-08-13T03:00:00Z");
        return new AgentSessionHistory(
                "session-record-2",
                "user-1",
                "thread-1",
                "trace-1",
                "codex",
                "codex",
                "provider-session-2",
                2,
                AgentSessionHistoryStatus.ACTIVE,
                "session-record-1",
                "Earlier conversation summary",
                "message-1",
                "message-20",
                "message-21",
                300L,
                "gpt-5.6-codex",
                "summary-v1",
                "TOKEN_THRESHOLD",
                null,
                createdAt,
                createdAt,
                null);
    }
}
