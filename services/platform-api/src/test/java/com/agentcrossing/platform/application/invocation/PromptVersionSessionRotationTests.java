package com.agentcrossing.platform.application.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.agentcrossing.platform.application.chat.*;
import com.agentcrossing.platform.domain.chat.*;
import com.agentcrossing.platform.domain.context.InMemoryAgentContextCursorRepository;
import com.agentcrossing.platform.domain.invocation.*;
import com.agentcrossing.platform.domain.message.*;
import com.agentcrossing.platform.domain.session.*;
import com.agentcrossing.platform.domain.task.*;
import com.agentcrossing.platform.infrastructure.runtime.HttpAgentRuntimeClient;
import com.agentcrossing.platform.support.InvocationServiceTestFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PromptVersionSessionRotationTests {
    @ParameterizedTest
    @ValueSource(strings = {"success", "summary_failure", "canceled", "deleted", "retry_conflict"})
    void promptChangeRestoresHistoryOrStopsBeforeStartingANewSession(String outcome) {
        var threads = new InMemoryChatThreadRepository();
        var messages = new InMemoryChatMessageRepository();
        var tasks = new InMemoryTaskRepository();
        var invocations = new InMemoryInvocationRepository();
        var sessions = new InMemoryAgentSessionRepository();
        var histories = new InMemoryAgentSessionHistoryRepository();
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        Task task = new Task("task", "alice", "trace", null, TaskStatus.PROCESSING,
                TaskSource.USER, 0, "codex", "continue", now, now);
        tasks.save(task);
        threads.save(new ChatThread("thread", "alice", "title", ChatThreadStatus.RUNNING, "trace", now, now));
        sessions.save(new AgentSession("alice", "thread", "trace", "codex", "codex",
                "old-session", "old-prompt", now, now));
        histories.save(new AgentSessionHistory("history", "alice", "thread", "trace", "codex", "codex",
                "old-session", 2, AgentSessionHistoryStatus.ACTIVE, "predecessor", "{\"constraint\":\"keep legacy API\"}",
                "m1", "m2", "m3", null, "gpt-5.6", "summary-v1", "TOKEN_THRESHOLD", null, now, now, null));
        for (int i = 1; i <= 8; i++) {
            messages.save(new ChatMessage("m" + i, "thread", ChatMessageRole.USER, "requirement " + i,
                    ChatMessageStatus.COMPLETED, null, null, null, now.plusSeconds(i), now.plusSeconds(i)));
        }
        var receipts = messages.findByThreadId("thread").stream()
                .map(message -> ContextMessageReceipt.of(message.messageId(), message.content())).toList();
        messages.acknowledgeContextMessages("alice", "thread", "codex", receipts);
        messages.acknowledgeSummarizedMessages("alice", "thread", "codex", receipts.subList(0, 2), true);
        var context = new AgentContextService(threads, messages, new InMemoryAgentContextCursorRepository(), null, histories);
        assertThat(context.buildContextPack(task).incrementalChatMessages()).isEmpty();
        assertThat(context.buildContextPack(task).startupSummary()).isNull();
        AtomicReference<SessionCompressionRequest> summaryRequest = new AtomicReference<>();
        AtomicInteger summaryCalls = new AtomicInteger();
        var compression = new AgentSessionCompressionService(threads, messages, tasks, sessions, histories,
                request -> {
                    summaryCalls.incrementAndGet();
                    summaryRequest.set(request);
                    if (outcome.equals("summary_failure")) {
                        throw new IllegalStateException("summary unavailable");
                    }
                    if (outcome.equals("canceled")) {
                        new ExecutionStateService(tasks, invocations).cancelTrace("alice", "trace");
                    }
                    if (outcome.equals("deleted")) {
                        var chat = com.agentcrossing.platform.support.ChatServiceTestFactory.create(
                                threads, messages, null, null, null, null, null, invocations,
                                tasks, null, null, sessions, null, Runnable::run, null);
                        org.springframework.test.util.ReflectionTestUtils.setField(chat, "agentSessionHistoryRepository", histories);
                        chat.deleteThread("alice", "thread");
                    }
                    return new SessionCompressionResult(Map.of("constraint", "keep legacy API", "recent", "requirements 3-5"), "summary-v2");
                }, new ObjectMapper(), false, 1_000_000L, 0.8, 3, 20, 200_000, "gpt-5.6");
        RestClient.Builder builder = RestClient.builder().baseUrl("http://runtime");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://runtime/api/runtime/execute"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":{\"code\":\"PROMPT_VERSION_CHANGED\",\"currentPromptVersion\":\"new-prompt\"}}"));
        if (outcome.equals("success") || outcome.equals("retry_conflict") || outcome.equals("canceled")) {
            var retry = server.expect(requestTo("http://runtime/api/runtime/execute"))
                .andExpect(request -> {
                    var body = new ObjectMapper().readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertThat(body.path("providerSessionId").isNull()).isTrue();
                    assertThat(body.path("contextPack").path("startupSummary").asText()).contains("keep legacy API");
                    assertThat(body.path("contextPack").path("incrementalChatMessages").toString())
                            .contains("requirement 6", "requirement 7", "requirement 8");
                    assertThat(histories.findCreating("alice", "thread", "codex", "codex")).isPresent();
                });
            if (outcome.equals("retry_conflict")) {
                retry.andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":{\"code\":\"PROMPT_VERSION_CHANGED\",\"currentPromptVersion\":\"v3\"}}"));
            } else {
                retry.andRespond(withSuccess("""
                        {"messages":[{"invocationId":"unused","taskId":"task","traceId":"trace","agentId":"codex",
                        "type":"done","content":"","raw":{"providerSessionId":"new-session"}}],
                        "finalText":"done", "promptVersion":"new-prompt"}
                        """, MediaType.APPLICATION_JSON));
            }
        }
        var service = InvocationServiceTestFactory.create(invocations, tasks, new HttpAgentRuntimeClient(builder.build()),
                "http://unused", () -> {}, new InMemoryInvocationMessageRepository(), threads, messages, null, null,
                new AssistantStreamBuffer(messages), new InMemoryTaskDependencyRepository(), context, sessions,
                new InMemoryInvocationUsageRepository(), compression,
                new ThreadStatusAggregator(threads, tasks, invocations, new ThreadPlanningQueue(Runnable::run), null));

        InvocationStatus expectedStatus = switch (outcome) {
            case "success" -> InvocationStatus.SUCCEEDED;
            case "summary_failure", "retry_conflict" -> InvocationStatus.FAILED;
            default -> InvocationStatus.CANCELED;
        };
        assertThat(service.execute(task).status()).isEqualTo(expectedStatus);
        assertThat(summaryRequest.get()).isNotNull();
        assertThat(summaryCalls.get()).isEqualTo(1);
        assertThat(summaryRequest.get().previousStartupSummary()).contains("keep legacy API");
        assertThat(summaryRequest.get().messages()).extracting(SessionCompressionRequest.CompressionMessage::messageId)
                .containsExactly("m3", "m4", "m5");
        assertThat(summaryRequest.get().tasks()).filteredOn(item -> item.taskId().equals("task"))
                .singleElement().satisfies(item -> assertThat(item.status()).isEqualTo("PROCESSING"));
        if (outcome.equals("deleted")) {
            assertThat(histories.findByThreadId("alice", "thread", "codex", "codex")).isEmpty();
            assertThat(sessions.findByThreadId("alice", "thread", "codex", "codex")).isEmpty();
            assertThat(messages.findByThreadId("thread")).isEmpty();
        } else if (outcome.equals("canceled")) {
            assertThat(histories.findCreating("alice", "thread", "codex", "codex")).isPresent();
            assertThat(sessions.findByThreadId("alice", "thread", "codex", "codex")).isEmpty();
            Task next = tasks.save(new Task("next-task", "alice", "trace", null, TaskStatus.PROCESSING,
                    TaskSource.USER, 0, "codex", "resume after cancellation", now.plusSeconds(30), now.plusSeconds(30)));
            assertThat(service.execute(next).status()).isEqualTo(InvocationStatus.SUCCEEDED);
            assertThat(histories.findActive("alice", "thread", "codex", "codex")).get()
                    .satisfies(history -> assertThat(history.startupSummary()).contains("keep legacy API"));
            assertThat(summaryCalls.get()).isEqualTo(1);
        } else if (outcome.equals("retry_conflict")) {
            assertThat(histories.findCreating("alice", "thread", "codex", "codex")).isPresent();
            assertThat(sessions.findByThreadId("alice", "thread", "codex", "codex")).isEmpty();
        } else if (outcome.equals("summary_failure")) {
            assertThat(histories.findCreating("alice", "thread", "codex", "codex")).isEmpty();
            assertThat(histories.findActive("alice", "thread", "codex", "codex")).get()
                    .satisfies(history -> assertThat(history.generation()).isEqualTo(2));
            assertThat(sessions.findByThreadId("alice", "thread", "codex", "codex")).get()
                    .satisfies(session -> assertThat(session.providerSessionId()).isEqualTo("old-session"));
        } else {
            assertThat(histories.findActive("alice", "thread", "codex", "codex")).get().satisfies(history -> {
                assertThat(history.generation()).isEqualTo(3);
                assertThat(history.rotationReason()).isEqualTo("PROMPT_VERSION_CHANGED");
                assertThat(history.startupSummary()).contains("keep legacy API");
                assertThat(history.providerSessionId()).isEqualTo("new-session");
            });
        }
        server.verify();
    }
}
