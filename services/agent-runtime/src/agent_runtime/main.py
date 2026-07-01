from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from agent_runtime.api.routes import router
from agent_runtime.contracts.models import ApiError, ApiResponse
from agent_runtime.master_agent.mcp_server import master_agent_mcp


def create_app() -> FastAPI:
    mcp_app = master_agent_mcp.http_app(path="/", transport="http", stateless_http=True)
    app = FastAPI(
        title="Agent Crossing Agent Runtime",
        version="0.1.0",
        lifespan=mcp_app.lifespan,
    )
    app.include_router(router, prefix="/api")
    app.mount("/mcp/master-agent", mcp_app)

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=400,
            content=ApiResponse.failure(
                ApiError(code="VALIDATION_ERROR", message=str(exc))
            ).model_dump(mode="json", by_alias=True),
        )

    @app.exception_handler(Exception)
    async def unexpected_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(
            status_code=500,
            content=ApiResponse.failure(
                ApiError(code="INTERNAL_ERROR", message="Unexpected server error")
            ).model_dump(mode="json", by_alias=True),
        )

    return app


app = create_app()
