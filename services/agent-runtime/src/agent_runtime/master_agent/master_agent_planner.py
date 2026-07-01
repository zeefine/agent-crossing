import asyncio
import json
import os
import shlex
from collections.abc import Awaitable, Callable

from agent_runtime.config import settings
from agent_runtime.contracts.models import ParsedTask, UserInputParseRequest, UserInputParseResponse
from agent_runtime.master_agent.errors import MasterAgentPlanningError
from agent_runtime.master_agent.models import PlannedTask
from agent_runtime.master_agent.planning_sessions import PlanningResult, PlanningSessionStore, planning_session_store
from agent_runtime.prompt_config import load_prompt_config


class MasterAgentPlanner:
    def __init__(
        self,
        session_store: PlanningSessionStore | None = None,
        process_runner: Callable[[list[str], dict[str, str]], Awaitable[int]] | None = None,
    ) -> None:
        self._session_store = session_store or planning_session_store
        self._process_runner = process_runner

    async def plan_user_input(self, request: UserInputParseRequest) -> UserInputParseResponse:
        if not settings.master_agent_enabled:
            raise MasterAgentPlanningError(
                "MASTER_AGENT_DISABLED",
                "MasterAgent is disabled; user input cannot be planned.",
            )

        session = self._session_store.create()
        process_task: asyncio.Task[int] | None = None
        plan_task: asyncio.Task[PlanningResult] | None = None
        try:
            command = self._build_command(session.planning_session_id, request)
            if not command:
                raise MasterAgentPlanningError(
                    "MASTER_AGENT_UNAVAILABLE",
                    "MasterAgent command is empty.",
                )

            env = os.environ.copy()
            env["AGENT_CROSSING_PLANNING_SESSION_ID"] = session.planning_session_id
            env["AGENT_CROSSING_AVAILABLE_AGENT_IDS"] = ",".join(
                agent.agent_id for agent in request.available_agents
            )

            session_ids: list[str] = []
            stdout_text_chunks: list[str] = []
            stderr_lines: list[str] = []
            process_task = asyncio.create_task(
                self._run_master_process(command, env, session_ids, stdout_text_chunks, stderr_lines)
            )
            plan_task = asyncio.create_task(
                self._session_store.wait(
                    session.planning_session_id,
                    settings.master_agent_timeout_seconds,
                )
            )
            done, pending = await asyncio.wait(
                {process_task, plan_task},
                return_when=asyncio.FIRST_COMPLETED,
                timeout=settings.master_agent_timeout_seconds,
            )
            if plan_task in done:
                planning_result = plan_task.result()
                return self._to_response(planning_result, request, self._provider_session_id(request, session_ids))

            if process_task in done and not plan_task.done():
                return_code = process_task.result()
                try:
                    planning_result = await asyncio.wait_for(plan_task, timeout=0.5)
                    return self._to_response(planning_result, request, self._provider_session_id(request, session_ids))
                except TimeoutError:
                    stdout_answer = self._stdout_direct_answer(stdout_text_chunks)
                    if return_code == 0 and stdout_answer:
                        return UserInputParseResponse(
                            tasks=[],
                            directAnswer=stdout_answer,
                            providerSessionId=self._provider_session_id(request, session_ids),
                        )
                    if return_code != 0:
                        raise MasterAgentPlanningError(
                            "MASTER_AGENT_PROCESS_FAILED",
                            self._process_error_message(return_code, stderr_lines),
                        )
                    raise MasterAgentPlanningError(
                        "MASTER_AGENT_NO_TASK_PLAN",
                        self._no_plan_message(stderr_lines),
                    )

            stdout_answer = self._stdout_direct_answer(stdout_text_chunks)
            if stdout_answer:
                return UserInputParseResponse(
                    tasks=[],
                    directAnswer=stdout_answer,
                    providerSessionId=self._provider_session_id(request, session_ids),
                )
            raise MasterAgentPlanningError(
                "MASTER_AGENT_TIMEOUT",
                "MasterAgent did not submit a task plan before the timeout.",
            )
        except MasterAgentPlanningError:
            raise
        except FileNotFoundError as exception:
            raise MasterAgentPlanningError(
                "MASTER_AGENT_UNAVAILABLE",
                f"MasterAgent command was not found: {exception.filename or settings.master_agent_command}",
            ) from exception
        except Exception as exception:
            raise MasterAgentPlanningError(
                "MASTER_AGENT_FAILED",
                f"MasterAgent planning failed: {exception}",
            ) from exception
        finally:
            tasks = [task for task in (process_task, plan_task) if task is not None and not task.done()]
            for task in tasks:
                task.cancel()
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
            self._session_store.discard(session.planning_session_id)

    def _build_command(self, planning_session_id: str, request: UserInputParseRequest) -> list[str]:
        command = shlex.split(settings.master_agent_command)
        if not command:
            return []
        args = [
            *command,
            "-p",
            self._build_prompt(planning_session_id, request),
            "--output-format",
            "stream-json",
            "--verbose",
            "--permission-mode",
            settings.claudecode_permission_mode,
        ]
        mcp_config_json = self._mcp_config_json()
        if mcp_config_json:
            args.extend(["--mcp-config", mcp_config_json])
        if request.provider_session_id:
            args.extend(["--resume", request.provider_session_id])
        if settings.master_agent_extra_args:
            args.extend(shlex.split(settings.master_agent_extra_args))
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
                    "agent-crossing-master-agent": {
                        "type": "http",
                        "url": settings.master_agent_mcp_url,
                    }
                }
            },
            ensure_ascii=False,
        )

    @staticmethod
    def _build_prompt(planning_session_id: str, request: UserInputParseRequest) -> str:
        available_agents = json.dumps(
            [agent.model_dump(mode="json", by_alias=True) for agent in request.available_agents],
            ensure_ascii=False,
        )
        static_prompt = load_prompt_config().master_agent_static_prompt.strip()
        return (
            f"{static_prompt}\n\n"
            "[Planning Context]\n"
            f"planningSessionId: {planning_session_id}\n"
            f"availableAgents: {available_agents}\n\n"
            f"[User Input]\n{request.input}\n"
        )

    async def _run_master_process(
        self,
        command: list[str],
        env: dict[str, str],
        session_ids: list[str],
        stdout_text_chunks: list[str],
        stderr_lines: list[str],
    ) -> int:
        if self._process_runner is not None:
            return await self._process_runner(command, env)
        return await self._run_process(command, env, session_ids, stdout_text_chunks, stderr_lines)

    @staticmethod
    async def _run_process(
        command: list[str],
        env: dict[str, str],
        session_ids: list[str],
        stdout_text_chunks: list[str],
        stderr_lines: list[str],
    ) -> int:
        process = await asyncio.create_subprocess_exec(
            *command,
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env=env,
        )
        try:
            await asyncio.wait_for(
                asyncio.gather(
                    process.wait(),
                    MasterAgentPlanner._collect_stdout(process, session_ids, stdout_text_chunks),
                    MasterAgentPlanner._collect_stderr(process, stderr_lines),
                ),
                timeout=settings.master_agent_timeout_seconds,
            )
        except asyncio.CancelledError:
            if process.returncode is None:
                process.kill()
                await process.wait()
            raise
        except TimeoutError:
            process.kill()
            await process.wait()
        return process.returncode or 0

    @staticmethod
    async def _collect_stderr(
        process: asyncio.subprocess.Process,
        stderr_lines: list[str],
    ) -> None:
        if process.stderr is None:
            return
        while line := await process.stderr.readline():
            text = line.decode("utf-8", errors="replace").strip()
            if text:
                stderr_lines.append(text)

    @staticmethod
    async def _collect_stdout(
        process: asyncio.subprocess.Process,
        session_ids: list[str],
        stdout_text_chunks: list[str],
    ) -> None:
        if process.stdout is None:
            return
        while line := await process.stdout.readline():
            decoded = line.decode("utf-8", errors="replace")
            session_id = MasterAgentPlanner._extract_provider_session_id(decoded)
            if session_id:
                session_ids.append(session_id)
            text = MasterAgentPlanner._extract_stdout_text(decoded)
            if text:
                stdout_text_chunks.append(text)

    @staticmethod
    def _extract_provider_session_id(line: str) -> str | None:
        try:
            event = json.loads(line.strip())
        except json.JSONDecodeError:
            return None
        if not isinstance(event, dict):
            return None
        for key in ("session_id", "sessionId", "sessionID"):
            value = event.get(key)
            if value:
                return str(value)
        message = event.get("message")
        if isinstance(message, dict):
            value = message.get("session_id") or message.get("sessionId") or message.get("sessionID")
            if value:
                return str(value)
        return None

    @staticmethod
    def _extract_stdout_text(line: str) -> str | None:
        try:
            event = json.loads(line.strip())
        except json.JSONDecodeError:
            text = line.strip()
            return text or None
        if not isinstance(event, dict):
            return None
        event_type = str(event.get("type") or "")
        if event_type == "assistant":
            message = event.get("message")
            content = message.get("content") if isinstance(message, dict) else event.get("content")
            return MasterAgentPlanner._extract_content_text(content)
        if event_type in ("text", "text_delta", "message_delta"):
            return MasterAgentPlanner._extract_text_value(event)
        if event_type == "result":
            value = event.get("result")
            return value.strip() if isinstance(value, str) and value.strip() else None
        return None

    @staticmethod
    def _extract_content_text(content: object) -> str | None:
        blocks = content if isinstance(content, list) else [content]
        parts: list[str] = []
        for block in blocks:
            if isinstance(block, str) and block.strip():
                parts.append(block.strip())
                continue
            if not isinstance(block, dict):
                continue
            block_type = str(block.get("type") or "")
            if block_type in ("text", "text_delta"):
                text = MasterAgentPlanner._extract_text_value(block)
                if text:
                    parts.append(text)
        return "\n".join(parts) if parts else None

    @staticmethod
    def _extract_text_value(data: dict[str, object]) -> str | None:
        for key in ("text", "content", "delta"):
            value = data.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        return None

    @staticmethod
    def _stdout_direct_answer(stdout_text_chunks: list[str]) -> str | None:
        parts: list[str] = []
        seen: set[str] = set()
        for chunk in stdout_text_chunks:
            text = chunk.strip()
            if not text or text in seen:
                continue
            seen.add(text)
            parts.append(text)
        return "\n".join(parts) if parts else None

    @staticmethod
    def _provider_session_id(request: UserInputParseRequest, session_ids: list[str]) -> str | None:
        return session_ids[-1] if session_ids else request.provider_session_id

    @staticmethod
    def _process_error_message(return_code: int, stderr_lines: list[str]) -> str:
        detail = MasterAgentPlanner._stderr_summary(stderr_lines)
        if detail:
            return f"MasterAgent process exited with code {return_code}: {detail}"
        return f"MasterAgent process exited with code {return_code}."

    @staticmethod
    def _no_plan_message(stderr_lines: list[str]) -> str:
        detail = MasterAgentPlanner._stderr_summary(stderr_lines)
        if detail:
            return f"MasterAgent exited without calling submit_task_plan or submit_direct_answer: {detail}"
        return "MasterAgent exited without calling submit_task_plan or submit_direct_answer."

    @staticmethod
    def _stderr_summary(stderr_lines: list[str]) -> str:
        if not stderr_lines:
            return ""
        return " | ".join(stderr_lines[-3:])[:1000]

    @staticmethod
    def _to_parsed_tasks(
        planned_tasks: list[PlannedTask],
        request: UserInputParseRequest,
    ) -> list[ParsedTask]:
        available_agent_ids = {agent.agent_id for agent in request.available_agents}
        accepted: list[ParsedTask] = []
        seen_task_ids: set[str] = set()
        for planned_task in planned_tasks:
            task_id = planned_task.task_id.strip()
            context = planned_task.context.strip()
            if (
                not task_id
                or task_id in seen_task_ids
                or planned_task.agent_id not in available_agent_ids
                or not context
            ):
                continue
            seen_task_ids.add(task_id)
            accepted.append(
                ParsedTask(
                    taskId=task_id,
                    agentId=planned_task.agent_id,
                    context=context,
                    dependsOn=planned_task.depends_on,
                )
            )
        return accepted

    @staticmethod
    def _to_response(
        planning_result: PlanningResult,
        request: UserInputParseRequest,
        provider_session_id: str | None = None,
    ) -> UserInputParseResponse:
        return UserInputParseResponse(
            tasks=MasterAgentPlanner._to_parsed_tasks(planning_result.tasks, request),
            directAnswer=planning_result.direct_answer,
            providerSessionId=provider_session_id,
        )
