import asyncio
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from agent_runtime.api import routes
from agent_runtime.business_prompt import business_prompt_composer
from agent_runtime.config import settings
from agent_runtime.contracts.models import (
    AgentContextPack,
    AgentExecutionRequest,
    AgentExecutionResponse,
    AgentExecutionUsage,
    AgentMessage,
    AgentMessageType,
    IncrementalChatMessage,
)
from agent_runtime.main import create_app
from agent_runtime.master_agent.session_compressor import SessionCompressor
from agent_runtime.providers.claudecode import ClaudeCodeProvider
from agent_runtime.providers.codex import CodexProvider
from agent_runtime.providers.opencode import OpenCodeProvider
from agent_runtime.providers.registry import ProviderRegistry
from agent_runtime.prompt_config import load_prompt_config
from agent_runtime.prompt_session import prepare_execution_request
from agent_runtime.runtime.service import AgentRuntimeService


def execution_request() -> AgentExecutionRequest:
    return AgentExecutionRequest(
        invocationId="invocation-1",
        userId="user-1",
        taskId="task-1",
        traceId="trace-1",
        agentId="opencode",
        context="hello",
        callbackBaseUrl="http://127.0.0.1:8080/api/callback",
        providerPromptVersion=load_prompt_config().business_agent_prompt_version("opencode"),
    )


class FakeProvider:
    agent_id = "opencode"

    async def execute(self, request: AgentExecutionRequest) -> list[AgentMessage]:
        return [
            AgentMessage(
                invocationId=request.invocation_id,
                taskId=request.task_id,
                traceId=request.trace_id,
                agentId=request.agent_id,
                type=AgentMessageType.MESSAGE,
                content="done",
                raw=None,
                createdAt=datetime.now(timezone.utc),
            )
        ]


def test_runtime_service_routes_to_provider() -> None:
    service = AgentRuntimeService(ProviderRegistry([FakeProvider()]))

    response = asyncio.run(service.execute(execution_request()))

    assert response.messages[0].content == "done"
    assert response.final_text == "done"


def test_runtime_service_exposes_prompt_version_from_done_message() -> None:
    class VersionedProvider:
        agent_id = "opencode"

        async def execute(self, request: AgentExecutionRequest) -> list[AgentMessage]:
            return [
                AgentMessage(
                    invocationId=request.invocation_id,
                    taskId=request.task_id,
                    traceId=request.trace_id,
                    agentId=request.agent_id,
                    type=AgentMessageType.DONE,
                    raw={"promptVersion": "prompt-v2"},
                    createdAt=datetime.now(timezone.utc),
                )
            ]

    service = AgentRuntimeService(ProviderRegistry([VersionedProvider()]))

    response = asyncio.run(service.execute(execution_request()))

    assert response.prompt_version == "prompt-v2"


def test_runtime_service_exposes_usage_from_done_message() -> None:
    class UsageProvider:
        agent_id = "codex"

        async def execute(self, request: AgentExecutionRequest) -> list[AgentMessage]:
            return [
                AgentMessage(
                    invocationId=request.invocation_id,
                    taskId=request.task_id,
                    traceId=request.trace_id,
                    agentId=request.agent_id,
                    type=AgentMessageType.DONE,
                    raw={
                        "usage": {
                            "provider": "codex",
                            "model": "gpt-test",
                            "usagePrecision": "TURN_AGGREGATE",
                            "totalInputTokens": 120,
                            "inputTokens": 120,
                            "contextInputTokens": None,
                            "observedAt": "2026-08-13T03:00:00Z",
                        }
                    },
                    createdAt=datetime.now(timezone.utc),
                )
            ]

    service = AgentRuntimeService(ProviderRegistry([UsageProvider()]))
    request = execution_request().model_copy(update={"agent_id": "codex"})

    response = asyncio.run(service.execute(request))

    assert response.usage is not None
    assert response.usage.provider == "codex"
    assert response.usage.total_input_tokens == 120
    assert response.usage.context_input_tokens is None


@pytest.mark.parametrize("context_tokens", [100, None])
def test_runtime_schema_declares_the_full_usage_response_contract(context_tokens: int | None) -> None:
    schema_path = Path(__file__).resolve().parents[3] / "contracts/schemas/runtime-event.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    response_schema = schema["$defs"]["AgentExecutionResponse"]
    usage_schema = schema["$defs"]["AgentExecutionUsage"]
    response = AgentExecutionResponse(
        messages=[],
        usage=AgentExecutionUsage(
            provider="codex",
            model="gpt-5.6",
            providerSessionId="session-1",
            totalInputTokens=120,
            lastRequestInputTokens=context_tokens,
            usagePrecision="EXACT" if context_tokens is not None else "TURN_AGGREGATE",
            inputTokens=120,
            cachedInputTokens=80,
            cacheCreationInputTokens=0,
            cacheReadInputTokens=80,
            outputTokens=9,
            reasoningOutputTokens=3,
            contextInputTokens=context_tokens,
            rawUsageJson={"usage": {"input_tokens": 120}},
            providerCliVersion="test",
            observedAt="2026-08-13T03:00:00Z",
        ),
    ).model_dump(mode="json", by_alias=True)

    assert set(response) <= set(response_schema["properties"])
    assert response_schema["properties"]["usage"]["anyOf"][0]["$ref"] == "#/$defs/AgentExecutionUsage"
    assert set(response["usage"]) <= set(usage_schema["properties"])
    assert set(usage_schema["required"]) <= set(response["usage"])
    assert usage_schema["properties"]["contextInputTokens"]["type"] == ["integer", "null"]


def test_business_prompt_reinjects_static_rules_summary_and_retained_tail() -> None:
    request = execution_request().model_copy(
        update={
            "agent_id": "codex",
            "provider_session_id": None,
            "context_pack": AgentContextPack(
                startupSummary='{"schemaVersion":1,"objective":"continue"}',
                incrementalChatMessages=[
                    IncrementalChatMessage(
                        messageId="message-1",
                        role="user",
                        content="latest user message",
                        createdAt="2026-08-13T03:00:00Z",
                    )
                ],
            ),
        }
    )

    prompt = business_prompt_composer.compose(request)

    assert prompt.index("[Role]") < prompt.index("[Compressed Conversation Memory]")
    assert prompt.index("[Compressed Conversation Memory]") < prompt.rindex("[Invocation Context]")
    assert "[Retained Conversation Tail]" in prompt
    assert prompt.index("[Retained Conversation Tail]") < prompt.index("Task:")


def test_session_compressor_parses_claude_json_envelope() -> None:
    output = json.dumps(
        {
            "type": "result",
            "result": json.dumps(
                {
                    "schemaVersion": 1,
                    "objective": "continue",
                    "continuationGuidance": "use retained tail",
                }
            ),
        }
    )

    summary = SessionCompressor._parse_summary(output)

    assert summary["schemaVersion"] == 1
    assert summary["objective"] == "continue"


def test_session_compressor_keeps_outer_summary_with_nested_objects() -> None:
    output = json.dumps(
        {
            "schemaVersion": 1,
            "objective": "continue",
            "decisions": [{"subject": "storage", "decision": "mysql"}],
        }
    )

    summary = SessionCompressor._parse_summary(output)

    assert summary["schemaVersion"] == 1
    assert summary["decisions"][0]["decision"] == "mysql"


def test_runtime_service_rejects_unknown_agent() -> None:
    service = AgentRuntimeService(ProviderRegistry([]))

    with pytest.raises(HTTPException):
        asyncio.run(service.execute(execution_request()))


def test_runtime_service_cancels_active_execution() -> None:
    class BlockingProvider:
        agent_id = "opencode"

        def __init__(self) -> None:
            self.started = asyncio.Event()

        async def execute(self, request: AgentExecutionRequest) -> list[AgentMessage]:
            self.started.set()
            await asyncio.Event().wait()
            return []

    async def scenario() -> None:
        provider = BlockingProvider()
        service = AgentRuntimeService(ProviderRegistry([provider]))
        execution = asyncio.create_task(service.execute(execution_request()))
        await provider.started.wait()

        assert await service.cancel("invocation-1") is True
        response = await execution

        assert response.messages == []
        assert await service.cancel("invocation-1") is True

    asyncio.run(scenario())


def test_default_provider_registry_contains_business_agents() -> None:
    registry = ProviderRegistry()

    assert registry.get("opencode") is not None
    assert registry.get("claudecode") is not None
    assert registry.get("codex") is not None


