import asyncio

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from agent_runtime.api import routes
from agent_runtime.config import Settings, settings
from agent_runtime.contracts.models import AgentExecutionRequest
from agent_runtime.main import create_app
from agent_runtime.providers.registry import ProviderRegistry
from agent_runtime.runtime import service as runtime_module
from agent_runtime.runtime.service import AgentRuntimeService


def request(invocation_id: str = "inv-canceled") -> AgentExecutionRequest:
    return AgentExecutionRequest(
        invocationId=invocation_id, userId="user", taskId="task", traceId="trace",
        agentId="codex", context="work",
    )


class RecordingProvider:
    agent_id = "codex"

    def __init__(self):
        self.calls: list[str] = []

    async def execute(self, request):
        self.calls.append(request.invocation_id)
        return []


def test_cancel_before_registration_blocks_delayed_and_repeated_execution() -> None:
    async def scenario():
        provider = RecordingProvider()
        service = AgentRuntimeService(ProviderRegistry([provider]))
        await service.cancel("inv-canceled")

        for _ in range(2):
            response = await service.execute(request())
            assert response.messages == []
            assert response.final_text is None
        assert provider.calls == []
        await service.execute(request("inv-unrelated"))
        assert provider.calls == ["inv-unrelated"]

    asyncio.run(scenario())


def test_cancel_before_execute_is_accepted_by_http_api(monkeypatch) -> None:
    provider = RecordingProvider()
    monkeypatch.setattr(routes, "runtime_service", AgentRuntimeService(ProviderRegistry([provider])))
    client = TestClient(create_app())

    canceled = client.post("/api/runtime/invocations/inv-canceled/cancel")
    executed = client.post("/api/runtime/execute", json=request().model_dump(mode="json", by_alias=True))

    assert canceled.status_code == 200
    assert canceled.json() == {"invocationId": "inv-canceled", "accepted": True}
    assert executed.status_code == 200
    assert executed.json()["messages"] == []
    assert provider.calls == []


def test_registration_waiting_on_lock_observes_earlier_cancellation() -> None:
    async def scenario():
        provider = RecordingProvider()
        service = AgentRuntimeService(ProviderRegistry([provider]))
        async with service._active_execution_lock:
            cancellation = asyncio.create_task(service.cancel("inv-canceled"))
            await asyncio.sleep(0)
            execution = asyncio.create_task(service.execute(request()))
            await asyncio.sleep(0)
            assert not cancellation.done() and not execution.done()
        await asyncio.wait_for(asyncio.gather(cancellation, execution), timeout=1)
        assert provider.calls == []

    asyncio.run(scenario())


def test_repeated_active_cancellation_does_not_interrupt_provider_cleanup() -> None:
    async def scenario():
        started, cleaning, allow_cleanup = asyncio.Event(), asyncio.Event(), asyncio.Event()

        class CleaningProvider(RecordingProvider):
            async def execute(self, request):
                self.calls.append(request.invocation_id)
                started.set()
                try:
                    await asyncio.Event().wait()
                except asyncio.CancelledError:
                    cleaning.set()
                    await allow_cleanup.wait()
                    raise

        provider = CleaningProvider()
        service = AgentRuntimeService(ProviderRegistry([provider]))
        execution = asyncio.create_task(service.execute(request()))
        try:
            await asyncio.wait_for(started.wait(), timeout=1)
            assert await service.cancel("inv-canceled") is True
            await asyncio.wait_for(cleaning.wait(), timeout=1)
            assert await service.cancel("inv-canceled") is True
            assert execution.cancelling() == 1
        finally:
            allow_cleanup.set()
            await asyncio.wait_for(execution, timeout=1)
        assert execution.result().messages == []
        # Cleanup must not consume the cancellation marker and let a delayed retry restart the CLI.
        await asyncio.wait_for(service.execute(request()), timeout=1)
        assert provider.calls == ["inv-canceled"]

    asyncio.run(scenario())


@pytest.fixture
def cancellation_clock(monkeypatch):
    now = [100.0]
    monkeypatch.setattr(settings, "cancellation_ttl_seconds", 10.0)
    monkeypatch.setattr(runtime_module, "monotonic", lambda: now[0])
    return now


def test_cancellation_expires_at_deadline_without_consuming_marker_on_execute(cancellation_clock) -> None:
    async def scenario():
        provider = RecordingProvider()
        service = AgentRuntimeService(ProviderRegistry([provider]))
        assert await service.cancel("inv-canceled") is True
        cancellation_clock[0] = 109.0
        await service.execute(request())
        assert provider.calls == []
        cancellation_clock[0] = 110.0
        await service.execute(request())
        assert provider.calls == ["inv-canceled"]

    asyncio.run(scenario())


def test_repeated_cancellation_renews_marker(cancellation_clock) -> None:
    async def scenario():
        provider = RecordingProvider()
        service = AgentRuntimeService(ProviderRegistry([provider]))
        await service.cancel("inv-canceled")
        cancellation_clock[0] = 101.0
        await service.cancel("expires-earlier")
        cancellation_clock[0] = 105.0
        await service.cancel("inv-canceled")
        cancellation_clock[0] = 111.0
        await service.execute(request())
        assert provider.calls == []
        assert "expires-earlier" not in service._canceled_invocations
        cancellation_clock[0] = 115.0
        await service.execute(request())
        assert provider.calls == ["inv-canceled"]

    asyncio.run(scenario())


@pytest.mark.parametrize("operation", ["execute", "cancel"])
def test_expired_cancellation_markers_are_reclaimed(cancellation_clock, operation) -> None:
    async def scenario():
        service = AgentRuntimeService(ProviderRegistry([RecordingProvider()]))
        await service.cancel("expired")
        cancellation_clock[0] = 105.0
        await service.cancel("live")
        cancellation_clock[0] = 110.0
        if operation == "execute":
            await service.execute(request("unrelated"))
        else:
            await service.cancel("unrelated")
        assert "expired" not in service._canceled_invocations
        assert "live" in service._canceled_invocations

    asyncio.run(scenario())


@pytest.mark.parametrize("ttl", [0, -1, float("inf"), float("nan")])
def test_cancellation_ttl_must_be_positive_and_finite(ttl) -> None:
    with pytest.raises(ValidationError):
        Settings(cancellation_ttl_seconds=ttl)
