#!/usr/bin/env python3
"""Analyze the machine-readable output from the six-pass T8 meeting probe."""

import argparse
import json
import math
import shlex
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path


PINNED_VARIANTS = ["asr", "both", "both", "asr", "asr", "both"]
RESULT_FIELDS = {
    "pass", "phase", "revision", "utterance_id", "mono_ms", "stable_speaker_ms", "word_count"
}
WORD_FIELDS = {
    "pass", "revision", "utterance_id", "word_index", "start_ms", "end_ms", "speaker_tag", "word"
}
FINAL_PHASES = {"final_initial", "final_refresh"}


def _fields(line):
    fields = {}
    for token in shlex.split(line, posix=True):
        if "=" in token:
            key, value = token.split("=", 1)
            fields[key] = value
    return fields


def _int_field(fields, name, line_number, record):
    try:
        return int(fields[name])
    except (KeyError, ValueError) as error:
        raise ValueError(f"line {line_number}: invalid or missing {name} in {record}") from error


def _normalize_word(word):
    """Casefold Unicode text and trim only leading/trailing Unicode punctuation."""
    value = word.casefold().strip()
    while value and unicodedata.category(value[0]).startswith("P"):
        value = value[1:]
    while value and unicodedata.category(value[-1]).startswith("P"):
        value = value[:-1]
    return value


def nearest_rank(values, quantile):
    """Return rank=ceil(q*n), one-based; the empty sample has no percentile."""
    if not 0 < quantile <= 1:
        raise ValueError("quantile must be greater than zero and at most one")
    ordered = sorted(values)
    if not ordered:
        return None
    return ordered[math.ceil(quantile * len(ordered)) - 1]


def _latency_summary(values):
    return {
        "count": len(values),
        "p50_ms": nearest_rank(values, 0.50),
        "p95_ms": nearest_rank(values, 0.95),
    }


