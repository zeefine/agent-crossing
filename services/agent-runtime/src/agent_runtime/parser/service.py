from agent_runtime.contracts.models import (
    AgentOutputParseRequest,
    AgentOutputParseResponse,
    UserInputParseRequest,
    UserInputParseResponse,
)
from agent_runtime.parser.agent_output_parser import AgentOutputParser
from agent_runtime.master_agent.master_agent_planner import MasterAgentPlanner


class QuestParserService:
    def __init__(
        self,
        agent_output_parser: AgentOutputParser | None = None,
        master_agent_planner: MasterAgentPlanner | None = None,
    ) -> None:
        self._agent_output_parser = agent_output_parser or AgentOutputParser()
        self._master_agent_planner = master_agent_planner or MasterAgentPlanner()

    async def parse_user_input(self, request: UserInputParseRequest) -> UserInputParseResponse:
        return await self._master_agent_planner.plan_user_input(request)

    def parse_agent_output(self, request: AgentOutputParseRequest) -> AgentOutputParseResponse:
        return self._agent_output_parser.parse(request)
