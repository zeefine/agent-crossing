import asyncio
import json
import shlex
import sys

import pytest

from agent_runtime.config import settings
from agent_runtime.contracts.models import SessionCompressionRequest
from agent_runtime.master_agent.session_compressor import SessionCompressor
from agent_runtime.prompt_config import load_prompt_config


def compression_request(content: str = "history") -> SessionCompressionRequest:
    return SessionCompressionRequest(
        userId="user-1", threadId="thread-1", traceId="trace-1", agentId="codex",
        provider="codex", generation=2,
        messages=[{"messageId": "message-1", "role": "user", "content": content,
                   "createdAt": "2026-10-02T00:00:00Z"}],
    )


def test_long_history_is_sent_via_stdin_not_command_arguments(monkeypatch) -> None:
    # A real, side-effect-free child: this exceeds ARG_MAX on macOS and Linux if put in argv.
    script = (
        "import json, sys; "
        "assert sum(len(arg) for arg in sys.argv) < 1024; "
        "prompt = sys.stdin.read(); "
        "assert prompt; "
        "print(json.dumps({'schemaVersion': 1, 'objective': 'end-marker' in prompt}))"
    )
    monkeypatch.setattr(settings, "master_agent_command", shlex.join([sys.executable, "-c", script]))
    monkeypatch.setattr(settings, "master_agent_extra_args", "")
    result = asyncio.run(SessionCompressor().compress(compression_request("x" * 2_000_000 + "end-marker")))

    assert result.startup_summary["objective"] is True


class RecordingCLI:
    def __init__(self, monkeypatch, summary=None):
        self.prompts = []
        self.summary = summary
        monkeypatch.setattr(asyncio, "create_subprocess_exec", self.spawn)

    async def spawn(self, *command, **kwargs):
        assert kwargs["stdin"] == asyncio.subprocess.PIPE
        assert "[Compression Input]" not in " ".join(command)
        cli = self

        class Process:
            returncode = 0

            async def communicate(self, input=None):
                cli.prompts.append(input.decode("utf-8"))
                summary = cli.summary or {"schemaVersion": 1, "objective": f"batch-{len(cli.prompts)}"}
                return json.dumps({"result": json.dumps(summary)}).encode(), b""

        return Process()


def input_payload(prompt):
    return json.loads(prompt.split("[Compression Input]\n", 1)[1])


def test_batches_preserve_all_sources_and_chain_bounded_summaries(monkeypatch) -> None:
    monkeypatch.setattr(settings, "session_compression_max_input_bytes", 4096)
    request = compression_request('中文🙂\\"\n' * 1500)
    request.previous_startup_summary = json.dumps({"prior": "早期决定" * 900}, ensure_ascii=False)
    payload = request.model_dump(mode="json", by_alias=True)
    payload["tasks"] = [
        {"taskId": "task-1", "agentId": "codex", "status": "COMPLETED", "context": "任务" * 1600}
    ]
    request = SessionCompressionRequest.model_validate(payload)
    cli = RecordingCLI(monkeypatch)

    result = asyncio.run(SessionCompressor().compress(request))

    assert len(cli.prompts) > 1
    fragments = []
    for index, prompt in enumerate(cli.prompts):
        assert len(prompt.encode("utf-8")) <= 4096
        payload = input_payload(prompt)
        assert payload["sourceOffset"] == sum(len(part) for part in fragments)
        assert payload["sourceComplete"] is (index == len(cli.prompts) - 1)
        expected = None if index == 0 else {"schemaVersion": 1, "objective": f"batch-{index}"}
        assert payload["previousStartupSummary"] == expected
        fragments.append(payload["sourceFragment"])
    records = [json.loads(line) for line in "".join(fragments).splitlines()]
    assert records[0]["sourceType"] == "session"
    assert records[0]["data"]["threadId"] == request.thread_id
    assert records[1] == {"sourceType": "previousStartupSummary", "data": request.previous_startup_summary}
    assert records[2] == {"sourceType": "message", "data": request.messages[0].model_dump(mode="json", by_alias=True)}
    assert records[3] == {"sourceType": "task", "data": request.tasks[0].model_dump(mode="json", by_alias=True)}
    assert result.startup_summary["objective"] == f"batch-{len(cli.prompts)}"


def test_small_input_stays_one_call_with_original_payload(monkeypatch) -> None:
    cli = RecordingCLI(monkeypatch)
    request = compression_request()

    asyncio.run(SessionCompressor().compress(request))

    assert len(cli.prompts) == 1
    assert input_payload(cli.prompts[0]) == request.model_dump(mode="json", by_alias=True)


