package com.agentcrossing.platform.application.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationStatus;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageRepository;
import com.agentcrossing.platform.domain.message.ChatMessageRole;
import com.agentcrossing.platform.domain.message.ChatMessageStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AssistantStreamBufferTests {
    @Test
    void firstChunkAlwaysWritesEvenIfTinyToGetAStableMessageId() {
        RecordingRepository repo = new RecordingRepository();
        AssistantStreamBuffer buffer = new AssistantStreamBuffer(repo);
        Invocation invocation = inv("inv-1", "task-1");

        ChatMessage returned = buffer.appendChunk(invocation, "thread-1", "hi");

        assertThat(repo.saveCount).as("首个分片必须写 DB 拿到 messageId").isEqualTo(1);
        assertThat(returned.content()).isEqualTo("hi");
        assertThat(returned.status()).isEqualTo(ChatMessageStatus.STREAMING);
        assertThat(returned.messageId()).isNotBlank();
        assertThat(returned.agentId()).isEqualTo("opencode");
    }

    @Test
    void subsequentSmallChunksAccumulateInMemoryWithoutDbWrite() {
        RecordingRepository repo = new RecordingRepository();
        AssistantStreamBuffer buffer = new AssistantStreamBuffer(repo);
        Invocation invocation = inv("inv-2", "task-2");

        ChatMessage first = buffer.appendChunk(invocation, "thread-1", "abc");
        // 1KB 阈值下，几十字符根本到不了
        ChatMessage second = buffer.appendChunk(invocation, "thread-1", "def");
        ChatMessage third = buffer.appendChunk(invocation, "thread-1", "ghi");

        assertThat(repo.saveCount).as("仅首个分片写 DB，后续累积").isEqualTo(1);
        assertThat(second.content()).isEqualTo("abcdef");
        assertThat(third.content()).isEqualTo("abcdefghi");
        // messageId 全程稳定
        assertThat(second.messageId()).isEqualTo(first.messageId());
        assertThat(third.messageId()).isEqualTo(first.messageId());
    }

    @Test
    void accumulationOver1KbTriggersFlush() {
        RecordingRepository repo = new RecordingRepository();
        AssistantStreamBuffer buffer = new AssistantStreamBuffer(repo);
        Invocation invocation = inv("inv-3", "task-3");

        // 首分片 100 字节 → write #1
        buffer.appendChunk(invocation, "thread-1", "x".repeat(100));
        // 再来 500 字节 → 累积 500，未到 1024，不 flush
        buffer.appendChunk(invocation, "thread-1", "y".repeat(500));
        assertThat(repo.saveCount).isEqualTo(1);

        // 再来 600 字节 → 累积 1100 ≥ 1024 → flush
        ChatMessage afterOverflow = buffer.appendChunk(invocation, "thread-1", "z".repeat(600));
        assertThat(repo.saveCount).as("过阈值触发第二次写").isEqualTo(2);
        assertThat(afterOverflow.content().length()).isEqualTo(1200);

        // 再来 200 字节 → 计数归零后又涨到 200，不 flush
        buffer.appendChunk(invocation, "thread-1", "w".repeat(200));
        assertThat(repo.saveCount).as("flush 后计数归零，下一波继续累积").isEqualTo(2);
    }

    @Test
    void chineseCharactersHitThresholdFasterByByteCount() {
        // 中文一个字符 = UTF-8 3 字节。一个汉字 ≈ 3 bytes，1KB ≈ 340 字。
        RecordingRepository repo = new RecordingRepository();
        AssistantStreamBuffer buffer = new AssistantStreamBuffer(repo);
        Invocation invocation = inv("inv-4", "task-4");

        // 首分片 1 个汉字 → write #1
        buffer.appendChunk(invocation, "thread-1", "中");
        // 再来 340 个汉字 ≈ 1020 字节，未到 1024，刚好不 flush
        buffer.appendChunk(invocation, "thread-1", "字".repeat(340));
        assertThat(repo.saveCount).isEqualTo(1);

        // 再来 5 个汉字 ≈ 15 字节，累积 1035 ≥ 1024 → flush
        buffer.appendChunk(invocation, "thread-1", "测".repeat(5));
        assertThat(repo.saveCount).isEqualTo(2);
    }

    @Test
    void drainForcesFinalFlushAndRemovesBufferEntry() {
        RecordingRepository repo = new RecordingRepository();
        AssistantStreamBuffer buffer = new AssistantStreamBuffer(repo);
        Invocation invocation = inv("inv-5", "task-5");

        buffer.appendChunk(invocation, "thread-1", "first");   // INSERT
        buffer.appendChunk(invocation, "thread-1", "second");  // 内存
        buffer.appendChunk(invocation, "thread-1", "third");   // 内存
        assertThat(repo.saveCount).isEqualTo(1);

        buffer.drain("inv-5");
        assertThat(repo.saveCount).as("drain 强制 flush 内存尾巴").isEqualTo(2);
        ChatMessage lastSaved = repo.savedMessages.get(repo.savedMessages.size() - 1);
        assertThat(lastSaved.content()).isEqualTo("firstsecondthird");

        // drain 后再 append 等同于新 invocation，新 messageId
        ChatMessage afterDrain = buffer.appendChunk(invocation, "thread-1", "again");
        assertThat(repo.saveCount).as("drain 后 buffer 已移除，新分片是新 INSERT").isEqualTo(3);
        assertThat(afterDrain.messageId()).isNotEqualTo(lastSaved.messageId());
    }

    @Test
    void drainOnUnknownInvocationIsNoOp() {
        RecordingRepository repo = new RecordingRepository();
        AssistantStreamBuffer buffer = new AssistantStreamBuffer(repo);

        buffer.drain("never-existed");
        assertThat(repo.saveCount).isEqualTo(0);
    }

    @Test
    void drainImmediatelyAfterFlushDoesNotWriteAgain() {
        RecordingRepository repo = new RecordingRepository();
        AssistantStreamBuffer buffer = new AssistantStreamBuffer(repo);
        Invocation invocation = inv("inv-6", "task-6");

        // 故意触发一次 flush
        buffer.appendChunk(invocation, "thread-1", "x".repeat(2000));
        assertThat(repo.saveCount).isEqualTo(1);

        // 紧接着 drain：utf8BytesSinceLastWrite=0，不应再写一次
        buffer.drain("inv-6");
        assertThat(repo.saveCount).as("drain 看到 lastWrite 后无新内容，不重复写").isEqualTo(1);
    }

    private static Invocation inv(String invocationId, String taskId) {
        return new Invocation(
                invocationId,
                "anonymous",
                taskId,
                "trace-x",
                "opencode",
                InvocationStatus.RUNNING,
                Instant.now(),
                null,
                null);
    }

    /** 不实现 findAssistantStreamByInvocationId，因为 AssistantStreamBuffer 不调它。 */
    private static final class RecordingRepository implements ChatMessageRepository {
        int saveCount = 0;
        final List<ChatMessage> savedMessages = new ArrayList<>();

        @Override
        public ChatMessage save(ChatMessage message) {
            saveCount++;
            savedMessages.add(message);
            return message;
        }

        @Override
        public List<ChatMessage> findByThreadId(String threadId) {
            return List.of();
        }

        @Override
        public List<ChatMessage> findLatestAgentConclusions(String threadId, String excludedAgentId, int limit) {
            throw new UnsupportedOperationException("Stream buffer must not query planning summaries");
        }

        @Override
        public List<ChatMessage> findVisibleMessagesAfterCursor(
                String threadId,
                String currentAgentId,
                Instant lastInjectedCreatedAt,
                String lastInjectedMessageId,
                int limit) {
            return List.of();
        }

        @Override
        public Optional<ChatMessage> findAssistantStreamByInvocationId(String invocationId) {
            return Optional.empty();
        }

        @Override
        public void deleteByThreadId(String threadId) {
        }
    }
}
