from agent_runtime.contracts.models import AgentExecutionRequest, AgentMessage, AgentMessageType


def prepare_execution_request(
    request: AgentExecutionRequest,
    current_prompt_version: str,
) -> AgentExecutionRequest:
    """Only reuse a provider session when it was created with the current static prompt."""
    if (
        request.provider_session_id
        and request.provider_prompt_version == current_prompt_version
    ):
        return request
    if not request.provider_session_id:
        return request
    return request.model_copy(
        update={
            "provider_session_id": None,
            "provider_prompt_version": None,
        }
    )


def annotate_prompt_version(messages: list[AgentMessage], prompt_version: str) -> None:
    for message in reversed(messages):
        if message.type != AgentMessageType.DONE:
            continue
        raw = dict(message.raw) if isinstance(message.raw, dict) else {}
        raw["promptVersion"] = prompt_version
        message.raw = raw
        return
