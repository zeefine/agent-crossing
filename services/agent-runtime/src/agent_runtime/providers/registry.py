from agent_runtime.providers.base import BaseProvider
from agent_runtime.providers.claudecode import ClaudeCodeProvider
from agent_runtime.providers.opencode import OpenCodeProvider


class ProviderRegistry:
    def __init__(self, providers: list[BaseProvider] | None = None) -> None:
        provider_list = [OpenCodeProvider(), ClaudeCodeProvider()] if providers is None else providers
        self._providers = {provider.agent_id: provider for provider in provider_list}

    def get(self, agent_id: str) -> BaseProvider | None:
        return self._providers.get(agent_id)