def _parse_log(log_text):
    pass_order = []
    pass_variants = {}
    pass_ended = set()
    word_rows = defaultdict(list)
    final_header_rows = defaultdict(list)
    final_states = defaultdict(dict)
    pss_rows = defaultdict(list)
    active_pass = None
    current_header = None

    def finish_current(line_number):
        nonlocal current_header
        if current_header is not None and len(current_header["words"]) != current_header["word_count"]:
            raise ValueError(
                f"line {line_number}: RESULT word_count does not match following WORD rows"
            )
        current_header = None

    for line_number, line in enumerate(log_text.splitlines(), start=1):
        if line.startswith("BENCH_PASS_BEGIN "):
            finish_current(line_number)
            fields = _fields(line)
            pass_id = _int_field(fields, "pass", line_number, "BENCH_PASS_BEGIN")
            variant = fields.get("variant")
            if variant not in {"asr", "both"}:
                raise ValueError(f"line {line_number}: invalid benchmark variant")
            if active_pass is not None or pass_id in pass_variants:
                raise ValueError(f"line {line_number}: overlapping or duplicate pass begin")
            active_pass = pass_id
            pass_order.append(pass_id)
            pass_variants[pass_id] = variant
            continue

        if line.startswith("BENCH_PASS_END "):
            finish_current(line_number)
            fields = _fields(line)
            pass_id = _int_field(fields, "pass", line_number, "BENCH_PASS_END")
            variant = fields.get("variant")
            if active_pass != pass_id or pass_variants.get(pass_id) != variant:
                raise ValueError(f"line {line_number}: schedule pass end does not match begin")
            if pass_id in pass_ended:
                raise ValueError(f"line {line_number}: duplicate pass end")
            pass_ended.add(pass_id)
            active_pass = None
            continue

        if line.startswith("RESULT "):
            finish_current(line_number)
            fields = _fields(line)
            missing = RESULT_FIELDS - fields.keys()
            if missing:
                raise ValueError(f"line {line_number}: RESULT missing {sorted(missing)}")
            pass_id = _int_field(fields, "pass", line_number, "RESULT")
            if active_pass != pass_id:
                raise ValueError(f"line {line_number}: RESULT is outside its pass")
            header = {
                "pass": pass_id,
                "phase": fields["phase"],
                "revision": _int_field(fields, "revision", line_number, "RESULT"),
                "utterance_id": _int_field(fields, "utterance_id", line_number, "RESULT"),
                "mono_ms": _int_field(fields, "mono_ms", line_number, "RESULT"),
                "stable_speaker_ms": _int_field(fields, "stable_speaker_ms", line_number, "RESULT"),
                "word_count": _int_field(fields, "word_count", line_number, "RESULT"),
                "words": [],
            }
            if header["phase"] not in {"interim", *FINAL_PHASES}:
                raise ValueError(f"line {line_number}: unsupported RESULT phase")
            if header["revision"] <= 0 or header["utterance_id"] <= 0 or header["word_count"] < 0:
                raise ValueError(f"line {line_number}: invalid RESULT identity or word_count")
            current_header = header
            if header["phase"] in FINAL_PHASES:
                previous = final_states[pass_id].get(header["utterance_id"])
                if previous is not None and header["revision"] <= previous["revision"]:
                    raise ValueError(f"line {line_number}: final revisions must increase per utterance")
                final_header_rows[pass_id].append(header)
                final_states[pass_id][header["utterance_id"]] = header
            continue

        if line.startswith("WORD "):
            if current_header is None:
                raise ValueError(f"line {line_number}: WORD has no preceding RESULT header")
            fields = _fields(line)
            missing = WORD_FIELDS - fields.keys()
            if missing:
                raise ValueError(f"line {line_number}: WORD missing {sorted(missing)}")
            row = {
                "pass": _int_field(fields, "pass", line_number, "WORD"),
                "revision": _int_field(fields, "revision", line_number, "WORD"),
                "utterance_id": _int_field(fields, "utterance_id", line_number, "WORD"),
                "word_index": _int_field(fields, "word_index", line_number, "WORD"),
                "start_ms": _int_field(fields, "start_ms", line_number, "WORD"),
                "end_ms": _int_field(fields, "end_ms", line_number, "WORD"),
                "speaker_tag": _int_field(fields, "speaker_tag", line_number, "WORD"),
                "word": fields["word"],
            }
            if (row["pass"] != current_header["pass"] or
                    row["revision"] != current_header["revision"] or
                    row["utterance_id"] != current_header["utterance_id"]):
                raise ValueError(f"line {line_number}: WORD identity differs from RESULT header")
            if row["word_index"] < 0 or row["word_index"] >= current_header["word_count"]:
                raise ValueError(f"line {line_number}: WORD index is outside the declared state")
            if any(old["word_index"] == row["word_index"] for old in current_header["words"]):
                raise ValueError(f"line {line_number}: duplicate WORD index in RESULT state")
            complete_row = {key: value for key, value in current_header.items() if key != "words"}
            complete_row.update(row)
            complete_row["normalized_word"] = _normalize_word(row["word"])
            current_header["words"].append(complete_row)
            if complete_row["normalized_word"]:
                word_rows[pass_id].append(complete_row)
            continue

        if line.startswith("PSS_SAMPLE "):
            fields = _fields(line)
            pass_id = _int_field(fields, "pass", line_number, "PSS_SAMPLE")
            sample = {
                "mono_ms": _int_field(fields, "mono_ms", line_number, "PSS_SAMPLE"),
                "pss_kb": _int_field(fields, "pss_kb", line_number, "PSS_SAMPLE"),
                "rss_kb": _int_field(fields, "rss_kb", line_number, "PSS_SAMPLE"),
            }
            if pass_id not in range(1, 7) or sample["pss_kb"] < 0 or sample["rss_kb"] < 0:
                raise ValueError(f"line {line_number}: invalid or unavailable memory sample")
            pss_rows[pass_id].append(sample)

    finish_current(len(log_text.splitlines()) + 1)
    if active_pass is not None:
        raise ValueError("log must contain six complete begin/end pairs")
    if len(pass_order) != 6 or pass_order != list(range(1, 7)) or pass_ended != set(range(1, 7)):
        raise ValueError("log must contain six complete begin/end pairs in order")
    if [pass_variants[pass_id] for pass_id in pass_order] != PINNED_VARIANTS:
        raise ValueError("benchmark schedule does not match pinned ASR/BOTH schedule")
    return pass_variants, word_rows, final_header_rows, pss_rows, final_states


