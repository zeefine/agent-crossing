package com.agentcrossing.platform.application.invocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.invocation.InvocationUsage;
import com.agentcrossing.platform.domain.invocation.UsagePrecision;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageRole;
import com.agentcrossing.platform.domain.message.ChatMessageStatus;
import com.agentcrossing.platform.domain.message.InMemoryChatMessageRepository;
import com.agentcrossing.platform.domain.session.AgentSession;
import com.agentcrossing.platform.domain.session.AgentSessionHistoryStatus;
import com.agentcrossing.platform.domain.session.InMemoryAgentSessionHistoryRepository;
import com.agentcrossing.platform.domain.session.InMemoryAgentSessionRepository;
import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private Task task() {
        return new Task(
                "task-1", "user-1", "trace-1", null, TaskStatus.PROCESSING, TaskSource.USER,
                0, "codex", "continue", base, base);
    }

    private InvocationUsage usage(long contextTokens) {
        return new InvocationUsage(
                "invocation-1", "codex", "gpt-5.6", "provider-session-1",
                contextTokens, null, UsagePrecision.TURN_AGGREGATE,
                contextTokens, null, null, null, 100L, null, contextTokens,
                Map.of("input_tokens", contextTokens), "test", base);
    }

    private static final class FakeCompressionClient implements SessionCompressionClient {
        private SessionCompressionRequest request;

        @Override
        public SessionCompressionResult compress(SessionCompressionRequest request) {
            this.request = request;
            return new SessionCompressionResult(
                    Map.of(
                            "schemaVersion", 1,
                            "objective", "continue",
                            "continuationGuidance", "Use the retained tail"),
                    "compression-prompt-v1");
        }
    }
}
