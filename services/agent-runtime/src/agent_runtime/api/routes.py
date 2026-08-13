from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException

from agent_runtime.contracts.models import (
    AgentExecutionRequest,
    AgentExecutionResponse,
    ApiResponse,
    HealthResponse,
    RuntimeCancelResponse,
    SessionCompressionRequest,
    SessionCompressionResponse,
    UserInputParseRequest,
    UserInputParseResponse,
)
from agent_runtime.parser.service import QuestParserService
from agent_runtime.master_agent.errors import MasterAgentPlanningError
from agent_runtime.master_agent.session_compressor import session_compressor
from agent_runtime.runtime.service import AgentRuntimeService

router = APIRouter()
parser_service = QuestParserService()
runtime_service = AgentRuntimeService()


@router.get("/health", response_model=ApiResponse[HealthResponse])
async def health() -> ApiResponse[HealthResponse]:
    return ApiResponse.ok(
        HealthResponse(
            status="UP",
            service="agent-runtime",
            timestamp=datetime.now(timezone.utc),
        )
    )


@router.post("/parser/user-input", response_model=UserInputParseResponse)
async def parse_user_input(request: UserInputParseRequest) -> UserInputParseResponse:
    try:
        return await parser_service.parse_user_input(request)
    except MasterAgentPlanningError as exception:
        raise HTTPException(
            status_code=503,
            detail={"code": exception.code, "message": exception.message},
        ) from exception


@router.post("/runtime/execute", response_model=AgentExecutionResponse)
async def execute_agent(request: AgentExecutionRequest) -> AgentExecutionResponse:
    return await runtime_service.execute(request)


@router.post("/runtime/invocations/{invocation_id}/cancel", response_model=RuntimeCancelResponse)
async def cancel_agent_execution(invocation_id: str) -> RuntimeCancelResponse:
    return RuntimeCancelResponse(
        invocationId=invocation_id,
        accepted=await runtime_service.cancel(invocation_id),
    )


@router.post("/runtime/compress", response_model=SessionCompressionResponse)
async def compress_business_session(request: SessionCompressionRequest) -> SessionCompressionResponse:
    try:
        return await session_compressor.compress(request)
    except RuntimeError as exception:
        raise HTTPException(status_code=503, detail=str(exception)) from exception
