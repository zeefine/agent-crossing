import asyncio
import json
import sys

import pytest
from fastmcp import Client
from fastapi.testclient import TestClient

from agent_runtime.config import settings
from agent_runtime.contracts.models import AvailableAgentCard, UserInputParseRequest
from agent_runtime.master_agent.errors import MasterAgentPlanningError
from agent_runtime.master_agent.models import CreateTasksResult, PlannedTask, TaskStatusSnapshotResult
from agent_runtime.master_agent.mcp_server import master_agent_mcp
from agent_runtime.master_agent import mcp_server
from agent_runtime.master_agent.master_agent_planner import MasterAgentPlanner
from agent_runtime.master_agent.planning_sessions import PlanningSessionStore, planning_session_store
from agent_runtime.main import create_app


def agent_card(agent_id: str, display_name: str | None = None) -> AvailableAgentCard:
    return AvailableAgentCard(
        agentId=agent_id,
        displayName=display_name or agent_id,
        role="Coding agent",
        capabilities=["implementation", "project analysis"],
        tools=["filesystem", "shell"],
    )


def test_master_agent_prompt_places_static_rules_before_dynamic_context() -> None:
    prompt = MasterAgentPlanner._build_prompt(
        "planning-session-1",
        UserInputParseRequest(
            input="分析当前项目",
            availableAgents=[agent_card("opencode", "OpenCode")],
        ),
    )

    assert prompt.index("Protocol rule: you must finish by calling exactly one MCP tool") < prompt.index(
        "Planning policy:"
    )
    assert prompt.index("Explicit agent routing has highest priority") < prompt.index(
        "For ordinary questions, short explanations, simple Q&A"
    )
    assert "Do not call submit_direct_answer for explicit @agentId input" in prompt
    assert prompt.index("submit_direct_answer") < prompt.index(
        "For complex work that clearly needs multiple steps"
    )
    assert prompt.index("Tool input rules for submit_task_plan:") < prompt.index("[Planning Context]")
    assert prompt.index("[Planning Context]") < prompt.index("planningSessionId: planning-session-1")
    assert prompt.index("availableAgents:") < prompt.index("[User Input]")
    assert prompt.rstrip().endswith("分析当前项目")


def test_master_agent_reads_static_prompt_from_config(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path,
) -> None:
    prompt_config = tmp_path / "prompts.json"
    prompt_config.write_text(
        json.dumps(
            {
                "businessAgentStaticPrompt": "Custom business static prompt.",
                "masterAgentStaticPrompt": "Custom master static prompt.",
            }
        ),
        encoding="utf-8",
    )
    monkeypatch.setattr(settings, "prompt_config_path", str(prompt_config))

    prompt = MasterAgentPlanner._build_prompt(
        "planning-session-1",
        UserInputParseRequest(input="分析当前项目", availableAgents=[agent_card("opencode")]),
    )

    assert prompt.startswith("Custom master static prompt.\n\n[Planning Context]")
    assert prompt.rstrip().endswith("分析当前项目")


def test_master_agent_builds_claudecode_command_with_mcp_config(monkeypatch: pytest.MonkeyPatch) -> None:
    mcp_config = '{"mcpServers":{"agent-crossing":{"type":"http","url":"http://127.0.0.1:8090/mcp/master-agent/"}}}'
    monkeypatch.setattr(settings, "claudecode_mcp_config_json", mcp_config)
    command = MasterAgentPlanner()._build_command(
        "planning-session-1",
        UserInputParseRequest(
            input="你是谁",
            providerSessionId="claude-existing",
            availableAgents=[agent_card("opencode")],
        ),
    )

    assert command[0:2] == [settings.master_agent_command, "-p"]
    assert command[2].startswith("You are the Agent Crossing MasterAgent.")
    assert command[3:7] == ["--output-format", "stream-json", "--verbose", "--permission-mode"]
    assert command[7] == settings.claudecode_permission_mode
    assert command[command.index("--mcp-config") + 1] == mcp_config
    assert command[command.index("--resume") + 1] == "claude-existing"
    assert "--agent" not in command
    assert "run" not in command


