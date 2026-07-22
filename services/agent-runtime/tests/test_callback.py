import asyncio
import json

import httpx
import pytest

from agent_runtime.callback.client import CallbackClient
from agent_runtime.callback.dispatcher import CallbackDispatcher
from agent_runtime.contracts.models import AgentExecutionRequest


def test_callback_client_posts_message_without_token(monkeypatch: pytest.MonkeyPatch) -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"success": True, "data": []})

    transport = httpx.MockTransport(handler)
    original_async_client = httpx.AsyncClient

    class FakeAsyncClient:
        def __init__(self, timeout: float) -> None:
            self._client = original_async_client(transport=transport, timeout=timeout)

        async def __aenter__(self) -> httpx.AsyncClient:
            return await self._client.__aenter__()

        async def __aexit__(self, exc_type: object, exc: object, tb: object) -> None:
            await self._client.__aexit__(exc_type, exc, tb)

    monkeypatch.setattr(httpx, "AsyncClient", FakeAsyncClient)

    result = asyncio.run(
        CallbackClient("http://platform-api:8080/api/callback").post_message(
            invocation_id="invocation-1",
            content="@opencode continue",
        )
    )

    assert result.status_code == 200
    assert result.body["success"] is True
    assert len(requests) == 1
    assert str(requests[0].url) == "http://platform-api:8080/api/callback/messages"
    assert requests[0].headers.get("authorization") is None
    assert requests[0].read() == b'{"invocationId":"invocation-1","content":"@opencode continue","stream":false}'


def test_callback_dispatcher_coalesces_chunks_and_assigns_sequence() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"success": True, "data": []})

    async def run() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        dispatcher = CallbackDispatcher(client=client, flush_interval_seconds=0.075)
        request = AgentExecutionRequest(
            invocationId="invocation-1",
            userId="user-1",
            taskId="task-1",
            traceId="trace-1",
            agentId="claudecode",
            context="hello",
            callbackBaseUrl="http://platform-api:8080/api/callback",
        )
        stream = dispatcher.open_stream(request)
        assert stream is not None
        await stream.publish("hello")
        await stream.publish(" world")
        summary = await stream.close()
        await client.aclose()

        assert summary.completed is True
        assert summary.last_sequence == 1

    asyncio.run(run())

    assert len(requests) == 1
    assert requests[0].read() == (
        b'{"invocationId":"invocation-1","content":"hello world","stream":true,"sequence":1}'
    )


def test_callback_dispatcher_sequences_multiple_batches_monotonically() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"success": True, "data": []})

    async def run() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        dispatcher = CallbackDispatcher(
            client=client,
            flush_interval_seconds=0.075,
            flush_threshold_bytes=5,
        )
        request = AgentExecutionRequest(
            invocationId="invocation-2",
            userId="user-1",
            taskId="task-1",
            traceId="trace-1",
            agentId="claudecode",
            context="hello",
            callbackBaseUrl="http://platform-api:8080/api/callback",
        )
        stream = dispatcher.open_stream(request)
        assert stream is not None
        await stream.publish("first")
        await stream.publish("second")
        summary = await stream.close()
        await client.aclose()

        assert summary.completed is True
        assert summary.last_sequence == 2

    asyncio.run(run())

    assert [json.loads(request.read())["sequence"] for request in requests] == [1, 2]


def test_callback_dispatcher_bounds_slow_flush_and_marks_stream_incomplete() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(10)
        return httpx.Response(200, json={"success": True, "data": []})

    async def run() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        dispatcher = CallbackDispatcher(
            client=client,
            flush_interval_seconds=0.001,
            close_timeout_seconds=0.02,
        )
        request = AgentExecutionRequest(
            invocationId="invocation-slow",
            userId="user-1",
            taskId="task-1",
            traceId="trace-1",
            agentId="claudecode",
            context="hello",
            callbackBaseUrl="http://platform-api:8080/api/callback",
        )
        stream = dispatcher.open_stream(request)
        assert stream is not None
        await stream.publish("hello")
        summary = await stream.close()
        await client.aclose()

        assert summary.completed is False
        assert summary.last_sequence == 1

    asyncio.run(run())


def test_callback_dispatcher_drops_overflow_without_blocking_cli_reader() -> None:
    async def run() -> None:
        client = httpx.AsyncClient(
            transport=httpx.MockTransport(
                lambda request: httpx.Response(200, json={"success": True, "data": []})
            )
        )
        dispatcher = CallbackDispatcher(
            client=client,
            flush_interval_seconds=0.001,
            queue_capacity=1,
        )
        request = AgentExecutionRequest(
            invocationId="invocation-overflow",
            userId="user-1",
            taskId="task-1",
            traceId="trace-1",
            agentId="claudecode",
            context="hello",
            callbackBaseUrl="http://platform-api:8080/api/callback",
        )
        stream = dispatcher.open_stream(request)
        assert stream is not None
        await stream.publish("first")
        await stream.publish("dropped")
        summary = await stream.close()
        await client.aclose()

        assert summary.completed is False
        assert summary.last_sequence == 1

    asyncio.run(run())
