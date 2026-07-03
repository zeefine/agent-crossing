from agent_runtime.contracts.models import AvailableAgentCard, UserInputParseRequest
from agent_runtime.parser.user_input_parser import UserInputParser


def test_user_input_stub_returns_single_parallel_task_for_first_agent() -> None:
    response = UserInputParser().parse(
        UserInputParseRequest(
            input="分析需求",
            availableAgents=[
                AvailableAgentCard(
                    agentId="opencode",
                    displayName="OpenCode",
                    role="Coding agent",
                    capabilities=["implementation"],
                    tools=["filesystem"],
                ),
                AvailableAgentCard(
                    agentId="claude-code",
                    displayName="ClaudeCode",
                    role="Review agent",
                    capabilities=["code review"],
                    tools=["filesystem"],
                ),
            ],
        )
    )

    assert len(response.tasks) == 1
    task = response.tasks[0]
    assert task.task_id.startswith("task-")
    assert task.agent_id == "opencode"
    assert task.context == "分析需求"
    assert task.depends_on == []


def test_user_input_stub_returns_empty_when_no_agents_are_available() -> None:
    response = UserInputParser().parse(UserInputParseRequest(input="分析需求", availableAgents=[]))

    assert response.tasks == []
