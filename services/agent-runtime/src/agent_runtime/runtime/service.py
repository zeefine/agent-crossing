from fastapi import HTTPException

from agent_runtime.contracts.models import AgentExecutionRequest, AgentExecutionResponse
from agent_runtime.providers.registry import ProviderRegistry


class AgentRuntimeService:
    def __init__(self, provider_registry: ProviderRegistry | None = None) -> None:
        self._provider_registry = provider_registry or ProviderRegistry()

    async def execute(self, request: AgentExecutionRequest) -> AgentExecutionResponse:
        provider = self._provider_registry.get(request.agent_id)
        if provider is None:
            raise HTTPException(status_code=400, detail=f"Unknown agentId: {request.agent_id}")
        messages = await provider.execute(request)
        return AgentExecutionResponse(messages=messages)

