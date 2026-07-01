from uuid import uuid4

from agent_runtime.contracts.models import ParsedTask, UserInputParseRequest, UserInputParseResponse


class UserInputParser:
    def parse(self, request: UserInputParseRequest) -> UserInputParseResponse:
        if not request.available_agents:
            return UserInputParseResponse(tasks=[])
        agent_id = request.available_agents[0].agent_id
        return UserInputParseResponse(
            tasks=[
                ParsedTask(
                    taskId=f"task-{uuid4()}",
                    agentId=agent_id,
                    context=request.input,
                    dependsOn=[],
                )
            ]
        )
