from datetime import datetime, timezone
from typing import Any


def normalize_cli_usage(
    provider: str,
    event: dict[str, Any] | None,
    configured_model: str | None,
    provider_session_id: str | None,
) -> dict[str, Any] | None:
    if not isinstance(event, dict):
        return None
    raw_usage = event.get("usage")
    if not isinstance(raw_usage, dict):
        return None

    input_tokens = _integer(raw_usage, "input_tokens", "inputTokens")
    cached_input_tokens = _integer(raw_usage, "cached_input_tokens", "cachedInputTokens")
    cache_creation_input_tokens = _integer(
        raw_usage,
        "cache_creation_input_tokens",
        "cacheCreationInputTokens",
    )
    cache_read_input_tokens = _integer(
        raw_usage,
        "cache_read_input_tokens",
        "cacheReadInputTokens",
    )
    output_tokens = _integer(raw_usage, "output_tokens", "outputTokens")
    reasoning_output_tokens = _integer(
        raw_usage,
        "reasoning_output_tokens",
        "reasoningOutputTokens",
    )
    last_request_input_tokens = _integer(
        raw_usage,
        "last_request_input_tokens",
        "lastRequestInputTokens",
    )
    context_input_tokens = _integer(
        raw_usage,
        "context_input_tokens",
        "contextInputTokens",
    )
    # Only explicitly reported request/context counts measure window occupancy.
    # Final CLI input_tokens can accumulate multiple model requests in one turn.
    if last_request_input_tokens is not None:
        context_input_tokens = last_request_input_tokens

    total_input_tokens = _integer(raw_usage, "total_input_tokens", "totalInputTokens")
    if total_input_tokens is None:
        total_input_tokens = _provider_total_input_tokens(
            provider,
            input_tokens,
            cache_creation_input_tokens,
            cache_read_input_tokens,
        )
    if context_input_tokens is not None:
        precision = "EXACT"
    elif total_input_tokens is not None:
        precision = "TURN_AGGREGATE"
    else:
        precision = "UNKNOWN"
    model = _model(event, configured_model)
    return {
        "provider": provider,
        "model": model,
        "providerSessionId": provider_session_id,
        "totalInputTokens": total_input_tokens,
        "lastRequestInputTokens": last_request_input_tokens,
        "usagePrecision": precision,
        "inputTokens": input_tokens,
        "cachedInputTokens": cached_input_tokens,
        "cacheCreationInputTokens": cache_creation_input_tokens,
        "cacheReadInputTokens": cache_read_input_tokens,
        "outputTokens": output_tokens,
        "reasoningOutputTokens": reasoning_output_tokens,
        "contextInputTokens": context_input_tokens,
        "rawUsageJson": event,
        "providerCliVersion": _string(event, "cli_version", "cliVersion", "version"),
        "observedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }


def _provider_total_input_tokens(
    provider: str,
    input_tokens: int | None,
    cache_creation_input_tokens: int | None,
    cache_read_input_tokens: int | None,
) -> int | None:
    if provider == "claudecode":
        values = (input_tokens, cache_creation_input_tokens, cache_read_input_tokens)
        return sum(value or 0 for value in values) if any(value is not None for value in values) else None
    # Cached input is a subset, not an alternative measure of total input.
    return input_tokens


def _model(event: dict[str, Any], configured_model: str | None) -> str:
    direct = _string(event, "model")
    if direct:
        return direct
    model_usage = event.get("modelUsage") or event.get("model_usage")
    if isinstance(model_usage, dict) and len(model_usage) == 1:
        return str(next(iter(model_usage)))
    return configured_model or "unknown"


def _integer(values: dict[str, Any], *keys: str) -> int | None:
    for key in keys:
        value = values.get(key)
        if isinstance(value, bool):
            continue
        if isinstance(value, int) and value >= 0:
            return value
        if isinstance(value, float) and value >= 0 and value.is_integer():
            return int(value)
    return None


def _string(values: dict[str, Any], *keys: str) -> str | None:
    for key in keys:
        value = values.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None
