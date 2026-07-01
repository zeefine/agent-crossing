import asyncio
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import uuid4

from agent_runtime.master_agent.models import PlannedTask


@dataclass(frozen=True)
class PlanningResult:
    tasks: list[PlannedTask]
    direct_answer: str | None = None


@dataclass
class PlanningSession:
    planning_session_id: str
    future: asyncio.Future[PlanningResult]
    created_at: datetime
    loop: asyncio.AbstractEventLoop


class PlanningSessionStore:
    def __init__(self) -> None:
        self._sessions: dict[str, PlanningSession] = {}

    def create(self) -> PlanningSession:
        loop = asyncio.get_running_loop()
        planning_session_id = f"plan-{uuid4()}"
        session = PlanningSession(
            planning_session_id=planning_session_id,
            future=loop.create_future(),
            created_at=datetime.now(timezone.utc),
            loop=loop,
        )
        self._sessions[planning_session_id] = session
        return session

    def submit(self, planning_session_id: str, tasks: list[PlannedTask]) -> bool:
        session = self._resolve_session(planning_session_id)
        if session is None or session.future.done():
            return False
        session.loop.call_soon_threadsafe(session.future.set_result, PlanningResult(tasks=tasks))
        return True

    def submit_direct_answer(self, planning_session_id: str, answer: str) -> bool:
        session = self._resolve_session(planning_session_id)
        if session is None or session.future.done() or not answer.strip():
            return False
        session.loop.call_soon_threadsafe(
            session.future.set_result,
            PlanningResult(tasks=[], direct_answer=answer.strip()),
        )
        return True

    async def wait(self, planning_session_id: str, timeout_seconds: float) -> PlanningResult:
        session = self._sessions[planning_session_id]
        return await asyncio.wait_for(session.future, timeout=timeout_seconds)

    def discard(self, planning_session_id: str) -> None:
        session = self._sessions.pop(planning_session_id, None)
        if session is not None and not session.future.done():
            session.future.cancel()

    def _resolve_session(self, planning_session_id: str) -> PlanningSession | None:
        return self._sessions.get(planning_session_id)


planning_session_store = PlanningSessionStore()