def test_runtime_api_returns_agent_messages(monkeypatch: pytest.MonkeyPatch) -> None:
    class FakeRuntimeService:
        async def execute(self, request: AgentExecutionRequest) -> AgentExecutionResponse:
            return AgentExecutionResponse(
                messages=[
                    AgentMessage(
                        invocationId=request.invocation_id,
                        taskId=request.task_id,
                        traceId=request.trace_id,
                        agentId=request.agent_id,
                        type=AgentMessageType.DONE,
                        content=None,
                        raw=None,
                        createdAt=datetime.now(timezone.utc),
                    )
                ]
            )

    monkeypatch.setattr(routes, "runtime_service", FakeRuntimeService())
    client = TestClient(create_app())

    response = client.post(
        "/api/runtime/execute",
        json={
            "invocationId": "invocation-1",
            "userId": "user-1",
            "taskId": "task-1",
            "traceId": "trace-1",
            "agentId": "opencode",
            "context": "hello",
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["messages"][0]["type"] == "done"


def test_claudecode_provider_builds_stream_json_command_and_parses_events() -> None:
    provider = ClaudeCodeProvider(
        command=(
            f"{sys.executable} -c \"import json, sys; "
            "assert sys.argv[1] == '-p'; "
            "assert '--output-format' in sys.argv; "
            "assert 'stream-json' in sys.argv; "
            "print(json.dumps({'type':'system','subtype':'init','session_id':'claude-session'})); "
            "print(json.dumps({'type':'assistant','message':{'content':[{'type':'text','text':'answer'}, {'type':'tool_use','name':'Read','input':{'file':'README.md'}}]}})); "
            "print(json.dumps({'type':'result','subtype':'success','usage':{'input_tokens':100,'cache_creation_input_tokens':20,'cache_read_input_tokens':30,'output_tokens':8}}))\""
        )
    )
    request = execution_request().model_copy(update={"agent_id": "claudecode"})

    messages = asyncio.run(provider.execute(request))

    assert [message.type for message in messages] == [
        AgentMessageType.TEXT_DELTA,
        AgentMessageType.MESSAGE,
        AgentMessageType.DONE,
    ]
    assert messages[0].content == "answer"
    assert messages[1].content == "tool_use:Read"
    assert messages[-1].raw["providerSessionId"] == "claude-session"
    assert messages[-1].raw["usage"]["totalInputTokens"] == 150
    assert messages[-1].raw["usage"]["contextInputTokens"] is None
    assert messages[-1].raw["usage"]["usagePrecision"] == "TURN_AGGREGATE"


def test_claudecode_provider_dedupes_repeated_assistant_snapshots() -> None:
    provider = ClaudeCodeProvider(
        command=(
            f"{sys.executable} -c \"import json; "
            "event={'type':'assistant','message':{'content':[{'type':'text','text':'same answer'}]}}; "
            "print(json.dumps(event)); "
            "print(json.dumps(event)); "
            "print(json.dumps({'type':'result','subtype':'success'}))\""
        )
    )
    request = execution_request().model_copy(update={"agent_id": "claudecode"})

    messages = asyncio.run(provider.execute(request))

    text_messages = [message for message in messages if message.type == AgentMessageType.TEXT_DELTA]
    assert [message.content for message in text_messages] == ["same answer"]


def test_claudecode_provider_sends_only_delta_for_cumulative_assistant_snapshot() -> None:
    provider = ClaudeCodeProvider(
        command=(
            f"{sys.executable} -c \"import json; "
            "print(json.dumps({'type':'assistant','message':{'content':[{'type':'text','text':'hello'}]}})); "
            "print(json.dumps({'type':'assistant','message':{'content':[{'type':'text','text':'hello world'}]}})); "
            "print(json.dumps({'type':'result','subtype':'success'}))\""
        )
    )
    request = execution_request().model_copy(update={"agent_id": "claudecode"})

    messages = asyncio.run(provider.execute(request))

    text_messages = [message for message in messages if message.type == AgentMessageType.TEXT_DELTA]
    assert [message.content for message in text_messages] == ["hello", " world"]


def test_claudecode_provider_runs_from_configured_working_directory(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    cli_cwd = tmp_path / "project-root"
    cli_cwd.mkdir()
    cwd_log = tmp_path / "claude-cwd.txt"
    script = tmp_path / "fake_claude_cwd.py"
    script.write_text(
        "\n".join(
            [
                "import json",
                "import os",
                "import pathlib",
                f"pathlib.Path({str(cwd_log)!r}).write_text(os.getcwd(), encoding='utf-8')",
                "print(json.dumps({'type': 'assistant', 'message': {'content': [{'type': 'text', 'text': 'ok'}]}}))",
            ]
        )
    )
    monkeypatch.setattr(settings, "cli_working_directory", str(cli_cwd))
    provider = ClaudeCodeProvider(command=f"{sys.executable} {script}")
    request = execution_request().model_copy(update={"agent_id": "claudecode"})

    messages = asyncio.run(provider.execute(request))

    assert cwd_log.read_text(encoding="utf-8") == str(cli_cwd)
    assert messages[0].content == "ok"


def test_claudecode_provider_uses_provider_session_id_from_request(tmp_path: Path) -> None:
    script = tmp_path / "fake_claude_reuse.py"
    command_log = tmp_path / "commands.jsonl"
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "print(json.dumps({'type': 'system', 'subtype': 'init', 'session_id': 'claude-existing'}))",
                "print(json.dumps({'type': 'assistant', 'message': {'content': [{'type': 'text', 'text': 'ok'}]}}))",
            ]
        )
    )
    request = execution_request().model_copy(
        update={
            "agent_id": "claudecode",
            "provider_session_id": "claude-existing",
            "provider_prompt_version": load_prompt_config().business_agent_prompt_version("claudecode"),
        }
    )
    provider = ClaudeCodeProvider(command=f"{sys.executable} {script}")

    messages = asyncio.run(provider.execute(request))

    commands = [json.loads(line) for line in command_log.read_text(encoding="utf-8").splitlines()]
    assert commands[0][commands[0].index("--resume") + 1] == "claude-existing"
    assert messages[-1].raw["providerSessionId"] == "claude-existing"


def test_claudecode_provider_does_not_cache_session_for_same_trace_and_agent(tmp_path: Path) -> None:
    script = tmp_path / "fake_claude_no_cache.py"
    command_log = tmp_path / "commands.jsonl"
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "print(json.dumps({'type': 'system', 'subtype': 'init', 'session_id': 'claude-captured'}))",
                "print(json.dumps({'type': 'assistant', 'message': {'content': [{'type': 'text', 'text': 'ok'}]}}))",
            ]
        )
    )
    request = execution_request().model_copy(update={"agent_id": "claudecode"})
    second_request = request.model_copy(update={"taskId": "task-2"})
    provider = ClaudeCodeProvider(command=f"{sys.executable} {script}")

    asyncio.run(provider.execute(request))
    asyncio.run(provider.execute(second_request))

    commands = [json.loads(line) for line in command_log.read_text(encoding="utf-8").splitlines()]
    assert "--resume" not in commands[0]
    assert "--resume" not in commands[1]


def test_claudecode_provider_passes_mcp_config_json_as_single_argument(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    mcp_config = '{"mcpServers":{"agent-crossing":{"command":"uv","args":["run","python","-m","agent_runtime.master_agent.mcp_server"]}}}'
    monkeypatch.setattr(settings, "claudecode_mcp_config_json", mcp_config)
    script = tmp_path / "fake_claude_mcp.py"
    command_log = tmp_path / "commands.jsonl"
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "print(json.dumps({'type': 'assistant', 'message': {'content': [{'type': 'text', 'text': 'ok'}]}}))",
            ]
        )
    )
    provider = ClaudeCodeProvider(command=f"{sys.executable} {script}")
    request = execution_request().model_copy(update={"agent_id": "claudecode"})

    asyncio.run(provider.execute(request))

    command = json.loads(command_log.read_text(encoding="utf-8").splitlines()[0])
    assert command[command.index("--mcp-config") + 1] == mcp_config


def test_claudecode_provider_defaults_to_platform_http_mcp_config(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "claudecode_mcp_config_json", None)
    monkeypatch.setattr(settings, "master_agent_mcp_url", "http://127.0.0.1:8090/mcp/master-agent/")
    script = tmp_path / "fake_claude_default_mcp.py"
    command_log = tmp_path / "commands.jsonl"
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "print(json.dumps({'type': 'assistant', 'message': {'content': [{'type': 'text', 'text': 'ok'}]}}))",
            ]
        )
    )
    provider = ClaudeCodeProvider(command=f"{sys.executable} {script}")
    request = execution_request().model_copy(update={"agent_id": "claudecode"})

    asyncio.run(provider.execute(request))

    command = json.loads(command_log.read_text(encoding="utf-8").splitlines()[0])
    mcp_config = json.loads(command[command.index("--mcp-config") + 1])
    assert mcp_config["mcpServers"]["agent-crossing"] == {
        "type": "http",
        "url": "http://127.0.0.1:8090/mcp/master-agent/",
    }


def test_claudecode_provider_parses_result_error() -> None:
    provider = ClaudeCodeProvider(
        command=(
            f"{sys.executable} -c \"import json; "
            "print(json.dumps({'type':'result','subtype':'error','error':'claude failed'}))\""
        )
    )
    request = execution_request().model_copy(update={"agent_id": "claudecode"})

    messages = asyncio.run(provider.execute(request))

    assert [message.type for message in messages] == [AgentMessageType.ERROR, AgentMessageType.MESSAGE, AgentMessageType.DONE]
    assert messages[0].content == "claude failed"


