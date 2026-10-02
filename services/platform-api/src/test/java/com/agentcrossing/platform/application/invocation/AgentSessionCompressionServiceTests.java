package com.agentcrossing.platform.application.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.invocation.InvocationUsage;
import com.agentcrossing.platform.domain.invocation.UsagePrecision;
import com.agentcrossing.platform.domain.context.InMemoryAgentContextCursorRepository;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageRole;
import com.agentcrossing.platform.domain.message.ChatMessageStatus;
import com.agentcrossing.platform.domain.message.InMemoryChatMessageRepository;
import com.agentcrossing.platform.domain.session.AgentSession;
import com.agentcrossing.platform.domain.session.AgentSessionHistory;
import com.agentcrossing.platform.domain.session.AgentSessionHistoryStatus;
import com.agentcrossing.platform.domain.session.InMemoryAgentSessionHistoryRepository;
import com.agentcrossing.platform.domain.session.InMemoryAgentSessionRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class AgentSessionCompressionServiceTests {
    private final InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
    private final InMemoryChatMessageRepository messageRepository = new InMemoryChatMessageRepository();
    private final InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
    private final InMemoryAgentSessionRepository sessionRepository = new InMemoryAgentSessionRepository();
    private final InMemoryAgentSessionHistoryRepository historyRepository =
            new InMemoryAgentSessionHistoryRepository();
    private final FakeCompressionClient compressionClient = new FakeCompressionClient();
    private final Instant base = Instant.parse("2026-08-13T03:00:00Z");
    private final AgentSessionCompressionService service = new AgentSessionCompressionService(
            threadRepository, messageRepository, taskRepository, sessionRepository, historyRepository,
            compressionClient, new ObjectMapper(), true, 1_000_000L, 0.8, 3, 20, 200_000, "gpt-5.6");

    @BeforeEach
    void setUp() {
        threadRepository.save(new ChatThread(
                "thread-1", "user-1", "Compression", ChatThreadStatus.RUNNING,
                "trace-1", base, base));
        taskRepository.save(task());
        AgentSession session = new AgentSession(
                "user-1", "thread-1", "trace-1", "codex", "codex",
                "provider-session-1", "prompt-v1", base, base);
        service.rememberSession(task(), "thread-1", session);
        for (int index = 1; index <= 8; index++) {
            ChatMessageRole role = index % 2 == 1 ? ChatMessageRole.USER : ChatMessageRole.ASSISTANT;
            messageRepository.save(new ChatMessage(
                    "message-" + index, "thread-1", role, "content " + index,
                    ChatMessageStatus.COMPLETED, role == ChatMessageRole.ASSISTANT ? "invocation-" + index : null,
                    "task-1", role == ChatMessageRole.ASSISTANT ? "codex" : null,
                    base.plusSeconds(index), base.plusSeconds(index)));
        }
    }

    @Test
    void rejectsAmbientTransactionBeforeReservingOrCallingModel() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var manager = new DataSourceTransactionManager(source);
        service.setTransactionManager(manager);

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThatThrownBy(() -> service.compactIfNeeded(task(), usage(800_000L)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outside a database transaction");
            assertThat(compressionClient.request).isNull();
            assertThat(service.isCompacting(task())).isFalse();
        });
        verify(source).getConnection();
        verify(connection).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void releasesJdbcConnectionBeforeSummaryAndUsesShortTransactionForFinish(boolean failure) throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        service.setTransactionManager(new DataSourceTransactionManager(source));
        compressionClient.onCompress = request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
            assertThat(service.isCompacting(task())).isTrue();
            try {
                verify(connection).commit();
                verify(connection).close();
            } catch (java.sql.SQLException exception) {
                throw new AssertionError(exception);
            }
            if (failure) {
                throw new IllegalStateException("summary unavailable");
            }
        };

        if (failure) {
            assertThatThrownBy(() -> service.compactIfNeeded(task(), usage(800_000L)))
                    .hasMessage("summary unavailable");
            assertThat(historyRepository.findActive("user-1", "thread-1", "codex", "codex")).isPresent();
            assertThat(sessionRepository.findByThreadId("user-1", "thread-1", "codex", "codex")).isPresent();
        } else {
            service.compactIfNeeded(task(), usage(800_000L));
            assertThat(historyRepository.findCreating("user-1", "thread-1", "codex", "codex")).isPresent();
            assertThat(sessionRepository.findByThreadId("user-1", "thread-1", "codex", "codex")).isEmpty();
        }
        verify(source, times(2)).getConnection();
        verify(connection, times(2)).commit();
        verify(connection, times(2)).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleSummaryCannotRotateOrRestoreChangedGeneration(boolean failure) {
        compressionClient.onCompress = request -> {
            AgentSessionHistory reserved = historyRepository
                    .findCompacting("user-1", "thread-1", "codex", "codex").orElseThrow();
            historyRepository.save(new AgentSessionHistory(
                    reserved.sessionRecordId(), reserved.userId(), reserved.threadId(), reserved.traceId(),
                    reserved.agentId(), reserved.provider(), reserved.providerSessionId(), reserved.generation() + 1,
                    reserved.status(), reserved.predecessorSessionRecordId(), reserved.startupSummary(),
                    reserved.compactedStartMessageId(), reserved.compactedEndMessageId(), reserved.keepTailFromMessageId(),
                    reserved.summaryTokens(), reserved.summaryModel(), reserved.summaryPromptVersion(),
                    reserved.rotationReason(), reserved.finalContextInputTokens(), reserved.createdAt(),
                    reserved.activatedAt(), reserved.supersededAt()));
            if (failure) {
                throw new IllegalStateException("stale summary failed");
            }
        };
        if (failure) {
            assertThatThrownBy(() -> service.compactIfNeeded(task(), usage(800_000L)))
                    .hasMessage("stale summary failed");
        } else {
            service.compactIfNeeded(task(), usage(800_000L));
        }
        assertThat(historyRepository.findByThreadId("user-1", "thread-1", "codex", "codex"))
                .singleElement().satisfies(history -> {
                    assertThat(history.generation()).isEqualTo(2);
                    assertThat(history.status()).isEqualTo(AgentSessionHistoryStatus.COMPACTING);
                });
        assertThat(sessionRepository.findByThreadId("user-1", "thread-1", "codex", "codex")).isPresent();
    }

    @Test
    void compressesAtEightyPercentAndSealsCurrentSession() {
        service.compactIfNeeded(task(), usage(800_000L));

        assertThat(compressionClient.request).isNotNull();
        assertThat(compressionClient.request.messages())
                .extracting(SessionCompressionRequest.CompressionMessage::messageId)
                .containsExactly("message-1", "message-2");
        assertThat(sessionRepository.findByThreadId("user-1", "thread-1", "codex", "codex"))
                .isEmpty();
        assertThat(historyRepository.findByGeneration("user-1", "thread-1", "codex", "codex", 1))
                .get()
                .satisfies(history -> {
                    assertThat(history.status()).isEqualTo(AgentSessionHistoryStatus.SUPERSEDED);
                    assertThat(history.finalContextInputTokens()).isEqualTo(800_000L);
                });
        assertThat(historyRepository.findCreating("user-1", "thread-1", "codex", "codex"))
                .get()
                .satisfies(history -> {
                    assertThat(history.generation()).isEqualTo(2);
                    assertThat(history.providerSessionId()).isNull();
                    assertThat(history.keepTailFromMessageId()).isEqualTo("message-3");
                    assertThat(history.startupSummary()).contains("continuationGuidance");
                });
    }

    @Test
    void doesNotCompressBelowThreshold() {
        service.compactIfNeeded(task(), usage(799_999L));

        assertThat(compressionClient.request).isNull();
        assertThat(sessionRepository.findByThreadId("user-1", "thread-1", "codex", "codex"))
                .isPresent();
    }

    @Test
    void doesNotCompressUsingAggregateTokensWhenLastRequestIsBelowThreshold() {
        service.compactIfNeeded(task(), new InvocationUsage(
                "invocation-1", "codex", "gpt-5.6", "provider-session-1",
                900_000L, 100_000L, UsagePrecision.EXACT,
                900_000L, null, null, null, 100L, null, 900_000L,
                Map.of(), "test", base));

        assertThat(compressionClient.request).isNull();
        assertThat(sessionRepository.findByThreadId("user-1", "thread-1", "codex", "codex")).isPresent();
    }

    @Test
    void ignoresAllNonExactUsageEvenWithLargeLegacyContextValues() {
        for (UsagePrecision precision : new UsagePrecision[] {
                UsagePrecision.TURN_AGGREGATE, UsagePrecision.ESTIMATED, UsagePrecision.UNKNOWN}) {
            service.compactIfNeeded(task(), new InvocationUsage(
                    "invocation-1", "codex", "gpt-5.6", "provider-session-1",
                    900_000L, 900_000L, precision,
                    900_000L, null, null, null, 100L, null, 900_000L,
                    Map.of(), "test", base));

            assertThat(compressionClient.request).as(precision.name()).isNull();
        }
    }

    @Test
    void doesNotCompressWithoutReliableContextEvenIfReportedAsExact() {
        service.compactIfNeeded(task(), new InvocationUsage(
                "invocation-1", "codex", "gpt-5.6", "provider-session-1",
                900_000L, null, UsagePrecision.EXACT,
                900_000L, null, null, null, 100L, null, null,
                Map.of(), "test", base));

        assertThat(compressionClient.request).isNull();
        assertThat(sessionRepository.findByThreadId("user-1", "thread-1", "codex", "codex")).isPresent();
    }

    @Test
    void compressesUsingExplicitExactContextWithoutLastRequestField() {
        service.compactIfNeeded(task(), new InvocationUsage(
                "invocation-1", "codex", "gpt-5.6", "provider-session-1",
                900_000L, null, UsagePrecision.EXACT,
                900_000L, null, null, null, 100L, null, 800_000L,
                Map.of(), "test", base));

        assertThat(compressionClient.request).isNotNull();
        assertThat(historyRepository.findByGeneration("user-1", "thread-1", "codex", "codex", 1))
                .get().satisfies(history -> assertThat(history.finalContextInputTokens()).isEqualTo(800_000L));
    }

    @Test
    void activatesPendingGenerationWhenNewProviderSessionStarts() {
        service.compactIfNeeded(task(), usage(800_000L));
        AgentSession next = new AgentSession(
                "user-1", "thread-1", "trace-1", "codex", "codex",
                "provider-session-2", "prompt-v1", base, base.plusSeconds(100));
        sessionRepository.save(next);

        service.rememberSession(task(), "thread-1", next);

        assertThat(historyRepository.findActive("user-1", "thread-1", "codex", "codex"))
                .get()
                .satisfies(history -> {
                    assertThat(history.generation()).isEqualTo(2);
                    assertThat(history.providerSessionId()).isEqualTo("provider-session-2");
                });
        assertThat(historyRepository.findCreating("user-1", "thread-1", "codex", "codex"))
                .isEmpty();
    }

    @Test
    void ignoresStaleProviderSessionThatFinishesAfterCompression() {
        service.compactIfNeeded(task(), usage(800_000L));
        AgentSession stale = new AgentSession(
                "user-1", "thread-1", "trace-1", "codex", "codex",
                "provider-session-1", "prompt-v1", base, base.plusSeconds(100));

        service.rememberSession(task(), "thread-1", stale);

        assertThat(sessionRepository.findByThreadId("user-1", "thread-1", "codex", "codex"))
                .isEmpty();
        assertThat(historyRepository.findCreating("user-1", "thread-1", "codex", "codex"))
                .isPresent();
        assertThat(historyRepository.findActive("user-1", "thread-1", "codex", "codex"))
                .isEmpty();
    }

    @Test
    void serializesConcurrentSessionHistoryTransitions() throws Exception {
        int writers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = java.util.stream.IntStream.range(0, writers)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        try {
                            if (!start.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Concurrent session test did not start");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                        service.rememberSession(task(), "thread-1", new AgentSession(
                                "user-1", "thread-1", "trace-1", "codex", "codex",
                                "provider-session-concurrent-" + index, "prompt-v1",
                                base.plusSeconds(index + 1), base.plusSeconds(index + 1)));
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        var histories = historyRepository.findByThreadId("user-1", "thread-1", "codex", "codex");
        assertThat(histories).hasSize(writers + 1);
        assertThat(histories).filteredOn(history -> history.status() == AgentSessionHistoryStatus.ACTIVE)
                .hasSize(1);
        AgentSession current = sessionRepository
                .findByThreadId("user-1", "thread-1", "codex", "codex")
                .orElseThrow();
        assertThat(historyRepository.findActive("user-1", "thread-1", "codex", "codex"))
                .get()
                .extracting(history -> history.providerSessionId())
                .isEqualTo(current.providerSessionId());
    }

    @Test
    void snapshotWaitsForRotationCommitAndSummaryRunsOutsideTransaction() throws Exception {
        CountDownLatch committingRotation = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        AtomicInteger commits = new AtomicInteger();
        service.setTransactionManager(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() { return new Object(); }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {}

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                if (commits.incrementAndGet() == 2) {
                    committingRotation.countDown();
                    await(allowCommit);
                }
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {}
        });
        compressionClient.onCompress = request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(commits.get()).isEqualTo(1);
            assertThat(service.isCompacting(task())).isTrue();
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var compression = executor.submit(() -> service.compactIfNeeded(task(), usage(800_000L)));
            try {
                assertThat(committingRotation.await(3, TimeUnit.SECONDS)).isTrue();
                CountDownLatch readerStarted = new CountDownLatch(1);
                var snapshot = executor.submit(() -> {
                    readerStarted.countDown();
                    return service.prepareExecution(task(), this::loadSnapshot);
                });
                assertThat(readerStarted.await(3, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> snapshot.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                allowCommit.countDown();
                compression.get(3, TimeUnit.SECONDS);
                AgentExecutionSnapshot prepared = snapshot.get(3, TimeUnit.SECONDS);
                assertThat(prepared.providerSession()).isNull();
                assertThat(prepared.contextPack().startupSummary()).contains("continue");
                assertThat(prepared.contextPack().incrementalChatMessages())
                        .extracting(IncrementalChatMessage::messageId)
                        .containsExactly("message-3", "message-4", "message-5", "message-6", "message-7", "message-8");
            } finally {
                allowCommit.countDown();
            }
        }
    }

    @Test
    void rotationCannotInterleaveBetweenContextAndSessionReads() throws Exception {
        CountDownLatch contextRead = new CountDownLatch(1);
        CountDownLatch allowSessionRead = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var snapshot = executor.submit(() -> service.prepareExecution(task(), () -> {
                AgentContextPack context = contextService().buildContextPack(task());
                contextRead.countDown();
                await(allowSessionRead);
                return new AgentExecutionSnapshot(context,
                        sessionRepository.findByThreadId("user-1", "thread-1", "codex", "codex").orElse(null));
            }));
            try {
                assertThat(contextRead.await(3, TimeUnit.SECONDS)).isTrue();
                CountDownLatch rotationStarted = new CountDownLatch(1);
                var compression = executor.submit(() -> {
                    rotationStarted.countDown();
                    service.compactIfNeeded(task(), usage(800_000L));
                });
                assertThat(rotationStarted.await(3, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> compression.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(compressionClient.request).isNull();
                allowSessionRead.countDown();
                AgentExecutionSnapshot prepared = snapshot.get(3, TimeUnit.SECONDS);
                assertThat(prepared.providerSession().providerSessionId()).isEqualTo("provider-session-1");
                assertThat(prepared.contextPack().startupSummary()).isNull();
                compression.get(3, TimeUnit.SECONDS);
                assertThat(service.prepareExecution(task(), this::loadSnapshot).providerSession()).isNull();
            } finally {
                allowSessionRead.countDown();
            }
        }
    }

    private AgentExecutionSnapshot loadSnapshot() {
        return new AgentExecutionSnapshot(contextService().buildContextPack(task()),
                sessionRepository.findByThreadId("user-1", "thread-1", "codex", "codex").orElse(null));
    }

    private AgentContextService contextService() {
        return new AgentContextService(threadRepository, messageRepository,
                new InMemoryAgentContextCursorRepository(), null, historyRepository);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Task task() {
        return new Task(
                "task-1", "user-1", "trace-1", null, TaskStatus.PROCESSING, TaskSource.USER,
                0, "codex", "continue", base, base);
    }

    private InvocationUsage usage(long contextTokens) {
        return new InvocationUsage(
                "invocation-1", "codex", "gpt-5.6", "provider-session-1",
                contextTokens, contextTokens, UsagePrecision.EXACT,
                contextTokens, null, null, null, 100L, null, contextTokens,
                Map.of("input_tokens", contextTokens), "test", base);
    }

    private static final class FakeCompressionClient implements SessionCompressionClient {
        private SessionCompressionRequest request;
        private Consumer<SessionCompressionRequest> onCompress = request -> {};

        @Override
        public SessionCompressionResult compress(SessionCompressionRequest request) {
            this.request = request;
            onCompress.accept(request);
            return new SessionCompressionResult(
                    Map.of(
                            "schemaVersion", 1,
                            "objective", "continue",
                            "continuationGuidance", "Use the retained tail"),
                    "compression-prompt-v1");
        }
    }
}
