from agent_runtime.contracts.models import AgentExecutionRequest, AgentMessage, AgentMessageType


class PromptVersionChangedError(RuntimeError):
    """The platform must prepare a handoff before starting a replacement business session."""

    def __init__(self, current_prompt_version: str) -> None:
        super().__init__("Static prompt changed; session context recovery is required")
        self.current_prompt_version = current_prompt_version


def prepare_execution_request(
    request: AgentExecutionRequest,
    current_prompt_version: str,
) -> AgentExecutionRequest:
    """Never silently replace a session carrying history absent from this incremental request."""
    if request.provider_session_id and request.provider_prompt_version != current_prompt_version:
        raise PromptVersionChangedError(current_prompt_version)
    return request


def annotate_prompt_version(messages: list[AgentMessage], prompt_version: str) -> None:
    for message in reversed(messages):
        if message.type != AgentMessageType.DONE:
            continue
        raw = dict(message.raw) if isinstance(message.raw, dict) else {}
        raw["promptVersion"] = prompt_version
        message.raw = raw
        return
