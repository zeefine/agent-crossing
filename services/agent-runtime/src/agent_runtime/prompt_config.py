from __future__ import annotations

import json
from pathlib import Path

from pydantic import BaseModel, Field

from agent_runtime.config import settings


class PromptConfig(BaseModel):
    default_business_agent_static_prompt: str = Field(default="", alias="defaultBusinessAgentStaticPrompt")
    business_agent_static_prompt: str = Field(default="", alias="businessAgentStaticPrompt")
    business_agent_static_prompts: dict[str, str] = Field(default_factory=dict, alias="businessAgentStaticPrompts")
    master_agent_static_prompt: str = Field(alias="masterAgentStaticPrompt")

    def static_prompt_for_business_agent(self, agent_id: str) -> str:
        return (
            self.business_agent_static_prompts.get(agent_id)
            or self.default_business_agent_static_prompt
            or self.business_agent_static_prompt
        )


def load_prompt_config() -> PromptConfig:
    path = Path(settings.prompt_config_path)
    if not path.is_absolute():
        path = Path.cwd() / path
        if not path.exists():
            path = Path(__file__).resolve().parents[2] / settings.prompt_config_path
    with path.open(encoding="utf-8") as file:
        return PromptConfig.model_validate(json.load(file))