def test_over_budget_summary_fails_without_returning_partial_result(monkeypatch) -> None:
    monkeypatch.setattr(settings, "session_compression_max_summary_bytes", 256)
    cli = RecordingCLI(monkeypatch, {"schemaVersion": 1, "objective": "摘要" * 256})

    with pytest.raises(RuntimeError, match="summary exceeds"):
        asyncio.run(SessionCompressor().compress(compression_request()))

    assert len(cli.prompts) == 1


def test_batch_limit_does_not_return_incomplete_summary(monkeypatch) -> None:
    monkeypatch.setattr(settings, "session_compression_max_input_bytes", 4096)
    monkeypatch.setattr(settings, "session_compression_max_batches", 2)
    cli = RecordingCLI(monkeypatch)

    with pytest.raises(RuntimeError, match="batch limit"):
        asyncio.run(SessionCompressor().compress(compression_request("x" * 50_000)))

    assert len(cli.prompts) <= 2


def test_unusable_input_budget_fails_before_starting_cli(monkeypatch) -> None:
    config = load_prompt_config().model_copy(update={"master_agent_compression_prompt": "x" * 5000})
    monkeypatch.setattr("agent_runtime.master_agent.session_compressor.load_prompt_config", lambda: config)
    monkeypatch.setattr(settings, "session_compression_max_input_bytes", 4096)
    cli = RecordingCLI(monkeypatch)

    with pytest.raises(RuntimeError, match="input budget"):
        asyncio.run(SessionCompressor().compress(compression_request()))

    assert cli.prompts == []


def test_timeout_covers_all_batches_not_each_batch(monkeypatch) -> None:
    monkeypatch.setattr(settings, "session_compression_max_input_bytes", 4096)
    monkeypatch.setattr(settings, "master_agent_timeout_seconds", 0.15)
    processes = []

    class SlowProcess:
        returncode = None
        reaped = False

        async def communicate(self, input=None):
            if self.returncode == -9:
                self.reaped = True
                return b"", b""
            await asyncio.sleep(0.1)
            self.returncode = 0
            return b'{"schemaVersion":1}', b""

        def kill(self):
            self.returncode = -9

    async def spawn(*args, **kwargs):
        process = SlowProcess()
        processes.append(process)
        return process

    monkeypatch.setattr(asyncio, "create_subprocess_exec", spawn)
    with pytest.raises(RuntimeError, match="timed out"):
        asyncio.run(SessionCompressor().compress(compression_request("x" * 30_000)))

    assert len(processes) <= 2
    assert processes[-1].returncode == -9
    assert processes[-1].reaped


@pytest.mark.parametrize("cancel", [False, True])
def test_timeout_or_cancellation_reaps_real_child_without_reading_stdin(monkeypatch, cancel) -> None:
    script = "import time; time.sleep(60)"
    monkeypatch.setattr(settings, "master_agent_command", shlex.join([sys.executable, "-c", script]))
    monkeypatch.setattr(settings, "master_agent_extra_args", "")
    monkeypatch.setattr(settings, "master_agent_timeout_seconds", 5 if cancel else 0.1)
    real_spawn = asyncio.create_subprocess_exec
    processes = []

    async def check():
        started = asyncio.Event()

        async def spawn(*args, **kwargs):
            process = await real_spawn(*args, **kwargs)
            processes.append(process)
            started.set()
            return process

        monkeypatch.setattr(asyncio, "create_subprocess_exec", spawn)
        task = asyncio.create_task(SessionCompressor().compress(compression_request("x" * 2_000_000)))
        if cancel:
            await asyncio.wait_for(started.wait(), 3)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        else:
            with pytest.raises(RuntimeError, match="timed out"):
                await task

    asyncio.run(check())

    assert len(processes) == 1
    assert processes[0].returncode is not None
    assert processes[0].returncode != 0


@pytest.mark.parametrize("script,error", [
    ("import sys; sys.stdin.read(); sys.stderr.write('provider unavailable'); sys.exit(2)", "provider unavailable"),
    ("import sys; sys.stdin.read(); print('not json')", "no JSON object"),
])
def test_cli_failure_never_returns_summary(monkeypatch, script, error) -> None:
    monkeypatch.setattr(settings, "master_agent_command", shlex.join([sys.executable, "-c", script]))
    monkeypatch.setattr(settings, "master_agent_extra_args", "")

    with pytest.raises(RuntimeError, match=error):
        asyncio.run(SessionCompressor().compress(compression_request()))


def test_missing_cli_is_reported_as_compression_failure(monkeypatch) -> None:
    async def spawn(*args, **kwargs):
        raise FileNotFoundError("missing CLI")

    monkeypatch.setattr(asyncio, "create_subprocess_exec", spawn)
    with pytest.raises(RuntimeError, match="could not start"):
        asyncio.run(SessionCompressor().compress(compression_request()))
