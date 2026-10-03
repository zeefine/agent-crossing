"""Prompt changes require platform context recovery before any provider side effects."""

import asyncio
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from agent_runtime.api import routes
from agent_runtime.config import settings
from agent_runtime.contracts.models import AgentExecutionRequest
from agent_runtime.main import create_app
from agent_runtime.prompt_config import load_prompt_config
from agent_runtime.providers.claudecode import ClaudeCodeProvider
from agent_runtime.providers.codex import CodexProvider
from agent_runtime.providers.opencode import OpenCodeProvider
from agent_runtime.providers.registry import ProviderRegistry
from agent_runtime.runtime.service import AgentRuntimeService


@pytest.mark.parametrize("provider_class", [CodexProvider, ClaudeCodeProvider, OpenCodeProvider])
@pytest.mark.parametrize("previous_version", ["legacy-prompt-version", None])
def test_changed_prompt_returns_conflict_before_cli_or_callback(
    monkeypatch: pytest.MonkeyPatch,
    provider_class: type,
    previous_version: str | None,
) -> None:
    side_effects: list[str] = []

    class RecordingCallbackDispatcher:
        def open_stream(self, request: AgentExecutionRequest) -> None:
            side_effects.append("callback")
            return None

        async def aclose(self) -> None:
            pass

    async def blocked_subprocess(*args, **kwargs):
        side_effects.append("cli")
        # Safe even on the buggy implementation: never start an actual CLI.
        raise FileNotFoundError("CLI launch disabled by regression test")

    monkeypatch.setattr(asyncio, "create_subprocess_exec", blocked_subprocess)
    monkeypatch.setattr(settings, "opencode_use_pty", False)
    provider = provider_class(command="test-cli", callback_dispatcher=RecordingCallbackDispatcher())
    service = AgentRuntimeService(ProviderRegistry([provider]))
    monkeypatch.setattr(routes, "runtime_service", service)
    request = AgentExecutionRequest(
        invocationId="prompt-conflict-invocation",
        userId="alice",
        taskId="task-1",
        traceId="trace-1",
        agentId=provider.agent_id,
        context="Continue the previous task without losing its constraints.",
        callbackBaseUrl="http://callback.invalid/api/callback",
        providerSessionId="existing-session-with-important-history",
        providerPromptVersion=previous_version,
    )

    with TestClient(create_app()) as client:
        response = client.post("/api/runtime/execute", json=request.model_dump(mode="json", by_alias=True))

    assert response.status_code == 409, response.text
    detail = response.json()["detail"]
    assert detail["code"] == "PROMPT_VERSION_CHANGED"
    assert detail["currentPromptVersion"] == load_prompt_config().business_agent_prompt_version(provider.agent_id)
    assert side_effects == []
    # Rejecting this attempt must not leave a phantom active runtime invocation.
    assert service._active_execution_tasks == {}
    assert request.provider_session_id == "existing-session-with-important-history"


def test_prompt_conflict_is_documented_in_openapi_and_shared_contract() -> None:
    response = create_app().openapi()["paths"]["/api/runtime/execute"]["post"]["responses"]["409"]
    assert response["content"]["application/json"]["schema"]["$ref"].endswith("/PromptVersionConflictResponse")
    contract = json.loads((Path(__file__).parents[3] / "contracts/schemas/runtime-event.schema.json").read_text())
    detail = contract["$defs"]["PromptVersionConflictResponse"]["properties"]["detail"]
    assert detail["required"] == ["code", "currentPromptVersion"]
    assert detail["properties"]["code"]["const"] == "PROMPT_VERSION_CHANGED"
