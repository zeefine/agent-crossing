from agent_runtime.providers.base import BaseProvider
from agent_runtime.callback.dispatcher import CallbackDispatcher
from agent_runtime.providers.claudecode import ClaudeCodeProvider
from agent_runtime.providers.codex import CodexProvider
from agent_runtime.providers.opencode import OpenCodeProvider


class ProviderRegistry:
    def __init__(self, providers: list[BaseProvider] | None = None) -> None:
        self._callback_dispatcher = CallbackDispatcher() if providers is None else None
        provider_list = (
            [
                OpenCodeProvider(callback_dispatcher=self._callback_dispatcher),
                ClaudeCodeProvider(callback_dispatcher=self._callback_dispatcher),
                CodexProvider(callback_dispatcher=self._callback_dispatcher),
            ]
            if providers is None
            else providers
        )
        self._providers = {provider.agent_id: provider for provider in provider_list}

    def get(self, agent_id: str) -> BaseProvider | None:
        return self._providers.get(agent_id)

    async def aclose(self) -> None:
        if self._callback_dispatcher is not None:
            await self._callback_dispatcher.aclose()
