from agent_runtime.contracts.models import (
    UserInputParseRequest,
    UserInputParseResponse,
)
from agent_runtime.master_agent.master_agent_planner import MasterAgentPlanner


class QuestParserService:
    def __init__(
        self,
        master_agent_planner: MasterAgentPlanner | None = None,
    ) -> None:
        self._master_agent_planner = master_agent_planner or MasterAgentPlanner()

    async def parse_user_input(self, request: UserInputParseRequest) -> UserInputParseResponse:
        return await self._master_agent_planner.plan_user_input(request)
