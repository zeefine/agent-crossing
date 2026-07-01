package com.agentcrossing.platform.api.dto;

import com.agentcrossing.platform.application.chat.ChatSubmitResult;
import com.agentcrossing.platform.domain.task.TaskDependencyRepository;
import java.util.List;

public record SubmitChatMessageResponse(
        ChatThreadResponse thread,
        ChatMessageResponse message,
        ChatMessageResponse assistantMessage,
        List<TaskResponse> tasks) {
    public static SubmitChatMessageResponse from(
            ChatSubmitResult result, TaskDependencyRepository taskDependencyRepository) {
        return new SubmitChatMessageResponse(
                ChatThreadResponse.from(result.thread()),
                ChatMessageResponse.from(result.userMessage()),
                result.assistantMessage() == null ? null : ChatMessageResponse.from(result.assistantMessage()),
                result.tasks().stream()
                        .map(task -> TaskResponse.from(
                                task, taskDependencyRepository.findParentTaskIds(task.taskId())))
                        .toList());
    }
}
