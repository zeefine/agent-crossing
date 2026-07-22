import asyncio
import logging
import time
from dataclasses import dataclass

import httpx

from agent_runtime.callback.client import CallbackClient
from agent_runtime.contracts.models import AgentExecutionRequest


logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class CallbackDeliverySummary:
    last_sequence: int | None
    completed: bool


class InvocationCallbackStream:
    _STOP = object()

    def __init__(
        self,
        request: AgentExecutionRequest,
        client: httpx.AsyncClient,
        flush_interval_seconds: float,
        flush_threshold_bytes: int,
        queue_capacity: int,
        close_timeout_seconds: float,
    ) -> None:
        self._request = request
        self._callback_client = CallbackClient(
            request.callback_base_url or "",
            client=client,
        )
        self._flush_interval_seconds = flush_interval_seconds
        self._flush_threshold_bytes = flush_threshold_bytes
        self._close_timeout_seconds = close_timeout_seconds
        self._queue: asyncio.Queue[str | object] = asyncio.Queue(maxsize=queue_capacity)
        self._sequence = 0
        self._delivery_failed = False
        self._overflow_logged = False
        self._closed = False
        self._worker = asyncio.create_task(self._run())

    async def publish(self, content: str) -> None:
        if self._closed or not content:
            return
        try:
            self._queue.put_nowait(content)
        except asyncio.QueueFull:
            # callback 是实时体验通道，不能反向阻塞 CLI stdout。队列满时丢中间分片并把
            # streamCompleted 标成 false；同步 response 的 finalText 会在 Java 侧校准终态正文。
            self._delivery_failed = True
            if not self._overflow_logged:
                self._overflow_logged = True
                logger.warning(
                    "Callback queue overflow provider=%s invocationId=%s capacity=%s",
                    self._request.agent_id,
                    self._request.invocation_id,
                    self._queue.maxsize,
                )

    async def close(self) -> CallbackDeliverySummary:
        if self._closed:
            return CallbackDeliverySummary(
                last_sequence=self._sequence or None,
                completed=not self._delivery_failed,
            )
        self._closed = True
        try:
            await asyncio.wait_for(self._stop_and_wait(), timeout=self._close_timeout_seconds)
        except TimeoutError:
            self._delivery_failed = True
            self._worker.cancel()
            await asyncio.gather(self._worker, return_exceptions=True)
            logger.warning(
                "Callback flush timed out provider=%s invocationId=%s timeoutSeconds=%s",
                self._request.agent_id,
                self._request.invocation_id,
                self._close_timeout_seconds,
            )
        return CallbackDeliverySummary(
            last_sequence=self._sequence or None,
            completed=not self._delivery_failed,
        )

    async def _stop_and_wait(self) -> None:
        await self._queue.put(self._STOP)
        await self._worker

    async def _run(self) -> None:
        stop_after_batch = False
        while not stop_after_batch:
            item = await self._queue.get()
            if item is self._STOP:
                self._queue.task_done()
                break

            chunks = [str(item)]
            total_bytes = len(chunks[0].encode("utf-8"))
            self._queue.task_done()
            deadline = asyncio.get_running_loop().time() + self._flush_interval_seconds

            while total_bytes < self._flush_threshold_bytes:
                remaining = deadline - asyncio.get_running_loop().time()
                if remaining <= 0:
                    break
                try:
                    next_item = await asyncio.wait_for(self._queue.get(), timeout=remaining)
                except TimeoutError:
                    break
                if next_item is self._STOP:
                    self._queue.task_done()
                    stop_after_batch = True
                    break
                text = str(next_item)
                chunks.append(text)
                total_bytes += len(text.encode("utf-8"))
                self._queue.task_done()

            await self._send("".join(chunks))

    async def _send(self, content: str) -> None:
        self._sequence += 1
        started_at = time.perf_counter()
        try:
            await self._callback_client.post_message(
                invocation_id=self._request.invocation_id,
                content=content,
                stream=True,
                sequence=self._sequence,
            )
            logger.info(
                "agent_crossing_perf event=provider_callback durationMs=%s provider=%s invocationId=%s taskId=%s traceId=%s agentId=%s sequence=%s contentChars=%s",
                int((time.perf_counter() - started_at) * 1000),
                self._request.agent_id,
                self._request.invocation_id,
                self._request.task_id,
                self._request.trace_id,
                self._request.agent_id,
                self._sequence,
                len(content),
            )
        except Exception as exception:
            self._delivery_failed = True
            logger.warning(
                "Callback delivery failed provider=%s invocationId=%s sequence=%s: %s",
                self._request.agent_id,
                self._request.invocation_id,
                self._sequence,
                exception,
            )


class CallbackDispatcher:
    def __init__(
        self,
        client: httpx.AsyncClient | None = None,
        flush_interval_seconds: float = 0.075,
        flush_threshold_bytes: int = 1024,
        queue_capacity: int = 256,
        close_timeout_seconds: float = 5.0,
    ) -> None:
        self._client = client or self._new_client()
        self._owns_client = client is None
        self._flush_interval_seconds = flush_interval_seconds
        self._flush_threshold_bytes = flush_threshold_bytes
        self._queue_capacity = queue_capacity
        self._close_timeout_seconds = close_timeout_seconds

    @staticmethod
    def _new_client() -> httpx.AsyncClient:
        return httpx.AsyncClient(
            timeout=httpx.Timeout(connect=1.0, write=2.0, read=5.0, pool=1.0),
            limits=httpx.Limits(max_connections=20, max_keepalive_connections=10),
        )

    def open_stream(self, request: AgentExecutionRequest) -> InvocationCallbackStream | None:
        if not request.callback_base_url:
            return None
        if self._owns_client and self._client.is_closed:
            self._client = self._new_client()
        return InvocationCallbackStream(
            request,
            self._client,
            self._flush_interval_seconds,
            self._flush_threshold_bytes,
            self._queue_capacity,
            self._close_timeout_seconds,
        )

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()
