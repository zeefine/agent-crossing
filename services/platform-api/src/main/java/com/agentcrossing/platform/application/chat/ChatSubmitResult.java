package com.agentcrossing.platform.application.chat;

import com.agentcrossing.platform.domain.chat.ChatThread;
import com.agentcrossing.platform.domain.message.ChatMessage;
import com.agentcrossing.platform.domain.task.Task;
import java.util.List;

public record ChatSubmitResult(
        ChatThread thread,
        ChatMessage userMessage,
        ChatMessage assistantMessage,
        List<Task> tasks) {
}
