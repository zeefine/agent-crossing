from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AGENT_RUNTIME_", extra="ignore")

    opencode_command: str = Field(default="opencode")
    opencode_model: str | None = Field(default=None)
    opencode_extra_args: str = Field(default="")
    opencode_use_pty: bool = Field(default=True)
    claudecode_command: str = Field(default="claude")
    claudecode_model: str | None = Field(default=None)
    claudecode_permission_mode: str = Field(default="bypassPermissions")
    claudecode_mcp_config_json: str | None = Field(default=None)
    claudecode_extra_args: str = Field(default="")
    codex_command: str = Field(default="codex")
    codex_model: str | None = Field(default=None)
    codex_sandbox_mode: str = Field(default="read-only")
    codex_approval_policy: str = Field(default="never")
    codex_ignore_user_config: bool = Field(default=True)
    codex_mcp_url: str | None = Field(default=None)
    codex_extra_args: str = Field(default="")
    cli_working_directory: str | None = Field(default="/Users/fine/PyProjects/agent-crossing")
    provider_timeout_seconds: float = Field(default=60.0)
    master_agent_enabled: bool = Field(default=True)
    master_agent_command: str = Field(default="claude")
    master_agent_extra_args: str = Field(default="")
    master_agent_mcp_url: str = Field(default="http://127.0.0.1:8090/mcp/master-agent/")
    master_agent_timeout_seconds: float = Field(default=60.0)
    platform_api_base_url: str = Field(default="http://127.0.0.1:8080")
    platform_api_timeout_seconds: float = Field(default=5.0)
    prompt_config_path: str = Field(default="config/prompts.json")

    @property
    def cli_cwd(self) -> str | None:
        if self.cli_working_directory is None or not self.cli_working_directory.strip():
            return None
        return str(Path(self.cli_working_directory).expanduser())


settings = Settings()