def test_claudecode_provider_keeps_successful_stderr_as_diagnostics() -> None:
    provider = ClaudeCodeProvider(
        command=(
            f"{sys.executable} -c \"import json, sys; "
            "sys.stderr.write('verbose diagnostic\\\\n'); "
            "print(json.dumps({'type':'assistant','message':{'content':[{'type':'text','text':'answer'}]}}))\""
        )
    )
    request = execution_request().model_copy(update={"agent_id": "claudecode"})

    messages = asyncio.run(provider.execute(request))

    assert [message.type for message in messages] == [AgentMessageType.TEXT_DELTA, AgentMessageType.DONE]
    assert messages[0].content == "answer"
    assert messages[-1].raw["diagnostics"] == {
        "stderrChars": len("verbose diagnostic\n"),
        "stderrTail": "verbose diagnostic\n",
    }


def test_claudecode_provider_marks_nonzero_exit_as_error_with_stderr_diagnostics() -> None:
    provider = ClaudeCodeProvider(
        command=(
            f"{sys.executable} -c \"import sys; "
            "sys.stderr.write('fatal diagnostic\\\\n'); "
            "sys.exit(7)\""
        )
    )
    request = execution_request().model_copy(update={"agent_id": "claudecode"})

    messages = asyncio.run(provider.execute(request))

    assert [message.type for message in messages] == [AgentMessageType.ERROR, AgentMessageType.DONE]
    assert messages[0].content == "Claude Code exited with code 7"
    assert messages[-1].raw["diagnostics"]["stderrTail"] == "fatal diagnostic\n"


def test_codex_provider_builds_json_command_and_parses_completed_agent_message(tmp_path: Path) -> None:
    script = tmp_path / "fake_codex.py"
    command_log = tmp_path / "codex-command.json"
    prompt_log = tmp_path / "codex-prompt.txt"
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"pathlib.Path({str(command_log)!r}).write_text(json.dumps(sys.argv[1:]), encoding='utf-8')",
                f"pathlib.Path({str(prompt_log)!r}).write_text(sys.stdin.read(), encoding='utf-8')",
                "print(json.dumps({'type': 'thread.started', 'thread_id': 'codex-thread-1'}))",
                "print(json.dumps({'type': 'item.started', 'item': {'type': 'command_execution', 'command': 'pwd'}}))",
                "print(json.dumps({'type': 'item.completed', 'item': {'type': 'command_execution', 'aggregated_output': '/tmp'}}))",
                "print(json.dumps({'type': 'item.completed', 'item': {'type': 'agent_message', 'text': 'Codex answer'}}))",
                "print(json.dumps({'type': 'turn.completed', 'usage': {'input_tokens': 120, 'cached_input_tokens': 80, 'output_tokens': 9}}))",
            ]
        ),
        encoding="utf-8",
    )
    provider = CodexProvider(command=f"{sys.executable} {script}")
    request = execution_request().model_copy(
        update={"agent_id": "codex", "callback_base_url": None}
    )

    messages = asyncio.run(provider.execute(request))

    command = json.loads(command_log.read_text(encoding="utf-8"))
    assert command[:2] == ["exec", "--json"]
    assert command[command.index("--sandbox") + 1] == "read-only"
    assert "--ignore-user-config" in command
    assert command[-1] == "-"
    assert any(arg.startswith("mcp_servers.agent-crossing.url=") for arg in command)
    assert prompt_log.read_text(encoding="utf-8").startswith("[Role]")
    assert [message.type for message in messages] == [
        AgentMessageType.TEXT_DELTA,
        AgentMessageType.DONE,
    ]
    assert messages[0].content == "Codex answer"
    assert messages[-1].raw["providerSessionId"] == "codex-thread-1"
    assert messages[-1].raw["diagnostics"]["nonChatEventCount"] == 2
    assert messages[-1].raw["usage"]["totalInputTokens"] == 120
    assert messages[-1].raw["usage"]["contextInputTokens"] is None
    assert messages[-1].raw["usage"]["usagePrecision"] == "TURN_AGGREGATE"
    assert messages[-1].raw["usage"]["cachedInputTokens"] == 80


def test_codex_provider_resumes_provider_session_and_omits_static_prompt(tmp_path: Path) -> None:
    script = tmp_path / "fake_codex_resume.py"
    command_log = tmp_path / "codex-resume-command.json"
    prompt_log = tmp_path / "codex-resume-prompt.txt"
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"pathlib.Path({str(command_log)!r}).write_text(json.dumps(sys.argv[1:]), encoding='utf-8')",
                f"pathlib.Path({str(prompt_log)!r}).write_text(sys.stdin.read(), encoding='utf-8')",
                "print(json.dumps({'type': 'thread.started', 'thread_id': 'codex-thread-existing'}))",
                "print(json.dumps({'type': 'item.completed', 'item': {'type': 'agent_message', 'text': 'continued'}}))",
            ]
        ),
        encoding="utf-8",
    )
    request = execution_request().model_copy(
        update={
            "agent_id": "codex",
            "callback_base_url": None,
            "provider_session_id": "codex-thread-existing",
            "provider_prompt_version": load_prompt_config().business_agent_prompt_version("codex"),
        }
    )
    provider = CodexProvider(command=f"{sys.executable} {script}")

    messages = asyncio.run(provider.execute(request))

    command = json.loads(command_log.read_text(encoding="utf-8"))
    assert command[:3] == ["exec", "resume", "codex-thread-existing"]
    assert "--sandbox" not in command
    assert "--ignore-user-config" in command
    assert command[-1] == "-"
    assert prompt_log.read_text(encoding="utf-8").startswith("[Invocation Context]")
    assert messages[0].content == "continued"
    assert messages[-1].raw["providerSessionId"] == "codex-thread-existing"


def test_codex_provider_filters_tool_events_and_dedupes_assistant_snapshots() -> None:
    provider = CodexProvider(
        command=(
            f"{sys.executable} -c \"import json, sys; sys.stdin.read(); "
            "print(json.dumps({'type':'item.completed','item':{'type':'mcp_tool_call','server':'agent-crossing','tool':'create_tasks'}})); "
            "print(json.dumps({'type':'item.completed','item':{'type':'agent_message','text':'hello'}})); "
            "print(json.dumps({'type':'item.completed','item':{'type':'agent_message','text':'hello world'}}))\""
        )
    )
    request = execution_request().model_copy(
        update={"agent_id": "codex", "callback_base_url": None}
    )

    messages = asyncio.run(provider.execute(request))

    text_messages = [message for message in messages if message.type == AgentMessageType.TEXT_DELTA]
    assert [message.content for message in text_messages] == ["hello", " world"]
    assert messages[-1].raw["diagnostics"]["nonChatEventCount"] == 1


def test_codex_provider_parses_structured_error_without_silent_completion() -> None:
    provider = CodexProvider(
        command=(
            f"{sys.executable} -c \"import json, sys; sys.stdin.read(); "
            "print(json.dumps({'type':'error','message':'codex failed'}))\""
        )
    )
    request = execution_request().model_copy(
        update={"agent_id": "codex", "callback_base_url": None}
    )

    messages = asyncio.run(provider.execute(request))

    assert [message.type for message in messages] == [AgentMessageType.ERROR, AgentMessageType.DONE]
    assert messages[0].content == "codex failed"


def test_codex_provider_marks_nonzero_exit_as_error_with_stderr_diagnostics() -> None:
    provider = CodexProvider(
        command=(
            f"{sys.executable} -c \"import sys; sys.stdin.read(); "
            "sys.stderr.write('fatal diagnostic\\\\n'); sys.exit(7)\""
        )
    )
    request = execution_request().model_copy(
        update={"agent_id": "codex", "callback_base_url": None}
    )

    messages = asyncio.run(provider.execute(request))

    assert [message.type for message in messages] == [AgentMessageType.ERROR, AgentMessageType.DONE]
    assert messages[0].content == "Codex exited with code 7"
    assert messages[-1].raw["diagnostics"]["stderrTail"] == "fatal diagnostic\n"


def test_opencode_provider_builds_plain_command_and_parses_stdout() -> None:
    provider = OpenCodeProvider(
        command=(
            f"{sys.executable} -c \"import sys; "
            "assert sys.argv[1] == 'run'; "
            "assert '--format' not in sys.argv; "
            "print('> build · deepseek-v4-flash'); "
            "print('answer:' + sys.argv[-1].splitlines()[-1])\""
        )
    )

    messages = asyncio.run(provider.execute(execution_request()))

    assert [message.type for message in messages] == [AgentMessageType.TEXT_DELTA, AgentMessageType.DONE]
    assert messages[0].content == "answer:hello"


