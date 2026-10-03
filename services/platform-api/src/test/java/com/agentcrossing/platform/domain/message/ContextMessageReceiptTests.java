package com.agentcrossing.platform.domain.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextMessageReceiptTests {
    @Test
    void receiptUsesStableSha256OfContentNotWallClockOrMessageId() {
        assertThat(ContextMessageReceipt.of("a", "abc").contentVersion())
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(ContextMessageReceipt.of("a", "正文😀").contentVersion())
                .isEqualTo(ContextMessageReceipt.of("b", "正文😀").contentVersion())
                .isNotEqualTo(ContextMessageReceipt.of("a", "正文").contentVersion());
    }

    @Test
    void deliveryDoesNotOverwriteSummaryCoverageAndNewBaselineResetsIt() {
        var repository = new InMemoryChatMessageRepository();
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        for (String id : List.of("m1", "m2")) {
            repository.save(new ChatMessage(id, "thread", ChatMessageRole.USER, "old", ChatMessageStatus.COMPLETED,
                    null, null, null, now, now));
        }
        var old = List.of(ContextMessageReceipt.of("m1", "old"), ContextMessageReceipt.of("m2", "old"));
        var revised = List.of(ContextMessageReceipt.of("m1", "new"));
        repository.acknowledgeSummarizedMessages("alice", "thread", "codex", old, false);
        repository.acknowledgeSummarizedMessages("alice", "thread", "claudecode", old, false);
        repository.acknowledgeContextMessages("alice", "thread", "codex", revised);
        assertThat(repository.findSummarizedContextMessages("alice", "thread", "codex"))
                .containsExactlyInAnyOrderElementsOf(old);
        repository.acknowledgeSummarizedMessages("alice", "thread", "codex", revised, true);
        assertThat(repository.findSummarizedContextMessages("alice", "thread", "codex")).containsExactlyElementsOf(revised);
        assertThat(repository.findSummarizedContextMessages("alice", "thread", "claudecode"))
                .containsExactlyInAnyOrderElementsOf(old);
        repository.deleteByThreadId("thread");
        assertThat(repository.findSummarizedContextMessages("alice", "thread", "codex")).isEmpty();
    }

    @Test
    void receiptsAreScopedToUserThreadAgentAndRemovedWithMessages() {
        var repository = new InMemoryChatMessageRepository();
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        var message = new ChatMessage("m", "thread", ChatMessageRole.USER, "abc", ChatMessageStatus.COMPLETED,
                null, null, null, now, now);
        var receipt = List.of(ContextMessageReceipt.of("m", "abc"));
        repository.save(message);
        repository.acknowledgeContextMessages("alice", "wrong-thread", "codex", receipt);
        assertThat(repository.findUnacknowledgedVisibleMessages("alice", "thread", "codex", 20)).containsExactly(message);
        repository.acknowledgeContextMessages("alice", "thread", "codex", receipt);
        assertThat(repository.findUnacknowledgedVisibleMessages("alice", "thread", "codex", 20)).isEmpty();
        assertThat(repository.findUnacknowledgedVisibleMessages("alice", "thread", "claudecode", 20)).containsExactly(message);
        assertThat(repository.findUnacknowledgedVisibleMessages("bob", "thread", "codex", 20)).containsExactly(message);
        repository.deleteByThreadId("thread");
        repository.acknowledgeContextMessages("alice", "thread", "codex", receipt); // Late acknowledgment after delete.
        repository.save(message);
        assertThat(repository.findUnacknowledgedVisibleMessages("alice", "thread", "codex", 20)).containsExactly(message);
    }
}
