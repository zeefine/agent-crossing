from __future__ import annotations

import hashlib
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

    def business_agent_prompt_version(self, agent_id: str) -> str:
        return _prompt_version(self.static_prompt_for_business_agent(agent_id))

    def master_agent_prompt_version(self) -> str:
        return _prompt_version(self.master_agent_static_prompt)


def _prompt_version(prompt: str) -> str:
    return hashlib.sha256(prompt.strip().encode("utf-8")).hexdigest()


def load_prompt_config() -> PromptConfig:
    path = Path(settings.prompt_config_path)
    if not path.is_absolute():
        path = Path.cwd() / path
        if not path.exists():
            path = Path(__file__).resolve().parents[2] / settings.prompt_config_path
    with path.open(encoding="utf-8") as file:
        return PromptConfig.model_validate(json.load(file))
