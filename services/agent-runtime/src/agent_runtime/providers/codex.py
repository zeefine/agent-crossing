import asyncio
import json
import logging
import os
import shlex
import time
from typing import Any

from agent_runtime.business_prompt import business_prompt_composer
from agent_runtime.callback.dispatcher import (
    CallbackDispatcher,
    InvocationCallbackStream,
)
from agent_runtime.config import settings
from agent_runtime.contracts.models import AgentExecutionRequest, AgentMessage, AgentMessageType
from agent_runtime.prompt_config import load_prompt_config
from agent_runtime.prompt_session import annotate_prompt_version, prepare_execution_request
from agent_runtime.providers.base import BaseProvider
from agent_runtime.providers.cli_support import annotate_callback_summary, close_callback_stream, terminate_process
from agent_runtime.providers.usage import normalize_cli_usage
from agent_runtime.streaming.normalizer import AgentMessageNormalizer


logger = logging.getLogger(__name__)


class CodexProvider(BaseProvider):
    """Adapt Codex CLI JSONL events to the platform AgentMessage contract."""

    _DIAGNOSTIC_TAIL_CHARS = 4000

    def __init__(
        self,
        command: str | None = None,
        timeout_seconds: float | None = None,
        normalizer: AgentMessageNormalizer | None = None,
        callback_dispatcher: CallbackDispatcher | None = None,
    ) -> None:
        self._command = command or settings.codex_command
        self._timeout_seconds = timeout_seconds or settings.provider_timeout_seconds
        self._normalizer = normalizer or AgentMessageNormalizer()
        self._callback_dispatcher = callback_dispatcher or CallbackDispatcher()
        self._callback_streams: dict[str, InvocationCallbackStream] = {}

    @property
    def agent_id(self) -> str:
        return "codex"

    async def execute(self, request: AgentExecutionRequest) -> list[AgentMessage]:
        prompt_config = load_prompt_config()
        prompt_version = prompt_config.business_agent_prompt_version(request.agent_id)
        request = prepare_execution_request(request, prompt_version)

        command = self._build_command(request)
        if not command:
            return [
                self._normalizer.error(request, "Codex command is empty"),
                self._normalizer.done(request),
            ]

        env = os.environ.copy()
        env.pop("AGENT_CROSSING_CALLBACK_TOKEN", None)
        env.update(
            {
                "AGENT_CROSSING_INVOCATION_ID": request.invocation_id,
                "AGENT_CROSSING_USER_ID": request.user_id,
                "AGENT_CROSSING_TASK_ID": request.task_id,
                "AGENT_CROSSING_TRACE_ID": request.trace_id,
                "AGENT_CROSSING_AGENT_ID": request.agent_id,
            }
        )
        if request.callback_base_url:
            env["AGENT_CROSSING_CALLBACK_BASE_URL"] = request.callback_base_url

        callback_stream = self._callback_dispatcher.open_stream(request)
        if callback_stream is not None:
            self._callback_streams[request.invocation_id] = callback_stream

        process: asyncio.subprocess.Process | None = None
        messages: list[AgentMessage]
        try:
            cli_start = time.perf_counter()
            process = await asyncio.create_subprocess_exec(
                *command,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=env,
                cwd=settings.cli_cwd,
            )
            logger.info(
                "agent_crossing_perf event=cli_startup durationMs=%s provider=codex invocationId=%s taskId=%s traceId=%s agentId=%s cwd=%s",
                _elapsed_ms(cli_start),
                request.invocation_id,
                request.task_id,
                request.trace_id,
                request.agent_id,
                settings.cli_cwd,
            )
            await self._write_prompt(process, self._build_prompt(request))
            first_output_state: dict[str, bool] = {"logged": False}
            messages = await asyncio.wait_for(
                self._collect_process_messages(request, process, cli_start, first_output_state),
                timeout=self._timeout_seconds,
            )
        except FileNotFoundError:
            messages = [
                self._normalizer.error(request, f"Codex command not found: {command[0]}"),
                self._normalizer.done(request),
            ]
        except TimeoutError:
            await terminate_process(process)
            messages = [
                self._normalizer.error(request, "Codex command timed out"),
                self._normalizer.done(request),
            ]
        except asyncio.CancelledError:
            await terminate_process(process)
            raise
        finally:
            summary = await close_callback_stream(request.invocation_id, self._callback_streams, callback_stream)

        annotate_callback_summary(messages, summary)
        annotate_prompt_version(messages, prompt_version)
        return messages

    async def _collect_process_messages(
        self,
        request: AgentExecutionRequest,
        process: asyncio.subprocess.Process,
        process_started_at: float,
        first_output_state: dict[str, bool],
    ) -> list[AgentMessage]:
        messages: list[AgentMessage] = []
        session_ids: list[str] = []
        diagnostics = self._empty_diagnostics()
        await asyncio.gather(
            self._read_stdout(
                request,
                process,
                messages,
                session_ids,
                diagnostics,
                process_started_at,
                first_output_state,
            ),
            self._read_stderr(request, process, diagnostics, process_started_at, first_output_state),
        )
        return_code = await process.wait()
        session_id = session_ids[-1] if session_ids else request.provider_session_id
        self._log_diagnostics(request, diagnostics)

        if return_code not in (0, None) and not self._has_error_message(messages):
            error = self._normalizer.error(
                request,
                f"Codex exited with code {return_code}",
                raw={"provider": "codex", "returnCode": return_code, "diagnostics": diagnostics},
            )
            messages.append(error)
            await self._post_stream_message(request, error)
        elif not self._has_content_message(messages) and not self._has_error_message(messages):
            message = self._silent_completion_message(request, session_id)
            messages.append(message)

        done = self._normalizer.done(
            request,
            raw={"provider": "codex", "returnCode": return_code, "diagnostics": diagnostics},
        )
        if session_id:
            done.raw["sessionId"] = session_id
            done.raw["providerSessionId"] = session_id
        usage = normalize_cli_usage(
            "codex",
            diagnostics.pop("usageEvent", None),
            settings.codex_model,
            session_id,
        )
        if usage is not None:
            done.raw["usage"] = usage
        messages.append(done)
        return messages

    async def _read_stdout(
        self,
        request: AgentExecutionRequest,
        process: asyncio.subprocess.Process,
        messages: list[AgentMessage],
        session_ids: list[str],
        diagnostics: dict[str, Any],
        process_started_at: float,
        first_output_state: dict[str, bool],
    ) -> None:
        if process.stdout is None:
            return
        emitted_assistant_text = ""
        while line := await process.stdout.readline():
            self._log_first_output(request, process_started_at, first_output_state, "stdout")
            text = line.decode("utf-8", errors="replace").rstrip("\r\n")
            if not text:
                continue
            event = self._parse_json_line(text)
            if event is None:
                self._append_diagnostic(diagnostics, "stdout", text + "\n")
                continue
            session_id = self._extract_session_id(event)
            if session_id:
                session_ids.append(session_id)
            self._record_non_chat_event(diagnostics, event)
            for message in self._event_to_messages(request, event):
                message, emitted_assistant_text = self._dedupe_assistant_text_message(
                    message,
                    emitted_assistant_text,
                )
                if message is None:
                    continue
                messages.append(message)
                await self._post_stream_message(request, message)

    async def _read_stderr(
        self,
        request: AgentExecutionRequest,
        process: asyncio.subprocess.Process,
        diagnostics: dict[str, Any],
        process_started_at: float,
        first_output_state: dict[str, bool],
    ) -> None:
        if process.stderr is None:
            return
        while line := await process.stderr.readline():
            self._log_first_output(request, process_started_at, first_output_state, "stderr")
            text = line.decode("utf-8", errors="replace").rstrip("\r\n")
            if text:
                self._append_diagnostic(diagnostics, "stderr", text + "\n")

    def _event_to_messages(
        self,
        request: AgentExecutionRequest,
        event: dict[str, Any],
    ) -> list[AgentMessage]:
        event_type = str(event.get("type") or "")
        if event_type == "item.completed":
            item = event.get("item")
            if not isinstance(item, dict) or str(item.get("type") or "") != "agent_message":
                return []
            text = self._extract_text(item)
            if not text:
                return []
            return [
                self._normalizer.text_delta(
                    request,
                    text,
                    raw={"provider": "codex", "event": event},
                )
            ]
        if event_type in ("error", "turn.failed"):
            return [
                self._normalizer.error(
                    request,
                    self._extract_error(event),
                    raw={"provider": "codex", "event": event},
                )
            ]
        return []

    def _build_command(self, request: AgentExecutionRequest) -> list[str]:
        command = shlex.split(self._command)
        if not command:
            return []

        common_args = ["--json"]
        if settings.codex_model:
            common_args.extend(["--model", settings.codex_model])
        common_args.extend(
            ["--config", f"approval_policy={json.dumps(settings.codex_approval_policy)}"]
        )
        if settings.codex_ignore_user_config:
            common_args.append("--ignore-user-config")
        mcp_url = settings.codex_mcp_url or settings.master_agent_mcp_url
        if mcp_url:
            common_args.extend(
                ["--config", f"mcp_servers.agent-crossing.url={json.dumps(mcp_url)}"]
            )
        if settings.codex_extra_args:
            common_args.extend(shlex.split(settings.codex_extra_args))

        if request.provider_session_id:
            return [
                *command,
                "exec",
                "resume",
                request.provider_session_id,
                *common_args,
                "-",
            ]
        return [
            *command,
            "exec",
            *common_args,
            "--sandbox",
            settings.codex_sandbox_mode,
            "-",
        ]

    @staticmethod
    async def _write_prompt(process: asyncio.subprocess.Process, prompt: str) -> None:
        if process.stdin is None:
            return
        try:
            process.stdin.write(prompt.encode("utf-8"))
            await process.stdin.drain()
            process.stdin.close()
            await process.stdin.wait_closed()
        except (BrokenPipeError, ConnectionResetError):
            # The exit code/stderr path below owns the user-visible failure when Codex exits before reading stdin.
            return

    async def _post_stream_message(self, request: AgentExecutionRequest, message: AgentMessage) -> None:
        if not request.callback_base_url or not message.content or not self._should_callback_message(message):
            return
        callback_stream = self._callback_streams.get(request.invocation_id)
        if callback_stream is not None:
            await callback_stream.publish(message.content)

    @staticmethod
    def _parse_json_line(line: str) -> dict[str, Any] | None:
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            return None
        return event if isinstance(event, dict) else None

    @staticmethod
    def _extract_session_id(event: dict[str, Any]) -> str | None:
        if str(event.get("type") or "") != "thread.started":
            return None
        value = event.get("thread_id") or event.get("threadId")
        return str(value) if value else None

    @classmethod
    def _extract_text(cls, value: Any) -> str:
        if isinstance(value, str):
            return value
        if isinstance(value, list):
            return "".join(cls._extract_text(item) for item in value)
        if not isinstance(value, dict):
            return ""
        for key in ("text", "content", "output_text"):
            nested = value.get(key)
            text = cls._extract_text(nested)
            if text:
                return text
        return ""

    @classmethod
    def _extract_error(cls, event: dict[str, Any]) -> str:
        for key in ("message", "error", "details"):
            value = event.get(key)
            if isinstance(value, str) and value:
                return value
            if isinstance(value, dict):
                nested = cls._extract_error(value)
                if nested:
                    return nested
        return "Codex emitted an error event"

    @staticmethod
    def _dedupe_assistant_text_message(
        message: AgentMessage,
        emitted_assistant_text: str,
    ) -> tuple[AgentMessage | None, str]:
        if message.type not in (AgentMessageType.TEXT_DELTA, AgentMessageType.MESSAGE) or not message.content:
            return message, emitted_assistant_text
        content = message.content
        if not emitted_assistant_text:
            return message, content
        if content == emitted_assistant_text:
            return None, emitted_assistant_text
        if content.startswith(emitted_assistant_text):
            suffix = content[len(emitted_assistant_text) :]
            if not suffix:
                return None, emitted_assistant_text
            return message.model_copy(update={"content": suffix}), emitted_assistant_text + suffix
        return message, emitted_assistant_text + content

    @staticmethod
    def _record_non_chat_event(diagnostics: dict[str, Any], event: dict[str, Any]) -> None:
        event_type = str(event.get("type") or "")
        if event_type == "turn.completed" and isinstance(event.get("usage"), dict):
            diagnostics["usageEvent"] = event
        item = event.get("item")
        item_type = str(item.get("type") or "") if isinstance(item, dict) else ""
        if event_type.startswith("item.") and item_type and item_type != "agent_message":
            diagnostics["nonChatEventCount"] = int(diagnostics.get("nonChatEventCount", 0)) + 1

    @classmethod
    def _append_diagnostic(cls, diagnostics: dict[str, Any], stream: str, text: str) -> None:
        chars_key = f"{stream}Chars"
        tail_key = f"{stream}Tail"
        diagnostics[chars_key] = int(diagnostics.get(chars_key, 0)) + len(text)
        diagnostics[tail_key] = (str(diagnostics.get(tail_key, "")) + text)[-cls._DIAGNOSTIC_TAIL_CHARS :]

    @staticmethod
    def _empty_diagnostics() -> dict[str, Any]:
        return {
            "stdoutChars": 0,
            "stdoutTail": "",
            "stderrChars": 0,
            "stderrTail": "",
            "nonChatEventCount": 0,
        }

    @staticmethod
    def _log_diagnostics(request: AgentExecutionRequest, diagnostics: dict[str, Any]) -> None:
        if not diagnostics["stdoutChars"] and not diagnostics["stderrChars"] and not diagnostics["nonChatEventCount"]:
            return
        logger.info(
            "Codex diagnostics captured provider=codex invocationId=%s taskId=%s traceId=%s agentId=%s stdoutChars=%s stderrChars=%s nonChatEvents=%s",
            request.invocation_id,
            request.task_id,
            request.trace_id,
            request.agent_id,
            diagnostics["stdoutChars"],
            diagnostics["stderrChars"],
            diagnostics["nonChatEventCount"],
        )

    @staticmethod
    def _log_first_output(
        request: AgentExecutionRequest,
        process_started_at: float,
        first_output_state: dict[str, bool],
        stream: str,
    ) -> None:
        if first_output_state.get("logged"):
            return
        first_output_state["logged"] = True
        logger.info(
            "agent_crossing_perf event=first_output durationMs=%s provider=codex invocationId=%s taskId=%s traceId=%s agentId=%s stream=%s",
            _elapsed_ms(process_started_at),
            request.invocation_id,
            request.task_id,
            request.trace_id,
            request.agent_id,
            stream,
        )

    def _silent_completion_message(
        self,
        request: AgentExecutionRequest,
        session_id: str | None,
    ) -> AgentMessage:
        return self._normalizer.message(
            request,
            "Codex completed without text output. Check the execution log or Codex local logs for provider/session details.",
            raw={"provider": "codex", "reason": "silent_completion", "sessionId": session_id},
        )

    @staticmethod
    def _has_content_message(messages: list[AgentMessage]) -> bool:
        return any(
            message.type in (AgentMessageType.TEXT_DELTA, AgentMessageType.MESSAGE)
            and bool(message.content and message.content.strip())
            for message in messages
        )

    @staticmethod
    def _has_error_message(messages: list[AgentMessage]) -> bool:
        return any(message.type == AgentMessageType.ERROR for message in messages)

    @staticmethod
    def _should_callback_message(message: AgentMessage) -> bool:
        return message.type in (AgentMessageType.TEXT_DELTA, AgentMessageType.MESSAGE, AgentMessageType.ERROR)

    @staticmethod
    def _build_prompt(request: AgentExecutionRequest) -> str:
        return business_prompt_composer.compose(request)


def _elapsed_ms(started_at: float) -> int:
    return int((time.perf_counter() - started_at) * 1000)