def _pass_report(pass_id, variant, word_rows, final_headers, pss_rows, final_states):
    appearances = defaultdict(list)
    for row in word_rows:
        key = (row["utterance_id"], row["normalized_word"], row["start_ms"], row["end_ms"])
        appearances[key].append(row)

    final_words = []
    for state in final_states.values():
        if state["pass"] == pass_id:
            final_words.extend(state["words"])

    final_revisions = {(row["utterance_id"], row["revision"]) for row in final_headers}
    final_refreshes = {
        (row["utterance_id"], row["revision"])
        for row in final_headers if row["phase"] == "final_refresh"
    }
    final_identity_counts = defaultdict(int)
    for row in final_words:
        key = (row["utterance_id"], row["normalized_word"], row["start_ms"], row["end_ms"])
        final_identity_counts[key] += 1
    ambiguous_keys = {key for key, count in final_identity_counts.items() if count > 1}
    for key, occurrences in appearances.items():
        counts_by_revision = defaultdict(int)
        for occurrence in occurrences:
            counts_by_revision[occurrence["revision"]] += 1
        if any(count > 1 for count in counts_by_revision.values()):
            ambiguous_keys.add(key)

    first_appearance_latencies = []
    positive_latencies = []
    stable_latencies = []
    unmatched_final_words = 0
    ambiguous_final_keys = set()
    final_positive_words = 0
    stable_final_words = 0
    positive_unconfirmed = 0
    unassigned_at_end = 0
    for word in final_words:
        if word["speaker_tag"] <= 0:
            unassigned_at_end += 1
        else:
            final_positive_words += 1
            if word["stable_speaker_ms"] >= word["end_ms"]:
                stable_final_words += 1
            else:
                positive_unconfirmed += 1

        key = (word["utterance_id"], word["normalized_word"], word["start_ms"], word["end_ms"])
        if key in ambiguous_keys:
            ambiguous_final_keys.add(key)
            continue
        candidates = appearances.get(key, [])
        if not candidates:
            unmatched_final_words += 1
            continue
        first_appearance = min(candidates, key=lambda row: row["mono_ms"])
        first_appearance_latencies.append(first_appearance["mono_ms"] - word["end_ms"])
        positive = [row for row in candidates if row["speaker_tag"] > 0]
        stable = [row for row in positive if row["stable_speaker_ms"] >= word["end_ms"]]
        if positive:
            first_positive = min(positive, key=lambda row: row["mono_ms"])
            positive_latencies.append(first_positive["mono_ms"] - word["end_ms"])
        if stable:
            first_stable = min(stable, key=lambda row: row["mono_ms"])
            stable_latencies.append(first_stable["mono_ms"] - word["end_ms"])

    report = {
        "pass": pass_id,
        "variant": variant,
        "final_words": len(final_words),
        "final_revisions": len(final_revisions),
        "final_refreshes": len(final_refreshes),
        "first_appearance": _latency_summary(first_appearance_latencies),
        "first_positive_attribution": _latency_summary(positive_latencies),
        "stable_attribution": _latency_summary(stable_latencies),
        "final_positive_words": final_positive_words,
        "stable_final_words": stable_final_words,
        "positive_unconfirmed": positive_unconfirmed,
        "unassigned_at_end": unassigned_at_end,
        "ambiguous_joins": len(ambiguous_final_keys),
        "ambiguous_final_words": sum(
            1 for word in final_words
            if (word["utterance_id"], word["normalized_word"], word["start_ms"], word["end_ms"])
            in ambiguous_final_keys
        ),
        "unmatched_final_words": unmatched_final_words,
    }
    if pss_rows:
        pss = sorted(pss_rows, key=lambda sample: sample["mono_ms"])
        pss_values = [sample["pss_kb"] for sample in pss]
        rss_values = [sample["rss_kb"] for sample in pss]
        report["memory_samples"] = {
            "count": len(pss),
            "duration_ms": pss[-1]["mono_ms"] - pss[0]["mono_ms"],
            "pss_max_kb": max(pss_values),
            "pss_mean_kb": round(sum(pss_values) / len(pss_values), 1),
            "rss_max_kb": max(rss_values),
            "rss_mean_kb": round(sum(rss_values) / len(rss_values), 1),
            "rss_is_not_pss": True,
        }
    else:
        report["memory_samples"] = {
            "count": 0, "duration_ms": 0, "pss_max_kb": None, "pss_mean_kb": None,
            "rss_max_kb": None, "rss_mean_kb": None, "rss_is_not_pss": True,
        }
    report["_latencies"] = {
        "first_appearance": first_appearance_latencies,
        "first_positive_attribution": positive_latencies,
        "stable_attribution": stable_latencies,
    }
    return report


