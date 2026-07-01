package com.agentcrossing.platform.domain.task;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryTaskDependencyRepository implements TaskDependencyRepository {
    private final Set<TaskDependency> dependencies = ConcurrentHashMap.newKeySet();

    @Override
    public void saveAll(Collection<TaskDependency> dependencies) {
        this.dependencies.addAll(dependencies);
    }

    @Override
    public List<String> findParentTaskIds(String childTaskId) {
        return dependencies.stream()
                .filter(dependency -> dependency.childTaskId().equals(childTaskId))
                .map(TaskDependency::parentTaskId)
                .sorted()
                .toList();
    }

    @Override
    public List<String> findChildTaskIds(String parentTaskId) {
        return dependencies.stream()
                .filter(dependency -> dependency.parentTaskId().equals(parentTaskId))
                .map(TaskDependency::childTaskId)
                .sorted()
                .toList();
    }
}
