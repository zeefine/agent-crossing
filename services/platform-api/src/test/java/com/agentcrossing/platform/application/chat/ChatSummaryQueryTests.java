package com.agentcrossing.platform.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.agentcrossing.platform.application.parser.ThreadExecutionSummary;
import com.agentcrossing.platform.domain.chat.*;
import com.agentcrossing.platform.domain.message.*;
import com.agentcrossing.platform.domain.task.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatSummaryQueryTests {
    @Test
    void boundedReadsKeepStableTieOrderAndFilterBeforeSelectingLatestPerAgent() {
        var tasks = new InMemoryTaskRepository();
        var messages = new InMemoryChatMessageRepository();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        for (String id : java.util.List.of("b", "a")) {
            tasks.save(new Task(id, "user", "trace", null, TaskStatus.COMPLETED, TaskSource.USER, 0, "codex", "work", now, now));
            messages.save(new ChatMessage(id, "thread", ChatMessageRole.ASSISTANT, id, ChatMessageStatus.COMPLETED,
                    null, null, "codex", now, now));
        }
        messages.save(new ChatMessage("new-failed", "thread", ChatMessageRole.ASSISTANT, "skip", ChatMessageStatus.FAILED,
                null, null, "codex", now.plusSeconds(1), now));
        messages.save(new ChatMessage("blank-agent", "thread", ChatMessageRole.ASSISTANT, "skip", ChatMessageStatus.COMPLETED,
                null, null, " \t", now.plusSeconds(2), now));
        messages.save(new ChatMessage("foreign", "other-thread", ChatMessageRole.ASSISTANT, "skip", ChatMessageStatus.COMPLETED,
                null, null, "other", now.plusSeconds(3), now));

        assertThat(tasks.findRecentByTraceIdAndUserId("trace", "user", 1)).extracting(Task::taskId).containsExactly("a");
        assertThat(messages.findLatestAgentConclusions("thread", "masteragent", 6)).extracting(ChatMessage::messageId).containsExactly("a");
        assertThat(tasks.findRecentByTraceIdAndUserId("trace", "user", -1)).isEmpty();
        assertThat(messages.findLatestAgentConclusions("thread", "masteragent", 0)).isEmpty();
    }

    @Test
    void boundedSummaryDoesNotLoadEntireTaskOrMessageHistory() {
        var tasks = spy(new InMemoryTaskRepository());
        var messages = spy(new InMemoryChatMessageRepository());
        var service = new ChatService(new InMemoryChatThreadRepository(), messages, null);
        ReflectionTestUtils.setField(service, "taskRepository", tasks);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        var thread = new ChatThread("thread", "user", "title", ChatThreadStatus.RUNNING, "trace", base, base);
        for (int index = 0; index < 200; index++) {
            Instant at = base.plusSeconds(index);
            tasks.save(new Task("task-" + index, "user", "trace", null, TaskStatus.COMPLETED,
                    TaskSource.USER, 0, "agent-" + index % 8, "context", at, at));
            messages.save(new ChatMessage("msg-" + index, "thread", ChatMessageRole.ASSISTANT, "answer-" + index,
                    ChatMessageStatus.COMPLETED, null, "task-" + index, "agent-" + index % 8, at, at));
        }
        tasks.save(new Task("foreign", "other-user", "trace", null, TaskStatus.FAILED,
                TaskSource.USER, 0, "codex", "private", base, base));
        messages.save(new ChatMessage("master", "thread", ChatMessageRole.ASSISTANT, "exclude",
                ChatMessageStatus.COMPLETED, null, null, "masteragent", base.plusSeconds(999), base));
        messages.save(new ChatMessage("stream", "thread", ChatMessageRole.ASSISTANT, "exclude",
                ChatMessageStatus.STREAMING, null, null, "agent-7", base.plusSeconds(999), base));

        ThreadExecutionSummary summary = ReflectionTestUtils.invokeMethod(service, "buildThreadExecutionSummary", thread);

        assertThat(summary.taskStatusCounts()).containsExactlyEntriesOf(java.util.Map.of("completed", 200));
        assertThat(summary.recentTasks()).hasSize(12);
        assertThat(summary.recentTasks().getFirst().taskId()).isEqualTo("task-199");
        assertThat(summary.latestAgentConclusions()).hasSize(6);
        assertThat(summary.latestAgentConclusions().getFirst().content()).isEqualTo("answer-199");
        verify(tasks, never()).findByTraceIdAndUserId(anyString(), anyString());
        verify(messages, never()).findByThreadId(anyString());
    }
}