def test_opencode_provider_runs_from_configured_working_directory(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    cli_cwd = tmp_path / "project-root"
    cli_cwd.mkdir()
    cwd_log = tmp_path / "opencode-cwd.txt"
    script = tmp_path / "fake_opencode_cwd.py"
    script.write_text(
        "\n".join(
            [
                "import os",
                "import pathlib",
                f"pathlib.Path({str(cwd_log)!r}).write_text(os.getcwd(), encoding='utf-8')",
                "print('ok from opencode')",
            ]
        )
    )
    monkeypatch.setattr(settings, "cli_working_directory", str(cli_cwd))
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")

    messages = asyncio.run(provider.execute(execution_request()))

    assert cwd_log.read_text(encoding="utf-8") == str(cli_cwd)
    assert messages[0].content == "ok from opencode"


def test_opencode_provider_strips_ansi_and_filters_plain_banner() -> None:
    provider = OpenCodeProvider(
        command=(
            f"{sys.executable} -c \""
            "print('\\\\x1b[0m'); "
            "print('\\\\x1b[0m> build · deepseek-v4-flash\\\\x1b[0m'); "
            "print('\\\\x1b[32m我是 opencode\\\\x1b[0m')\""
        )
    )

    messages = asyncio.run(provider.execute(execution_request()))

    assert [message.type for message in messages] == [AgentMessageType.TEXT_DELTA, AgentMessageType.DONE]
    assert messages[0].content == "我是 opencode"


def test_opencode_provider_keeps_text_after_same_line_banner() -> None:
    provider = OpenCodeProvider(
        command=(
            f"{sys.executable} -c \""
            "print('\\\\x1b[0m> build · deepseek-v4-flash\\\\x1b[0m我是 opencode')\""
        )
    )

    messages = asyncio.run(provider.execute(execution_request()))

    assert [message.type for message in messages] == [AgentMessageType.TEXT_DELTA, AgentMessageType.DONE]
    assert messages[0].content == "我是 opencode"


def test_opencode_provider_normalizes_repeated_and_cumulative_pty_text(tmp_path: Path) -> None:
    script = tmp_path / "fake_opencode_snapshots.py"
    script.write_text(
        "\n".join(
            [
                "import json",
                "print(json.dumps({'type': 'text', 'part': {'text': '完整'}}))",
                "print(json.dumps({'type': 'text', 'part': {'text': '完整'}}))",
                "print(json.dumps({'type': 'text', 'part': {'text': '完整回答'}}))",
            ]
        )
    )
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")
    posted: list[str] = []

    async def capture_stream_message(_request: AgentExecutionRequest, message: AgentMessage) -> None:
        if message.content:
            posted.append(message.content)

    provider._post_stream_message = capture_stream_message  # type: ignore[method-assign]
    service = AgentRuntimeService(ProviderRegistry([provider]))

    response = asyncio.run(service.execute(execution_request()))

    assert [message.content for message in response.messages[:-1]] == ["完整", "回答"]
    assert posted == ["完整", "回答"]
    assert response.final_text == "完整回答"


def test_opencode_provider_filters_stderr_banner_in_pipe_mode(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "opencode_use_pty", False)
    provider = OpenCodeProvider(
        command=(
            f"{sys.executable} -c \"import sys; "
            "print('\\\\x1b[0m> build · deepseek-v4-flash\\\\x1b[0m', file=sys.stderr); "
            "print('answer')\""
        )
    )

    messages = asyncio.run(provider.execute(execution_request()))

    assert [message.type for message in messages] == [AgentMessageType.TEXT_DELTA, AgentMessageType.DONE]
    assert messages[0].content == "answer"


def test_opencode_provider_command_shape_without_and_with_session(
    tmp_path: Path,
) -> None:
    script = tmp_path / "fake_opencode_command_shape.py"
    command_log = tmp_path / "commands.jsonl"
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "print('ok')",
            ]
        )
    )
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")

    asyncio.run(provider.execute(execution_request()))
    request_with_session = execution_request().model_copy(update={"provider_session_id": "ses_0ed7c21caffeTEa2W954dnLRlU"})
    asyncio.run(provider.execute(request_with_session))

    commands = [json.loads(line) for line in command_log.read_text(encoding="utf-8").splitlines()]
    run_commands = [command for command in commands if "run" in command]
    assert run_commands[0][0] == "run"
    assert "-s" not in run_commands[0]
    assert run_commands[1][0:3] == ["-s", "ses_0ed7c21caffeTEa2W954dnLRlU", "run"]


def test_opencode_provider_parses_tool_and_error_events() -> None:
    provider = OpenCodeProvider(
        command=(
            f"{sys.executable} -c \"import json; "
            "print(json.dumps({'type':'tool_use','part':{'tool':'read','state':{'input':{'path':'README.md'}}}})); "
            "print(json.dumps({'type':'error','error':{'data':{'message':'provider failed'}}}))\""
        )
    )

    messages = asyncio.run(provider.execute(execution_request()))

    assert [message.type for message in messages] == [
        AgentMessageType.MESSAGE,
        AgentMessageType.ERROR,
        AgentMessageType.MESSAGE,
        AgentMessageType.DONE,
    ]
    assert messages[0].content == "tool_use:read"
    assert messages[0].raw["tool"] == "read"
    assert messages[0].raw["input"] == {"path": "README.md"}
    assert messages[1].content == "provider failed"
    assert "without text output" in messages[2].content


def test_opencode_provider_keeps_plain_stdout_fallback() -> None:
    provider = OpenCodeProvider(command=f"{sys.executable} -c \"print('plain output')\"")

    messages = asyncio.run(provider.execute(execution_request()))

    assert [message.type for message in messages] == [AgentMessageType.TEXT_DELTA, AgentMessageType.DONE]
    assert messages[0].content == "plain output"


def test_opencode_provider_injects_incremental_chat_messages_into_prompt() -> None:
    provider = OpenCodeProvider(
        command=(
            f"{sys.executable} -c \"import json, sys; "
            "prompt = sys.argv[-1]; "
            "assert prompt.index('[Rules]') < prompt.index('[Invocation Context]'); "
            "assert prompt.index('[Invocation Context]') < prompt.index('[Agent Directory]'); "
            "assert prompt.index('[Agent Directory]') < prompt.index('[New Conversation Since Last Invocation]'); "
            "assert prompt.index('[New Conversation Since Last Invocation]') < prompt.index('Task:'); "
            "assert 'agentId=claudecode | name=ClaudeCode | role=Architecture reviewer' in prompt; "
            "assert 'capabilities=reasoning,code review | tools=shell,filesystem' in prompt; "
            "assert '[New Conversation Since Last Invocation]' in prompt; "
            "assert 'User: user said hi' in prompt; "
            "assert 'claude-code/task-2: peer result' in prompt; "
            "assert prompt.rstrip().endswith('hello'); "
            "print(json.dumps({'type':'text','part':{'text':'ok'}}))\""
        )
    )
    request = AgentExecutionRequest(
        invocationId="invocation-1",
        userId="user-1",
        taskId="task-1",
        traceId="trace-1",
        agentId="opencode",
        context="hello",
        callbackBaseUrl="http://127.0.0.1:8080/api/callback",
        contextPack={
            "availableAgents": [
                {
                    "agentId": "claudecode",
                    "displayName": "ClaudeCode",
                    "role": "Architecture reviewer",
                    "capabilities": ["reasoning", "code review"],
                    "tools": ["shell", "filesystem"],
                }
            ],
            "incrementalChatMessages": [
                {
                    "messageId": "message-1",
                    "role": "user",
                    "agentId": None,
                    "taskId": None,
                    "content": "user said hi",
                    "createdAt": "2026-06-22T00:00:00Z",
                },
                {
                    "messageId": "message-2",
                    "role": "assistant",
                    "agentId": "claude-code",
                    "taskId": "task-2",
                    "content": "peer result",
                    "createdAt": "2026-06-22T00:00:01Z",
                },
            ]
        },
    )

    messages = asyncio.run(provider.execute(request))

    assert messages[0].content == "ok"


