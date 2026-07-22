import asyncio
import logging

from fastapi import HTTPException

from agent_runtime.contracts.models import AgentExecutionRequest, AgentExecutionResponse
from agent_runtime.providers.registry import ProviderRegistry

logger = logging.getLogger(__name__)


class AgentRuntimeService:
    def __init__(self, provider_registry: ProviderRegistry | None = None) -> None:
        self._provider_registry = provider_registry or ProviderRegistry()
        self._active_execution_tasks: dict[str, asyncio.Task[object]] = {}
        self._active_execution_lock = asyncio.Lock()

    async def execute(self, request: AgentExecutionRequest) -> AgentExecutionResponse:
        provider = self._provider_registry.get(request.agent_id)
        if provider is None:
            raise HTTPException(status_code=400, detail=f"Unknown agentId: {request.agent_id}")
        current_task = asyncio.current_task()
        if current_task is None:
            raise RuntimeError("Agent runtime execute must run inside an asyncio task")
        async with self._active_execution_lock:
            self._active_execution_tasks[request.invocation_id] = current_task
        try:
            messages = await provider.execute(request)
        except asyncio.CancelledError:
            # The platform has already committed CANCELED before it asks us to terminate the CLI.
            logger.info("Runtime invocation canceled invocationId=%s", request.invocation_id)
            return AgentExecutionResponse(messages=[])
        finally:
            async with self._active_execution_lock:
                if self._active_execution_tasks.get(request.invocation_id) is current_task:
                    self._active_execution_tasks.pop(request.invocation_id, None)
        done_raw = next(
            (
                message.raw
                for message in reversed(messages)
                if message.type.value == "done" and isinstance(message.raw, dict)
            ),
            {},
        )
        return AgentExecutionResponse(
            messages=messages,
            finalText=self._aggregate_final_text(messages),
            streamCompleted=bool(done_raw.get("callbackCompleted", False)),
            lastSequence=done_raw.get("callbackLastSequence"),
            promptVersion=done_raw.get("promptVersion"),
        )

    async def cancel(self, invocation_id: str) -> bool:
        async with self._active_execution_lock:
            execution_task = self._active_execution_tasks.get(invocation_id)
            if execution_task is None or execution_task.done():
                return False
            execution_task.cancel()
            return True

    async def aclose(self) -> None:
        await self._provider_registry.aclose()

    @staticmethod
    def _aggregate_final_text(messages: list) -> str | None:
        parts: list[str] = []
        for message in messages:
            if message.type.value not in ("textDelta", "message") or not message.content:
                continue
            if isinstance(message.raw, dict) and message.raw.get("tool"):
                continue
            if message.content == "tool_use" or message.content.startswith("tool_use:"):
                continue
            parts.append(message.content)
        text = "".join(parts).strip()
        return text or None
