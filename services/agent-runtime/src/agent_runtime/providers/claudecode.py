import asyncio
import json
import os
import shlex
from typing import Any

from agent_runtime.callback.client import CallbackClient
from agent_runtime.config import settings
from agent_runtime.contracts.models import AgentExecutionRequest, AgentMessage, AgentMessageType
from agent_runtime.prompt_config import load_prompt_config
from agent_runtime.providers.base import BaseProvider
from agent_runtime.streaming.normalizer import AgentMessageNormalizer


class ClaudeCodeProvider(BaseProvider):
    def __init__(
        self,
        command: str | None = None,
        timeout_seconds: float | None = None,
        normalizer: AgentMessageNormalizer | None = None,
    ) -> None:
        self._command = command or settings.claudecode_command
        self._timeout_seconds = timeout_seconds or settings.provider_timeout_seconds
        self._normalizer = normalizer or AgentMessageNormalizer()

    @property
    def agent_id(self) -> str:
        return "claudecode"

    async def execute(self, request: AgentExecutionRequest) -> list[AgentMessage]:
        command = self._build_command(request)
        if not command:
            return [
                self._normalizer.error(request, "Claude Code command is empty"),
                self._normalizer.done(request),
            ]

        env = os.environ.copy()
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

        process: asyncio.subprocess.Process | None = None
        try:
            process = await asyncio.create_subprocess_exec(
                *command,
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=env,
            )
            messages = await asyncio.wait_for(
                self._collect_process_messages(request, process),
                timeout=self._timeout_seconds,
            )
        except FileNotFoundError:
            return [
                self._normalizer.error(request, f"Claude Code command not found: {command[0]}"),
                self._normalizer.done(request),
            ]
        except TimeoutError:
            if process is not None and process.returncode is None:
                process.kill()
                await process.wait()
            return [
                self._normalizer.error(request, "Claude Code command timed out"),
                self._normalizer.done(request),
            ]

        return messages

    async def _collect_process_messages(
        self,
        request: AgentExecutionRequest,
        process: asyncio.subprocess.Process,
    ) -> list[AgentMessage]:
        messages: list[AgentMessage] = []
        session_ids: list[str] = []
        await asyncio.gather(
            self._read_stdout(request, process, messages, session_ids),
            self._read_stderr(request, process, messages),
        )
        return_code = await process.wait()
        session_id = session_ids[-1] if session_ids else None
        if return_code not in (0, None):
            error = self._normalizer.error(
                request,
                f"Claude Code exited with code {return_code}",
                raw={"provider": "claudecode", "returnCode": return_code},
            )
            messages.append(error)
            await self._post_stream_message(request, error)
        elif not self._has_content_message(messages):
            message = self._silent_completion_message(request, session_id)
            messages.append(message)
            await self._post_stream_message(request, message)

        done = self._normalizer.done(request, raw={"provider": "claudecode", "returnCode": return_code})
        if session_id:
            done.raw["sessionId"] = session_id
            done.raw["providerSessionId"] = session_id
        messages.append(done)
        return messages

    async def _read_stdout(
        self,
        request: AgentExecutionRequest,
        process: asyncio.subprocess.Process,
        messages: list[AgentMessage],
        session_ids: list[str],
    ) -> None:
        if process.stdout is None:
            return
        while line := await process.stdout.readline():
            text = line.decode("utf-8", errors="replace").rstrip("\r\n")
            for message in self._stdout_to_messages(request, text, session_ids):
                messages.append(message)
                await self._post_stream_message(request, message)

    async def _read_stderr(
        self,
        request: AgentExecutionRequest,
        process: asyncio.subprocess.Process,
        messages: list[AgentMessage],
    ) -> None:
        if process.stderr is None:
            return
        while line := await process.stderr.readline():
            text = line.decode("utf-8", errors="replace").rstrip("\r\n")
            if not text:
                continue
            message = self._normalizer.error(
                request,
                text,
                raw={"provider": "claudecode", "stream": "stderr"},
            )
            messages.append(message)
            await self._post_stream_message(request, message)

    async def _post_stream_message(self, request: AgentExecutionRequest, message: AgentMessage) -> None:
        if (
            not request.callback_base_url
            or not message.content
            or not self._should_callback_message(message)
        ):
            return
        try:
            await CallbackClient(request.callback_base_url, timeout_seconds=2.0).post_message(
                invocation_id=request.invocation_id,
                content=message.content,
                stream=True,
            )
        except Exception:
            return

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
        if settings.claudecode_mcp_config_json:
            args.extend(["--mcp-config", settings.claudecode_mcp_config_json])
        if settings.claudecode_extra_args:
            args.extend(shlex.split(settings.claudecode_extra_args))
        return args

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
        static_prompt = load_prompt_config().static_prompt_for_business_agent(request.agent_id).strip()
        return (
            f"{static_prompt}\n\n"
            "[Invocation Context]\n"
            f"userId: {request.user_id}\n"
            f"traceId: {request.trace_id}\n"
            f"taskId: {request.task_id}\n"
            "\n"
            f"{ClaudeCodeProvider._format_incremental_chat_messages(request)}"
            f"Task:\n{request.context}\n"
        )

    @staticmethod
    def _format_incremental_chat_messages(request: AgentExecutionRequest) -> str:
        messages = request.context_pack.incremental_chat_messages if request.context_pack else []
        if not messages:
            return ""
        lines = ["[New Conversation Since Last Invocation]"]
        for message in messages:
            if message.role == "user":
                speaker = "User"
            else:
                speaker = message.agent_id or "Agent"
                if message.task_id:
                    speaker = f"{speaker}/{message.task_id}"
            lines.append(f"{speaker}: {message.content}")
        lines.append("")
        return "\n".join(lines) + "\n"

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
    def _is_tool_use_message(message: AgentMessage) -> bool:
        if message.type != AgentMessageType.MESSAGE:
            return False
        if isinstance(message.raw, dict) and message.raw.get("tool"):
            return True
        content = (message.content or "").strip()
        return content == "tool_use" or content.startswith("tool_use:")
