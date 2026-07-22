import asyncio

import httpx

from agent_runtime.master_agent.task_status_client import TaskStatusClient
from agent_runtime.master_agent.models import PlannedTask


def test_task_status_client_fetches_direct_downstream_tasks_from_platform_api() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/tasks/task-a/downstream"
        assert request.headers["X-User-Id"] == "user-1"
        return httpx.Response(
            200,
            json={
                "success": True,
                "data": [
                    {
                        "taskId": "task-a",
                        "userId": "user-1",
                        "traceId": "trace-1",
                        "createdByTaskId": None,
                        "dependsOn": [],
                        "status": "completed",
                        "source": "user",
                        "depth": 0,
                        "agentId": "opencode",
                        "context": "A",
                        "createdAt": "2026-06-22T00:00:00Z",
                        "updatedAt": "2026-06-22T00:00:01Z",
                    },
                    {
                        "taskId": "task-b",
                        "userId": "user-1",
                        "traceId": "trace-1",
                        "createdByTaskId": None,
                        "dependsOn": ["task-a"],
                        "status": "queued",
                        "source": "user",
                        "depth": 0,
                        "agentId": "opencode",
                        "context": "B",
                        "createdAt": "2026-06-22T00:00:02Z",
                        "updatedAt": "2026-06-22T00:00:02Z",
                    },
                ],
            },
        )

    client = TaskStatusClient(
        platform_api_base_url="http://platform.local",
        timeout_seconds=1.0,
    )

    transport = httpx.MockTransport(handler)

    async def scenario():
        original_client = httpx.AsyncClient

        class TestAsyncClient(httpx.AsyncClient):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, transport=transport, **kwargs)

        httpx.AsyncClient = TestAsyncClient
        try:
            return await client.get_snapshot("task-a", "user-1")
        finally:
            httpx.AsyncClient = original_client

    snapshot = asyncio.run(scenario())

    assert snapshot.current_task_id == "task-a"
    assert snapshot.total_count == 2
    assert snapshot.status_counts == {"completed": 1, "queued": 1}
    assert snapshot.downstream_tasks[1].depends_on == ["task-a"]


def test_task_status_client_posts_append_only_tasks_to_platform_api() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        assert request.url.path == "/api/tasks/task-a/append"
        assert request.headers["X-User-Id"] == "user-1"
        assert request.read() == (
            b'{"idempotencyKey":"append-task-a-1","tasks":[{"taskId":"task-d","agentId":"opencode","context":"D","dependsOn":[]}]}'
        )
        return httpx.Response(
            200,
            json={
                "success": True,
                "data": [
                    {
                        "taskId": "task-d",
                        "userId": "user-1",
                        "traceId": "trace-1",
                        "createdByTaskId": "task-a",
                        "dependsOn": ["task-a"],
                        "status": "queued",
                        "source": "agent",
                        "depth": 1,
                        "agentId": "opencode",
                        "context": "D",
                        "createdAt": "2026-06-22T00:00:02Z",
                        "updatedAt": "2026-06-22T00:00:02Z",
                    }
                ],
            },
        )

    client = TaskStatusClient(
        platform_api_base_url="http://platform.local",
        timeout_seconds=1.0,
    )
    transport = httpx.MockTransport(handler)

    async def scenario():
        original_client = httpx.AsyncClient

        class TestAsyncClient(httpx.AsyncClient):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, transport=transport, **kwargs)

        httpx.AsyncClient = TestAsyncClient
        try:
            return await client.create_tasks(
                "task-a",
                [PlannedTask(taskId="task-d", agentId="opencode", context="D", dependsOn=[])],
                "append-task-a-1",
                "user-1",
            )
        finally:
            httpx.AsyncClient = original_client

    result = asyncio.run(scenario())

    assert result.source_task_id == "task-a"
    assert result.created_count == 1
    assert result.created_tasks[0].created_by_task_id == "task-a"
