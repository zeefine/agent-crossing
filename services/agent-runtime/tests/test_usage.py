import pytest

from agent_runtime.contracts.models import AgentExecutionUsage
from agent_runtime.providers.usage import normalize_cli_usage


@pytest.mark.parametrize("last_request", [0, 100_000, 800_000])
def test_last_request_context_takes_priority_over_aggregate_and_legacy_context(last_request: int) -> None:
    usage = normalize_cli_usage(
        "codex",
        {"usage": {
            "input_tokens": 900_000,
            "cached_input_tokens": 500_000,
            "last_request_input_tokens": last_request,
            "context_input_tokens": 900_000,
        }},
        "gpt-5.6",
        "session-1",
    )

    assert usage["totalInputTokens"] == 900_000
    assert usage["lastRequestInputTokens"] == last_request
    assert usage["contextInputTokens"] == last_request
    assert usage["usagePrecision"] == "EXACT"


@pytest.mark.parametrize("provider,raw_usage,total", [
    ("codex", {"input_tokens": 900_000, "cached_input_tokens": 500_000}, 900_000),
    ("claudecode", {"input_tokens": 100_000, "cache_creation_input_tokens": 200_000,
                    "cache_read_input_tokens": 600_000}, 900_000),
    ("codex", {"totalInputTokens": 900_000}, 900_000),
])
def test_aggregate_usage_is_preserved_without_fabricating_context(provider, raw_usage, total) -> None:
    usage = normalize_cli_usage(provider, {"usage": raw_usage}, "gpt-5.6", "session-1")

    assert usage["totalInputTokens"] == total
    assert usage["contextInputTokens"] is None
    assert usage["lastRequestInputTokens"] is None
    assert usage["usagePrecision"] == "TURN_AGGREGATE"
    assert AgentExecutionUsage.model_validate(usage).context_input_tokens is None


def test_explicit_context_without_total_does_not_fabricate_aggregate_usage() -> None:
    usage = normalize_cli_usage(
        "codex", {"usage": {"contextInputTokens": 800_000}}, "gpt-5.6", "session-1"
    )

    assert usage["totalInputTokens"] is None
    assert usage["contextInputTokens"] == 800_000
    assert usage["usagePrecision"] == "EXACT"


def test_explicit_total_is_preserved_when_last_request_is_available() -> None:
    usage = normalize_cli_usage(
        "claudecode",
        {"usage": {"total_input_tokens": 900_000, "lastRequestInputTokens": 100_000}},
        "gpt-5.6", "session-1",
    )

    assert usage["totalInputTokens"] == 900_000
    assert usage["contextInputTokens"] == 100_000
    assert usage["usagePrecision"] == "EXACT"


@pytest.mark.parametrize("invalid_context", [None, -1, True, "800000", 1.5])
def test_invalid_context_does_not_upgrade_aggregate_precision(invalid_context) -> None:
    usage = normalize_cli_usage(
        "codex", {"usage": {"input_tokens": 900_000, "last_request_input_tokens": invalid_context}},
        "gpt-5.6", "session-1",
    )

    assert usage["contextInputTokens"] is None
    assert usage["usagePrecision"] == "TURN_AGGREGATE"


def test_output_only_usage_is_not_lost_when_context_is_unknown() -> None:
    usage = normalize_cli_usage("codex", {"usage": {"output_tokens": 100}}, "gpt-5.6", None)

    assert usage["outputTokens"] == 100
    assert usage["totalInputTokens"] is None
    assert usage["contextInputTokens"] is None
    assert usage["usagePrecision"] == "UNKNOWN"
