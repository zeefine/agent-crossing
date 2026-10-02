package com.agentcrossing.platform.application.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentcrossing.platform.application.task.TaskEventService;
import com.agentcrossing.platform.domain.invocation.*;
import com.agentcrossing.platform.domain.queue.QuestHub;
import com.agentcrossing.platform.domain.task.*;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.TaskMapper;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.repository.MybatisTaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class RouterBatchQueryTests {
    @Test
    void springInjectsOptimizedSnapshotReaderFromMysqlTaskRepository() {
        new ApplicationContextRunner()
                .withPropertyValues("agent-crossing.storage-mode=mysql")
                .withBean(TaskMapper.class, () -> mock(TaskMapper.class))
                .withBean(MybatisTaskRepository.class)
                .withBean(TaskDependencyRepository.class, () -> mock(TaskDependencyRepository.class))
                .withBean(InvocationRepository.class, () -> mock(InvocationRepository.class))
                .withBean(QuestHub.class)
                .withBean(ParallelTaskWorker.class, () -> mock(ParallelTaskWorker.class))
                .withBean(TaskEventService.class, () -> mock(TaskEventService.class))
                .withBean(com.agentcrossing.platform.application.invocation.AgentSessionCompressionService.class,
                        () -> mock(com.agentcrossing.platform.application.invocation.AgentSessionCompressionService.class))
                .withBean(QuestRouterService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(ReflectionTestUtils.getField(context.getBean(QuestRouterService.class), "dispatchSnapshotRepository"))
                            .isSameAs(context.getBean(MybatisTaskRepository.class));
                });
    }

    @Test
    void preservesQueueOrderSkipsBusyWaitingAndCompactingAndDoesNotReviveCancellation() {
        var tasks = new InMemoryTaskRepository();
        var queue = new QuestHub();
        var worker = mock(ParallelTaskWorker.class);
        when(worker.submit(any())).thenReturn(true);
        var router = new QuestRouterService(queue, tasks, new InMemoryTaskDependencyRepository(),
                new InMemoryInvocationRepository(), worker, mock(TaskEventService.class));
        Instant now = Instant.now();
        List<String> ids = List.of("missing", "busy", "waiting", "compacting", "canceled", "first", "second");
        ids.stream().filter(id -> !id.equals("missing")).forEach(id -> tasks.save(new Task(id, "user", "trace", null,
                TaskStatus.QUEUED, TaskSource.USER, 0, id, "context", now, now)));
        queue.enqueueAll(ids);
        router.setDispatchSnapshotRepository(batch -> {
            // Cancellation commits after the snapshot was read but before the router claims it.
            tasks.updateStatus("canceled", TaskStatus.CANCELED);
            return List.of(new TaskDispatchSnapshot("second", false, false, false, false),
                    new TaskDispatchSnapshot("first", false, false, false, false),
                    new TaskDispatchSnapshot("canceled", false, false, false, false),
                    new TaskDispatchSnapshot("busy", false, false, true, false),
                    new TaskDispatchSnapshot("waiting", false, true, false, false),
                    new TaskDispatchSnapshot("compacting", false, false, false, true));
        });

        assertThat(router.processNext()).map(Task::taskId).contains("first");
        assertThat(tasks.findByTaskId("canceled").orElseThrow().status()).isEqualTo(TaskStatus.CANCELED);
        assertThat(queue.snapshot()).containsExactly("busy", "waiting", "compacting", "second");
        verify(worker).submit(argThat(task -> task.taskId().equals("first") && task.status() == TaskStatus.PROCESSING));
    }

    @Test
    void refreshesSnapshotAfterBlockingParentSoDescendantsCannotStayQueued() {
        var tasks = new InMemoryTaskRepository();
        var queue = new QuestHub();
        var snapshots = mock(TaskDispatchSnapshotRepository.class);
        var worker = mock(ParallelTaskWorker.class);
        var router = new QuestRouterService(queue, tasks, new InMemoryTaskDependencyRepository(),
                new InMemoryInvocationRepository(), worker, mock(TaskEventService.class));
        router.setDispatchSnapshotRepository(snapshots);
        Instant now = Instant.now();
        for (String id : List.of("parent", "child")) {
            tasks.save(new Task(id, "user", "trace", null, TaskStatus.QUEUED, TaskSource.USER, 0, id, "work", now, now));
            queue.enqueue(id);
        }
        when(snapshots.findDispatchSnapshots(List.of("parent", "child"))).thenReturn(List.of(
                new TaskDispatchSnapshot("parent", true, true, false, false),
                new TaskDispatchSnapshot("child", false, true, false, false)));
        when(snapshots.findDispatchSnapshots(List.of("child"))).thenAnswer(call -> {
            assertThat(tasks.findByTaskId("parent").orElseThrow().status()).isEqualTo(TaskStatus.BLOCKED);
            return List.of(new TaskDispatchSnapshot("child", true, true, false, false));
        });

        assertThat(router.processNext()).isEmpty();
        assertThat(queue.snapshot()).isEmpty();
        assertThat(tasks.findAll()).allSatisfy(task -> assertThat(task.status()).isEqualTo(TaskStatus.BLOCKED));
        verify(snapshots, times(2)).findDispatchSnapshots(anyList());
        verifyNoInteractions(worker);
    }

    @Test
    void scansThreeHundredBusyTasksInThreeBatchesWithoutPerTaskReads() {
        var mapper = mock(TaskMapper.class);
        var tasks = new MybatisTaskRepository(mapper);
        var dependencies = mock(TaskDependencyRepository.class);
        var invocations = mock(InvocationRepository.class);
        var queue = new QuestHub();
        var worker = mock(ParallelTaskWorker.class);
        var router = new QuestRouterService(queue, tasks, dependencies, invocations, worker, mock(TaskEventService.class));
        router.setDispatchSnapshotRepository(tasks);
        List<String> ids = IntStream.range(0, 300).mapToObj(index -> "task-" + index).toList();
        queue.enqueueAll(ids);
        Instant now = Instant.now();
        when(mapper.findByTaskId(anyString())).thenAnswer(call -> new Task(call.getArgument(0), "user", "trace", null,
                TaskStatus.QUEUED, TaskSource.USER, 0, "codex", "context", now, now));
        when(invocations.findRunningByAgentIdAndUserId(anyString(), anyString())).thenReturn(List.of(
                new Invocation("inv", "user", "running", "trace", "codex", InvocationStatus.RUNNING, now, now, null)));
        when(mapper.findDispatchSnapshots(anyList())).thenAnswer(call -> {
            List<String> batch = call.getArgument(0);
            assertThat(batch).hasSizeLessThanOrEqualTo(128);
            return batch.stream().map(id -> new TaskDispatchSnapshot(id, false, false, true, false)).toList();
        });

        assertThat(router.processNext()).isEmpty();
        assertThat(queue.snapshot()).containsExactlyElementsOf(ids);
        verify(mapper, times(3)).findDispatchSnapshots(anyList());
        verify(mapper, never()).findByTaskId(anyString());
        verifyNoInteractions(dependencies, invocations, worker);
    }
}
