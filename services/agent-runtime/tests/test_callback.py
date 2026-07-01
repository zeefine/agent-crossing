import asyncio

import httpx
import pytest

from agent_runtime.callback.client import CallbackClient


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
