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


class UserInputParseRequest(ContractModel):
    user_id: str | None = Field(default=None, alias="userId")
    thread_id: str | None = Field(default=None, alias="threadId")
    trace_id: str | None = Field(default=None, alias="traceId")
    input: str
    provider_session_id: str | None = Field(default=None, alias="providerSessionId")
    available_agents: list[AvailableAgentCard] = Field(alias="availableAgents")


class UserInputParseResponse(ContractModel):
    tasks: list[ParsedTask]
    direct_answer: str | None = Field(default=None, alias="directAnswer")
    provider_session_id: str | None = Field(default=None, alias="providerSessionId")


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


class AgentExecutionResponse(ContractModel):
    messages: list[AgentMessage]
