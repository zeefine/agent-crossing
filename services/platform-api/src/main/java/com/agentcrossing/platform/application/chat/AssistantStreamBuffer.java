package com.agentcrossing.platform.application.chat;

import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.message.ChatMessageRepository;
import com.agentcrossing.platform.domain.message.ChatMessageRole;
import com.agentcrossing.platform.domain.message.ChatMessageStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * agent 流式回复的内存缓冲：每个 invocation 一个 {@link PendingFlush}，分片到达时累积到内存，
 * 只在累积达到 {@link #FLUSH_THRESHOLD_BYTES}（默认 1KB UTF-8 字节）时才 UPDATE chat_message。
 *
 * <p>每个分片仍然会立刻返回一个 {@link ChatMessage}（含当前累积内容）让上游 publish 给 WS——
 * 前端的流式打字效果保持不变。省下的是中间那些 UPDATE。</p>
 *
 * <p>约束：</p>
 * <ul>
 *   <li>首个分片**总是**触发 INSERT（要先在 DB 落一行才有 messageId）</li>
 *   <li>中间分片若累积未达阈值，不写 DB，只返回内存 ChatMessage 供 WS push 用</li>
 *   <li>{@link #drain(String)} 由 invocation 完成 / 失败时调用，把还在内存里的尾巴强制 flush 到 DB 并清掉 buffer</li>
 * </ul>
 *
 * <p>线程安全：{@link ConcurrentHashMap} 装 PendingFlush；每个 PendingFlush 内部用 synchronized 锁，
 * 同一 invocation 的并发 callback 串行化。不同 invocation 互不干扰。</p>
 *
 * <p>崩溃语义：platform-api crash 时所有未 flush 的 buffer 内容丢失——这是用户接受的 trade-off
 * （详见 docs 讨论）。重启后 DB 里看到最后一次 flush 的内容。</p>
 */
@Component
public class AssistantStreamBuffer {
    /** 累积达此 UTF-8 字节数后触发 UPDATE。1KB ≈ 1024 英文字符 ≈ 340 个中文字符。 */
    public static final int FLUSH_THRESHOLD_BYTES = 1024;

    private final ChatMessageRepository chatMessageRepository;
    private final ConcurrentMap<String, PendingFlush> buffers = new ConcurrentHashMap<>();

    public AssistantStreamBuffer(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    /**
     * 收到一个分片：累积到 buffer，按需 flush，返回最新累积内容的 ChatMessage（供 WS publish）。
     * 不管是否 flush，返回的 ChatMessage 都带最新内容，messageId 在首次写 DB 时分配后保持稳定。
     */
    public ChatMessage appendChunk(Invocation invocation, String threadId, String chunk) {
        PendingFlush pending = buffers.computeIfAbsent(
                invocation.invocationId(),
                ignored -> new PendingFlush(threadId, invocation));
        synchronized (pending) {
            pending.content.append(chunk);
            pending.utf8BytesSinceLastWrite += chunk.getBytes(StandardCharsets.UTF_8).length;

            boolean firstWrite = pending.messageId == null;
            boolean overThreshold = pending.utf8BytesSinceLastWrite >= FLUSH_THRESHOLD_BYTES;
            if (firstWrite || overThreshold) {
                if (firstWrite) {
                    pending.messageId = "message-" + UUID.randomUUID();
                    pending.createdAt = Instant.now();
                }
                ChatMessage saved = chatMessageRepository.save(buildChatMessage(pending, invocation, ChatMessageStatus.STREAMING));
                pending.utf8BytesSinceLastWrite = 0;
                return saved;
            }
            // 未到阈值：不写 DB，构造一个内存 ChatMessage 让 WS push 携带最新累积内容。
            return buildChatMessage(pending, invocation, ChatMessageStatus.STREAMING);
        }
    }

    /**
     * invocation 终态时调用：把内存 buffer 里剩下的内容强制 flush 到 DB，然后移除 buffer 条目。
     * 调用方拿到一个保证"DB 已和内存同步"的状态，后续可以再做状态翻转（COMPLETED / FAILED）。
     *
     * <p>如果 buffer 不存在（callback 从未到达，纯 batch 路径），no-op。</p>
     */
    public void drain(String invocationId) {
        PendingFlush pending = buffers.remove(invocationId);
        if (pending == null) {
            return;
        }
        synchronized (pending) {
            if (pending.messageId == null) {
                // 还没收到过任何分片，没东西可 flush
                return;
            }
            if (pending.utf8BytesSinceLastWrite == 0) {
                // 上一次 flush 之后没新内容，DB 已经是最新
                return;
            }
            chatMessageRepository.save(buildChatMessage(pending, null, ChatMessageStatus.STREAMING));
        }
    }

    private ChatMessage buildChatMessage(PendingFlush pending, Invocation invocation, ChatMessageStatus status) {
        // drain() 调用时 invocation 已经传不进来，复用 pending 上记下的 taskId / invocationId。
        String invocationId = invocation != null ? invocation.invocationId() : pending.invocationId;
        String taskId = invocation != null ? invocation.taskId() : pending.taskId;
        String agentId = invocation != null ? invocation.agentId() : pending.agentId;
        Instant now = Instant.now();
        return new ChatMessage(
                pending.messageId,
                pending.threadId,
                ChatMessageRole.ASSISTANT,
                pending.content.toString(),
                status,
                invocationId,
                taskId,
                agentId,
                pending.createdAt,
                now);
    }

    private static final class PendingFlush {
        final String threadId;
        final String invocationId;
        final String taskId;
        final String agentId;
        final StringBuilder content = new StringBuilder();
        String messageId;
        Instant createdAt;
        int utf8BytesSinceLastWrite;

        PendingFlush(String threadId, Invocation invocation) {
            this.threadId = threadId;
            this.invocationId = invocation.invocationId();
            this.taskId = invocation.taskId();
            this.agentId = invocation.agentId();
        }
    }
}
