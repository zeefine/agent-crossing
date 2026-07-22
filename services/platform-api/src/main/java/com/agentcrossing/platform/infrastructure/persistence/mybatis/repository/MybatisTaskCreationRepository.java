package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.task.TaskCreation;
import com.agentcrossing.platform.domain.task.TaskCreationRepository;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.TaskCreationMapper;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "mysql")
public class MybatisTaskCreationRepository implements TaskCreationRepository {
    private final TaskCreationMapper taskCreationMapper;

    public MybatisTaskCreationRepository(TaskCreationMapper taskCreationMapper) {
        this.taskCreationMapper = taskCreationMapper;
    }

    @Override
    public Optional<TaskCreation> findBySourceTaskIdAndClientTaskId(String sourceTaskId, String clientTaskId) {
        return Optional.ofNullable(taskCreationMapper.findBySourceTaskIdAndClientTaskId(sourceTaskId, clientTaskId));
    }

    @Override
    public boolean saveIfAbsent(TaskCreation taskCreation) {
        return taskCreationMapper.insertIgnore(taskCreation) == 1;
    }

    @Override
    public void deleteByTraceIdAndUserId(String traceId, String userId) {
        taskCreationMapper.deleteByTraceIdAndUserId(traceId, userId);
    }
}
