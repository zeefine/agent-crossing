from datetime import datetime, timezone
from typing import Any

from agent_runtime.contracts.models import AgentExecutionRequest, AgentMessage, AgentMessageType


class AgentMessageNormalizer:
    def text_delta(self, request: AgentExecutionRequest, content: str, raw: Any | None = None) -> AgentMessage:
        return self._message(request, AgentMessageType.TEXT_DELTA, content, raw)

    def message(self, request: AgentExecutionRequest, content: str, raw: Any | None = None) -> AgentMessage:
        return self._message(request, AgentMessageType.MESSAGE, content, raw)

    def error(self, request: AgentExecutionRequest, content: str, raw: Any | None = None) -> AgentMessage:
        return self._message(request, AgentMessageType.ERROR, content, raw)

    def done(self, request: AgentExecutionRequest, raw: Any | None = None) -> AgentMessage:
        return self._message(request, AgentMessageType.DONE, None, raw)

    def _message(
        self,
        request: AgentExecutionRequest,
        message_type: AgentMessageType,
        content: str | None,
        raw: Any | None,
    ) -> AgentMessage:
        return AgentMessage(
            invocationId=request.invocation_id,
            taskId=request.task_id,
            traceId=request.trace_id,
            agentId=request.agent_id,
            type=message_type,
            content=content,
            raw=raw,
            createdAt=datetime.now(timezone.utc),
        )

