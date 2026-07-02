package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import com.agentcrossing.platform.domain.task.TaskDependency;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskDependencyMapper {
    void insertAll(@Param("dependencies") List<TaskDependency> dependencies);

    List<String> findParentTaskIds(@Param("childTaskId") String childTaskId);

    List<String> findChildTaskIds(@Param("parentTaskId") String parentTaskId);

    void deleteByTaskIds(@Param("taskIds") List<String> taskIds);
}
