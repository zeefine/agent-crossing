package com.agentcrossing.platform.api.controller;

import com.agentcrossing.platform.api.dto.ApiResponse;
import com.agentcrossing.platform.api.dto.InvocationResponse;
import com.agentcrossing.platform.application.auth.CurrentUserResolver;
import com.agentcrossing.platform.domain.invocation.Invocation;
import com.agentcrossing.platform.domain.invocation.InvocationRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invocations")
public class InvocationController {
    private final InvocationRepository invocationRepository;
    private final CurrentUserResolver currentUserResolver;

    public InvocationController(InvocationRepository invocationRepository) {
        this(invocationRepository, new CurrentUserResolver());
    }

    @Autowired
    public InvocationController(InvocationRepository invocationRepository, CurrentUserResolver currentUserResolver) {
        this.invocationRepository = invocationRepository;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping("/{invocationId}")
    public ApiResponse<InvocationResponse> getInvocation(
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = CurrentUserResolver.USER_ID_HEADER,
                            required = false)
                    String userId,
            @PathVariable String invocationId) {
        Invocation invocation = invocationRepository
                .findByInvocationIdAndUserId(invocationId, currentUserResolver.fromHeader(userId).userId())
                .orElseThrow(() -> new IllegalArgumentException("Invocation not found: " + invocationId));
        return ApiResponse.ok(InvocationResponse.from(invocation));
    }

    @GetMapping
    public ApiResponse<List<InvocationResponse>> getInvocations(
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = CurrentUserResolver.USER_ID_HEADER,
                            required = false)
                    String userId,
            @RequestParam String taskId) {
        return ApiResponse.ok(invocationRepository.findByTaskIdAndUserId(
                        taskId, currentUserResolver.fromHeader(userId).userId()).stream()
                .map(InvocationResponse::from)
                .toList());
    }

}
