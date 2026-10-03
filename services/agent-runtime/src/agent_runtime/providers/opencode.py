import asyncio
import errno
import json
import logging
import os
import pty
import re
import select
import shlex
import time
from typing import Any

from agent_runtime.config import settings
from agent_runtime.callback.dispatcher import (
    CallbackDispatcher,
    InvocationCallbackStream,
)
from agent_runtime.business_prompt import business_prompt_composer
from agent_runtime.contracts.models import AgentExecutionRequest, AgentMessage, AgentMessageType
from agent_runtime.prompt_config import load_prompt_config
from agent_runtime.prompt_session import annotate_prompt_version, prepare_execution_request
from agent_runtime.providers.base import BaseProvider
from agent_runtime.providers.cli_support import annotate_callback_summary, close_callback_stream, terminate_process
from agent_runtime.streaming.normalizer import AgentMessageNormalizer


logger = logging.getLogger(__name__)


class _IncrementalTextReconciler:
    """把终端重复绘制/累计快照归一化为只包含新增正文的分片。"""

    def __init__(self) -> None:
        self._last_observed_text = ""
        self._emitted_text = ""

    def reconcile(self, message: AgentMessage) -> AgentMessage | None:
        if (
            message.type not in (AgentMessageType.TEXT_DELTA, AgentMessageType.MESSAGE)
            or OpenCodeProvider._is_tool_use_message(message)
            or not message.content
        ):
            return message

        content = message.content
        if content == self._last_observed_text or content == self._emitted_text:
            self._last_observed_text = content
            return None

        if self._last_observed_text and content.startswith(self._last_observed_text):
            delta = content[len(self._last_observed_text) :]
        elif self._emitted_text and content.startswith(self._emitted_text):
            delta = content[len(self._emitted_text) :]
        else:
            delta = content

        self._last_observed_text = content
        if not delta:
            return None
        self._emitted_text += delta
        if delta == content:
            return message
        raw = dict(message.raw) if isinstance(message.raw, dict) else {}
        raw["streamNormalization"] = "cumulative_snapshot_suffix"
        return message.model_copy(update={"content": delta, "raw": raw})


