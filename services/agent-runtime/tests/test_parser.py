from agent_runtime.contracts.models import AvailableAgentCard, AgentOutputParseRequest, UserInputParseRequest
from agent_runtime.parser.agent_output_parser import AgentOutputParser
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


def test_agent_output_parser_parses_line_start_mentions_only() -> None:
    output = "\n".join(
        [
            "@opencode 继续补充边界条件",
            "普通说明行不会生成任务",
            "  @opencode 前面有空格也不是句首",
            "@claude-code 请复核方案，@opencode 这个不会作为第二个任务",
            "@unknown 直接丢弃",
        ]
    )

    response = AgentOutputParser().parse(
        AgentOutputParseRequest(
            sourceTaskId="task-1",
            sourceAgentId="opencode",
            output=output,
            availableAgentIds=["opencode", "claude-code"],
        )
    )

    assert len(response.tasks) == 2
    assert response.tasks[0].agent_id == "opencode"
    assert response.tasks[0].context == "继续补充边界条件"
    assert response.tasks[0].depends_on == []
    assert response.tasks[1].agent_id == "claude-code"
    assert response.tasks[1].context == "请复核方案，@opencode 这个不会作为第二个任务"


def test_agent_output_parser_parses_create_tasks_json_fallback() -> None:
    output = """
收到，我会让 OpenCode 回答。

```json
{
  "tool": "create_tasks",
  "args": {
    "sourceTaskId": "task-1",
    "userId": "user-1",
    "tasks": [
      {
        "taskId": "task-opencode-introspect",
        "agentId": "opencode",
        "context": "请介绍 OpenCode 的功能与能力边界。",
        "dependsOn": ["task-1"]
      }
    ]
  }
}
```
"""

    response = AgentOutputParser().parse(
        AgentOutputParseRequest(
            sourceTaskId="task-1",
            sourceAgentId="claudecode",
            output=output,
            availableAgentIds=["opencode", "claudecode"],
        )
    )

    assert len(response.tasks) == 1
    task = response.tasks[0]
    assert task.task_id == "task-opencode-introspect"
    assert task.agent_id == "opencode"
    assert task.context == "请介绍 OpenCode 的功能与能力边界。"
    assert task.depends_on == ["task-1"]
