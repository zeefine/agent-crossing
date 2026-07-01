package com.agentcrossing.platform.api.dto;

import com.agentcrossing.platform.domain.task.Task;
import java.util.List;

public record QueueSnapshotResponse(List<TaskResponse> questHub) {}
