from datetime import datetime, timezone
from enum import StrEnum
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field


class ContractModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")


class ApiError(ContractModel):
    code: str
    message: str


T = TypeVar("T")


class ApiResponse(ContractModel, Generic[T]):
    success: bool
    data: T | None = None
    error: ApiError | None = None
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    @classmethod
    def ok(cls, data: T) -> "ApiResponse[T]":
        return cls(success=True, data=data)

    @classmethod
    def failure(cls, error: ApiError) -> "ApiResponse[None]":
        return cls(success=False, error=error)


class HealthResponse(ContractModel):
    status: str
    service: str
    timestamp: datetime


class ParsedTask(ContractModel):
    """MODEL_SYNC(ParsedTask) — 改字段时三处同步（无 codegen）：
    - contracts/schemas/parser.schema.json ($defs/ParsedTask)
    - services/platform-api/src/main/java/com/agentcrossing/platform/application/parser/ParsedTask.java
    - services/agent-runtime/src/agent_runtime/contracts/models.py (class ParsedTask)  ← 本文件
    """

    task_id: str = Field(alias="taskId")
    agent_id: str = Field(alias="agentId")
    context: str
    depends_on: list[str] = Field(default_factory=list, alias="dependsOn")


class AvailableAgentCard(ContractModel):
    agent_id: str = Field(alias="agentId")
    display_name: str = Field(alias="displayName")
    role: str
    capabilities: list[str]
    tools: list[str]


class ThreadTaskSummary(ContractModel):
    task_id: str = Field(alias="taskId")
    agent_id: str = Field(alias="agentId")
    status: str
    context: str
    updated_at: str = Field(alias="updatedAt")


class AgentConclusionSummary(ContractModel):
    agent_id: str = Field(alias="agentId")
    task_id: str | None = Field(default=None, alias="taskId")
    content: str
    created_at: str = Field(alias="createdAt")


class ThreadExecutionSummary(ContractModel):
    thread_status: str = Field(alias="threadStatus")
    task_status_counts: dict[str, int] = Field(default_factory=dict, alias="taskStatusCounts")
    recent_tasks: list[ThreadTaskSummary] = Field(default_factory=list, alias="recentTasks")
    latest_agent_conclusions: list[AgentConclusionSummary] = Field(
        default_factory=list,
        alias="latestAgentConclusions",
    )


class UserInputParseRequest(ContractModel):
    user_id: str | None = Field(default=None, alias="userId")
    thread_id: str | None = Field(default=None, alias="threadId")
    trace_id: str | None = Field(default=None, alias="traceId")
    input: str
    provider_session_id: str | None = Field(default=None, alias="providerSessionId")
    provider_prompt_version: str | None = Field(default=None, alias="providerPromptVersion")
    available_agents: list[AvailableAgentCard] = Field(alias="availableAgents")
    thread_execution_summary: ThreadExecutionSummary | None = Field(
        default=None,
        alias="threadExecutionSummary",
    )


class UserInputParseResponse(ContractModel):
    tasks: list[ParsedTask]
    direct_answer: str | None = Field(default=None, alias="directAnswer")
    provider_session_id: str | None = Field(default=None, alias="providerSessionId")
    prompt_version: str | None = Field(default=None, alias="promptVersion")


class AgentMessageType(StrEnum):
    TEXT_DELTA = "textDelta"
    MESSAGE = "message"
    DONE = "done"
    ERROR = "error"


class AgentMessage(ContractModel):
    """MODEL_SYNC(AgentMessage) — 改字段时三处同步（无 codegen）：
    - contracts/schemas/agent-message.schema.json
    - services/platform-api/src/main/java/com/agentcrossing/platform/application/invocation/AgentMessage.java
    - services/agent-runtime/src/agent_runtime/contracts/models.py (class AgentMessage)  ← 本文件
    """

    invocation_id: str = Field(alias="invocationId")
    task_id: str = Field(alias="taskId")
    trace_id: str = Field(alias="traceId")
    agent_id: str = Field(alias="agentId")
    type: AgentMessageType
    content: str | None = None
    raw: Any | None = None
    created_at: datetime = Field(alias="createdAt")


class IncrementalChatMessage(ContractModel):
    message_id: str = Field(alias="messageId")
    role: str
    agent_id: str | None = Field(default=None, alias="agentId")
    task_id: str | None = Field(default=None, alias="taskId")
    content: str
    created_at: str = Field(alias="createdAt")


