package com.agentcrossing.platform.domain.task;

import java.util.Collection;
import java.util.List;

public interface TaskDependencyRepository {
    void saveAll(Collection<TaskDependency> dependencies);

    List<String> findParentTaskIds(String childTaskId);

    List<String> findChildTaskIds(String parentTaskId);

    void deleteByTaskIds(Collection<String> taskIds);
}
