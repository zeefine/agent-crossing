from dataclasses import dataclass
from urllib.parse import urljoin

import httpx


@dataclass(frozen=True)
class CallbackMessageResult:
    status_code: int
    body: dict


class CallbackClient:
    def __init__(self, callback_base_url: str, timeout_seconds: float = 30.0) -> None:
        self._callback_base_url = callback_base_url.rstrip("/") + "/"
        self._timeout_seconds = timeout_seconds

    async def post_message(self, invocation_id: str, content: str, stream: bool = False) -> CallbackMessageResult:
        url = urljoin(self._callback_base_url, "messages")
        async with httpx.AsyncClient(timeout=self._timeout_seconds) as client:
            # callback 写回只携带 invocationId 和内容；token 认证在 v1.0 先关闭。
            response = await client.post(
                url,
                json={
                    "invocationId": invocation_id,
                    "content": content,
                    "stream": stream,
                },
            )
            response.raise_for_status()
            return CallbackMessageResult(status_code=response.status_code, body=response.json())
