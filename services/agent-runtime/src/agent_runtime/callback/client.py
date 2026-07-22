from dataclasses import dataclass
from urllib.parse import urljoin

import httpx


@dataclass(frozen=True)
class CallbackMessageResult:
    status_code: int
    body: dict


class CallbackClient:
    def __init__(
        self,
        callback_base_url: str,
        timeout_seconds: float = 30.0,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._callback_base_url = callback_base_url.rstrip("/") + "/"
        self._timeout_seconds = timeout_seconds
        self._client = client

    async def post_message(
        self,
        invocation_id: str,
        content: str,
        stream: bool = False,
        sequence: int | None = None,
    ) -> CallbackMessageResult:
        url = urljoin(self._callback_base_url, "messages")
        payload: dict[str, object] = {
            "invocationId": invocation_id,
            "content": content,
            "stream": stream,
        }
        if sequence is not None:
            payload["sequence"] = sequence

        if self._client is not None:
            response = await self._client.post(url, json=payload)
        else:
            async with httpx.AsyncClient(timeout=self._timeout_seconds) as client:
                response = await client.post(url, json=payload)
        response.raise_for_status()
        return CallbackMessageResult(status_code=response.status_code, body=response.json())
