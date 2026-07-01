package com.agentcrossing.platform.domain.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QuestHubTests {
    @Test
    void supportsEnqueuePeekPollRemoveAndSnapshot() {
        QuestHub questHub = new QuestHub();
        String first = "task-1";
        String second = "task-2";

        questHub.enqueue(first);
        questHub.enqueue(second);

        assertThat(questHub.peek()).contains(first);
        assertThat(questHub.snapshot()).containsExactly(first, second);
        assertThat(questHub.remove("task-1")).isTrue();
        assertThat(questHub.poll()).contains(second);
        assertThat(questHub.isEmpty()).isTrue();
    }

    @Test
    void supportsDoubleEndedOperations() {
        QuestHub questHub = new QuestHub();
        String middle = "task-middle";
        String first = "task-first";
        String last = "task-last";

        questHub.enqueueLast(middle);
        questHub.enqueueFirst(first);
        questHub.enqueueLast(last);

        assertThat(questHub.peekFirst()).contains(first);
        assertThat(questHub.peekLast()).contains(last);
        assertThat(questHub.snapshot()).containsExactly(first, middle, last);
        assertThat(questHub.dequeueLast()).contains(last);
        assertThat(questHub.dequeueFirst()).contains(first);
        assertThat(questHub.dequeueFirst()).contains(middle);
        assertThat(questHub.isEmpty()).isTrue();
    }
}
