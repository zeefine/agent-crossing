from agent_runtime.contracts.models import AvailableAgentCard


def format_agent_directory(agents: list[AvailableAgentCard]) -> str:
    if not agents:
        return ""
    lines = ["[Agent Directory]"]
    for agent in agents:
        fields = [f"agentId={agent.agent_id}", f"name={agent.display_name}"]
        if agent.role:
            fields.append(f"role={agent.role}")
        if agent.capabilities:
            fields.append(f"capabilities={','.join(agent.capabilities)}")
        if agent.tools:
            fields.append(f"tools={','.join(agent.tools)}")
        lines.append("- " + " | ".join(fields))
    lines.append("")
    return "\n".join(lines) + "\n"
