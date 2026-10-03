package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.agentcrossing.platform.domain.message.ContextMessageReceipt;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.ChatMessageMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class MybatisContextReceiptTests {
    @Test
    void queriesCompletedUnacknowledgedVersionsWithoutATimestampWatermark() throws Exception {
        Configuration config = configuration();
        String sql = sql(config, "findUnacknowledgedVisibleMessages", Map.of(
                "userId", "alice", "threadId", "thread", "currentAgentId", "codex", "limit", 20));
        assertThat(sql).contains("thread.user_id = ?", "receipt.user_id = ?", "receipt.agent_id = ?",
                "receipt.message_id = message.message_id", "message.status = 'COMPLETED'",
                "receipt.content_version != message.context_version", "message.agent_id != ?",
                "ORDER BY message.created_at DESC, message.message_id DESC LIMIT ?",
                "ORDER BY recent_messages.created_at ASC, recent_messages.message_id ASC");
        assertThat(sql).doesNotContain("created_at >", "updated_at >", "SHA2(message.content");
    }

    @Test
    void acknowledgmentsBindDeliveredVersionsAndIgnoreDeletedOrWrongTenantMessages() throws Exception {
        var params = Map.of("userId", "alice", "threadId", "thread", "agentId", "codex",
                "summarized", false,
                "receipts", List.of(ContextMessageReceipt.of("m1", "正文😀"), ContextMessageReceipt.of("m2", "reply")));
        String sql = sql(configuration(), "acknowledgeContextMessages", params);
        assertThat(sql).contains("? AS message_id, ? AS content_version UNION ALL SELECT",
                "message.message_id = delivered.message_id AND message.thread_id = ?",
                "thread.user_id = ?", "content_version = VALUES(content_version)",
                "summarized_version = COALESCE(VALUES(summarized_version), summarized_version)");
        assertThat(sql).doesNotContain("SHA2", "正文", "reply");
        // Schema is additive, fingerprints update even for older writers, and delete cannot leave receipts behind.
        try (var input = getClass().getResourceAsStream("/schema-mysql.sql")) {
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(schema).contains("GENERATED ALWAYS AS (SHA2(content, 256)) STORED",
                    "PRIMARY KEY (user_id, thread_id, agent_id, message_id)",
                    "REFERENCES chat_message (message_id) ON DELETE CASCADE");
        }
    }

    @Test
    void emptyInputsDoNotQueryAndLongSummaryAcknowledgmentsAreBatched() {
        var mapper = mock(ChatMessageMapper.class);
        var repository = new MybatisChatMessageRepository(mapper);
        assertThat(repository.findUnacknowledgedVisibleMessages("alice", "thread", "codex", 0)).isEmpty();
        repository.acknowledgeContextMessages("alice", "thread", "codex", List.of());
        verifyNoInteractions(mapper);
        var receipts = IntStream.range(0, 401).mapToObj(i -> ContextMessageReceipt.of("m" + i, "text")).toList();
        repository.acknowledgeContextMessages("alice", "thread", "codex", receipts);
        verify(mapper).acknowledgeContextMessages("alice", "thread", "codex", receipts.subList(0, 200), false);
        verify(mapper).acknowledgeContextMessages("alice", "thread", "codex", receipts.subList(200, 400), false);
        verify(mapper).acknowledgeContextMessages("alice", "thread", "codex", receipts.subList(400, 401), false);
        repository.acknowledgeSummarizedMessages("alice", "thread", "codex", receipts.subList(0, 1), true);
        verify(mapper).clearSummarizedContextMessages("alice", "thread", "codex");
        verify(mapper).acknowledgeContextMessages("alice", "thread", "codex", receipts.subList(0, 1), true);
    }

    private Configuration configuration() throws Exception {
        Configuration config = new Configuration();
        String resource = "mapper/ChatMessageMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        return config;
    }

    private String sql(Configuration config, String statement, Map<String, ?> params) {
        return config.getMappedStatement(ChatMessageMapper.class.getName() + "." + statement)
                .getBoundSql(params).getSql().replaceAll("\\s+", " ").trim();
    }
}