def test_master_agent_builds_default_http_mcp_config(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "claudecode_mcp_config_json", None)
    monkeypatch.setattr(settings, "master_agent_mcp_url", "http://127.0.0.1:8090/mcp/master-agent/")

    command = MasterAgentPlanner()._build_command(
        "planning-session-1",
        UserInputParseRequest(input="你是谁", availableAgents=[agent_card("opencode")]),
    )

    mcp_config = json.loads(command[command.index("--mcp-config") + 1])
    server = mcp_config["mcpServers"]["agent-crossing-master-agent"]
    assert server == {
        "type": "http",
        "url": "http://127.0.0.1:8090/mcp/master-agent/",
    }


def test_master_agent_runner_captures_claudecode_session_id(tmp_path) -> None:
    script = tmp_path / "fake_claude_master.py"
    script.write_text(
        "\n".join(
            [
                "import json",
                "print(json.dumps({'type': 'system', 'subtype': 'init', 'session_id': 'claude-master-new'}))",
            ]
        ),
        encoding="utf-8",
    )
    session_ids: list[str] = []
    stdout_text_chunks: list[str] = []
    stderr_lines: list[str] = []

    return_code = asyncio.run(
        MasterAgentPlanner._run_process(
            [sys.executable, str(script)],
            {},
            session_ids,
            stdout_text_chunks,
            stderr_lines,
        )
    )

    assert return_code == 0
    assert session_ids == ["claude-master-new"]


def test_master_agent_planner_uses_stdout_text_as_direct_answer_fallback(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path,
) -> None:
    script = tmp_path / "fake_claude_stdout_answer.py"
    script.write_text(
        "\n".join(
            [
                "import json",
                "print(json.dumps({'type': 'system', 'subtype': 'init', 'session_id': 'claude-master-stdout'}))",
                "print(json.dumps({'type': 'assistant', 'message': {'content': [{'type': 'text', 'text': '我是 MasterAgent。'}]}}))",
            ]
        ),
        encoding="utf-8",
    )

    monkeypatch.setattr(settings, "master_agent_enabled", True)
    monkeypatch.setattr(settings, "master_agent_command", f"{sys.executable} {script}")
    monkeypatch.setattr(settings, "master_agent_timeout_seconds", 1.0)
    planner = MasterAgentPlanner(session_store=PlanningSessionStore())

    response = asyncio.run(
        planner.plan_user_input(
            UserInputParseRequest(input="你是谁", availableAgents=[agent_card("opencode")])
        )
    )

    assert response.tasks == []
    assert response.direct_answer == "我是 MasterAgent。"
    assert response.provider_session_id == "claude-master-stdout"


def test_master_agent_planner_rejects_user_input_when_disabled(monkeypatch: pytest.MonkeyPatch) -> None:
    async def unexpected_runner(command: list[str], env: dict[str, str]) -> int:
        raise AssertionError("master agent process should not run when disabled")

    monkeypatch.setattr(settings, "master_agent_enabled", False)
    planner = MasterAgentPlanner(
        session_store=PlanningSessionStore(),
        process_runner=unexpected_runner,
    )

    with pytest.raises(MasterAgentPlanningError) as raised:
        asyncio.run(
            planner.plan_user_input(
                UserInputParseRequest(
                    input="分析项目",
                    availableAgents=[agent_card("opencode"), agent_card("claude-code")],
                )
            )
        )

    assert raised.value.code == "MASTER_AGENT_DISABLED"


