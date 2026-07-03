from typing import Annotated

from pydantic import Field
from fastmcp import FastMCP

from agent_runtime.master_agent.models import (
    AppendTask,
    CreateTasksResult,
    PlannedTask,
    SubmitDirectAnswerResult,
    SubmitTaskPlanResult,
    TaskStatusSnapshotResult,
)
from agent_runtime.master_agent.planning_sessions import planning_session_store
from agent_runtime.master_agent.task_status_client import task_status_client


master_agent_mcp = FastMCP(
    "agent-crossing-master-agent",
    instructions=(
        "For ordinary Q&A that does not need task execution, use "
        "submit_direct_answer to answer the user directly. For executable work, "
        "use submit_task_plan to return the task array for the current Agent "
        "Crossing planning session. Use get_task_status_snapshot before creating "
        "follow-up tasks. get_task_status_snapshot returns the direct downstream "
        "tasks of the current task, sorted by createdAt. Use create_tasks to "
        "append new tasks without modifying the existing DAG."
    ),
)


@master_agent_mcp.tool()
async def submit_task_plan(
    planningSessionId: str,
    tasks: list[PlannedTask],
) -> SubmitTaskPlanResult:
    accepted = planning_session_store.submit(planningSessionId, tasks)
    return SubmitTaskPlanResult(
        accepted=accepted,
        taskCount=len(tasks),
        message="accepted" if accepted else "unknown or completed planning session",
    )


@master_agent_mcp.tool()
async def submit_direct_answer(
    planningSessionId: str,
    answer: str,
) -> SubmitDirectAnswerResult:
    """Return a direct assistant answer when no downstream task is required."""
    accepted = planning_session_store.submit_direct_answer(planningSessionId, answer)
    return SubmitDirectAnswerResult(
        accepted=accepted,
        message="accepted" if accepted else "unknown, completed, or empty planning session",
    )


@master_agent_mcp.tool()
async def get_task_status_snapshot(
    taskId: str,
    userId: str = "anonymous",
) -> TaskStatusSnapshotResult:
    """Return direct downstream tasks for the current Agent Crossing task."""
    return await task_status_client.get_snapshot(taskId, userId)


@master_agent_mcp.tool()
async def create_tasks(
    sourceTaskId: Annotated[str, Field(description="Current task id. New tasks are appended after this task.")],
    tasks: Annotated[
        list[AppendTask],
        Field(
            description=(
                "New tasks to append. Each task.context must be short and self-contained; "
                "do not include full conversation transcripts, tool outputs, JSON blocks, "
                "or long quoted text. Conversation history is injected by the platform."
            )
        ),
    ],
    userId: Annotated[str, Field(description="Current user id from Invocation Context.")] = "anonymous",
) -> CreateTasksResult:
    """Append concise new task nodes after sourceTaskId without modifying existing DAG nodes or edges."""
    return await task_status_client.create_tasks(sourceTaskId, tasks, userId)
