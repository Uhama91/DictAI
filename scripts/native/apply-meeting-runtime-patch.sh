#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    printf 'Usage: %s <pinned-source-root> <patch-file>\n' "$0" >&2
    exit 2
fi

SOURCE_ROOT="$1"
PATCH_FILE="$2"
[[ -d "$SOURCE_ROOT" ]] || {
    printf 'ERROR: pinned source directory is missing: %s\n' "$SOURCE_ROOT" >&2
    exit 2
}
[[ -f "$PATCH_FILE" ]] || {
    printf 'ERROR: pinned runtime patch is missing: %s\n' "$PATCH_FILE" >&2
    exit 2
}

if git -C "$SOURCE_ROOT" apply --check "$PATCH_FILE" >/dev/null 2>&1; then
    git -C "$SOURCE_ROOT" apply "$PATCH_FILE"
    printf 'PATCH_APPLIED %s\n' "$PATCH_FILE"
elif git -C "$SOURCE_ROOT" apply --reverse --check "$PATCH_FILE" >/dev/null 2>&1; then
    printf 'PATCH_ALREADY_APPLIED %s\n' "$PATCH_FILE"
else
    printf 'ERROR: pinned runtime source matches neither patch state; refusing divergent state: %s\n' \
        "$SOURCE_ROOT" >&2
    exit 2
fi
