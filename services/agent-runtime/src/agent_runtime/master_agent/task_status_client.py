from collections import Counter
from urllib.parse import urljoin

import httpx

from agent_runtime.config import settings
from agent_runtime.master_agent.models import CreateTasksResult, PlannedTask, TaskStatusItem, TaskStatusSnapshotResult


class TaskStatusClient:
    def __init__(
        self,
        platform_api_base_url: str | None = None,
        timeout_seconds: float | None = None,
    ) -> None:
        self._platform_api_base_url = (platform_api_base_url or settings.platform_api_base_url).rstrip("/") + "/"
        self._timeout_seconds = timeout_seconds or settings.platform_api_timeout_seconds

    async def get_snapshot(self, task_id: str, user_id: str = "anonymous") -> TaskStatusSnapshotResult:
        url = urljoin(self._platform_api_base_url, f"api/tasks/{task_id}/downstream")
        async with httpx.AsyncClient(timeout=self._timeout_seconds) as client:
            response = await client.get(
                url,
                headers={"X-User-Id": user_id},
            )
            response.raise_for_status()

        body = response.json()
        if body.get("success") is not True:
            error = body.get("error") or {}
            message = error.get("message") or "platform-api returned an unsuccessful task snapshot response"
            raise RuntimeError(message)

        downstream_tasks = [TaskStatusItem.model_validate(item) for item in body.get("data") or []]
        status_counts = Counter(task.status for task in downstream_tasks)
        return TaskStatusSnapshotResult(
            currentTaskId=task_id,
            userId=user_id,
            downstreamTasks=downstream_tasks,
            totalCount=len(downstream_tasks),
            statusCounts=dict(sorted(status_counts.items())),
            message="ok",
        )

    async def create_tasks(
        self,
        source_task_id: str,
        tasks: list[PlannedTask],
        user_id: str = "anonymous",
    ) -> CreateTasksResult:
        url = urljoin(self._platform_api_base_url, f"api/tasks/{source_task_id}/append")
        async with httpx.AsyncClient(timeout=self._timeout_seconds) as client:
            response = await client.post(
                url,
                headers={"X-User-Id": user_id},
                json={"tasks": [task.model_dump(mode="json", by_alias=True) for task in tasks]},
            )
            response.raise_for_status()

        body = response.json()
        if body.get("success") is not True:
            error = body.get("error") or {}
            message = error.get("message") or "platform-api returned an unsuccessful create tasks response"
            raise RuntimeError(message)

        created_tasks = [TaskStatusItem.model_validate(item) for item in body.get("data") or []]
        return CreateTasksResult(
            sourceTaskId=source_task_id,
            userId=user_id,
            createdTasks=created_tasks,
            createdCount=len(created_tasks),
            message="created" if created_tasks else "no tasks created",
        )


task_status_client = TaskStatusClient()
