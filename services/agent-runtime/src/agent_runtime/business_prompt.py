from agent_runtime.contracts.models import AgentExecutionRequest, IncrementalChatMessage
from agent_runtime.prompt_config import PromptConfig, load_prompt_config
from agent_runtime.prompt_sections import format_agent_directory


class BusinessPromptComposer:
    """Single source of truth for provider-independent business-agent prompts."""

    def compose(
        self,
        request: AgentExecutionRequest,
        prompt_config: PromptConfig | None = None,
    ) -> str:
        config = prompt_config or load_prompt_config()
        static_section = self._static_section(request, config)
        agents = request.context_pack.available_agents if request.context_pack else []
        messages = request.context_pack.incremental_chat_messages if request.context_pack else []
        return (
            f"{static_section}"
            "[Invocation Context]\n"
            f"userId: {request.user_id}\n"
            f"traceId: {request.trace_id}\n"
            f"taskId: {request.task_id}\n"
            "\n"
            f"{format_agent_directory(agents)}"
            f"{self._format_incremental_chat_messages(messages)}"
            f"Task:\n{request.context}\n"
        )

    @staticmethod
    def _static_section(request: AgentExecutionRequest, prompt_config: PromptConfig) -> str:
        if request.provider_session_id:
            return ""
        static_prompt = prompt_config.static_prompt_for_business_agent(request.agent_id).strip()
        return f"{static_prompt}\n\n" if static_prompt else ""

    @staticmethod
    def _format_incremental_chat_messages(messages: list[IncrementalChatMessage]) -> str:
        if not messages:
            return ""
        lines = ["[New Conversation Since Last Invocation]"]
        for message in messages:
            if message.role == "user":
                speaker = "User"
            else:
                speaker = message.agent_id or "Agent"
                if message.task_id:
                    speaker = f"{speaker}/{message.task_id}"
            lines.append(f"{speaker}: {message.content}")
        lines.append("")
        return "\n".join(lines) + "\n"


business_prompt_composer = BusinessPromptComposer()
