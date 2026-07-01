from abc import ABC, abstractmethod

from agent_runtime.contracts.models import AgentExecutionRequest, AgentMessage


class BaseProvider(ABC):
    @property
    @abstractmethod
    def agent_id(self) -> str:
        raise NotImplementedError

    @abstractmethod
    async def execute(self, request: AgentExecutionRequest) -> list[AgentMessage]:
        raise NotImplementedError

