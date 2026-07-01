import json
import re
from uuid import uuid4

from agent_runtime.contracts.models import (
    AgentOutputParseRequest,
    AgentOutputParseResponse,
    ParsedTask,
)


AGENT_MENTION_PATTERN = re.compile(r"^@(?P<agent_id>[A-Za-z0-9_-]+)(?P<context>.*)$")
JSON_FENCE_PATTERN = re.compile(r"```json\s*(?P<body>.*?)```", re.DOTALL | re.IGNORECASE)


class AgentOutputParser:
    def parse(self, request: AgentOutputParseRequest) -> AgentOutputParseResponse:
        available_agent_ids = set(request.available_agent_ids)
        tasks: list[ParsedTask] = []

        for line in request.output.splitlines():
            # v1.0 只识别“行首 @agentId”，行中第二个 @ 不会生成额外任务。
            match = AGENT_MENTION_PATTERN.match(line)
            if match is None:
                continue

            agent_id = match.group("agent_id")
            # 未注册 agent 直接丢弃，保持 Java/Python 两侧规则一致。
            if agent_id not in available_agent_ids:
                continue

            tasks.append(
                ParsedTask(
                    taskId=f"task-{uuid4()}",
                    agentId=agent_id,
                    context=match.group("context").strip(),
                    # agent 输出生成的任务默认没有前置依赖。
                    dependsOn=[],
                )
            )

        tasks.extend(self._parse_create_tasks_json(request, available_agent_ids))
        return AgentOutputParseResponse(tasks=tasks)

    def _parse_create_tasks_json(
        self,
        request: AgentOutputParseRequest,
        available_agent_ids: set[str],
    ) -> list[ParsedTask]:
        tasks: list[ParsedTask] = []
        for match in JSON_FENCE_PATTERN.finditer(request.output):
            try:
                payload = json.loads(match.group("body"))
            except json.JSONDecodeError:
                continue
            if not isinstance(payload, dict) or payload.get("tool") != "create_tasks":
                continue
            args = payload.get("args")
            if not isinstance(args, dict):
                continue
            raw_tasks = args.get("tasks")
            if not isinstance(raw_tasks, list):
                continue
            for raw_task in raw_tasks:
                parsed = self._parse_task_item(raw_task, available_agent_ids)
                if parsed is not None:
                    tasks.append(parsed)
        return tasks

    @staticmethod
    def _parse_task_item(raw_task: object, available_agent_ids: set[str]) -> ParsedTask | None:
        if not isinstance(raw_task, dict):
            return None
        agent_id = raw_task.get("agentId")
        context = raw_task.get("context")
        if not isinstance(agent_id, str) or agent_id not in available_agent_ids:
            return None
        if not isinstance(context, str) or not context.strip():
            return None
        task_id = raw_task.get("taskId")
        depends_on = raw_task.get("dependsOn")
        return ParsedTask(
            taskId=task_id if isinstance(task_id, str) and task_id.strip() else f"task-{uuid4()}",
            agentId=agent_id,
            context=context.strip(),
            dependsOn=[
                dependency.strip()
                for dependency in depends_on
                if isinstance(dependency, str) and dependency.strip()
            ]
            if isinstance(depends_on, list)
            else [],
        )
