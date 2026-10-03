import asyncio
import json
import logging
import os
import shlex
import time
from typing import Any

from agent_runtime.callback.dispatcher import (
    CallbackDispatcher,
    InvocationCallbackStream,
)
from agent_runtime.business_prompt import business_prompt_composer
from agent_runtime.config import settings
from agent_runtime.contracts.models import AgentExecutionRequest, AgentMessage, AgentMessageType
from agent_runtime.prompt_config import load_prompt_config
from agent_runtime.prompt_session import annotate_prompt_version, prepare_execution_request
from agent_runtime.providers.base import BaseProvider
from agent_runtime.providers.cli_support import annotate_callback_summary, close_callback_stream, terminate_process
from agent_runtime.providers.usage import normalize_cli_usage
from agent_runtime.streaming.normalizer import AgentMessageNormalizer


logger = logging.getLogger(__name__)


class ClaudeCodeProvider(BaseProvider):
    _DIAGNOSTIC_TAIL_CHARS = 4000

    def __init__(
        self,
        command: str | None = None,
        timeout_seconds: float | None = None,
        normalizer: AgentMessageNormalizer | None = None,
        callback_dispatcher: CallbackDispatcher | None = None,
    ) -> None:
        self._command = command or settings.claudecode_command
        self._timeout_seconds = timeout_seconds or settings.provider_timeout_seconds
        self._normalizer = normalizer or AgentMessageNormalizer()
        self._callback_dispatcher = callback_dispatcher or CallbackDispatcher()
        self._callback_streams: dict[str, InvocationCallbackStream] = {}

    @property
    def agent_id(self) -> str:
        return "claudecode"

    async def execute(self, request: AgentExecutionRequest) -> list[AgentMessage]:
        prompt_config = load_prompt_config()
        prompt_version = prompt_config.business_agent_prompt_version(request.agent_id)
        request = prepare_execution_request(request, prompt_version)
        command = self._build_command(request)
        if not command:
            return [
                self._normalizer.error(request, "Claude Code command is empty"),
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
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=env,
                cwd=settings.cli_cwd,
            )
            logger.info(
                "agent_crossing_perf event=cli_startup durationMs=%s provider=claudecode invocationId=%s taskId=%s traceId=%s agentId=%s cwd=%s",
                _elapsed_ms(cli_start),
                request.invocation_id,
                request.task_id,
                request.trace_id,
                request.agent_id,
                settings.cli_cwd,
            )
            first_output_state: dict[str, bool] = {"logged": False}
            messages = await asyncio.wait_for(
                self._collect_process_messages(request, process, cli_start, first_output_state),
                timeout=self._timeout_seconds,
            )
        except FileNotFoundError:
            messages = [
                self._normalizer.error(request, f"Claude Code command not found: {command[0]}"),
                self._normalizer.done(request),
            ]
        except TimeoutError:
            if process is not None and process.returncode is None:
                process.kill()
                await process.wait()
            messages = [
                self._normalizer.error(request, "Claude Code command timed out"),
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
        usage_state: dict[str, Any] = {}
        await asyncio.gather(
            self._read_stdout(
                request,
                process,
                messages,
                session_ids,
                usage_state,
                process_started_at,
                first_output_state,
            ),
            self._read_stderr(request, process, diagnostics, process_started_at, first_output_state),
        )
        return_code = await process.wait()
        session_id = session_ids[-1] if session_ids else None
        self._log_stderr_diagnostics(request, diagnostics)
        if return_code not in (0, None):
            error = self._normalizer.error(
                request,
                f"Claude Code exited with code {return_code}",
                raw={
                    "provider": "claudecode",
                    "returnCode": return_code,
                    "diagnostics": diagnostics,
                },
            )
            messages.append(error)
            await self._post_stream_message(request, error)
        elif not self._has_content_message(messages):
            message = self._silent_completion_message(request, session_id)
            messages.append(message)
            await self._post_stream_message(request, message)

        done = self._normalizer.done(
            request,
            raw={
                "provider": "claudecode",
                "returnCode": return_code,
                "diagnostics": diagnostics,
            },
        )
        if session_id:
            done.raw["sessionId"] = session_id
            done.raw["providerSessionId"] = session_id
        usage = normalize_cli_usage(
            "claudecode",
            usage_state.get("usageEvent"),
            settings.claudecode_model,
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
        usage_state: dict[str, Any],
        process_started_at: float,
        first_output_state: dict[str, bool],
    ) -> None:
        if process.stdout is None:
            return
        emitted_assistant_text = ""
        while line := await process.stdout.readline():
            self._log_first_output(request, process_started_at, first_output_state, "stdout")
            text = line.decode("utf-8", errors="replace").rstrip("\r\n")
            event = self._parse_json_line(text)
            if (
                isinstance(event, dict)
                and str(event.get("type") or "") == "result"
                and isinstance(event.get("usage"), dict)
            ):
                usage_state["usageEvent"] = event
            for message in self._stdout_to_messages(request, text, session_ids):
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
            if not text:
                continue
            self._append_stderr_diagnostic(diagnostics, text + "\n")

    async def _post_stream_message(self, request: AgentExecutionRequest, message: AgentMessage) -> None:
        if (
            not request.callback_base_url
            or not message.content
            or not self._should_callback_message(message)
        ):
            return
        callback_stream = self._callback_streams.get(request.invocation_id)
        if callback_stream is not None:
            await callback_stream.publish(message.content)

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
            "agent_crossing_perf event=first_output durationMs=%s provider=claudecode invocationId=%s taskId=%s traceId=%s agentId=%s stream=%s",
            _elapsed_ms(process_started_at),
            request.invocation_id,
            request.task_id,
            request.trace_id,
            request.agent_id,
            stream,
        )

    def _build_command(self, request: AgentExecutionRequest) -> list[str]:
        command = shlex.split(self._command)
        if not command:
            return []

        args = [
            *command,
            "-p",
            self._build_prompt(request),
            "--output-format",
            "stream-json",
            "--verbose",
            "--permission-mode",
            settings.claudecode_permission_mode,
        ]
        if request.provider_session_id:
            args.extend(["--resume", request.provider_session_id])
        if settings.claudecode_model:
            args.extend(["--model", settings.claudecode_model])
        mcp_config_json = self._mcp_config_json()
        if mcp_config_json:
            args.extend(["--mcp-config", mcp_config_json])
        if settings.claudecode_extra_args:
            args.extend(shlex.split(settings.claudecode_extra_args))
        return args

    @staticmethod
    def _mcp_config_json() -> str | None:
        if settings.claudecode_mcp_config_json:
            return settings.claudecode_mcp_config_json
        if not settings.master_agent_mcp_url:
            return None
        return json.dumps(
            {
                "mcpServers": {
                    "agent-crossing": {
                        "type": "http",
                        "url": settings.master_agent_mcp_url,
                    }
                }
            },
            ensure_ascii=False,
        )

    def _stdout_to_messages(
        self,
        request: AgentExecutionRequest,
        stdout_text: str,
        session_ids: list[str] | None = None,
    ) -> list[AgentMessage]:
        messages: list[AgentMessage] = []
        for line in stdout_text.splitlines():
            stripped = line.strip()
            if not stripped:
                continue
            event = self._parse_json_line(stripped)
            if event is None:
                messages.append(self._normalizer.text_delta(request, line))
                continue
            session_id = self._extract_session_id(event)
            if session_id and session_ids is not None:
                session_ids.append(session_id)
            messages.extend(self._event_to_messages(request, event))
        return messages

    def _event_to_messages(self, request: AgentExecutionRequest, event: dict[str, Any]) -> list[AgentMessage]:
        event_type = str(event.get("type") or "")
        subtype = str(event.get("subtype") or "")
        if event_type == "system" and subtype == "init":
            return []
        if event_type == "assistant":
            return self._assistant_event_to_messages(request, event)
        if event_type in ("text", "text_delta", "message_delta"):
            text = self._extract_text(event)
            return [self._normalizer.text_delta(request, text, raw={"provider": "claudecode", "event": event})] if text else []
        if event_type == "result":
            if subtype in ("error", "failed", "failure") or event.get("is_error") is True:
                return [
                    self._normalizer.error(
                        request,
                        self._extract_error(event),
                        raw={"provider": "claudecode", "event": event},
                    )
                ]
            return []
        if event_type == "error":
            return [
                self._normalizer.error(
                    request,
                    self._extract_error(event),
                    raw={"provider": "claudecode", "event": event},
                )
            ]
        return []

    def _assistant_event_to_messages(self, request: AgentExecutionRequest, event: dict[str, Any]) -> list[AgentMessage]:
        message = event.get("message")
        content = message.get("content") if isinstance(message, dict) else event.get("content")
        blocks = content if isinstance(content, list) else [content]
        messages: list[AgentMessage] = []
        for block in blocks:
            if isinstance(block, str):
                if block:
                    messages.append(self._normalizer.text_delta(request, block, raw={"provider": "claudecode", "event": event}))
                continue
            if not isinstance(block, dict):
                continue
            block_type = str(block.get("type") or "")
            if block_type in ("text", "text_delta"):
                text = self._extract_text(block)
                if text:
                    messages.append(
                        self._normalizer.text_delta(request, text, raw={"provider": "claudecode", "event": event})
                    )
            elif block_type == "tool_use":
                tool_name = str(block.get("name") or block.get("tool") or "unknown")
                messages.append(
                    self._normalizer.message(
                        request,
                        f"tool_use:{tool_name}",
                        raw={
                            "provider": "claudecode",
                            "event": event,
                            "tool": tool_name,
                            "input": block.get("input"),
                        },
                    )
                )
        return messages

    def _silent_completion_message(self, request: AgentExecutionRequest, session_id: str | None) -> AgentMessage:
        return self._normalizer.message(
            request,
            "Claude Code completed without text output. Check the execution log or Claude local logs for provider/session details.",
            raw={"provider": "claudecode", "reason": "silent_completion", "sessionId": session_id},
        )

    @staticmethod
    def _empty_diagnostics() -> dict[str, Any]:
        return {"stderrChars": 0, "stderrTail": ""}

    @classmethod
    def _append_stderr_diagnostic(cls, diagnostics: dict[str, Any], text: str) -> None:
        diagnostics["stderrChars"] = int(diagnostics.get("stderrChars", 0)) + len(text)
        diagnostics["stderrTail"] = (str(diagnostics.get("stderrTail", "")) + text)[-cls._DIAGNOSTIC_TAIL_CHARS :]

    @staticmethod
    def _log_stderr_diagnostics(request: AgentExecutionRequest, diagnostics: dict[str, Any]) -> None:
        stderr_chars = int(diagnostics.get("stderrChars", 0))
        if stderr_chars == 0:
            return
        logger.info(
            "Claude Code stderr captured provider=claudecode invocationId=%s taskId=%s traceId=%s agentId=%s stderrChars=%s",
            request.invocation_id,
            request.task_id,
            request.trace_id,
            request.agent_id,
            stderr_chars,
        )

    @staticmethod
    def _parse_json_line(line: str) -> dict[str, Any] | None:
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            return None
        return event if isinstance(event, dict) else None

    @staticmethod
    def _extract_session_id(event: dict[str, Any]) -> str | None:
        for key in ("session_id", "sessionId", "sessionID"):
            value = event.get(key)
            if value:
                return str(value)
        message = event.get("message")
        if isinstance(message, dict):
            value = message.get("session_id") or message.get("sessionId")
            if value:
                return str(value)
        return None

    @staticmethod
    def _extract_text(event: dict[str, Any]) -> str:
        for key in ("text", "content", "delta"):
            value = event.get(key)
            if isinstance(value, str):
                return value
            if isinstance(value, dict):
                nested = value.get("text") or value.get("content")
                if nested is not None:
                    return str(nested)
        return ""

    @staticmethod
    def _extract_error(event: dict[str, Any]) -> str:
        for key in ("error", "message", "result"):
            value = event.get(key)
            if isinstance(value, str):
                return value
            if isinstance(value, dict):
                nested = value.get("message") or value.get("error") or value.get("details")
                if nested is not None:
                    return str(nested)
        return "Claude Code emitted an error event"

    @staticmethod
    def _build_prompt(request: AgentExecutionRequest) -> str:
        return business_prompt_composer.compose(request)

    @staticmethod
    def _has_content_message(messages: list[AgentMessage]) -> bool:
        return any(
            message.type in (AgentMessageType.TEXT_DELTA, AgentMessageType.MESSAGE)
            and bool(message.content and message.content.strip())
            and not ClaudeCodeProvider._is_tool_use_message(message)
            for message in messages
        )

    @staticmethod
    def _should_callback_message(message: AgentMessage) -> bool:
        if message.type not in (AgentMessageType.TEXT_DELTA, AgentMessageType.MESSAGE, AgentMessageType.ERROR):
            return False
        return not ClaudeCodeProvider._is_tool_use_message(message)

    @staticmethod
    def _dedupe_assistant_text_message(
        message: AgentMessage,
        emitted_assistant_text: str,
    ) -> tuple[AgentMessage | None, str]:
        if (
            message.type not in (AgentMessageType.TEXT_DELTA, AgentMessageType.MESSAGE)
            or ClaudeCodeProvider._is_tool_use_message(message)
            or not message.content
        ):
            return message, emitted_assistant_text

        content = message.content
        if not emitted_assistant_text:
            return message, content

        if content == emitted_assistant_text:
            return None, emitted_assistant_text

        if content.startswith(emitted_assistant_text):
            suffix = content[len(emitted_assistant_text):]
            if not suffix:
                return None, emitted_assistant_text
            return message.model_copy(update={"content": suffix}), emitted_assistant_text + suffix

        return message, emitted_assistant_text + content

    @staticmethod
    def _is_tool_use_message(message: AgentMessage) -> bool:
        if message.type != AgentMessageType.MESSAGE:
            return False
        if isinstance(message.raw, dict) and message.raw.get("tool"):
            return True
        content = (message.content or "").strip()
        return content == "tool_use" or content.startswith("tool_use:")


def _elapsed_ms(started_at: float) -> int:
    return int((time.perf_counter() - started_at) * 1000)
