package com.agentcrossing.platform.api.controller;

import com.agentcrossing.platform.api.dto.AgentCardResponse;
import com.agentcrossing.platform.api.dto.ApiResponse;
import com.agentcrossing.platform.application.auth.CurrentUserResolver;
import com.agentcrossing.platform.domain.agent.Agent;
import com.agentcrossing.platform.domain.agent.AgentRegistry;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import com.agentcrossing.platform.domain.task.TaskRepository;
import com.agentcrossing.platform.domain.task.TaskStatus;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents")
public class AgentController {
    private final AgentRegistry agentRegistry;
    private final InvocationRepository invocationRepository;
    private final TaskRepository taskRepository;
    private final CurrentUserResolver currentUserResolver;

    public AgentController(
            AgentRegistry agentRegistry,
            InvocationRepository invocationRepository,
            TaskRepository taskRepository) {
        this(agentRegistry, invocationRepository, taskRepository, new CurrentUserResolver());
    }

    @Autowired
    public AgentController(
            AgentRegistry agentRegistry,
            InvocationRepository invocationRepository,
            TaskRepository taskRepository,
            CurrentUserResolver currentUserResolver) {
        this.agentRegistry = agentRegistry;
        this.invocationRepository = invocationRepository;
        this.taskRepository = taskRepository;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping
    public ApiResponse<List<AgentCardResponse>> getAgents(
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = CurrentUserResolver.USER_ID_HEADER,
                            required = false)
                    String userId) {
        String currentUserId = currentUserResolver.fromHeader(userId).userId();
        return ApiResponse.ok(agentRegistry.findAll().stream()
                .map(agent -> toStatus(agent, currentUserId))
                .toList());
    }

    private AgentCardResponse toStatus(Agent agent, String userId) {
        int runningInvocations = invocationRepository.findRunningByAgentIdAndUserId(agent.agentId(), userId).size();
        int processingTasks = (int) taskRepository.findByStatusAndUserId(TaskStatus.PROCESSING, userId).stream()
                .filter(task -> task.agentId().equals(agent.agentId()))
                .count();
        return AgentCardResponse.of(agent, runningInvocations, processingTasks);
    }
}
