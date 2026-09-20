"""Controlled V6 prompt-order variant for the context diagnostic."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from .prompt_v6 import PROMPT_V6_VERSION, build_prompt_v6


PROMPT_V6_SOURCE_LAST_VERSION = "gemma4-v6-source-last"
PROMPT_VERSION_SOURCE_LAST = PROMPT_V6_SOURCE_LAST_VERSION

_JSON_FIELDS = (
    "source_json",
    "context_before_json",
    "protected_terms_json",
)


def build_prompt_v6_source_last(record: Mapping[str, Any]) -> str:
    """Return V6 with only the final JSON-field order permuted.

    The parent builder remains the single owner of validation and instruction
    text.  This variant deliberately keeps its parent version line and every
    non-JSON line byte-identical.
    """

    parent_prompt = build_prompt_v6(record)
    # ``json.dumps`` may legally retain U+2028/U+2029 in data strings.  Only
    # the actual prompt newlines delimit structure, so do not use splitlines().
    lines = parent_prompt.split("\n")
    if len(lines) < 4 or lines[0] != f"version={PROMPT_V6_VERSION}":
        raise ValueError("unexpected V6 prompt shape")
    tail = lines[-3:]
    fields: dict[str, str] = {}
    for line in tail:
        field, separator, value = line.partition("=")
        if not separator or field not in _JSON_FIELDS or field in fields:
            raise ValueError("unexpected V6 JSON tail")
        fields[field] = line
    if set(fields) != set(_JSON_FIELDS):
        raise ValueError("V6 prompt JSON tail is incomplete")
    return "\n".join(
        lines[:-3]
        + [
            fields["context_before_json"],
            fields["protected_terms_json"],
            fields["source_json"],
        ]
    )


__all__ = [
    "PROMPT_V6_SOURCE_LAST_VERSION",
    "PROMPT_VERSION_SOURCE_LAST",
    "build_prompt_v6_source_last",
]
