"""Shared CLI cleanup and callback metadata; provider protocols stay in their adapters."""

import asyncio

from agent_runtime.callback.dispatcher import CallbackDeliverySummary, InvocationCallbackStream
from agent_runtime.contracts.models import AgentMessage, AgentMessageType


async def terminate_process(process: asyncio.subprocess.Process | None) -> None:
    """Try graceful termination, then kill and reap after the existing two-second grace period."""
    if process is None or process.returncode is not None:
        return
    process.terminate()
    try:
        await asyncio.wait_for(process.wait(), timeout=2)
    except TimeoutError:
        process.kill()
        await process.wait()


async def close_callback_stream(
    invocation_id: str,
    streams: dict[str, InvocationCallbackStream],
    callback_stream: InvocationCallbackStream | None,
) -> CallbackDeliverySummary:
    """Remove the active stream before awaiting delivery, including on close failure."""
    streams.pop(invocation_id, None)
    if callback_stream is None:
        return CallbackDeliverySummary(last_sequence=None, completed=False)
    return await callback_stream.close()


def annotate_callback_summary(messages: list[AgentMessage], summary: CallbackDeliverySummary) -> None:
    """Fill callback delivery fields on the last DONE, retaining provider/session/usage metadata."""
    for message in reversed(messages):
        if message.type != AgentMessageType.DONE:
            continue
        raw = message.raw if isinstance(message.raw, dict) else {}
        raw["callbackCompleted"] = summary.completed
        raw["callbackLastSequence"] = summary.last_sequence
        message.raw = raw
        return
