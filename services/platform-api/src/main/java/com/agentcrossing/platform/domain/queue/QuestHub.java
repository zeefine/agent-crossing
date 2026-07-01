package com.agentcrossing.platform.domain.queue;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class QuestHub {
    private final Deque<String> taskIds = new ArrayDeque<>();

    public synchronized void enqueue(String taskId) {
        enqueueLast(taskId);
    }

    public synchronized void enqueueFirst(String taskId) {
        taskIds.addFirst(taskId);
    }

    public synchronized void enqueueLast(String taskId) {
        taskIds.addLast(taskId);
    }

    public synchronized void enqueueAll(Collection<String> newTaskIds) {
        for (String taskId : newTaskIds) {
            enqueueLast(taskId);
        }
    }

    public synchronized Optional<String> peek() {
        return peekFirst();
    }

    public synchronized Optional<String> peekFirst() {
        return Optional.ofNullable(taskIds.peekFirst());
    }

    public synchronized Optional<String> peekLast() {
        return Optional.ofNullable(taskIds.peekLast());
    }

    public synchronized Optional<String> poll() {
        return dequeueFirst();
    }

    public synchronized Optional<String> dequeueFirst() {
        return Optional.ofNullable(taskIds.pollFirst());
    }

    public synchronized Optional<String> dequeueLast() {
        return Optional.ofNullable(taskIds.pollLast());
    }

    public synchronized boolean remove(String taskId) {
        return taskIds.removeIf(queuedTaskId -> queuedTaskId.equals(taskId));
    }

    public synchronized List<String> snapshot() {
        return List.copyOf(taskIds);
    }

    public synchronized boolean isEmpty() {
        return taskIds.isEmpty();
    }
}
