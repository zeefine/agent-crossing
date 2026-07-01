package com.agentcrossing.platform.application.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.domain.task.InMemoryTaskRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskSource;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoopGuardServiceTests {
    private final InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
    private final LoopGuardService loopGuardService = new LoopGuardService(taskRepository);

    @Test
    void rejectsTasksDeeperThanLimit() {
        assertThat(loopGuardService.allows(task("candidate", "source", 11, "opencode"))).isFalse();
    }

    @Test
    void rejectsSelfTriggerAfterTenExistingSelfTriggeredTasksInTrace() {
        Task root = task("root", null, 0, "opencode");
        taskRepository.save(root);
        for (int i = 0; i < 10; i++) {
            taskRepository.save(task("self-" + i, "root", i + 1, "opencode"));
        }

        assertThat(loopGuardService.allows(task("candidate", "root", 3, "opencode"))).isFalse();
    }

    @Test
    void allowsDifferentAgentEvenWhenSourceTaskExists() {
        taskRepository.save(task("source", null, 0, "opencode"));

        assertThat(loopGuardService.allows(task("candidate", "source", 1, "claude-code"))).isTrue();
    }

    private static Task task(String taskId, String createdByTaskId, int depth, String agentId) {
        Instant now = Instant.now();
        return new Task(
                taskId,
                "anonymous",
                "trace-1",
                createdByTaskId,
                TaskStatus.QUEUED,
                createdByTaskId == null ? TaskSource.USER : TaskSource.AGENT,
                depth,
                agentId,
                "context",
                now,
                now);
    }
}
