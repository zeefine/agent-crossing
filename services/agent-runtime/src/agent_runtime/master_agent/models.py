from pydantic import BaseModel, ConfigDict, Field


class MasterAgentModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")


class PlannedTask(MasterAgentModel):
    task_id: str = Field(alias="taskId")
    agent_id: str = Field(alias="agentId")
    context: str
    depends_on: list[str] = Field(default_factory=list, alias="dependsOn")


class SubmitTaskPlanInput(MasterAgentModel):
    planning_session_id: str = Field(alias="planningSessionId")
    tasks: list[PlannedTask]


class SubmitTaskPlanResult(MasterAgentModel):
    accepted: bool
    task_count: int = Field(alias="taskCount")
    message: str


class SubmitDirectAnswerResult(MasterAgentModel):
    accepted: bool
    message: str


class TaskStatusItem(MasterAgentModel):
    task_id: str = Field(alias="taskId")
    user_id: str = Field(alias="userId")
    trace_id: str = Field(alias="traceId")
    created_by_task_id: str | None = Field(default=None, alias="createdByTaskId")
    depends_on: list[str] = Field(default_factory=list, alias="dependsOn")
    status: str
    source: str
    depth: int
    agent_id: str = Field(alias="agentId")
    context: str
    created_at: str = Field(alias="createdAt")
    updated_at: str = Field(alias="updatedAt")


class TaskStatusSnapshotResult(MasterAgentModel):
    current_task_id: str = Field(alias="currentTaskId")
    user_id: str = Field(alias="userId")
    downstream_tasks: list[TaskStatusItem] = Field(alias="downstreamTasks")
    total_count: int = Field(alias="totalCount")
    status_counts: dict[str, int] = Field(alias="statusCounts")
    message: str


class CreateTasksResult(MasterAgentModel):
    source_task_id: str = Field(alias="sourceTaskId")
    user_id: str = Field(alias="userId")
    created_tasks: list[TaskStatusItem] = Field(alias="createdTasks")
    created_count: int = Field(alias="createdCount")
    message: str