def test_master_agent_planner_accepts_tasks_submitted_through_session(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    session_store = PlanningSessionStore()

    async def fake_runner(command: list[str], env: dict[str, str]) -> int:
        assert command[0] == settings.master_agent_command
        planning_session_id = env["AGENT_CROSSING_PLANNING_SESSION_ID"]
        accepted = session_store.submit(
            planning_session_id,
            [
                PlannedTask(
                    taskId="task-master-1",
                    agentId="opencode",
                    context="阅读代码并总结任务调度链路",
                    dependsOn=[],
                )
            ],
        )
        assert accepted is True
        return 0

    monkeypatch.setattr(settings, "master_agent_enabled", True)
    monkeypatch.setattr(settings, "master_agent_timeout_seconds", 1.0)
    planner = MasterAgentPlanner(
        session_store=session_store,
        process_runner=fake_runner,
    )

    response = asyncio.run(
        planner.plan_user_input(
            UserInputParseRequest(
                input="分析当前实现",
                availableAgents=[agent_card("opencode", "OpenCode"), agent_card("claude-code")],
            )
        )
    )

    assert len(response.tasks) == 1
    task = response.tasks[0]
    assert task.task_id == "task-master-1"
    assert task.agent_id == "opencode"
    assert task.context == "阅读代码并总结任务调度链路"
    assert task.depends_on == []
    assert response.direct_answer is None


def test_master_agent_planner_accepts_direct_answer_submitted_through_session(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    session_store = PlanningSessionStore()

    async def fake_runner(command: list[str], env: dict[str, str]) -> int:
        accepted = session_store.submit_direct_answer(
            env["AGENT_CROSSING_PLANNING_SESSION_ID"],
            "我是 Agent Crossing 的 MasterAgent。",
        )
        assert accepted is True
        return 0

    monkeypatch.setattr(settings, "master_agent_enabled", True)
    monkeypatch.setattr(settings, "master_agent_timeout_seconds", 1.0)
    planner = MasterAgentPlanner(
        session_store=session_store,
        process_runner=fake_runner,
    )

    response = asyncio.run(
        planner.plan_user_input(
            UserInputParseRequest(input="你是谁", availableAgents=[agent_card("opencode")])
        )
    )

    assert response.tasks == []
    assert response.direct_answer == "我是 Agent Crossing 的 MasterAgent。"


def test_master_agent_planner_filters_invalid_submitted_tasks(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    session_store = PlanningSessionStore()

    async def fake_runner(command: list[str], env: dict[str, str]) -> int:
        session_store.submit(
            env["AGENT_CROSSING_PLANNING_SESSION_ID"],
            [
                PlannedTask(taskId="task-ok", agentId="opencode", context="有效任务"),
                PlannedTask(taskId="task-bad-agent", agentId="unknown", context="未知 agent"),
                PlannedTask(taskId="task-empty-context", agentId="opencode", context=" "),
                PlannedTask(taskId="task-ok", agentId="opencode", context="重复 task id"),
            ],
        )
        return 0

    monkeypatch.setattr(settings, "master_agent_enabled", True)
    monkeypatch.setattr(settings, "master_agent_timeout_seconds", 1.0)
    planner = MasterAgentPlanner(
        session_store=session_store,
        process_runner=fake_runner,
    )

    response = asyncio.run(
        planner.plan_user_input(
            UserInputParseRequest(input="拆解任务", availableAgents=[agent_card("opencode")])
        )
    )

    assert [task.task_id for task in response.tasks] == ["task-ok"]


def test_master_agent_planner_fails_when_process_does_not_call_tool(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def fake_runner(command: list[str], env: dict[str, str]) -> int:
        return 0

    monkeypatch.setattr(settings, "master_agent_enabled", True)
    monkeypatch.setattr(settings, "master_agent_timeout_seconds", 1.0)
    planner = MasterAgentPlanner(
        session_store=PlanningSessionStore(),
        process_runner=fake_runner,
    )

    with pytest.raises(MasterAgentPlanningError) as raised:
        asyncio.run(
            planner.plan_user_input(
                UserInputParseRequest(input="拆解任务", availableAgents=[agent_card("opencode")])
            )
        )

    assert raised.value.code == "MASTER_AGENT_NO_TASK_PLAN"


def test_fastmcp_submit_task_plan_completes_planning_session() -> None:
    async def scenario():
        session = planning_session_store.create()
        try:
            async with Client(master_agent_mcp) as client:
                await client.call_tool(
                    "submit_task_plan",
                    {
                        "planningSessionId": session.planning_session_id,
                        "tasks": [
                            {
                                "taskId": "task-mcp-1",
                                "agentId": "opencode",
                                "context": "执行 MCP 规划任务",
                                "dependsOn": [],
                            }
                        ],
                    },
                )
            return await planning_session_store.wait(session.planning_session_id, 1.0)
        finally:
            planning_session_store.discard(session.planning_session_id)

    planning_result = asyncio.run(scenario())

    assert len(planning_result.tasks) == 1
    assert planning_result.tasks[0].task_id == "task-mcp-1"
    assert planning_result.direct_answer is None


def test_fastmcp_submit_direct_answer_completes_planning_session() -> None:
    async def scenario():
        session = planning_session_store.create()
        try:
            async with Client(master_agent_mcp) as client:
                await client.call_tool(
                    "submit_direct_answer",
                    {
                        "planningSessionId": session.planning_session_id,
                        "answer": "今天适合先查看天气服务配置。",
                    },
                )
            return await planning_session_store.wait(session.planning_session_id, 1.0)
        finally:
            planning_session_store.discard(session.planning_session_id)

    planning_result = asyncio.run(scenario())

    assert planning_result.tasks == []
    assert planning_result.direct_answer == "今天适合先查看天气服务配置。"


def test_planning_session_store_rejects_stale_plan_id_even_when_single_session() -> None:
    async def scenario():
        session_store = PlanningSessionStore()
        session = session_store.create()
        try:
            accepted = session_store.submit_direct_answer(
                "health-check-only",
                "不能通过唯一 active session 猜测归属。",
            )
            return accepted, session.future.done()
        finally:
            session_store.discard(session.planning_session_id)

    accepted, completed = asyncio.run(scenario())

    assert accepted is False
    assert completed is False


def test_planning_session_store_rejects_stale_plan_id_when_multiple_sessions() -> None:
    async def scenario():
        session_store = PlanningSessionStore()
        first = session_store.create()
        second = session_store.create()
        try:
            return session_store.submit_direct_answer(
                "health-check-only",
                "不能在多个活跃 session 间猜测归属。",
            )
        finally:
            session_store.discard(first.planning_session_id)
            session_store.discard(second.planning_session_id)

    accepted = asyncio.run(scenario())

    assert accepted is False


def test_fastmcp_get_task_status_snapshot_queries_platform_state(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[str, str]] = []

    class FakeTaskStatusClient:
        async def get_snapshot(self, task_id: str, user_id: str) -> TaskStatusSnapshotResult:
            calls.append((task_id, user_id))
            return TaskStatusSnapshotResult(
                currentTaskId=task_id,
                userId=user_id,
                downstreamTasks=[],
                totalCount=0,
                statusCounts={},
                message="ok",
            )

    async def scenario() -> None:
        monkeypatch.setattr(mcp_server, "task_status_client", FakeTaskStatusClient())
        async with Client(master_agent_mcp) as client:
            await client.call_tool(
                "get_task_status_snapshot",
                {"taskId": "task-a", "userId": "user-1"},
            )

    asyncio.run(scenario())

    assert calls == [("task-a", "user-1")]


def test_fastmcp_create_tasks_appends_platform_tasks(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[str, str, list[str]]] = []

    class FakeTaskStatusClient:
        async def create_tasks(
            self,
            source_task_id: str,
            tasks: list[PlannedTask],
            user_id: str,
        ) -> CreateTasksResult:
            calls.append((source_task_id, user_id, [task.task_id for task in tasks]))
            return CreateTasksResult(
                sourceTaskId=source_task_id,
                userId=user_id,
                createdTasks=[],
                createdCount=0,
                message="no tasks created",
            )

    async def scenario() -> None:
        monkeypatch.setattr(mcp_server, "task_status_client", FakeTaskStatusClient())
        async with Client(master_agent_mcp) as client:
            await client.call_tool(
                "create_tasks",
                {
                    "sourceTaskId": "task-a",
                    "userId": "user-1",
                    "tasks": [
                        {
                            "taskId": "task-d",
                            "agentId": "opencode",
                            "context": "追加 D",
                            "dependsOn": [],
                        }
                    ],
                },
            )

    asyncio.run(scenario())

    assert calls == [("task-a", "user-1", ["task-d"])]


def test_parser_api_accepts_agent_cards_and_reports_master_agent_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "master_agent_enabled", False)
    client = TestClient(create_app())

    response = client.post(
        "/api/parser/user-input",
        json={
            "input": "分析项目",
            "availableAgents": [
                agent_card("opencode", "OpenCode").model_dump(mode="json", by_alias=True)
            ],
        },
    )

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "MASTER_AGENT_DISABLED"
