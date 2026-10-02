package com.agentcrossing.platform.application.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.agentcrossing.platform.application.chat.AssistantStreamBuffer;
import com.agentcrossing.platform.application.chat.ChatEventService;
import com.agentcrossing.platform.application.realtime.RealtimeEventTypes;
import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.chat.ChatThreadStatus;
import com.agentcrossing.platform.domain.chat.InMemoryChatThreadRepository;
import com.agentcrossing.platform.domain.invocation.InMemoryInvocationRepository;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.message.*;
import com.agentcrossing.platform.domain.task.*;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class AssistantFinalizationTests {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "partial"})
    void unchangedOrAbsentFinalTextStillCompletesExistingMessageOnce(String finalText) {
        var fixture = new Fixture();
        fixture.response = new AgentExecutionResult(List.of(), finalText, true, null);
        fixture.onExecute = invocation -> fixture.seed(invocation, ChatMessageStatus.STREAMING);

        Invocation result = fixture.service.execute(fixture.task);

        ChatMessage saved = fixture.onlyMessage();
        assertThat(saved.content()).isEqualTo("partial");
        assertThat(saved.status()).isEqualTo(ChatMessageStatus.COMPLETED);
        fixture.verifySingleFinalization(result, saved);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void noStreamAndNoFinalTextDoNotCreateEmptyMessage(String finalText) {
        var fixture = new Fixture();
        fixture.response = new AgentExecutionResult(List.of(), finalText, true, null);

        fixture.service.execute(fixture.task);

        assertThat(fixture.messages.findByThreadId("thread")).isEmpty();
        verify(fixture.messages, never()).save(any());
        verify(fixture.publisher, never()).publish(anyString(), eq(RealtimeEventTypes.CHAT_MESSAGE), any());
        verify(fixture.buffer, never()).drain(anyString());
    }

    @Test
    void alreadyCompletedMatchingMessageIsNotWrittenOrBroadcastAgain() {
        var fixture = new Fixture();
        fixture.response = new AgentExecutionResult(List.of(), "partial", true, null);
        fixture.onExecute = invocation -> fixture.seed(invocation, ChatMessageStatus.COMPLETED);

        fixture.service.execute(fixture.task);

        assertThat(fixture.onlyMessage().status()).isEqualTo(ChatMessageStatus.COMPLETED);
        verify(fixture.messages, never()).save(any());
        verify(fixture.publisher, never()).publish(anyString(), eq(RealtimeEventTypes.CHAT_MESSAGE), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bufferedTailIsDrainedBeforeSingleFinalWrite(boolean hasAuthoritativeText) {
        var fixture = new Fixture();
        fixture.response = new AgentExecutionResult(List.of(), hasAuthoritativeText ? "full answer" : null, true, null);
        fixture.onExecute = invocation -> {
            fixture.buffer.appendChunk(invocation, "thread", "partial");
            fixture.buffer.appendChunk(invocation, "thread", " tail");
        };

        Invocation result = fixture.service.execute(fixture.task);

        ChatMessage saved = fixture.onlyMessage();
        assertThat(saved.content()).isEqualTo(hasAuthoritativeText ? "full answer" : "partial tail");
        assertThat(saved.status()).isEqualTo(ChatMessageStatus.COMPLETED);
        var writes = ArgumentCaptor.forClass(ChatMessage.class);
        verify(fixture.messages, times(2)).save(writes.capture());
        assertThat(writes.getAllValues()).extracting(ChatMessage::status)
                .containsExactly(ChatMessageStatus.STREAMING, ChatMessageStatus.COMPLETED);
        assertThat(writes.getAllValues().getFirst().content()).isEqualTo("partial tail");
        verify(fixture.messages).findAssistantStreamByInvocationId(result.invocationId());
        verify(fixture.publisher, times(1)).publish(eq("thread"), eq(RealtimeEventTypes.CHAT_MESSAGE), any());
        verify(fixture.buffer).closeCallbacksAndDrain(result.invocationId());
        verify(fixture.buffer, never()).drain(anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failureOrCancellationKeepsBufferedTextAndNeverPublishesCompleted(boolean canceled) {
        var fixture = new Fixture();
        fixture.runtimeFailure = canceled ? null : new IllegalStateException("runtime failed");
        fixture.onExecute = invocation -> {
            fixture.buffer.appendChunk(invocation, "thread", "partial");
            fixture.buffer.appendChunk(invocation, "thread", " tail");
            if (canceled) {
                fixture.invocations.updateStatus(invocation.invocationId(), InvocationStatus.CANCELED);
                fixture.tasks.updateStatus("task", TaskStatus.CANCELED);
            }
        };

        Invocation result = fixture.service.execute(fixture.task);

        assertThat(result.status()).isEqualTo(canceled ? InvocationStatus.CANCELED : InvocationStatus.FAILED);
        ChatMessage saved = fixture.onlyMessage();
        assertThat(saved.status()).isEqualTo(canceled ? ChatMessageStatus.CANCELED : ChatMessageStatus.FAILED);
        assertThat(saved.content()).isEqualTo(canceled ? "partial tail" : "partial tailruntime failed");
        verify(fixture.publisher, never()).publish(anyString(), eq(RealtimeEventTypes.CHAT_MESSAGE),
                argThat(value -> value instanceof ChatMessage message && message.status() == ChatMessageStatus.COMPLETED));
        verify(fixture.buffer, never()).drain(anyString());
    }

    @Test
    void correctsPartialTextAndCompletesInOneReadWriteAndBroadcast() {
        var fixture = new Fixture();
        fixture.onExecute = invocation -> fixture.seed(invocation, ChatMessageStatus.STREAMING);

        Invocation result = fixture.service.execute(fixture.task);

        assertThat(result.status()).isEqualTo(InvocationStatus.SUCCEEDED);
        ChatMessage saved = fixture.onlyMessage();
        assertThat(saved.messageId()).isEqualTo("message");
        assertThat(saved.createdAt()).isEqualTo(fixture.now);
        assertThat(saved.content()).isEqualTo("authoritative answer");
        assertThat(saved.status()).isEqualTo(ChatMessageStatus.COMPLETED);
        fixture.verifySingleFinalization(result, saved);
    }

    @Test
    void cancellationDuringFinalMessageWriteStillWinsExecutionAndMessageStatus() {
        var fixture = new Fixture();
        fixture.onExecute = invocation -> {
            fixture.seed(invocation, ChatMessageStatus.STREAMING);
            doAnswer(call -> {
                ChatMessage message = call.getArgument(0);
                if (message.status() == ChatMessageStatus.COMPLETED) {
                    fixture.invocations.updateStatus(invocation.invocationId(), InvocationStatus.CANCELED);
                    fixture.tasks.updateStatus("task", TaskStatus.CANCELED);
                }
                return call.callRealMethod();
            }).when(fixture.messages).save(any());
        };

        Invocation result = fixture.service.execute(fixture.task);

        assertThat(result.status()).isEqualTo(InvocationStatus.CANCELED);
        assertThat(fixture.tasks.findByTaskId("task").orElseThrow().status()).isEqualTo(TaskStatus.CANCELED);
        assertThat(fixture.onlyMessage().status()).isEqualTo(ChatMessageStatus.CANCELED);
    }

    @Test
    void missingStreamIsCreatedDirectlyAsCompleted() {
        var fixture = new Fixture();

        Invocation result = fixture.service.execute(fixture.task);

        ChatMessage saved = fixture.onlyMessage();
        assertThat(saved.content()).isEqualTo("authoritative answer");
        assertThat(saved.status()).isEqualTo(ChatMessageStatus.COMPLETED);
        fixture.verifySingleFinalization(result, saved);
    }

    @ParameterizedTest
    @EnumSource(value = ChatMessageStatus.class, names = {"FAILED", "CANCELED"})
    void successDoesNotOverwriteProtectedMessageStatusOrBody(ChatMessageStatus status) {
        var fixture = new Fixture();
        fixture.onExecute = invocation -> fixture.seed(invocation, status);

        fixture.service.execute(fixture.task);

        assertThat(fixture.onlyMessage().status()).isEqualTo(status);
        assertThat(fixture.onlyMessage().content()).isEqualTo("partial");
        verify(fixture.messages, never()).save(any());
        verify(fixture.publisher, never()).publish(anyString(), eq(RealtimeEventTypes.CHAT_MESSAGE), any());
    }

    private static final class Fixture {
        final Instant now = Instant.now();
        final InMemoryInvocationRepository invocations = new InMemoryInvocationRepository();
        final InMemoryTaskRepository tasks = new InMemoryTaskRepository();
        final InMemoryChatMessageRepository messages = spy(new InMemoryChatMessageRepository());
        final AssistantStreamBuffer buffer = spy(new AssistantStreamBuffer(messages));
        final ChatEventService publisher = mock(ChatEventService.class);
        final Task task = new Task("task", "user", "trace", null, TaskStatus.PROCESSING,
                TaskSource.USER, 0, "codex", "work", now, now);
        Consumer<Invocation> onExecute = invocation -> {};
        AgentExecutionResult response = new AgentExecutionResult(List.of(), "authoritative answer", true, null);
        RuntimeException runtimeFailure;
        final InvocationService service;

        Fixture() {
            var threads = new InMemoryChatThreadRepository();
            var events = new InMemoryInvocationMessageRepository();
            tasks.save(task);
            threads.save(new ChatThread("thread", "user", "thread", ChatThreadStatus.RUNNING, "trace", now, now));
            service = new InvocationService(invocations, tasks, request -> {
                Invocation invocation = invocations.findByInvocationId(request.invocationId()).orElseThrow();
                events.save(new InvocationMessage("event", "user", invocation.invocationId(), "task", "trace",
                        "codex", AgentMessageType.MESSAGE, "partial", null, now));
                onExecute.accept(invocation);
                clearInvocations(messages, buffer, publisher);
                if (runtimeFailure != null) {
                    throw runtimeFailure;
                }
                return response;
            }, "http://unused", () -> {}, events, threads, messages, publisher, null, buffer,
                    new InMemoryTaskDependencyRepository(), null, null);
        }

        void seed(Invocation invocation, ChatMessageStatus status) {
            messages.save(new ChatMessage("message", "thread", ChatMessageRole.ASSISTANT, "partial", status,
                    invocation.invocationId(), "task", "codex", now, now));
        }

        ChatMessage onlyMessage() {
            return messages.findByThreadId("thread").getFirst();
        }

        void verifySingleFinalization(Invocation invocation, ChatMessage saved) {
            assertThat(messages.findByThreadId("thread")).hasSize(1);
            verify(messages).findAssistantStreamByInvocationId(invocation.invocationId());
            verify(messages).save(saved);
            verify(messages, times(1)).save(any());
            verify(publisher).publish("thread", RealtimeEventTypes.CHAT_MESSAGE, saved);
            verify(publisher, times(1)).publish(eq("thread"), eq(RealtimeEventTypes.CHAT_MESSAGE), any());
            verify(buffer).closeCallbacksAndDrain(invocation.invocationId());
            verify(buffer, never()).drain(anyString());
        }
    }
}
