package com.agentcrossing.platform.application.task;

import com.agentcrossing.platform.api.dto.TaskResponse;
import com.agentcrossing.platform.application.chat.ChatEventService;
import com.agentcrossing.platform.application.realtime.RealtimeEventTypes;
import com.agentcrossing.platform.domain.chat.ChatThreadRepository;
import com.agentcrossing.platform.domain.task.Task;
import com.agentcrossing.platform.domain.task.TaskDependencyRepository;
import org.springframework.stereotype.Service;

@Service
public class TaskEventService {
    private final ChatThreadRepository chatThreadRepository;
    private final ChatEventService chatEventService;
    private final TaskDependencyRepository taskDependencyRepository;

    public TaskEventService(
            ChatThreadRepository chatThreadRepository,
            ChatEventService chatEventService,
            TaskDependencyRepository taskDependencyRepository) {
        this.chatThreadRepository = chatThreadRepository;
        this.chatEventService = chatEventService;
        this.taskDependencyRepository = taskDependencyRepository;
    }

    public void publish(Task task) {
        chatThreadRepository.findByTraceId(task.traceId())
                .ifPresent(thread -> chatEventService.publish(
                        thread.threadId(),
                        RealtimeEventTypes.TASK,
                        TaskResponse.from(task, taskDependencyRepository.findParentTaskIds(task.taskId()))));
    }
}
