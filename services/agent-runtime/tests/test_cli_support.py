import asyncio
from unittest.mock import AsyncMock, Mock, call

import pytest

from agent_runtime.callback.dispatcher import CallbackDeliverySummary, InvocationCallbackStream
from agent_runtime.config import settings
from agent_runtime.contracts.models import AgentExecutionRequest, AgentMessageType
from agent_runtime.providers.claudecode import ClaudeCodeProvider
from agent_runtime.providers.codex import CodexProvider
from agent_runtime.providers.opencode import OpenCodeProvider
from agent_runtime.providers.cli_support import (
    annotate_callback_summary,
    close_callback_stream,
    terminate_process,
)
from agent_runtime.streaming.normalizer import AgentMessageNormalizer


def test_terminate_skips_missing_or_exited_process() -> None:
    process = Mock(returncode=0)
    asyncio.run(terminate_process(None))
    asyncio.run(terminate_process(process))
    assert process.mock_calls == []


def test_terminate_waits_for_graceful_exit() -> None:
    process = Mock(returncode=None, wait=AsyncMock(return_value=0))
    asyncio.run(terminate_process(process))
    assert process.mock_calls == [call.terminate(), call.wait()]


def test_terminate_escalates_to_kill_and_reaps_after_timeout(monkeypatch) -> None:
    process = Mock(returncode=None, wait=AsyncMock(side_effect=[TimeoutError(), -9]))
    wait_for = AsyncMock(wraps=asyncio.wait_for)
    monkeypatch.setattr(asyncio, "wait_for", wait_for)

    asyncio.run(terminate_process(process))

    assert process.mock_calls == [call.terminate(), call.wait(), call.kill(), call.wait()]
    assert wait_for.call_args.kwargs == {"timeout": 2}


@pytest.mark.parametrize("failure", [None, RuntimeError("close failed"), asyncio.CancelledError()])
def test_callback_removed_before_close_and_other_invocations_preserved(failure) -> None:
    stream = Mock(spec=InvocationCallbackStream)
    other = Mock(spec=InvocationCallbackStream)
    streams = {"inv": stream, "other": other}
    summary = CallbackDeliverySummary(last_sequence=3, completed=False)

    async def close():
        assert streams == {"other": other}
        if failure is not None:
            raise failure
        return summary

    stream.close = AsyncMock(side_effect=close)
    if failure is None:
        assert asyncio.run(close_callback_stream("inv", streams, stream)) is summary
    else:
        with pytest.raises(type(failure)):
            asyncio.run(close_callback_stream("inv", streams, stream))
    stream.close.assert_awaited_once()
    assert streams == {"other": other}


def test_missing_callback_returns_incomplete_summary_and_removes_entry() -> None:
    streams = {"inv": Mock(spec=InvocationCallbackStream)}
    assert asyncio.run(close_callback_stream("inv", streams, None)) == CallbackDeliverySummary(None, False)
    assert streams == {}
    assert asyncio.run(close_callback_stream("inv", streams, None)) == CallbackDeliverySummary(None, False)


@pytest.mark.parametrize("raw", [None, "not-a-dict", {"providerSessionId": "session", "usage": {"inputTokens": 10}}])
def test_only_last_done_is_annotated_and_existing_metadata_preserved(raw) -> None:
    request = AgentExecutionRequest(
        invocationId="inv", userId="user", taskId="task", traceId="trace", agentId="codex", context="work"
    )
    normalizer = AgentMessageNormalizer()
    first = normalizer.done(request, raw={"earlier": True})
    last = normalizer.done(request, raw=raw)
    text = normalizer.message(request, "answer", raw={"untouched": True})
    original_raw = last.raw

    annotate_callback_summary([first, last, text], CallbackDeliverySummary(7, True))

    assert first.raw == {"earlier": True}
    assert text.raw == {"untouched": True}
    assert last.raw == {**(raw if isinstance(raw, dict) else {}), "callbackCompleted": True, "callbackLastSequence": 7}
    if isinstance(original_raw, dict):
        assert last.raw is original_raw
    annotate_callback_summary([last], CallbackDeliverySummary(None, False))
    assert last.raw["callbackCompleted"] is False
    assert last.raw["callbackLastSequence"] is None


def test_annotation_without_done_is_noop() -> None:
    messages = []
    annotate_callback_summary(messages, CallbackDeliverySummary(None, False))
    assert messages == []


@pytest.mark.parametrize("provider_type,use_pty", [
    (CodexProvider, False),
    (ClaudeCodeProvider, False),
    (OpenCodeProvider, False),
    (OpenCodeProvider, True),
])
@pytest.mark.parametrize("outcome", ["success", "timeout", "canceled", "missing_command"])
def test_provider_cleanup_and_done_metadata_preserve_each_execution_path(monkeypatch, provider_type, use_pty, outcome) -> None:
    stream = Mock(spec=InvocationCallbackStream)
    dispatcher = Mock(open_stream=Mock(return_value=stream))
    provider = provider_type(command="test-cli", callback_dispatcher=dispatcher)
    request = AgentExecutionRequest(
        invocationId="inv", userId="user", taskId="task", traceId="trace",
        agentId=provider.agent_id, context="work", callbackBaseUrl="http://unused"
    )
    process = Mock(returncode=None, stdin=None, wait=AsyncMock(return_value=0))
    spawn = AsyncMock(return_value=process)
    if outcome == "missing_command":
        spawn.side_effect = FileNotFoundError()
    monkeypatch.setattr(asyncio, "create_subprocess_exec", spawn)
    monkeypatch.setattr(settings, "opencode_use_pty", use_pty)
    normalizer = AgentMessageNormalizer()
    messages = [normalizer.message(request, "answer"), normalizer.done(request, raw={"providerSessionId": "session"})]
    collect = AsyncMock(return_value=messages)
    if outcome == "timeout":
        collect.side_effect = TimeoutError()
    elif outcome == "canceled":
        collect.side_effect = asyncio.CancelledError()
    collector_name = "_collect_pty_messages" if use_pty else "_collect_process_messages"
    monkeypatch.setattr(provider, collector_name, collect)

    async def close():
        assert request.invocation_id not in provider._callback_streams
        return CallbackDeliverySummary(4, True)

    stream.close = AsyncMock(side_effect=close)
    if outcome == "canceled":
        with pytest.raises(asyncio.CancelledError):
            asyncio.run(provider.execute(request))
    else:
        result = asyncio.run(provider.execute(request))
        done = result[-1]
        assert done.type == AgentMessageType.DONE
        assert done.raw["callbackCompleted"] is True
        assert done.raw["callbackLastSequence"] == 4
        if outcome == "success":
            assert done.raw["providerSessionId"] == "session"
            assert result[0].content == "answer"
        else:
            assert result[0].type == AgentMessageType.ERROR
    stream.close.assert_awaited_once()
    assert provider._callback_streams == {}
    if outcome == "canceled" or (outcome == "timeout" and provider_type is CodexProvider):
        process.terminate.assert_called_once()
        process.kill.assert_not_called()
        process.wait.assert_awaited_once()
    elif outcome == "timeout":
        process.terminate.assert_not_called()
        process.kill.assert_called_once()
        process.wait.assert_awaited_once()
    else:
        process.terminate.assert_not_called()
        process.kill.assert_not_called()