def test_opencode_provider_reads_static_prompt_from_config(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    prompt_config = tmp_path / "prompts.json"
    prompt_config.write_text(
        json.dumps(
            {
                "defaultBusinessAgentStaticPrompt": "Default business static prompt.",
                "businessAgentStaticPrompts": {
                    "opencode": "Custom opencode static prompt.",
                    "claudecode": "Custom claudecode static prompt.",
                },
                "masterAgentStaticPrompt": "Custom master static prompt.",
            }
        ),
        encoding="utf-8",
    )
    monkeypatch.setattr(settings, "prompt_config_path", str(prompt_config))

    prompt = OpenCodeProvider._build_prompt(execution_request())

    assert prompt.startswith("Custom opencode static prompt.\n\n[Invocation Context]")
    assert "Task:\nhello" in prompt


@pytest.mark.parametrize(
    ("provider_class", "agent_id"),
    (
        (OpenCodeProvider, "opencode"),
        (ClaudeCodeProvider, "claudecode"),
        (CodexProvider, "codex"),
    ),
)
def test_business_agent_reused_session_omits_static_prompt(
    provider_class: type[OpenCodeProvider] | type[ClaudeCodeProvider] | type[CodexProvider],
    agent_id: str,
) -> None:
    request = execution_request().model_copy(
        update={
            "agent_id": agent_id,
            "provider_session_id": "provider-session-1",
            "provider_prompt_version": load_prompt_config().business_agent_prompt_version(agent_id),
        }
    )

    prompt = provider_class._build_prompt(request)

    assert prompt.startswith("[Invocation Context]")
    assert "Task:\nhello" in prompt


def test_business_agent_keeps_session_when_prompt_version_matches() -> None:
    current_version = load_prompt_config().business_agent_prompt_version("opencode")
    request = execution_request().model_copy(
        update={
            "provider_session_id": "provider-session-current",
            "provider_prompt_version": current_version,
        }
    )

    prepared = prepare_execution_request(request, current_version)

    assert prepared is request
    assert prepared.provider_session_id == "provider-session-current"
    assert prepared.provider_prompt_version == current_version
    assert OpenCodeProvider._build_prompt(prepared).startswith("[Invocation Context]")


def test_business_agent_prompt_limits_multi_turn_collaboration_to_next_hop() -> None:
    opencode_prompt = OpenCodeProvider._build_prompt(execution_request())
    claudecode_prompt = ClaudeCodeProvider._build_prompt(
        execution_request().model_copy(update={"agent_id": "claudecode"})
    )
    codex_prompt = CodexProvider._build_prompt(
        execution_request().model_copy(update={"agent_id": "codex"})
    )

    assert "每轮最多追加一个下一跳" in opencode_prompt
    assert "不得预生成剩余轮次" in opencode_prompt
    assert "每轮最多追加一个下一跳" in claudecode_prompt
    assert "不得预生成剩余轮次" in claudecode_prompt
    assert "每轮最多追加一个下一跳" in codex_prompt
    assert "不得预生成剩余轮次" in codex_prompt


def test_business_agent_providers_share_the_same_prompt_composer() -> None:
    request = execution_request()

    assert OpenCodeProvider._build_prompt(request) == ClaudeCodeProvider._build_prompt(request)
    assert OpenCodeProvider._build_prompt(request) == CodexProvider._build_prompt(request)


def test_business_agent_prompt_removes_static_agent_roster_and_tool_examples() -> None:
    prompts = (
        OpenCodeProvider._build_prompt(execution_request()),
        ClaudeCodeProvider._build_prompt(execution_request().model_copy(update={"agent_id": "claudecode"})),
        CodexProvider._build_prompt(execution_request().model_copy(update={"agent_id": "codex"})),
    )

    for prompt in prompts:
        assert "[Agent Member]" not in prompt
        assert "[Tool Usage Example]" not in prompt
        assert prompt.count("\n1. ") == 1
        assert "\n8. " in prompt
        assert "\n9. " not in prompt


def test_business_agent_prompt_keeps_follow_up_task_context_concise() -> None:
    opencode_prompt = OpenCodeProvider._build_prompt(execution_request())
    claudecode_prompt = ClaudeCodeProvider._build_prompt(
        execution_request().model_copy(update={"agent_id": "claudecode"})
    )
    codex_prompt = CodexProvider._build_prompt(
        execution_request().model_copy(update={"agent_id": "codex"})
    )

    for prompt in (opencode_prompt, claudecode_prompt, codex_prompt):
        assert "tasks[].context 只写可独立执行的简短指令" in prompt
        assert "不复制对话历史、长原文、工具结果或 JSON" in prompt
        assert "平台会增量注入用户和其他 agent 的新消息" in prompt


def test_business_agent_static_prompt_falls_back_to_default(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    prompt_config = tmp_path / "prompts.json"
    prompt_config.write_text(
        json.dumps(
            {
                "defaultBusinessAgentStaticPrompt": "Default business static prompt.",
                "businessAgentStaticPrompts": {
                    "opencode": "Custom opencode static prompt.",
                },
                "masterAgentStaticPrompt": "Custom master static prompt.",
            }
        ),
        encoding="utf-8",
    )
    monkeypatch.setattr(settings, "prompt_config_path", str(prompt_config))

    request = execution_request().model_copy(update={"agent_id": "claudecode"})

    prompt = OpenCodeProvider._build_prompt(request)

    assert prompt.startswith("Default business static prompt.\n\n[Invocation Context]")


def test_opencode_provider_reports_silent_completion() -> None:
    provider = OpenCodeProvider(command=f"{sys.executable} -c \"pass\"")

    messages = asyncio.run(provider.execute(execution_request()))

    assert [message.type for message in messages] == [AgentMessageType.MESSAGE, AgentMessageType.DONE]
    assert "without text output" in messages[0].content
    assert messages[0].raw["reason"] == "silent_completion"


def test_opencode_provider_does_not_stream_silent_completion_callback() -> None:
    provider = OpenCodeProvider(command="opencode")
    message = provider._silent_completion_message(execution_request(), "ses-empty", "missing_run_stdout_content")

    assert not OpenCodeProvider._should_callback_message(message)


def test_opencode_provider_exports_session_from_run_when_run_has_no_text(tmp_path: Path) -> None:
    script = tmp_path / "fake_opencode.py"
    command_log = tmp_path / "commands.jsonl"
    export_payload = {
        "messages": [
            {
                "info": {"role": "assistant"},
                "parts": [{"type": "text", "text": "recovered from run session"}],
            },
        ]
    }
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "if sys.argv[1] == 'run':",
                "    print(json.dumps({'type': 'step_start', 'sessionID': 'ses_test'}))",
                "elif sys.argv[1] == 'export':",
                f"    print(json.dumps({export_payload!r}, ensure_ascii=False))",
            ]
        )
    )
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")

    messages = asyncio.run(provider.execute(execution_request()))

    commands = [json.loads(line) for line in command_log.read_text(encoding="utf-8").splitlines()]
    assert commands[0][0] == "run"
    assert commands[1] == ["export", "ses_test"]
    assert [message.type for message in messages] == [AgentMessageType.TEXT_DELTA, AgentMessageType.DONE]
    assert messages[0].content == "recovered from run session"
    assert messages[0].raw["source"] == "session_export_fallback"
    assert messages[-1].raw["providerSessionId"] == "ses_test"


def test_opencode_provider_reads_session_export_through_pty(tmp_path: Path) -> None:
    script = tmp_path / "fake_opencode_export_tty.py"
    command_log = tmp_path / "commands.jsonl"
    export_payload = {
        "messages": [
            {
                "info": {"role": "assistant"},
                "parts": [{"type": "text", "text": "pty recovered"}],
            },
        ]
    }
    script.write_text(
        "\n".join(
            [
                "import json",
                "import os",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps({'args': sys.argv[1:], 'stdoutIsTty': os.isatty(1)}, ensure_ascii=False) + '\\n')",
                "if sys.argv[1] == 'run':",
                "    print(json.dumps({'type': 'step_start', 'sessionID': 'ses_tty'}))",
                "elif sys.argv[1] == 'export':",
                f"    print(json.dumps({export_payload!r}, ensure_ascii=False))",
            ]
        )
    )
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")

    messages = asyncio.run(provider.execute(execution_request()))

    commands = [json.loads(line) for line in command_log.read_text(encoding="utf-8").splitlines()]
    assert commands[1]["args"] == ["export", "ses_tty"]
    assert commands[1]["stdoutIsTty"] is True
    assert messages[0].content == "pty recovered"


def test_opencode_provider_does_not_cache_session_for_same_trace_and_agent(tmp_path: Path) -> None:
    script = tmp_path / "fake_opencode_reuse.py"
    command_log = tmp_path / "commands.jsonl"
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "print(json.dumps({'type': 'step_start', 'sessionID': 'ses-reused'}))",
                "print(json.dumps({'type': 'text', 'part': {'text': 'ok'}}))",
            ]
        )
    )
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")

    asyncio.run(provider.execute(execution_request()))
    second_request = execution_request().model_copy(update={"taskId": "task-2"})
    asyncio.run(provider.execute(second_request))

    commands = [json.loads(line) for line in command_log.read_text(encoding="utf-8").splitlines()]
    assert "-s" not in commands[0]
    assert "-s" not in commands[1]
    assert commands[1][0] == "run"


def test_opencode_provider_uses_provider_session_id_from_request(tmp_path: Path) -> None:
    script = tmp_path / "fake_opencode_provider_session.py"
    command_log = tmp_path / "commands.jsonl"
    old_export_payload = {
        "messages": [
            {
                "info": {"role": "user", "id": "msg-user-old"},
                "parts": [{"type": "text", "text": "old question"}],
            },
            {
                "info": {"role": "assistant", "id": "msg-assistant-old", "parentID": "msg-user-old"},
                "parts": [{"type": "text", "text": "old answer"}],
            },
        ]
    }
    export_payload = {
        "messages": [
            {
                "info": {"role": "user", "id": "msg-user-old"},
                "parts": [{"type": "text", "text": "old question"}],
            },
            {
                "info": {"role": "assistant", "id": "msg-assistant-old", "parentID": "msg-user-old"},
                "parts": [{"type": "text", "text": "old answer"}],
            },
            {
                "info": {"role": "user", "id": "msg-user-current"},
                "parts": [{"type": "text", "text": "current question"}],
            },
            {
                "info": {"role": "assistant", "id": "msg-assistant-current", "parentID": "msg-user-current"},
                "parts": [{"type": "text", "text": "recovered from provider session"}],
            },
        ]
    }
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "export_count = sum(1 for line in log_path.read_text(encoding='utf-8').splitlines() if json.loads(line)[0] == 'export')",
                "if 'run' in sys.argv[1:]:",
                "    print(json.dumps({'type': 'step_start', 'sessionID': 'ses-existing'}))",
                "elif sys.argv[1:3] == ['export', 'ses-existing']:",
                "    if export_count == 1:",
                f"        print(json.dumps({old_export_payload!r}, ensure_ascii=False))",
                "    else:",
                f"        print(json.dumps({export_payload!r}, ensure_ascii=False))",
            ]
        )
    )
    request = execution_request().model_copy(update={"provider_session_id": "ses-existing"})
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")

    messages = asyncio.run(provider.execute(request))

    commands = [json.loads(line) for line in command_log.read_text(encoding="utf-8").splitlines()]
    assert commands[0] == ["export", "ses-existing"]
    assert commands[1][commands[1].index("-s") + 1] == "ses-existing"
    assert commands[1][commands[1].index("-s") + 2] == "run"
    assert commands[2] == ["export", "ses-existing"]
    assert [message.type for message in messages] == [AgentMessageType.TEXT_DELTA, AgentMessageType.DONE]
    assert messages[0].content == "recovered from provider session"
    assert messages[-1].raw["providerSessionId"] == "ses-existing"