def analyze_log(log_text):
    pass_variants, word_rows, final_headers, pss_by_pass, final_states = _parse_log(log_text)
    passes = [
        _pass_report(pass_id, pass_variants[pass_id], word_rows[pass_id], final_headers[pass_id],
                     pss_by_pass[pass_id], final_states[pass_id])
        for pass_id in range(1, 7)
    ]
    variants = {}
    for variant in ("asr", "both"):
        selected = [run for run in passes if run["variant"] == variant]
        aggregate = {"passes": [run["pass"] for run in selected]}
        for metric in ("first_appearance", "first_positive_attribution", "stable_attribution"):
            values = [value for run in selected for value in run["_latencies"][metric]]
            aggregate[metric] = _latency_summary(values)
        for field in (
            "final_words", "final_revisions", "final_refreshes", "final_positive_words",
            "stable_final_words", "positive_unconfirmed", "unassigned_at_end",
            "ambiguous_joins", "ambiguous_final_words", "unmatched_final_words",
        ):
            aggregate[field] = sum(run[field] for run in selected)
        variants[variant] = aggregate
    for run in passes:
        del run["_latencies"]
    return {
        "protocol": "T8-6-pass-cpu",
        "latency_definition": (
            "first publication monotonic_ms minus final word end_ms; unique join by utterance, "
            "audio start/end, and Unicode casefolded word with leading/trailing Unicode "
            "punctuation trimmed; ambiguous joins excluded; nearest-rank percentiles"
        ),
        "stable_definition": (
            "positive speaker tag observed with stable_speaker_ms >= final word end_ms; "
            "EOF alone does not confirm stability"
        ),
        "memory_sampling_note": (
            "PSS and RSS are sampled separately every 250 ms from /proc; process scans and "
            "the adb sampling stream add measurement overhead"
        ),
        "measurement_scope": "native probe process only; not APK/JNI/UI/Poco performance evidence",
        "passes": passes,
        "variants": variants,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="concatenated six-pass probe and PSS log")
    parser.add_argument("--output", help="write JSON report here instead of stdout")
    args = parser.parse_args(argv)
    report = analyze_log(Path(args.input).read_text(encoding="utf-8"))
    output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        Path(args.output).write_text(output, encoding="utf-8")
    else:
        sys.stdout.write(output)


if __name__ == "__main__":
    main()
