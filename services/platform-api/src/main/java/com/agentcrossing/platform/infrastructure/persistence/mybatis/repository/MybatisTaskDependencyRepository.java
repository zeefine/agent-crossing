package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.task.TaskDependency;
import com.agentcrossing.platform.domain.task.TaskDependencyRepository;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.TaskDependencyMapper;
import java.util.Collection;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "mysql")
public class MybatisTaskDependencyRepository implements TaskDependencyRepository {
    private final TaskDependencyMapper taskDependencyMapper;

    public MybatisTaskDependencyRepository(TaskDependencyMapper taskDependencyMapper) {
        this.taskDependencyMapper = taskDependencyMapper;
    }

    @Override
    public void saveAll(Collection<TaskDependency> dependencies) {
        if (!dependencies.isEmpty()) {
            taskDependencyMapper.insertAll(List.copyOf(dependencies));
        }
    }

    @Override
    public List<String> findParentTaskIds(String childTaskId) {
        return taskDependencyMapper.findParentTaskIds(childTaskId);
    }

    @Override
    public List<String> findChildTaskIds(String parentTaskId) {
        return taskDependencyMapper.findChildTaskIds(parentTaskId);
    }
}