def test_opencode_provider_ignores_reused_session_run_stdout_and_uses_export(tmp_path: Path) -> None:
    script = tmp_path / "fake_opencode_stale_stdout.py"
    command_log = tmp_path / "commands.jsonl"
    old_export_payload = {
        "messages": [
            {
                "info": {"role": "user", "id": "msg-user-old"},
                "parts": [{"type": "text", "text": "1+1=?"}],
            },
            {
                "info": {"role": "assistant", "id": "msg-assistant-old", "parentID": "msg-user-old"},
                "parts": [{"type": "text", "text": "2"}],
            },
        ]
    }
    new_export_payload = {
        "messages": [
            {
                "info": {"role": "user", "id": "msg-user-old"},
                "parts": [{"type": "text", "text": "1+1=?"}],
            },
            {
                "info": {"role": "assistant", "id": "msg-assistant-old", "parentID": "msg-user-old"},
                "parts": [{"type": "text", "text": "2"}],
            },
            {
                "info": {"role": "user", "id": "msg-user-current"},
                "parts": [{"type": "text", "text": "2+2=?"}],
            },
            {
                "info": {"role": "assistant", "id": "msg-assistant-current", "parentID": "msg-user-current"},
                "parts": [{"type": "text", "text": "4"}],
            },
        ]
    }
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "export_count = sum(1 for line in log_path.read_text(encoding='utf-8').splitlines() if json.loads(line)[0] == 'export')",
                "if 'run' in sys.argv[1:]:",
                "    print(json.dumps({'type': 'step_start', 'sessionID': 'ses_wrong123'}))",
                "    print(json.dumps({'type': 'text', 'part': {'text': '2'}}))",
                "elif sys.argv[1:3] == ['export', 'ses-existing']:",
                "    if export_count < 3:",
                f"        print(json.dumps({old_export_payload!r}, ensure_ascii=False))",
                "    else:",
                f"        print(json.dumps({new_export_payload!r}, ensure_ascii=False))",
            ]
        )
    )
    request = execution_request().model_copy(update={"provider_session_id": "ses-existing"})
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")

    messages = asyncio.run(provider.execute(request))

    commands = [json.loads(line) for line in command_log.read_text(encoding="utf-8").splitlines()]
    assert commands[0] == ["export", "ses-existing"]
    assert commands[1][commands[1].index("-s") + 1] == "ses-existing"
    assert commands[2] == ["export", "ses-existing"]
    assert commands[3] == ["export", "ses-existing"]
    assert [message.type for message in messages] == [AgentMessageType.TEXT_DELTA, AgentMessageType.DONE]
    assert messages[0].content == "4"
    assert messages[0].raw["source"] == "session_export_fallback"
    assert messages[-1].raw["providerSessionId"] == "ses-existing"


def test_opencode_provider_publishes_only_final_matching_export_snapshot(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    async def fast_sleep(_seconds: float) -> None:
        return None

    monkeypatch.setattr(asyncio, "sleep", fast_sleep)
    script = tmp_path / "fake_opencode_export_snapshots.py"
    command_log = tmp_path / "commands.jsonl"
    old_export_payload = {
        "messages": [
            {
                "info": {"role": "user", "id": "msg-user-old"},
                "parts": [{"type": "text", "text": "old question"}],
            },
            {
                "info": {"role": "assistant", "id": "msg-assistant-old", "parentID": "msg-user-old"},
                "parts": [{"type": "text", "text": "old answer"}],
            },
        ]
    }
    new_export_payload = {
        "messages": [
            *old_export_payload["messages"],
            {
                "info": {"role": "user", "id": "msg-user-current"},
                "parts": [{"type": "text", "text": "current question"}],
            },
            {
                "info": {
                    "role": "assistant",
                    "id": "msg-assistant-current",
                    "parentID": "msg-user-current",
                },
                "parts": [{"type": "text", "text": "current answer"}],
            },
        ]
    }
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "export_count = sum(1 for line in log_path.read_text(encoding='utf-8').splitlines() if json.loads(line)[0] == 'export')",
                "if 'run' in sys.argv[1:]:",
                "    pass",
                "elif sys.argv[1:3] == ['export', 'ses-existing']:",
                "    if export_count < 3:",
                f"        print(json.dumps({old_export_payload!r}, ensure_ascii=False))",
                "    else:",
                f"        print(json.dumps({new_export_payload!r}, ensure_ascii=False))",
            ]
        )
    )
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")
    posted: list[str] = []

    async def capture_stream_message(_request: AgentExecutionRequest, message: AgentMessage) -> None:
        if message.content:
            posted.append(message.content)

    provider._post_stream_message = capture_stream_message  # type: ignore[method-assign]
    request = execution_request().model_copy(update={"provider_session_id": "ses-existing"})

    messages = asyncio.run(provider.execute(request))

    assert [message.content for message in messages[:-1]] == ["current answer"]
    assert posted == ["current answer"]

def test_opencode_provider_waits_for_post_run_assistant_when_baseline_marker_missing(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    async def fast_sleep(_seconds: float) -> None:
        return None

    monkeypatch.setattr(asyncio, "sleep", fast_sleep)
    monkeypatch.setattr("agent_runtime.providers.opencode.time.time", lambda: 2000.0)
    script = tmp_path / "fake_opencode_delayed_export.py"
    command_log = tmp_path / "commands.jsonl"
    old_export_payload = {
        "messages": [
            {
                "info": {"role": "user", "id": "msg-user-old", "time": {"created": 1000}},
                "parts": [{"type": "text", "text": "11+4=?"}],
            },
            {
                "info": {
                    "role": "assistant",
                    "id": "msg-assistant-old",
                    "parentID": "msg-user-old",
                    "time": {"created": 1000, "completed": 1200},
                },
                "parts": [{"type": "text", "text": "15"}],
            },
        ]
    }
    new_export_payload = {
        "messages": [
            {
                "info": {"role": "user", "id": "msg-user-old", "time": {"created": 1000}},
                "parts": [{"type": "text", "text": "11+4=?"}],
            },
            {
                "info": {
                    "role": "assistant",
                    "id": "msg-assistant-old",
                    "parentID": "msg-user-old",
                    "time": {"created": 1000, "completed": 1200},
                },
                "parts": [{"type": "text", "text": "15"}],
            },
            {
                "info": {"role": "user", "id": "msg-user-new", "time": {"created": 2_001_000}},
                "parts": [{"type": "text", "text": "1+4=?"}],
            },
            {
                "info": {
                    "role": "assistant",
                    "id": "msg-assistant-new",
                    "parentID": "msg-user-new",
                    "time": {"created": 2_001_000, "completed": 2_002_000},
                },
                "parts": [{"type": "text", "text": "5"}],
            },
        ]
    }
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "export_count = sum(1 for line in log_path.read_text(encoding='utf-8').splitlines() if json.loads(line)[0] == 'export')",
                "if 'run' in sys.argv[1:]:",
                "    pass",
                "elif sys.argv[1:3] == ['export', 'ses-existing']:",
                "    if export_count == 1:",
                "        print(json.dumps({'messages': []}, ensure_ascii=False))",
                "    elif export_count == 2:",
                f"        print(json.dumps({old_export_payload!r}, ensure_ascii=False))",
                "    else:",
                f"        print(json.dumps({new_export_payload!r}, ensure_ascii=False))",
            ]
        )
    )
    request = execution_request().model_copy(update={"provider_session_id": "ses-existing"})
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")

    messages = asyncio.run(provider.execute(request))

    commands = [json.loads(line) for line in command_log.read_text(encoding="utf-8").splitlines()]
    assert commands[0] == ["export", "ses-existing"]
    assert commands[1][commands[1].index("-s") + 1] == "ses-existing"
    assert commands[2] == ["export", "ses-existing"]
    assert commands[3] == ["export", "ses-existing"]
    assert [message.type for message in messages] == [AgentMessageType.TEXT_DELTA, AgentMessageType.DONE]
    assert messages[0].content == "5"
    assert messages[0].raw["source"] == "session_export_fallback"


