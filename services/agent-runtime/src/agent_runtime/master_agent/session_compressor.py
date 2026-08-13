import asyncio
import json
import shlex
from typing import Any

from agent_runtime.config import settings
from agent_runtime.contracts.models import SessionCompressionRequest, SessionCompressionResponse
from agent_runtime.prompt_config import load_prompt_config


class SessionCompressor:
    async def compress(self, request: SessionCompressionRequest) -> SessionCompressionResponse:
        config = load_prompt_config()
        command = self._build_command(self._build_prompt(request))
        if not command:
            raise RuntimeError("MasterAgent compression command is empty")
        process = await asyncio.create_subprocess_exec(
            *command,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            cwd=settings.cli_cwd,
        )
        try:
            stdout, stderr = await asyncio.wait_for(
                process.communicate(), timeout=settings.master_agent_timeout_seconds
            )
        except TimeoutError:
            process.kill()
            await process.wait()
            raise RuntimeError("MasterAgent session compression timed out")
        if process.returncode != 0:
            detail = stderr.decode("utf-8", errors="replace")[-2000:]
            raise RuntimeError(f"MasterAgent session compression failed: {detail}")
        summary = self._parse_summary(stdout.decode("utf-8", errors="replace"))
        return SessionCompressionResponse(
            startupSummary=summary,
            summaryPromptVersion=config.compression_prompt_version(),
        )

    @staticmethod
    def _build_command(prompt: str) -> list[str]:
        command = shlex.split(settings.master_agent_command)
        if not command:
            return []
        args = [*command, "-p", prompt, "--output-format", "json", "--tools", ""]
        if settings.master_agent_extra_args:
            args.extend(shlex.split(settings.master_agent_extra_args))
        return args

    @staticmethod
    def _build_prompt(request: SessionCompressionRequest) -> str:
        config = load_prompt_config()
        payload = request.model_dump(mode="json", by_alias=True)
        return (
            f"{config.master_agent_compression_prompt.strip()}\n\n"
            "[Compression Input]\n"
            f"{json.dumps(payload, ensure_ascii=False, separators=(',', ':'))}\n"
        )

    @classmethod
    def _parse_summary(cls, stdout: str) -> dict[str, Any]:
        outer = cls._last_json_object(stdout)
        candidate: Any = outer.get("result") if isinstance(outer.get("result"), str) else outer
        if isinstance(candidate, str):
            candidate = cls._last_json_object(candidate)
        if not isinstance(candidate, dict) or candidate.get("schemaVersion") != 1:
            raise RuntimeError("MasterAgent compression returned an invalid summary schema")
        return candidate

    @staticmethod
    def _last_json_object(text: str) -> dict[str, Any]:
        stripped = text.strip()
        try:
            value = json.loads(stripped)
        except json.JSONDecodeError:
            value = None
        if isinstance(value, dict):
            return value

        for line in reversed(stripped.splitlines()):
            try:
                value = json.loads(line.strip())
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict):
                return value

        decoder = json.JSONDecoder()
        best: tuple[int, int, dict[str, Any]] | None = None
        for index, char in enumerate(text):
            if char != "{":
                continue
            try:
                value, length = decoder.raw_decode(text[index:])
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict):
                candidate = (index + length, length, value)
                if best is None or candidate[:2] > best[:2]:
                    best = candidate
        if best is None:
            raise RuntimeError("MasterAgent compression returned no JSON object")
        return best[2]


session_compressor = SessionCompressor()
