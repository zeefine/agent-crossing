package com.agentcrossing.platform.application.invocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.domain.agent.Agent;
import com.agentcrossing.platform.domain.agent.AgentRegistry;
import com.agentcrossing.platform.domain.agent.InMemoryAgentCatalog;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.context.InMemoryAgentContextCursorRepository;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageRole;
import com.agentcrossing.platform.domain.message.ChatMessageStatus;
import com.agentcrossing.platform.domain.message.ContextMessageReceipt;
import com.agentcrossing.platform.domain.message.InMemoryChatMessageRepository;
import com.agentcrossing.platform.domain.session.AgentSessionHistory;
import com.agentcrossing.platform.domain.session.AgentSessionHistoryStatus;
import com.agentcrossing.platform.domain.session.InMemoryAgentSessionHistoryRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentContextServiceTests {
    @Test
    void injectsCompressedSummaryAndRetainedTailForPendingSession() {
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        InMemoryChatMessageRepository messageRepository = new InMemoryChatMessageRepository();
        InMemoryAgentSessionHistoryRepository historyRepository = new InMemoryAgentSessionHistoryRepository();
        Instant now = Instant.parse("2026-08-13T03:00:00Z");
        threadRepository.save(new ChatThread(
                "thread-1", "user-1", "Compressed", ChatThreadStatus.RUNNING, "trace-1", now, now));
        messageRepository.save(new ChatMessage(
                "message-old", "thread-1", ChatMessageRole.USER, "old", ChatMessageStatus.COMPLETED,
                null, null, null, now, now));
        messageRepository.acknowledgeSummarizedMessages("user-1", "thread-1", "opencode",
                List.of(ContextMessageReceipt.of("message-old", "old")), true);
        messageRepository.save(new ChatMessage(
                "message-tail", "thread-1", ChatMessageRole.ASSISTANT, "retained own answer",
                ChatMessageStatus.COMPLETED, "invocation-1", "task-1", "opencode",
                now.plusSeconds(1), now.plusSeconds(1)));
        historyRepository.save(new AgentSessionHistory(
                "session-record-2", "user-1", "thread-1", "trace-1", "opencode", "opencode",
                null, 2, AgentSessionHistoryStatus.CREATING, "session-record-1",
                "{\"schemaVersion\":1}", "message-old", "message-old", "message-tail",
                null, "gpt-5.6", "summary-v1", "TOKEN_THRESHOLD", null, now, null, null));
        AgentContextService service = new AgentContextService(
                threadRepository, messageRepository, new InMemoryAgentContextCursorRepository(), null,
                historyRepository);

        AgentContextPack pack = service.buildContextPack(task(now));

        assertThat(pack.startupSummary()).isEqualTo("{\"schemaVersion\":1}");
        assertThat(pack.incrementalChatMessages())
                .extracting(IncrementalChatMessage::messageId)
                .containsExactly("message-tail");
    }

    @Test
    void keepsOnlyTheLatestTwentyVisibleMessagesInChronologicalOrder() {
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        InMemoryChatMessageRepository messageRepository = new InMemoryChatMessageRepository();
        Instant base = Instant.parse("2026-07-17T10:00:00Z");
        threadRepository.save(new ChatThread(
                "thread-1",
                "user-1",
                "Recent context",
                ChatThreadStatus.RUNNING,
                "trace-1",
                base,
                base));
        for (int index = 1; index <= 25; index++) {
            String messageId = "message-%02d".formatted(index);
            Instant createdAt = base.plusSeconds(index);
            messageRepository.save(new ChatMessage(
                    messageId,
                    "thread-1",
                    ChatMessageRole.USER,
                    "message " + index,
                    ChatMessageStatus.COMPLETED,
                    null,
                    null,
                    null,
                    createdAt,
                    createdAt));
        }
        AgentContextService service = new AgentContextService(
                threadRepository,
                messageRepository,
                new InMemoryAgentContextCursorRepository());

        AgentContextPack contextPack = service.buildContextPack(task(base));

        assertThat(contextPack.incrementalChatMessages()).hasSize(20);
        assertThat(contextPack.incrementalChatMessages())
                .extracting(IncrementalChatMessage::messageId)
                .containsExactly(
                        "message-06", "message-07", "message-08", "message-09", "message-10",
                        "message-11", "message-12", "message-13", "message-14", "message-15",
                        "message-16", "message-17", "message-18", "message-19", "message-20",
                        "message-21", "message-22", "message-23", "message-24", "message-25");
    }

    @Test
    void deliversCompletedReplyEvenAfterAcknowledgingANewerUserMessage() {
        ContextFixture fixture = contextFixture();
        Instant base = fixture.base();
        fixture.messages().save(new ChatMessage(
                "message-reply", "thread-1", ChatMessageRole.ASSISTANT, "partial",
                ChatMessageStatus.STREAMING, "invocation-other", "task-other", "codex", base, base));
        fixture.messages().save(new ChatMessage(
                "message-user", "thread-1", ChatMessageRole.USER, "newer question",
                ChatMessageStatus.COMPLETED, null, null, null, base.plusSeconds(1), base.plusSeconds(1)));

        AgentContextPack first = fixture.service().buildContextPack(task(base));
        assertThat(first.incrementalChatMessages()).extracting(IncrementalChatMessage::messageId)
                .containsExactly("message-user");
        fixture.service().acknowledgeInjectedMessages(task(base), first);

        fixture.messages().save(new ChatMessage(
                "message-reply", "thread-1", ChatMessageRole.ASSISTANT, "complete answer",
                ChatMessageStatus.COMPLETED, "invocation-other", "task-other", "codex",
                base, base.plusSeconds(2)));
        AgentContextPack second = fixture.service().buildContextPack(task(base));
        assertThat(second.incrementalChatMessages()).extracting(IncrementalChatMessage::content)
                .containsExactly("complete answer");
        fixture.service().acknowledgeInjectedMessages(task(base), second);
        assertThat(fixture.service().buildContextPack(task(base)).incrementalChatMessages()).isEmpty();
    }

    @Test
    void acknowledgingAnOlderSnapshotDoesNotConsumeRevisedContentWithTheSameTimestamp() {
        ContextFixture fixture = contextFixture();
        Instant base = fixture.base();
        fixture.messages().save(new ChatMessage(
                "message-reply", "thread-1", ChatMessageRole.ASSISTANT, "first complete answer",
                ChatMessageStatus.COMPLETED, "invocation-other", "task-other", "codex", base, base));
        AgentContextPack first = fixture.service().buildContextPack(task(base));
        assertThat(first.incrementalChatMessages()).extracting(IncrementalChatMessage::content)
                .containsExactly("first complete answer");

        fixture.messages().save(new ChatMessage(
                "message-reply", "thread-1", ChatMessageRole.ASSISTANT, "corrected complete answer",
                ChatMessageStatus.COMPLETED, "invocation-other", "task-other", "codex", base, base));
        fixture.service().acknowledgeInjectedMessages(task(base), first);

        AgentContextPack second = fixture.service().buildContextPack(task(base));
        assertThat(second.incrementalChatMessages()).extracting(IncrementalChatMessage::content)
                .containsExactly("corrected complete answer");
        fixture.service().acknowledgeInjectedMessages(task(base), second);
        assertThat(fixture.service().buildContextPack(task(base)).incrementalChatMessages()).isEmpty();
    }

    @Test
    void acknowledgingLatestTwentyDoesNotConsumeFiveMessagesThatWereNotIncluded() {
        ContextFixture fixture = contextFixture();
        Instant base = fixture.base();
        for (int index = 1; index <= 25; index++) {
            Instant createdAt = base.plusSeconds(index);
            fixture.messages().save(new ChatMessage(
                    "message-%02d".formatted(index), "thread-1", ChatMessageRole.USER,
                    "message " + index, ChatMessageStatus.COMPLETED,
                    null, null, null, createdAt, createdAt));
        }
        AgentContextPack first = fixture.service().buildContextPack(task(base));
        assertThat(first.incrementalChatMessages()).hasSize(20);
        fixture.service().acknowledgeInjectedMessages(task(base), first);

        AgentContextPack second = fixture.service().buildContextPack(task(base));
        assertThat(second.incrementalChatMessages()).extracting(IncrementalChatMessage::messageId)
                .containsExactly("message-01", "message-02", "message-03", "message-04", "message-05");
        fixture.service().acknowledgeInjectedMessages(task(base), second);
        assertThat(fixture.service().buildContextPack(task(base)).incrementalChatMessages()).isEmpty();
    }

    @Test
    void incrementalContextExcludesFailedCanceledAndOwnAssistantMessages() {
        ContextFixture fixture = contextFixture();
        Instant base = fixture.base();
        fixture.messages().save(new ChatMessage(
                "message-failed", "thread-1", ChatMessageRole.ASSISTANT, "failed partial answer",
                ChatMessageStatus.FAILED, "invocation-failed", "task-failed", "codex", base, base));
        fixture.messages().save(new ChatMessage(
                "message-canceled", "thread-1", ChatMessageRole.ASSISTANT, "canceled partial answer",
                ChatMessageStatus.CANCELED, "invocation-canceled", "task-canceled", "codex", base, base));
        fixture.messages().save(new ChatMessage(
                "message-own", "thread-1", ChatMessageRole.ASSISTANT, "own answer already in session",
                ChatMessageStatus.COMPLETED, "invocation-own", "task-own", "opencode", base, base));
        fixture.messages().save(new ChatMessage(
                "message-other", "thread-1", ChatMessageRole.ASSISTANT, "other complete answer",
                ChatMessageStatus.COMPLETED, "invocation-other", "task-other", "codex", base, base));

        AgentContextPack pack = fixture.service().buildContextPack(task(base));

        assertThat(pack.incrementalChatMessages()).extracting(IncrementalChatMessage::messageId)
                .containsExactly("message-other");
    }

    @Test
    void buildsAvailableAgentDirectoryFromAgentRegistry() {
        InMemoryChatThreadRepository threadRepository = new InMemoryChatThreadRepository();
        AgentRegistry agentRegistry = new AgentRegistry(new InMemoryAgentCatalog());
        agentRegistry.register(new Agent(
                "opencode",
                "OpenCode",
                "Implementation agent",
                List.of("implementation", "project analysis"),
                List.of("filesystem", "shell")));
        agentRegistry.register(new Agent(
                "claudecode",
                "ClaudeCode",
                "Architecture reviewer",
                List.of("reasoning", "code review"),
                List.of("shell")));
        Instant now = Instant.now();
        threadRepository.save(new ChatThread(
                "thread-1",
                "user-1",
                "Agent directory",
                ChatThreadStatus.RUNNING,
                "trace-1",
                now,
                now));
        AgentContextService service = new AgentContextService(
                threadRepository,
                new InMemoryChatMessageRepository(),
                new InMemoryAgentContextCursorRepository(),
                agentRegistry);
        Task task = new Task(
                "task-1",
                "user-1",
                "trace-1",
                null,
                TaskStatus.PROCESSING,
                TaskSource.USER,
                0,
                "opencode",
                "answer",
                now,
                now);

        AgentContextPack contextPack = service.buildContextPack(task);

        assertThat(contextPack.availableAgents())
                .extracting(AvailableAgentContext::agentId)
                .containsExactly("claudecode", "opencode");
        assertThat(contextPack.availableAgents().getFirst().role()).isEqualTo("Architecture reviewer");
        assertThat(contextPack.availableAgents().getFirst().capabilities())
                .containsExactly("reasoning", "code review");
    }

    private static ContextFixture contextFixture() {
        Instant base = Instant.parse("2026-10-03T03:00:00.123Z");
        InMemoryChatThreadRepository threads = new InMemoryChatThreadRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        threads.save(new ChatThread(
                "thread-1", "user-1", "Incremental context", ChatThreadStatus.RUNNING,
                "trace-1", base, base));
        return new ContextFixture(base, messages, new AgentContextService(
                threads, messages, new InMemoryAgentContextCursorRepository()));
    }

    private record ContextFixture(
            Instant base, InMemoryChatMessageRepository messages, AgentContextService service) {
    }

    private static Task task(Instant now) {
        return new Task(
                "task-1",
                "user-1",
                "trace-1",
                null,
                TaskStatus.PROCESSING,
                TaskSource.USER,
                0,
                "opencode",
                "answer",
                now,
                now);
    }
}