def test_opencode_provider_retries_export_existing_provider_session_when_run_omits_text(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    async def fast_sleep(_seconds: float) -> None:
        return None

    monkeypatch.setattr(asyncio, "sleep", fast_sleep)
    monkeypatch.setattr("agent_runtime.providers.opencode.time.time", lambda: 2000.0)
    script = tmp_path / "fake_opencode_existing_session.py"
    command_log = tmp_path / "commands.jsonl"
    export_payload = {
        "messages": [
            {
                "info": {"role": "user", "id": "msg-user-current", "time": {"created": 2_001_000}},
                "parts": [{"type": "text", "text": "[Invocation Context]\ntaskId: task-1\n\nTask:\nhello"}],
            },
            {
                "info": {"role": "assistant", "parentID": "msg-user-current", "time": {"created": 2_001_000, "completed": 2_002_000}},
                "parts": [
                    {"type": "reasoning", "text": "hidden reasoning"},
                    {"type": "text", "text": "recovered answer"},
                ],
            },
        ]
    }
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "export_count = sum(1 for line in log_path.read_text(encoding='utf-8').splitlines() if json.loads(line)[0] == 'export')",
                "if 'run' in sys.argv[1:]:",
                "    pass",
                "elif sys.argv[1] == 'export':",
                "    if export_count < 3:",
                "        print(json.dumps({'messages': []}, ensure_ascii=False))",
                "    else:",
                f"        print(json.dumps({export_payload!r}, ensure_ascii=False))",
            ]
        )
    )
    request = execution_request().model_copy(update={"provider_session_id": "ses-existing"})
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")

    messages = asyncio.run(provider.execute(request))

    commands = [json.loads(line) for line in command_log.read_text(encoding="utf-8").splitlines()]
    assert commands[0] == ["export", "ses-existing"]
    assert commands[1][commands[1].index("-s") + 1] == "ses-existing"
    assert commands[1][commands[1].index("-s") + 2] == "run"
    assert commands[2] == ["export", "ses-existing"]
    assert commands[3] == ["export", "ses-existing"]
    assert [message.type for message in messages] == [AgentMessageType.TEXT_DELTA, AgentMessageType.DONE]
    assert messages[0].content == "recovered answer"
    assert messages[0].raw["source"] == "session_export_fallback"
    assert messages[0].raw["exportAttempts"] == 2
    assert messages[-1].raw["providerSessionId"] == "ses-existing"


@pytest.mark.parametrize("use_pty", [False, True])
def test_opencode_provider_exports_reported_session_when_fresh_run_omits_text(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, use_pty: bool,
) -> None:
    monkeypatch.setattr(settings, "opencode_use_pty", use_pty)
    script = tmp_path / "fake_opencode_fresh_session.py"
    command_log = tmp_path / "commands.jsonl"
    export_payload = {
        "messages": [
            {
                "info": {"role": "user", "id": "msg-user-current"},
                "parts": [{"type": "text", "text": "[Current Task]\n1+1=？"}],
            },
            {
                "info": {"role": "assistant", "parentID": "msg-user-current"},
                "parts": [{"type": "text", "text": "2"}],
            },
        ]
    }
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "if sys.argv[1:3] == ['export', 'ses_123abc']:",
                f"    print(json.dumps({export_payload!r}, ensure_ascii=False))",
                "elif 'run' in sys.argv[1:]:",
                "    print(json.dumps({'type': 'step_start', 'sessionID': 'ses_123abc'}))",
            ]
        )
    )
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")

    messages = asyncio.run(provider.execute(execution_request().model_copy(update={"callback_base_url": None})))

    commands = [json.loads(line) for line in command_log.read_text(encoding="utf-8").splitlines()]
    assert "run" in commands[0]
    assert commands[1:] == [["export", "ses_123abc"]]
    assert [message.type for message in messages] == [AgentMessageType.TEXT_DELTA, AgentMessageType.DONE]
    assert messages[0].content == "2"
    assert messages[0].raw["source"] == "session_export_fallback"
    assert messages[-1].raw["providerSessionId"] == "ses_123abc"


def test_opencode_provider_extracts_latest_assistant_from_session_export() -> None:
    export_payload = {
        "messages": [
            {
                "info": {"role": "user", "id": "msg-user-old"},
                "parts": [{"type": "text", "text": "taskId: old-task"}],
            },
            {
                "info": {"role": "assistant", "parentID": "msg-user-old"},
                "parts": [{"type": "text", "text": "old answer"}],
            },
            {
                "info": {"role": "user", "id": "msg-user-current"},
                "parts": [{"type": "text", "text": "taskId: task-1"}],
            },
            {
                "info": {"role": "assistant", "parentID": "msg-user-current"},
                "parts": [{"type": "text", "text": "latest answer"}],
            },
        ]
    }

    text = OpenCodeProvider._extract_latest_assistant_text_from_export(json.dumps(export_payload, ensure_ascii=False))

    assert text == "latest answer"


def test_opencode_provider_ignores_plain_tool_status_lines() -> None:
    provider = OpenCodeProvider(command="opencode")

    messages = provider._stdout_to_messages(
        execution_request(),
        "\n".join(
            [
                "⚙ agent-crossing-master-agent_get_task_status_snapshot {\"taskId\":\"task-1\"}",
                "visible answer",
                "⚙ agent-crossing-master-agent_create_tasks {\"tasks\":[]}",
            ]
        ),
    )

    assert [message.content for message in messages] == ["visible answer"]


def test_opencode_provider_strips_plain_invalid_tool_error_blocks() -> None:
    provider = OpenCodeProvider(command="opencode")

    messages = provider._stdout_to_messages(
        execution_request(),
        "\n".join(
            [
                "answer before tool error✗ Invalid Tool",
                "The arguments provided to the tool are invalid: Invalid input",
                "Error message: JSON Parse error",
                "answer after tool error",
            ]
        ),
    )

    assert [message.content for message in messages] == ["answer before tool error", "answer after tool error"]


def test_opencode_export_text_extraction_skips_tool_parts() -> None:
    export_payload = {
        "messages": [
            {
                "info": {"role": "assistant", "parentID": "msg-user-current"},
                "parts": [
                    {
                        "type": "tool",
                        "tool": "agent-crossing-master-agent_get_task_status_snapshot",
                        "state": {"input": {"taskId": "task-1"}},
                    },
                    {"type": "text", "text": "clean answer"},
                    {
                        "type": "tool",
                        "tool": "agent-crossing-master-agent_create_tasks",
                        "state": {"input": {"tasks": []}},
                    },
                ],
            }
        ]
    }

    text = OpenCodeProvider._extract_latest_assistant_text_from_export(json.dumps(export_payload, ensure_ascii=False))

    assert text == "clean answer"


def test_opencode_provider_matches_assistant_to_any_new_user_after_baseline() -> None:
    export_payload = {
        "messages": [
            {
                "info": {"role": "user", "id": "msg-user-old", "time": {"created": 1000}},
                "parts": [{"type": "text", "text": "first turn"}],
            },
            {
                "info": {
                    "role": "assistant",
                    "id": "msg-assistant-old",
                    "parentID": "msg-user-old",
                    "time": {"created": 1100, "completed": 1200},
                },
                "parts": [{"type": "text", "text": "old answer"}],
            },
            {
                "info": {"role": "user", "id": "msg-user-current", "time": {"created": 2000}},
                "parts": [{"type": "text", "text": "third turn"}],
            },
            {
                "info": {
                    "role": "assistant",
                    "id": "msg-assistant-current",
                    "parentID": "msg-user-current",
                    "time": {"created": 2100, "completed": 2200},
                },
                "parts": [{"type": "text", "text": "current answer"}],
            },
            {
                "info": {"role": "user", "id": "msg-user-extra", "time": {"created": 2300}},
                "parts": [{"type": "text", "text": "extra provider-side user marker without assistant"}],
            },
        ]
    }

    assistant = OpenCodeProvider._extract_assistant_for_current_run_from_export(
        json.dumps(export_payload, ensure_ascii=False),
        previous_user_marker="msg-user-old",
        min_message_created_at_ms=1500,
    )

    assert assistant["userMarker"] == "msg-user-current"
    assert assistant["marker"] == "msg-assistant-current"
    assert assistant["text"] == "current answer"


def test_opencode_provider_prefers_baseline_order_over_runtime_timestamp_gate() -> None:
    export_payload = {
        "messages": [
            {
                "info": {"role": "user", "id": "msg-user-old", "time": {"created": 1000}},
                "parts": [{"type": "text", "text": "old turn"}],
            },
            {
                "info": {
                    "role": "assistant",
                    "id": "msg-assistant-old",
                    "parentID": "msg-user-old",
                    "time": {"created": 1100, "completed": 1200},
                },
                "parts": [{"type": "text", "text": "old answer"}],
            },
            {
                "info": {"role": "user", "id": "msg-user-current", "time": {"created": 2000}},
                "parts": [{"type": "text", "text": "current turn"}],
            },
            {
                "info": {
                    "role": "assistant",
                    "id": "msg-assistant-current",
                    "parentID": "msg-user-current",
                    "time": {"created": 2100, "completed": 2200},
                },
                "parts": [{"type": "text", "text": "current answer"}],
            },
        ]
    }

    assistant = OpenCodeProvider._extract_assistant_for_current_run_from_export(
        json.dumps(export_payload, ensure_ascii=False),
        previous_user_marker="msg-user-old",
        min_message_created_at_ms=999999,
    )

    assert assistant["userMarker"] == "msg-user-current"
    assert assistant["marker"] == "msg-assistant-current"
    assert assistant["text"] == "current answer"