class OpenCodeProvider(BaseProvider):
    _ANSI_PATTERN = re.compile(r"(?:\x1B\][^\x07]*(?:\x07|\x1B\\)|\x1B\[[0-?]*[ -/]*[@-~]|\x1B[@-Z\\-_])")
    _PLAIN_STATUS_PREFIX_PATTERN = re.compile(r"^>\s+\S+\s+·\s+[A-Za-z0-9._/-]+\s*")
    _PLAIN_TOOL_STATUS_PATTERN = re.compile(r"^⚙\s+\S+(?:\s+\{.*)?$")
    _PLAIN_TOOL_ERROR_MARKER = "✗ Invalid Tool"
    _PLAIN_TOOL_ERROR_LINE_PREFIXES = (
        "The arguments provided to the tool are invalid:",
        "Error message:",
    )
    _SESSION_ID_PATTERN = re.compile(r"\bses_[A-Za-z0-9]+\b")
    _DIAGNOSTIC_TAIL_CHARS = 4000
    _SESSION_EXPORT_ATTEMPTS = 10
    _SESSION_EXPORT_RETRY_DELAY_SECONDS = 5.0
    _SESSION_LIST_TIMEOUT_SECONDS = 5.0

    def __init__(
        self,
        command: str | None = None,
        timeout_seconds: float | None = None,
        normalizer: AgentMessageNormalizer | None = None,
        callback_dispatcher: CallbackDispatcher | None = None,
    ) -> None:
        self._command = command or settings.opencode_command
        self._timeout_seconds = timeout_seconds or settings.provider_timeout_seconds
        self._normalizer = normalizer or AgentMessageNormalizer()
        self._callback_dispatcher = callback_dispatcher or CallbackDispatcher()
        self._callback_streams: dict[str, InvocationCallbackStream] = {}

    @property
    def agent_id(self) -> str:
        return "opencode"

    async def execute(self, request: AgentExecutionRequest) -> list[AgentMessage]:
        prompt_config = load_prompt_config()
        prompt_version = prompt_config.business_agent_prompt_version(request.agent_id)
        original_session_id = request.provider_session_id
        request = prepare_execution_request(request, prompt_version)
        if original_session_id and not request.provider_session_id:
            logger.info(
                "Rotating OpenCode session because the static prompt version changed: invocationId=%s taskId=%s agentId=%s",
                request.invocation_id,
                request.task_id,
                request.agent_id,
            )
        command = self._build_command(request)
        if not command:
            return [
                self._normalizer.error(request, "OpenCode command is empty"),
                self._normalizer.done(request),
            ]

        env = os.environ.copy()
        env.pop("AGENT_CROSSING_CALLBACK_TOKEN", None)
        # 这些环境变量让 CLI 进程知道本次 invocation 身份；v1.0 不注入 callback token。
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

        previous_user_marker = None
        if request.provider_session_id:
            previous_user_marker = await self._latest_user_marker_from_session_export(request.provider_session_id)
        min_message_created_at_ms = int(time.time() * 1000) - 1000 if request.provider_session_id else None

        callback_stream = self._callback_dispatcher.open_stream(request)
        if callback_stream is not None:
            self._callback_streams[request.invocation_id] = callback_stream
        messages: list[AgentMessage]
        try:
            if settings.opencode_use_pty:
                messages = await self._execute_with_pty(
                    request,
                    command,
                    env,
                    previous_user_marker,
                    min_message_created_at_ms,
                )
            else:
                messages = await self._execute_with_pipes(
                    request,
                    command,
                    env,
                    previous_user_marker,
                    min_message_created_at_ms,
                )
        except FileNotFoundError:
            messages = [
                self._normalizer.error(request, f"OpenCode command not found: {command[0]}"),
                self._normalizer.done(request),
            ]
        except TimeoutError:
            messages = [
                self._normalizer.error(request, "OpenCode command timed out"),
                self._normalizer.done(request),
            ]
        finally:
            summary = await close_callback_stream(request.invocation_id, self._callback_streams, callback_stream)
        annotate_callback_summary(messages, summary)
        annotate_prompt_version(messages, prompt_version)
        return messages

    async def _execute_with_pipes(
        self,
        request: AgentExecutionRequest,
        command: list[str],
        env: dict[str, str],
        previous_user_marker: str | None = None,
        min_message_created_at_ms: int | None = None,
    ) -> list[AgentMessage]:
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
            "agent_crossing_perf event=cli_startup durationMs=%s provider=opencode invocationId=%s taskId=%s traceId=%s agentId=%s mode=pipes cwd=%s",
            _elapsed_ms(cli_start),
            request.invocation_id,
            request.task_id,
            request.trace_id,
            request.agent_id,
            settings.cli_cwd,
        )
        first_output_state: dict[str, bool] = {"logged": False}
        try:
            return await asyncio.wait_for(
                self._collect_process_messages(
                    request,
                    process,
                    previous_user_marker,
                    min_message_created_at_ms,
                    cli_start,
                    first_output_state,
                ),
                timeout=self._timeout_seconds,
            )
        except TimeoutError:
            if process.returncode is None:
                process.kill()
                await process.wait()
            raise
        except asyncio.CancelledError:
            await terminate_process(process)
            raise

    async def _execute_with_pty(
        self,
        request: AgentExecutionRequest,
        command: list[str],
        env: dict[str, str],
        previous_user_marker: str | None = None,
        min_message_created_at_ms: int | None = None,
    ) -> list[AgentMessage]:
        master_fd, slave_fd = pty.openpty()
        process: asyncio.subprocess.Process | None = None
        try:
            cli_start = time.perf_counter()
            process = await asyncio.create_subprocess_exec(
                *command,
                stdin=asyncio.subprocess.DEVNULL,
                stdout=slave_fd,
                stderr=slave_fd,
                env=env,
                cwd=settings.cli_cwd,
            )
            logger.info(
                "agent_crossing_perf event=cli_startup durationMs=%s provider=opencode invocationId=%s taskId=%s traceId=%s agentId=%s mode=pty cwd=%s",
                _elapsed_ms(cli_start),
                request.invocation_id,
                request.task_id,
                request.trace_id,
                request.agent_id,
                settings.cli_cwd,
            )
            os.close(slave_fd)
            slave_fd = -1
            first_output_state: dict[str, bool] = {"logged": False}
            return await asyncio.wait_for(
                self._collect_pty_messages(
                    request,
                    process,
                    master_fd,
                    previous_user_marker,
                    min_message_created_at_ms,
                    cli_start,
                    first_output_state,
                ),
                timeout=self._timeout_seconds,
            )
        except TimeoutError:
            if process is not None and process.returncode is None:
                process.kill()
                await process.wait()
            raise
        except asyncio.CancelledError:
            await terminate_process(process)
            raise
        finally:
            if slave_fd >= 0:
                os.close(slave_fd)
            os.close(master_fd)

    async def _collect_process_messages(
        self,
        request: AgentExecutionRequest,
        process: asyncio.subprocess.Process,
        previous_user_marker: str | None = None,
        min_message_created_at_ms: int | None = None,
        process_started_at: float | None = None,
        first_output_state: dict[str, bool] | None = None,
    ) -> list[AgentMessage]:
        messages: list[AgentMessage] = []
        session_ids: list[str] = []
        diagnostics = self._empty_diagnostics("pipes")
        parse_run_output = not request.provider_session_id
        text_reconciler = _IncrementalTextReconciler()
        await asyncio.gather(
            self._read_stdout(
                request,
                process,
                messages,
                session_ids,
                diagnostics,
                parse_run_output,
                text_reconciler,
                process_started_at,
                first_output_state,
            ),
            self._read_stderr(
                request,
                process,
                messages,
                diagnostics,
                parse_run_output,
                process_started_at,
                first_output_state,
            ),
        )
        return_code = await process.wait()
        return await self._finalize_process_messages(
            request,
            messages,
            session_ids,
            return_code,
            diagnostics,
            previous_user_marker,
            min_message_created_at_ms,
        )

    async def _collect_pty_messages(
        self,
        request: AgentExecutionRequest,
        process: asyncio.subprocess.Process,
        master_fd: int,
        previous_user_marker: str | None = None,
        min_message_created_at_ms: int | None = None,
        process_started_at: float | None = None,
        first_output_state: dict[str, bool] | None = None,
    ) -> list[AgentMessage]:
        messages: list[AgentMessage] = []
        session_ids: list[str] = []
        diagnostics = self._empty_diagnostics("pty")
        text_reconciler = _IncrementalTextReconciler()
        buffer = ""
        while True:
            chunk = await asyncio.to_thread(self._read_pty_chunk, master_fd, 0.2)
            if chunk is None:
                if process.returncode is not None:
                    break
                continue
            if chunk == b"":
                break
            if process_started_at is not None and first_output_state is not None:
                self._log_first_output(request, process_started_at, first_output_state, "pty")
            text = chunk.decode("utf-8", errors="replace")
            self._append_diagnostic_text(diagnostics, "pty", text)
            if request.provider_session_id:
                continue
            buffer += text
            buffer = await self._emit_complete_lines(
                request,
                buffer,
                messages,
                session_ids,
                text_reconciler,
            )
        if buffer:
            await self._record_stream_messages(
                request,
                self._stdout_to_messages(request, buffer, session_ids),
                messages,
                text_reconciler,
            )
        return_code = await process.wait()
        return await self._finalize_process_messages(
            request,
            messages,
            session_ids,
            return_code,
            diagnostics,
            previous_user_marker,
            min_message_created_at_ms,
        )

    @staticmethod
    def _read_pty_chunk(master_fd: int, timeout_seconds: float | None = None) -> bytes | None:
        if timeout_seconds is not None:
            ready, _, _ = select.select([master_fd], [], [], timeout_seconds)
            if not ready:
                return None
        try:
            return os.read(master_fd, 4096)
        except BlockingIOError:
            return None
        except OSError as error:
            if error.errno == errno.EIO:
                return b""
            raise

    async def _emit_complete_lines(
        self,
        request: AgentExecutionRequest,
        buffer: str,
        messages: list[AgentMessage],
        session_ids: list[str],
        text_reconciler: _IncrementalTextReconciler,
    ) -> str:
        normalized = buffer.replace("\r\n", "\n").replace("\r", "\n")
        if normalized.endswith("\n"):
            lines = normalized.split("\n")
            remainder = ""
        else:
            lines = normalized.split("\n")
            remainder = lines.pop()
        for line in lines:
            await self._record_stream_messages(
                request,
                self._stdout_to_messages(request, line, session_ids),
                messages,
                text_reconciler,
            )
        return remainder

    async def _record_stream_messages(
        self,
        request: AgentExecutionRequest,
        parsed_messages: list[AgentMessage],
        messages: list[AgentMessage],
        text_reconciler: _IncrementalTextReconciler,
    ) -> None:
        for parsed_message in parsed_messages:
            message = text_reconciler.reconcile(parsed_message)
            if message is None:
                continue
            messages.append(message)
            await self._post_stream_message(request, message)

    async def _finalize_process_messages(
        self,
        request: AgentExecutionRequest,
        messages: list[AgentMessage],
        session_ids: list[str],
        return_code: int | None,
        diagnostics: dict[str, Any] | None = None,
        previous_user_marker: str | None = None,
        min_message_created_at_ms: int | None = None,
    ) -> list[AgentMessage]:
        session_id = session_ids[-1] if session_ids else request.provider_session_id
        if not session_id:
            session_id = await self._latest_session_id_from_session_list()
        if return_code not in (0, None):
            error = self._normalizer.error(
                request,
                f"OpenCode exited with code {return_code}",
                raw={"returnCode": return_code},
            )
            messages.append(error)
            await self._post_stream_message(request, error)
        elif request.provider_session_id or not self._has_content_message(messages):
            export_diagnostics: dict[str, Any] = {}
            recovered = await self._message_from_session_export(
                request,
                session_id,
                export_diagnostics,
                previous_user_marker,
                min_message_created_at_ms,
            )
            if recovered is not None:
                messages.append(recovered)
                await self._post_stream_message(request, recovered)
            else:
                raw = self._diagnostic_payload(diagnostics)
                if export_diagnostics:
                    raw["sessionExportFallback"] = export_diagnostics
                silent = self._silent_completion_message(
                    request,
                    session_id,
                    "missing_run_stdout_content",
                    raw=raw,
                )
                messages.append(silent)
                await self._post_stream_message(request, silent)
        done = self._normalizer.done(request, raw={"returnCode": return_code})
        if session_id:
            done.raw["provider"] = "opencode"
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
        diagnostics: dict[str, Any],
        parse_output: bool = True,
        text_reconciler: _IncrementalTextReconciler | None = None,
        process_started_at: float | None = None,
        first_output_state: dict[str, bool] | None = None,
    ) -> None:
        if process.stdout is None:
            return
        while line := await process.stdout.readline():
            if process_started_at is not None and first_output_state is not None:
                self._log_first_output(request, process_started_at, first_output_state, "stdout")
            text = line.decode("utf-8", errors="replace").rstrip("\r\n")
            self._append_diagnostic_text(diagnostics, "stdout", text + "\n")
            if not parse_output:
                continue
            await self._record_stream_messages(
                request,
                self._stdout_to_messages(request, text, session_ids),
                messages,
                text_reconciler or _IncrementalTextReconciler(),
            )

    async def _read_stderr(
        self,
        request: AgentExecutionRequest,
        process: asyncio.subprocess.Process,
        messages: list[AgentMessage],
        diagnostics: dict[str, Any],
        parse_output: bool = True,
        process_started_at: float | None = None,
        first_output_state: dict[str, bool] | None = None,
    ) -> None:
        if process.stderr is None:
            return
        while line := await process.stderr.readline():
            if process_started_at is not None and first_output_state is not None:
                self._log_first_output(request, process_started_at, first_output_state, "stderr")
            text = line.decode("utf-8", errors="replace").rstrip("\r\n")
            self._append_diagnostic_text(diagnostics, "stderr", text + "\n")
            if not parse_output:
                continue
            clean_text = self._clean_terminal_text(text)
            stripped = clean_text.strip()
            if not stripped:
                continue
            stripped = self._strip_plain_status_prefix(stripped)
            if not stripped:
                continue
            message = self._normalizer.error(
                request,
                stripped,
                raw={"stream": "stderr"},
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
            "agent_crossing_perf event=first_output durationMs=%s provider=opencode invocationId=%s taskId=%s traceId=%s agentId=%s stream=%s",
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

        args = [*command]
        if request.provider_session_id:
            args.extend(["-s", request.provider_session_id])
        args.append("run")
        if settings.opencode_model:
            args.extend(["-m", settings.opencode_model])
        if settings.opencode_extra_args:
            args.extend(shlex.split(settings.opencode_extra_args))
        args.append(self._build_prompt(request))
        return args

    def _stdout_to_messages(
        self,
        request: AgentExecutionRequest,
        stdout_text: str,
        session_ids: list[str] | None = None,
    ) -> list[AgentMessage]:
        messages: list[AgentMessage] = []
        for line in stdout_text.splitlines():
            clean_line = self._clean_terminal_text(line)
            stripped = clean_line.strip()
            if not stripped:
                continue
            plain_session_id = self._extract_session_id_from_text(stripped)
            if plain_session_id and session_ids is not None:
                session_ids.append(plain_session_id)
            stripped = self._strip_plain_status_prefix(stripped)
            if not stripped:
                continue
            stripped = self._strip_plain_tool_error(stripped)
            if not stripped:
                continue
            if self._is_plain_tool_status_line(stripped):
                continue
            event = self._parse_json_line(stripped)
            if event is None:
                messages.append(self._normalizer.text_delta(request, stripped))
                continue
            session_id = self._extract_session_id(event)
            if session_id and session_ids is not None:
                session_ids.append(session_id)
            transformed = self._event_to_message(request, event)
            if transformed is not None:
                messages.append(transformed)
        return messages

    async def _latest_session_id_from_session_list(self) -> str | None:
        command = self._build_session_list_command()
        if not command:
            return None
        try:
            return_code, stdout, _ = await self._run_pty_command_with_timeout(
                command,
                timeout_seconds=self._session_list_timeout_seconds(),
            )
        except (FileNotFoundError, TimeoutError):
            return None
        if return_code not in (0, None):
            return None
        return self._extract_latest_session_id_from_list(stdout.decode("utf-8", errors="replace"))

    async def _run_pty_command_with_timeout(
        self,
        command: list[str],
        timeout_seconds: float | None = None,
    ) -> tuple[int | None, bytes, bytes]:
        master_fd, slave_fd = pty.openpty()
        process: asyncio.subprocess.Process | None = None
        env = os.environ.copy()
        env.pop("AGENT_CROSSING_CALLBACK_TOKEN", None)
        try:
            process = await asyncio.create_subprocess_exec(
                *command,
                stdin=asyncio.subprocess.DEVNULL,
                stdout=slave_fd,
                stderr=slave_fd,
                env=env,
                cwd=settings.cli_cwd,
            )
            os.close(slave_fd)
            slave_fd = -1
            return await asyncio.wait_for(
                self._collect_pty_command_output(process, master_fd),
                timeout=timeout_seconds or self._timeout_seconds,
            )
        except TimeoutError:
            if process is not None and process.returncode is None:
                process.kill()
                await process.wait()
            raise
        except asyncio.CancelledError:
            await terminate_process(process)
            raise
        finally:
            if slave_fd >= 0:
                os.close(slave_fd)
            os.close(master_fd)

    async def _collect_pty_command_output(
        self,
        process: asyncio.subprocess.Process,
        master_fd: int,
    ) -> tuple[int | None, bytes, bytes]:
        chunks: list[bytes] = []
        while True:
            chunk = await asyncio.to_thread(self._read_pty_chunk, master_fd, 0.2)
            if chunk is None:
                if process.returncode is not None:
                    break
                continue
            if chunk == b"":
                break
            chunks.append(chunk)
        return_code = await process.wait()
        return return_code, b"".join(chunks), b""

    def _build_session_list_command(self) -> list[str]:
        command = shlex.split(self._command)
        if not command:
            return []
        return [*command, "session", "list"]

    def _session_list_timeout_seconds(self) -> float:
        return min(self._SESSION_LIST_TIMEOUT_SECONDS, max(0.1, self._timeout_seconds / 4))

    def _build_session_export_command(self, session_id: str) -> list[str]:
        command = shlex.split(self._command)
        if not command:
            return []
        return [*command, "export", session_id]

    async def _latest_user_marker_from_session_export(self, session_id: str) -> str | None:
        command = self._build_session_export_command(session_id)
        if not command:
            return None
        try:
            return_code, stdout, _ = await self._run_pty_command_with_timeout(command)
        except (FileNotFoundError, TimeoutError):
            return None
        if return_code not in (0, None):
            return None
        user = self._extract_latest_user_from_export(stdout.decode("utf-8", errors="replace"))
        marker = user.get("marker")
        return marker if isinstance(marker, str) and marker else None

    async def _message_from_session_export(
        self,
        request: AgentExecutionRequest,
        session_id: str | None,
        diagnostics: dict[str, Any],
        previous_user_marker: str | None = None,
        min_message_created_at_ms: int | None = None,
    ) -> AgentMessage | None:
        if not session_id:
            diagnostics["attempted"] = False
            diagnostics["reason"] = "missing_session_id"
            return None

        command = self._build_session_export_command(session_id)
        if not command:
            diagnostics["attempted"] = False
            diagnostics["reason"] = "empty_export_command"
            return None

        diagnostics["attempted"] = True
        diagnostics["sessionId"] = session_id
        diagnostics["maxAttempts"] = self._SESSION_EXPORT_ATTEMPTS
        diagnostics["retryDelaySeconds"] = self._SESSION_EXPORT_RETRY_DELAY_SECONDS
        diagnostics["readMode"] = "pty"
        diagnostics["previousUserMarker"] = previous_user_marker
        diagnostics["minMessageCreatedAtMs"] = min_message_created_at_ms
        needs_current_run_match = previous_user_marker is not None or min_message_created_at_ms is not None
        export_started_at = time.perf_counter()
        # export 返回的是完整 session 快照。轮询尝试只在本方法内比较，不向 callback 推送；
        # 命中当前 run 新增的 assistant 后，由调用方统一发布一次。
        for attempt in range(1, self._SESSION_EXPORT_ATTEMPTS + 1):
            diagnostics["attempts"] = attempt
            try:
                attempt_started_at = time.perf_counter()
                return_code, stdout, stderr = await self._run_pty_command_with_timeout(command)
                logger.info(
                    "agent_crossing_perf event=opencode_export_attempt durationMs=%s invocationId=%s taskId=%s traceId=%s agentId=%s sessionId=%s attempt=%s",
                    _elapsed_ms(attempt_started_at),
                    request.invocation_id,
                    request.task_id,
                    request.trace_id,
                    request.agent_id,
                    session_id,
                    attempt,
                )
            except FileNotFoundError:
                diagnostics["reason"] = "export_command_not_found"
                self._log_export_total(request, session_id, export_started_at, attempt, "export_command_not_found")
                return None
            except TimeoutError:
                diagnostics["reason"] = "export_timeout"
                self._log_export_total(request, session_id, export_started_at, attempt, "export_timeout")
                return None

            stdout_text = stdout.decode("utf-8", errors="replace")
            stderr_text = stderr.decode("utf-8", errors="replace")
            diagnostics["returnCode"] = return_code
            diagnostics["stdoutChars"] = len(stdout_text)
            diagnostics["stdoutTail"] = stdout_text[-self._DIAGNOSTIC_TAIL_CHARS :]
            diagnostics["stderrChars"] = len(stderr_text)
            diagnostics["stderrTail"] = stderr_text[-self._DIAGNOSTIC_TAIL_CHARS :]
            if return_code in (0, None):
                if needs_current_run_match:
                    assistant = self._extract_assistant_for_current_run_from_export(
                        stdout_text,
                        previous_user_marker,
                        min_message_created_at_ms,
                    )
                else:
                    assistant = self._extract_latest_assistant_from_export(stdout_text)
                text = assistant.get("text")
                marker = assistant.get("marker")
                if isinstance(marker, str) and marker:
                    diagnostics["latestAssistantMarker"] = marker
                user_marker = assistant.get("userMarker")
                if isinstance(user_marker, str) and user_marker:
                    diagnostics["currentRunUserMarker"] = user_marker
                assistant_time_ms = self._assistant_observed_at_ms(assistant)
                if assistant_time_ms is not None:
                    diagnostics["latestAssistantObservedAtMs"] = assistant_time_ms
                if text:
                    diagnostics["reason"] = "recovered_assistant_text"
                    diagnostics["recoveredAttempt"] = attempt
                    self._log_export_total(request, session_id, export_started_at, attempt, "recovered_assistant_text")
                    return self._normalizer.text_delta(
                        request,
                        text,
                        raw={
                            "provider": "opencode",
                            "source": "session_export_fallback",
                            "sessionId": session_id,
                            "exportAttempts": attempt,
                            "currentRunUserMarker": user_marker,
                        },
                    )
                diagnostics["reason"] = (
                    "export_current_run_assistant_missing"
                    if needs_current_run_match
                    else "export_missing_assistant_text"
                )
            else:
                diagnostics["reason"] = "export_nonzero_exit"

            if attempt < self._SESSION_EXPORT_ATTEMPTS:
                await asyncio.sleep(self._SESSION_EXPORT_RETRY_DELAY_SECONDS)

        self._log_export_total(request, session_id, export_started_at, self._SESSION_EXPORT_ATTEMPTS, diagnostics.get("reason", "not_recovered"))
        return None

    @staticmethod
    def _log_export_total(
        request: AgentExecutionRequest,
        session_id: str,
        started_at: float,
        attempts: int,
        reason: object,
    ) -> None:
        logger.info(
            "agent_crossing_perf event=opencode_export_total durationMs=%s invocationId=%s taskId=%s traceId=%s agentId=%s sessionId=%s attempts=%s reason=%s",
            _elapsed_ms(started_at),
            request.invocation_id,
            request.task_id,
            request.trace_id,
            request.agent_id,
            session_id,
            attempts,
            reason,
        )

    @staticmethod
    def _empty_diagnostics(execution_mode: str) -> dict[str, Any]:
        return {
            "executionMode": execution_mode,
            "stdoutChars": 0,
            "stdoutTail": "",
            "stderrChars": 0,
            "stderrTail": "",
            "ptyChars": 0,
            "ptyTail": "",
        }

    @classmethod
    def _append_diagnostic_text(cls, diagnostics: dict[str, Any], stream: str, text: str) -> None:
        chars_key = f"{stream}Chars"
        tail_key = f"{stream}Tail"
        diagnostics[chars_key] = int(diagnostics.get(chars_key, 0)) + len(text)
        diagnostics[tail_key] = (str(diagnostics.get(tail_key, "")) + text)[-cls._DIAGNOSTIC_TAIL_CHARS :]

    @staticmethod
    def _diagnostic_payload(diagnostics: dict[str, Any] | None) -> dict[str, Any]:
        if not diagnostics:
            return {}
        return {"diagnostics": diagnostics}

    @classmethod
    def _extract_latest_session_id_from_list(cls, output: str) -> str | None:
        clean_output = cls._clean_terminal_text(output)
        match = cls._SESSION_ID_PATTERN.search(clean_output)
        return match.group(0) if match else None

    def _silent_completion_message(
        self,
        request: AgentExecutionRequest,
        session_id: str | None,
        reason: str,
        raw: dict[str, Any] | None = None,
    ) -> AgentMessage:
        payload = {
            "provider": "opencode",
            "reason": "silent_completion",
            "sessionId": session_id,
            "detail": reason,
        }
        if raw:
            payload.update(raw)
        return self._normalizer.message(
            request,
            "OpenCode completed without text output. Check the execution log or OpenCode local logs for provider/session details.",
            raw=payload,
        )

    @staticmethod
    def _parse_json_line(line: str) -> dict[str, Any] | None:
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            return None
        return event if isinstance(event, dict) else None

    @classmethod
    def _strip_plain_status_prefix(cls, line: str) -> str:
        return cls._PLAIN_STATUS_PREFIX_PATTERN.sub("", line, count=1).strip()

    @classmethod
    def _is_plain_tool_status_line(cls, line: str) -> bool:
        return bool(cls._PLAIN_TOOL_STATUS_PATTERN.match(line.strip()))

    @classmethod
    def _strip_plain_tool_error(cls, line: str) -> str:
        stripped = line.strip()
        if any(stripped.startswith(prefix) for prefix in cls._PLAIN_TOOL_ERROR_LINE_PREFIXES):
            return ""
        marker_index = stripped.find(cls._PLAIN_TOOL_ERROR_MARKER)
        if marker_index >= 0:
            return stripped[:marker_index].strip()
        return stripped

    @classmethod
    def _clean_terminal_text(cls, text: str) -> str:
        return cls._ANSI_PATTERN.sub("", text)

    @classmethod
    def _extract_session_id_from_text(cls, line: str) -> str | None:
        match = cls._SESSION_ID_PATTERN.search(line)
        return match.group(0) if match else None

    @classmethod
    def _extract_latest_assistant_text_from_export(cls, output: str) -> str:
        assistant = cls._extract_latest_assistant_from_export(output)
        text = assistant.get("text")
        return text if isinstance(text, str) else ""

    @classmethod
    def _extract_latest_assistant_from_export(cls, output: str) -> dict[str, str | int | None]:
        clean_output = cls._clean_terminal_text(output).strip()
        if not clean_output:
            return {"marker": None, "text": ""}
        payload_text = cls._json_payload_text(clean_output)
        if not payload_text:
            return {"marker": None, "text": ""}
        try:
            payload = json.loads(payload_text)
        except json.JSONDecodeError:
            return {"marker": None, "text": ""}
        if not isinstance(payload, dict):
            return {"marker": None, "text": ""}
        messages = payload.get("messages")
        if not isinstance(messages, list):
            return {"marker": None, "text": ""}
        for message in reversed(messages):
            if not isinstance(message, dict):
                continue
            info = message.get("info")
            if not isinstance(info, dict) or info.get("role") != "assistant":
                continue
            text = cls._extract_text_from_export_parts(message.get("parts"))
            if text:
                marker = cls._assistant_marker_from_export_message(info)
                created_at_ms, completed_at_ms = cls._assistant_times_from_export_message(info)
                return {
                    "marker": marker,
                    "text": text,
                    "createdAtMs": created_at_ms,
                    "completedAtMs": completed_at_ms,
                }
        return {"marker": None, "text": ""}

    @classmethod
    def _extract_latest_user_from_export(cls, output: str) -> dict[str, str | int | None]:
        clean_output = cls._clean_terminal_text(output).strip()
        if not clean_output:
            return {"marker": None}
        payload_text = cls._json_payload_text(clean_output)
        if not payload_text:
            return {"marker": None}
        try:
            payload = json.loads(payload_text)
        except json.JSONDecodeError:
            return {"marker": None}
        if not isinstance(payload, dict):
            return {"marker": None}
        messages = payload.get("messages")
        if not isinstance(messages, list):
            return {"marker": None}
        for message in reversed(messages):
            if not isinstance(message, dict):
                continue
            info = message.get("info")
            if not isinstance(info, dict) or info.get("role") != "user":
                continue
            marker = cls._message_marker_from_export_info(info)
            created_at_ms, completed_at_ms = cls._message_times_from_export_info(info)
            return {
                "marker": marker,
                "createdAtMs": created_at_ms,
                "completedAtMs": completed_at_ms,
            }
        return {"marker": None}

    @classmethod
    def _extract_assistant_for_current_run_from_export(
        cls,
        output: str,
        previous_user_marker: str | None,
        min_message_created_at_ms: int | None,
    ) -> dict[str, str | int | None]:
        clean_output = cls._clean_terminal_text(output).strip()
        if not clean_output:
            return {"userMarker": None, "marker": None, "text": ""}
        payload_text = cls._json_payload_text(clean_output)
        if not payload_text:
            return {"userMarker": None, "marker": None, "text": ""}
        try:
            payload = json.loads(payload_text)
        except json.JSONDecodeError:
            return {"userMarker": None, "marker": None, "text": ""}
        if not isinstance(payload, dict):
            return {"userMarker": None, "marker": None, "text": ""}
        messages = payload.get("messages")
        if not isinstance(messages, list):
            return {"userMarker": None, "marker": None, "text": ""}

        previous_user_index: int | None = None
        if previous_user_marker:
            for index, message in enumerate(messages):
                if not isinstance(message, dict):
                    continue
                info = message.get("info")
                if not isinstance(info, dict) or info.get("role") != "user":
                    continue
                if cls._message_marker_from_export_info(info) == previous_user_marker:
                    previous_user_index = index
                    break

        candidate_users: dict[str, tuple[int, int | None]] = {}
        for index, message in enumerate(messages):
            message = messages[index]
            if not isinstance(message, dict):
                continue
            info = message.get("info")
            if not isinstance(info, dict) or info.get("role") != "user":
                continue
            if previous_user_index is not None and index <= previous_user_index:
                continue
            marker = cls._message_marker_from_export_info(info)
            if not marker or marker == previous_user_marker:
                continue
            created_at_ms, _ = cls._message_times_from_export_info(info)
            # 已经定位到上一轮 user 时，OpenCode export 内的消息顺序比运行时钟更可靠。
            # 运行时钟和 provider 写入的 createdAt 不一定同源，继续按时间过滤会误丢当前回复。
            if (
                previous_user_index is None
                and min_message_created_at_ms is not None
                and created_at_ms is not None
                and created_at_ms < min_message_created_at_ms
            ):
                continue
            if min_message_created_at_ms is not None and previous_user_marker is None and created_at_ms is None:
                continue
            candidate_users[marker] = (index, created_at_ms)

        if not candidate_users:
            return {"userMarker": None, "marker": None, "text": ""}

        for message in reversed(messages):
            if not isinstance(message, dict):
                continue
            info = message.get("info")
            if not isinstance(info, dict) or info.get("role") != "assistant":
                continue
            parent_id = info.get("parentID") or info.get("parentId")
            if parent_id not in candidate_users:
                continue
            text = cls._extract_text_from_export_parts(message.get("parts"))
            if not text:
                continue
            current_user_marker = str(parent_id)
            _, current_user_created_at_ms = candidate_users[current_user_marker]
            marker = cls._assistant_marker_from_export_message(info)
            created_at_ms, completed_at_ms = cls._assistant_times_from_export_message(info)
            return {
                "userMarker": current_user_marker,
                "userCreatedAtMs": current_user_created_at_ms,
                "marker": marker,
                "text": text,
                "createdAtMs": created_at_ms,
                "completedAtMs": completed_at_ms,
            }

        newest_user_marker = next(reversed(candidate_users))
        _, newest_user_created_at_ms = candidate_users[newest_user_marker]
        return {
            "userMarker": newest_user_marker,
            "userCreatedAtMs": newest_user_created_at_ms,
            "marker": None,
            "text": "",
        }

    @staticmethod
    def _assistant_marker_from_export_message(info: dict[str, Any]) -> str | None:
        return OpenCodeProvider._message_marker_from_export_info(info)

    @staticmethod
    def _message_marker_from_export_info(info: dict[str, Any]) -> str | None:
        for key in ("id", "messageID", "messageId"):
            value = info.get(key)
            if value:
                return str(value)
        time_info = info.get("time")
        if isinstance(time_info, dict):
            value = time_info.get("completed") or time_info.get("created")
            if value:
                return str(value)
        return None

    @staticmethod
    def _assistant_times_from_export_message(info: dict[str, Any]) -> tuple[int | None, int | None]:
        return OpenCodeProvider._message_times_from_export_info(info)

    @staticmethod
    def _message_times_from_export_info(info: dict[str, Any]) -> tuple[int | None, int | None]:
        time_info = info.get("time")
        if not isinstance(time_info, dict):
            return None, None
        return (
            OpenCodeProvider._int_or_none(time_info.get("created")),
            OpenCodeProvider._int_or_none(time_info.get("completed")),
        )

    @staticmethod
    def _int_or_none(value: Any) -> int | None:
        if isinstance(value, int):
            return value
        if isinstance(value, float):
            return int(value)
        if isinstance(value, str) and value.isdigit():
            return int(value)
        return None

    @staticmethod
    def _assistant_observed_at_ms(assistant: dict[str, str | int | None]) -> int | None:
        for key in ("completedAtMs", "createdAtMs"):
            value = assistant.get(key)
            if isinstance(value, int):
                return value
            if isinstance(value, str) and value.isdigit():
                return int(value)
        return None

    @staticmethod
    def _json_payload_text(output: str) -> str:
        start = output.find("{")
        end = output.rfind("}")
        if start < 0 or end < start:
            return ""
        return output[start : end + 1]

    @staticmethod
    def _extract_text_from_export_parts(parts: Any) -> str:
        if not isinstance(parts, list):
            return ""
        chunks: list[str] = []
        for part in parts:
            if not isinstance(part, dict):
                continue
            if part.get("type") != "text":
                continue
            text = part.get("text")
            if isinstance(text, str) and text.strip():
                chunks.append(text.strip())
        return "\n".join(chunks).strip()

    def _event_to_message(self, request: AgentExecutionRequest, event: dict[str, Any]) -> AgentMessage | None:
        event_type = str(event.get("type") or "")
        if event_type == "step_start":
            return None
        if event_type in ("text", "message", "assistant_message", "message_delta", "part", "part_delta", "part_updated"):
            text = self._extract_text(event)
            if text:
                return self._normalizer.text_delta(request, text, raw={"provider": "opencode", "event": event})
            return None
        if event_type in ("tool_use", "tool"):
            tool_name, tool_input = self._extract_tool(event)
            return self._normalizer.message(
                request,
                f"tool_use:{tool_name}" if tool_name else "tool_use",
                raw={"provider": "opencode", "event": event, "tool": tool_name, "input": tool_input},
            )
        if event_type == "step_finish":
            return None
        if event_type == "error":
            return self._normalizer.error(
                request,
                self._extract_error(event),
                raw={"provider": "opencode", "event": event},
            )
        return None

    @staticmethod
    def _extract_session_id(event: dict[str, Any]) -> str | None:
        for key in ("sessionID", "sessionId", "id"):
            value = event.get(key)
            if value:
                return str(value)
        session = event.get("session")
        if isinstance(session, dict):
            value = session.get("id") or session.get("sessionId")
            if value:
                return str(value)
        return None

    @staticmethod
    def _extract_text(event: dict[str, Any]) -> str:
        part = event.get("part")
        if isinstance(part, dict) and part.get("text") is not None:
            return str(part["text"])
        if isinstance(part, dict) and part.get("content") is not None:
            return OpenCodeProvider._stringify_content(part["content"])
        delta = event.get("delta")
        if isinstance(delta, dict):
            if delta.get("text") is not None:
                return str(delta["text"])
            if delta.get("content") is not None:
                return OpenCodeProvider._stringify_content(delta["content"])
        if event.get("text") is not None:
            return str(event["text"])
        if event.get("content") is not None:
            return OpenCodeProvider._stringify_content(event["content"])
        message = event.get("message")
        if isinstance(message, dict):
            if message.get("text") is not None:
                return str(message["text"])
            if message.get("content") is not None:
                return OpenCodeProvider._stringify_content(message["content"])
        return ""

    @staticmethod
    def _stringify_content(content: Any) -> str:
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            chunks: list[str] = []
            for item in content:
                if isinstance(item, str):
                    chunks.append(item)
                elif isinstance(item, dict):
                    value = item.get("text") or item.get("content")
                    if value is not None:
                        chunks.append(str(value))
            return "".join(chunks)
        return str(content)

    @staticmethod
    def _has_content_message(messages: list[AgentMessage]) -> bool:
        return any(
            message.type in (AgentMessageType.TEXT_DELTA, AgentMessageType.MESSAGE)
            and bool(message.content and message.content.strip())
            and not OpenCodeProvider._is_tool_use_message(message)
            for message in messages
        )

    @staticmethod
    def _should_callback_message(message: AgentMessage) -> bool:
        if message.type not in (AgentMessageType.TEXT_DELTA, AgentMessageType.MESSAGE, AgentMessageType.ERROR):
            return False
        if isinstance(message.raw, dict) and message.raw.get("reason") == "silent_completion":
            return False
        return not OpenCodeProvider._is_tool_use_message(message)

    @staticmethod
    def _is_tool_use_message(message: AgentMessage) -> bool:
        if message.type != AgentMessageType.MESSAGE:
            return False
        if isinstance(message.raw, dict) and message.raw.get("tool"):
            return True
        content = (message.content or "").strip()
        return content == "tool_use" or content.startswith("tool_use:")

    @staticmethod
    def _extract_tool(event: dict[str, Any]) -> tuple[str | None, Any | None]:
        part = event.get("part")
        if isinstance(part, dict):
            tool = part.get("tool")
            state = part.get("state")
            tool_input = state.get("input") if isinstance(state, dict) else None
            return (str(tool) if tool is not None else None, tool_input)
        tool = event.get("tool")
        state = event.get("state")
        tool_input = state.get("input") if isinstance(state, dict) else event.get("input")
        return (str(tool) if tool is not None else None, tool_input)

    @staticmethod
    def _extract_error(event: dict[str, Any]) -> str:
        error = event.get("error")
        if isinstance(error, dict):
            data = error.get("data")
            if isinstance(data, dict) and data.get("message") is not None:
                return str(data["message"])
            if error.get("message") is not None:
                return str(error["message"])
        if error is not None:
            return str(error)
        if event.get("message") is not None:
            return str(event["message"])
        return "OpenCode emitted an error event"

    @staticmethod
    def _build_prompt(request: AgentExecutionRequest) -> str:
        return business_prompt_composer.compose(request)


def _elapsed_ms(started_at: float) -> int:
    return int((time.perf_counter() - started_at) * 1000)