class AgentContextPack(ContractModel):
    incremental_chat_messages: list[IncrementalChatMessage] = Field(
        default_factory=list,
        alias="incrementalChatMessages",
    )
    available_agents: list[AvailableAgentCard] = Field(default_factory=list, alias="availableAgents")
    startup_summary: str | None = Field(default=None, alias="startupSummary")


class AgentExecutionRequest(ContractModel):
    """MODEL_SYNC(AgentExecutionRequest) — 改字段时三处同步（无 codegen）：
    - contracts/schemas/runtime-event.schema.json ($defs/AgentExecutionRequest)
    - services/platform-api/src/main/java/com/agentcrossing/platform/application/invocation/AgentExecutionRequest.java
    - services/agent-runtime/src/agent_runtime/contracts/models.py (class AgentExecutionRequest)  ← 本文件
    """

    invocation_id: str = Field(alias="invocationId")
    user_id: str = Field(alias="userId")
    task_id: str = Field(alias="taskId")
    trace_id: str = Field(alias="traceId")
    agent_id: str = Field(alias="agentId")
    context: str
    callback_base_url: str | None = Field(default=None, alias="callbackBaseUrl")
    context_pack: AgentContextPack | None = Field(default=None, alias="contextPack")
    provider_session_id: str | None = Field(default=None, alias="providerSessionId")
    provider_prompt_version: str | None = Field(default=None, alias="providerPromptVersion")


class AgentExecutionUsage(ContractModel):
    """MODEL_SYNC(AgentExecutionUsage) — 改字段时三处同步（无 codegen）：
    - contracts/schemas/runtime-event.schema.json ($defs/AgentExecutionUsage)
    - services/platform-api/src/main/java/com/agentcrossing/platform/application/invocation/AgentExecutionUsage.java
    - services/agent-runtime/src/agent_runtime/contracts/models.py (class AgentExecutionUsage)  ← 本文件
    """

    provider: str
    model: str
    provider_session_id: str | None = Field(default=None, alias="providerSessionId")
    total_input_tokens: int | None = Field(default=None, alias="totalInputTokens")
    last_request_input_tokens: int | None = Field(default=None, alias="lastRequestInputTokens")
    usage_precision: str = Field(default="UNKNOWN", alias="usagePrecision")
    input_tokens: int | None = Field(default=None, alias="inputTokens")
    cached_input_tokens: int | None = Field(default=None, alias="cachedInputTokens")
    cache_creation_input_tokens: int | None = Field(default=None, alias="cacheCreationInputTokens")
    cache_read_input_tokens: int | None = Field(default=None, alias="cacheReadInputTokens")
    output_tokens: int | None = Field(default=None, alias="outputTokens")
    reasoning_output_tokens: int | None = Field(default=None, alias="reasoningOutputTokens")
    context_input_tokens: int = Field(alias="contextInputTokens")
    raw_usage_json: dict[str, Any] | None = Field(default=None, alias="rawUsageJson")
    provider_cli_version: str | None = Field(default=None, alias="providerCliVersion")
    observed_at: str = Field(alias="observedAt")


class AgentExecutionResponse(ContractModel):
    messages: list[AgentMessage]
    final_text: str | None = Field(default=None, alias="finalText")
    stream_completed: bool = Field(default=False, alias="streamCompleted")
    last_sequence: int | None = Field(default=None, alias="lastSequence")
    prompt_version: str | None = Field(default=None, alias="promptVersion")
    usage: AgentExecutionUsage | None = None


class SessionCompressionMessage(ContractModel):
    message_id: str = Field(alias="messageId")
    role: str
    agent_id: str | None = Field(default=None, alias="agentId")
    task_id: str | None = Field(default=None, alias="taskId")
    content: str
    created_at: str = Field(alias="createdAt")


class SessionCompressionTask(ContractModel):
    task_id: str = Field(alias="taskId")
    agent_id: str = Field(alias="agentId")
    status: str
    context: str


class SessionCompressionRequest(ContractModel):
    user_id: str = Field(alias="userId")
    thread_id: str = Field(alias="threadId")
    trace_id: str = Field(alias="traceId")
    agent_id: str = Field(alias="agentId")
    provider: str
    generation: int
    previous_startup_summary: str | None = Field(default=None, alias="previousStartupSummary")
    messages: list[SessionCompressionMessage]
    tasks: list[SessionCompressionTask] = Field(default_factory=list)


class SessionCompressionResponse(ContractModel):
    startup_summary: dict[str, Any] = Field(alias="startupSummary")
    summary_prompt_version: str = Field(alias="summaryPromptVersion")


class RuntimeCancelResponse(ContractModel):
    invocation_id: str = Field(alias="invocationId")
    accepted: bool