@pytest.mark.parametrize("use_pty", [False, True])
@pytest.mark.parametrize("run_result", ["silent", "text", "error"])
def test_opencode_provider_never_uses_other_sessions_when_run_omits_session_id(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, use_pty: bool, run_result: str,
) -> None:
    monkeypatch.setattr(settings, "opencode_use_pty", use_pty)
    script = tmp_path / "fake_opencode_session_list.py"
    command_log = tmp_path / "commands.jsonl"
    export_payload = {
        "messages": [
            {
                "info": {"role": "assistant"},
                "parts": [{"type": "text", "text": "SYNTHETIC_OTHER_USER_PRIVATE_ANSWER"}],
            },
        ]
    }
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "if 'run' in sys.argv[1:]:",
                f"    if {run_result!r} == 'text': print('current invocation answer')",
                f"    if {run_result!r} == 'error': sys.exit(1)",
                "elif sys.argv[1:3] == ['session', 'list']:",
                "    print('ses_newest123  shiny-forest  2026-06-29')",
                "    print('ses_older123   old-forest    2026-06-28')",
                "elif sys.argv[1] == 'export':",
                f"    print(json.dumps({export_payload!r}, ensure_ascii=False))",
            ]
        )
    )
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")

    messages = asyncio.run(provider.execute(execution_request().model_copy(update={"callback_base_url": None})))

    commands = [json.loads(line) for line in command_log.read_text(encoding="utf-8").splitlines()]
    assert commands[0][0] == "run"
    assert len(commands) == 1, "Missing session identity must not trigger session list or export"
    assert messages[-1].type == AgentMessageType.DONE
    assert not messages[-1].raw.get("providerSessionId")
    assert not messages[-1].raw.get("sessionId")
    assert "SYNTHETIC_OTHER_USER_PRIVATE_ANSWER" not in json.dumps(
        [message.model_dump(mode="json") for message in messages]
    )
    if run_result == "text":
        assert messages[0].content == "current invocation answer"
    elif run_result == "error":
        assert messages[0].type == AgentMessageType.ERROR
    else:
        assert messages[0].raw["sessionExportFallback"]["reason"] == "missing_session_id"
        assert messages[0].raw["sessionExportFallback"]["attempted"] is False


@pytest.mark.parametrize("known_session", [False, True])
@pytest.mark.parametrize("json_output", [False, True])
def test_opencode_provider_does_not_treat_answer_text_as_session_identity(
    known_session: bool, json_output: bool,
) -> None:
    provider = OpenCodeProvider(command="unused")
    session_ids: list[str] = []
    request = execution_request().model_copy(update={"callback_base_url": None})
    if known_session:
        provider._stdout_to_messages(
            request, json.dumps({"type": "step_start", "sessionID": "ses_current123"}), session_ids
        )
    text = "Example session ses_other123 is quoted content, not this invocation's identity"
    output = json.dumps({"type": "text", "part": {"text": text}}) if json_output else text

    messages = provider._stdout_to_messages(request, output, session_ids)

    assert messages[0].content == text
    assert session_ids == (["ses_current123"] if known_session else [])


def test_opencode_provider_parses_part_event_text(tmp_path: Path) -> None:
    script = tmp_path / "fake_opencode_part_event.py"
    script.write_text(
        "\n".join(
            [
                "import json",
                "import sys",
                "print(json.dumps({'type': 'step_start', 'sessionID': 'ses-part'}))",
                "print(json.dumps({'type': 'part', 'part': {'type': 'text', 'text': 'visible part text'}}))",
            ]
        )
    )
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")

    messages = asyncio.run(provider.execute(execution_request()))

    assert [message.type for message in messages] == [AgentMessageType.TEXT_DELTA, AgentMessageType.DONE]
    assert messages[0].content == "visible part text"


def test_opencode_provider_reports_silent_completion_after_tool_events_when_export_has_no_text(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    async def fast_sleep(_seconds: float) -> None:
        return None

    monkeypatch.setattr(asyncio, "sleep", fast_sleep)
    script = tmp_path / "fake_opencode_tool_no_text.py"
    command_log = tmp_path / "commands.jsonl"
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "if sys.argv[1] == 'run':",
                "    print(json.dumps({'type': 'step_start', 'sessionID': 'ses-tool'}))",
                "    print(json.dumps({'type': 'tool_use', 'part': {'tool': 'webfetch', 'state': {'input': {'url': 'https://example.com'}}}}))",
                "    print(json.dumps({'type': 'tool_use', 'part': {'tool': 'webfetch', 'state': {'input': {'url': 'https://example.org'}}}}))",
                "elif sys.argv[1] == 'export':",
                "    print(json.dumps({'messages': []}, ensure_ascii=False))",
            ]
        )
    )
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")

    messages = asyncio.run(provider.execute(execution_request()))

    commands = [json.loads(line) for line in command_log.read_text(encoding="utf-8").splitlines()]
    export_commands = [command for command in commands if command and command[0] == "export"]
    assert export_commands
    assert all(command == ["export", "ses-tool"] for command in export_commands)
    assert [message.type for message in messages] == [
        AgentMessageType.MESSAGE,
        AgentMessageType.MESSAGE,
        AgentMessageType.MESSAGE,
        AgentMessageType.DONE,
    ]
    assert messages[0].content == "tool_use:webfetch"
    assert messages[1].content == "tool_use:webfetch"
    assert "without text output" in messages[2].content
    assert messages[2].raw["detail"] == "missing_run_stdout_content"
    assert messages[2].raw["sessionExportFallback"]["readMode"] == "pty"


def test_opencode_provider_treats_tool_event_as_tool_message_when_export_has_no_text(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    async def fast_sleep(_seconds: float) -> None:
        return None

    monkeypatch.setattr(asyncio, "sleep", fast_sleep)
    script = tmp_path / "fake_opencode_tool_event.py"
    command_log = tmp_path / "commands.jsonl"
    script.write_text(
        "\n".join(
            [
                "import json",
                "import pathlib",
                "import sys",
                f"log_path = pathlib.Path({str(command_log)!r})",
                "with log_path.open('a', encoding='utf-8') as log:",
                "    log.write(json.dumps(sys.argv[1:], ensure_ascii=False) + '\\n')",
                "if sys.argv[1] == 'run':",
                "    print(json.dumps({'type': 'step_start', 'sessionID': 'ses-tool-event'}))",
                "    print(json.dumps({'type': 'tool', 'tool': 'webfetch', 'state': {'input': {'url': 'https://example.com'}, 'output': 'raw web page'}}))",
                "elif sys.argv[1] == 'export':",
                "    print(json.dumps({'messages': []}, ensure_ascii=False))",
            ]
        )
    )
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")

    messages = asyncio.run(provider.execute(execution_request()))

    commands = [json.loads(line) for line in command_log.read_text(encoding="utf-8").splitlines()]
    export_commands = [command for command in commands if command and command[0] == "export"]
    assert export_commands
    assert all(command == ["export", "ses-tool-event"] for command in export_commands)
    assert [message.type for message in messages] == [
        AgentMessageType.MESSAGE,
        AgentMessageType.MESSAGE,
        AgentMessageType.DONE,
    ]
    assert messages[0].content == "tool_use:webfetch"
    assert not messages[0].content.startswith("{")
    assert messages[0].raw["input"] == {"url": "https://example.com"}
    assert "without text output" in messages[1].content
    assert messages[1].raw["detail"] == "missing_run_stdout_content"
    assert messages[1].raw["sessionExportFallback"]["readMode"] == "pty"


def test_opencode_provider_injects_callback_environment_without_token(tmp_path: Path) -> None:
    env_log = tmp_path / "opencode-env.json"
    script = tmp_path / "fake_opencode_env.py"
    script.write_text(
        "\n".join(
            [
                "import json",
                "import os",
                "import pathlib",
                f"pathlib.Path({str(env_log)!r}).write_text(json.dumps({{",
                "    'invocationId': os.environ['AGENT_CROSSING_INVOCATION_ID'],",
                "    'callbackBaseUrl': os.environ['AGENT_CROSSING_CALLBACK_BASE_URL'],",
                "    'callbackToken': os.environ.get('AGENT_CROSSING_CALLBACK_TOKEN', 'NO_TOKEN'),",
                "}, ensure_ascii=False), encoding='utf-8')",
                "print('ok')",
            ]
        )
    )
    provider = OpenCodeProvider(command=f"{sys.executable} {script}")

    messages = asyncio.run(provider.execute(execution_request()))

    payload = json.loads(env_log.read_text(encoding="utf-8"))
    assert payload == {
        "invocationId": "invocation-1",
        "callbackBaseUrl": "http://127.0.0.1:8080/api/callback",
        "callbackToken": "NO_TOKEN",
    }
    assert messages[0].content == "ok"
