import unittest

from analyze_meeting_benchmark import analyze_log, nearest_rank


def event(pass_id, phase, revision, mono_ms, word, start_ms, end_ms, speaker_tag=0,
          stable_speaker_ms=0, word_index=0, utterance_id=7):
    count = 0 if word is None else 1
    lines = [
        f'RESULT pass={pass_id} phase={phase} revision={revision} '
        f'utterance_id={utterance_id} mono_ms={mono_ms} '
        f'stable_speaker_ms={stable_speaker_ms} word_count={count}'
    ]
    if word is not None:
        lines.append(
            f'WORD pass={pass_id} revision={revision} utterance_id={utterance_id} '
            f'word_index={word_index} start_ms={start_ms} end_ms={end_ms} '
            f'speaker_tag={speaker_tag} word="{word}"'
        )
    return "\n".join(lines)


def benchmark_log():
    lines = []
    variants = ["asr", "both", "both", "asr", "asr", "both"]
    for pass_id, variant in enumerate(variants, start=1):
        lines.append(f"BENCH_PASS_BEGIN pass={pass_id} variant={variant}")
        if pass_id == 1:
            lines.extend([
                event(pass_id, "interim", 1, 105, "Salut", 0, 50),
                event(pass_id, "final_initial", 2, 110, "Salut", 0, 50),
                event(pass_id, "final_refresh", 3, 180, "Salut", 0, 50, 0, 0),
            ])
        if pass_id == 2:
            lines.extend([
                event(pass_id, "final_initial", 1, 200, "Bonjour", 50, 100,
                      utterance_id=10),
                event(pass_id, "final_refresh", 2, 210, "Bonjour", 50, 100, 2, 0,
                      utterance_id=10),
                event(pass_id, "final_refresh", 3, 300, "Bonjour", 50, 100, 2, 100,
                      utterance_id=10),
                event(pass_id, "final_initial", 1, 310, "encore", 200, 250, 3, 100,
                      utterance_id=11),
                event(pass_id, "final_initial", 1, 350, "sans", 300, 350, 0, 0,
                      utterance_id=12),
            ])
        if pass_id == 4:
            # A later final revision replaces the complete prior utterance state.
            lines.extend([
                event(pass_id, "interim", 1, 100, "euh", 10, 40, utterance_id=31),
                event(pass_id, "final_initial", 2, 110, "euh", 10, 40, utterance_id=31),
                event(pass_id, "final_refresh", 3, 150, "mardi", 50, 100,
                      utterance_id=31),
            ])
        if pass_id == 6:
            lines.extend([
                event(pass_id, "final_initial", 1, 100, "mardi", 10, 40,
                      utterance_id=61),
                event(pass_id, "final_refresh", 2, 130, None, 0, 0,
                      utterance_id=61),
            ])
        lines.append(f"BENCH_PASS_END pass={pass_id} variant={variant}")
        if pass_id == 1:
            lines.extend([
                "PSS_SAMPLE pass=1 mono_ms=1000 pss_kb=800 rss_kb=1200",
                "PSS_SAMPLE pass=1 mono_ms=1250 pss_kb=900 rss_kb=1400",
            ])
    return "\n".join(lines) + "\n"


class MeetingBenchmarkAnalysisTest(unittest.TestCase):
    def test_fixed_six_pass_schedule_and_revision_deduplication(self):
        report = analyze_log(benchmark_log())

        self.assertEqual(["asr", "both", "both", "asr", "asr", "both"],
                         [run["variant"] for run in report["passes"]])
        diar_run = report["passes"][1]
        self.assertEqual(3, diar_run["final_words"])
        self.assertEqual(3, diar_run["first_appearance"]["count"])
        self.assertEqual(2, diar_run["first_positive_attribution"]["count"])
        self.assertEqual(1, diar_run["stable_attribution"]["count"])
        self.assertEqual(2, diar_run["final_positive_words"])
        self.assertEqual(1, diar_run["stable_final_words"])
        self.assertEqual(1, diar_run["positive_unconfirmed"])
        self.assertEqual(1, diar_run["unassigned_at_end"])
        self.assertEqual(2, diar_run["final_refreshes"])
        self.assertEqual(250, report["passes"][0]["memory_samples"]["duration_ms"])
        self.assertEqual(900, report["passes"][0]["memory_samples"]["pss_max_kb"])
        self.assertEqual(1400, report["passes"][0]["memory_samples"]["rss_max_kb"])

    def test_latency_uses_first_appearance_and_nearest_rank_percentiles(self):
        report = analyze_log(benchmark_log())
        self.assertEqual(55, report["passes"][0]["first_appearance"]["p50_ms"])
        self.assertEqual(55, report["passes"][0]["first_appearance"]["p95_ms"])
        self.assertEqual(110, report["passes"][1]["first_positive_attribution"]["p95_ms"])
        self.assertEqual(200, report["passes"][1]["stable_attribution"]["p95_ms"])
        self.assertEqual(20, nearest_rank([10, 20, 30], 0.50))
        self.assertEqual(30, nearest_rank([10, 20, 30], 0.95))

    def test_rejects_a_schedule_that_is_not_the_pinned_alternation(self):
        bad = benchmark_log().replace("pass=2 variant=both", "pass=2 variant=asr", 1)
        with self.assertRaisesRegex(ValueError, "schedule"):
            analyze_log(bad)

    def test_requires_six_complete_begin_end_pairs(self):
        partial = "BENCH_PASS_BEGIN pass=1 variant=asr\nBENCH_PASS_END pass=1 variant=asr\n"
        with self.assertRaisesRegex(ValueError, "six complete"):
            analyze_log(partial)

    def test_latest_final_revision_replaces_abandoned_words(self):
        run = analyze_log(benchmark_log())["passes"][3]
        self.assertEqual(1, run["final_words"])
        self.assertEqual(1, run["first_appearance"]["count"])
        self.assertEqual(50, run["first_appearance"]["p50_ms"])

    def test_empty_final_revision_removes_prior_utterance_words(self):
        run = analyze_log(benchmark_log())["passes"][5]
        self.assertEqual(0, run["final_words"])
        self.assertEqual(0, run["first_appearance"]["count"])

    def test_ambiguous_final_identity_is_counted_and_excluded(self):
        raw = benchmark_log().splitlines()
        begin = raw.index("BENCH_PASS_BEGIN pass=1 variant=asr")
        end = raw.index("BENCH_PASS_END pass=1 variant=asr")
        raw[begin + 1:end] = [
            event(1, "interim", 1, 100, "oui", 10, 30),
            "RESULT pass=1 phase=final_initial revision=2 utterance_id=7 "
            "mono_ms=120 stable_speaker_ms=0 word_count=2",
            "WORD pass=1 revision=2 utterance_id=7 word_index=0 start_ms=10 end_ms=30 "
            'speaker_tag=0 word="oui"',
            "WORD pass=1 revision=2 utterance_id=7 word_index=1 start_ms=10 end_ms=30 "
            'speaker_tag=0 word="oui"',
        ]
        run = analyze_log("\n".join(raw))["passes"][0]
        self.assertEqual(2, run["final_words"])
        self.assertEqual(1, run["ambiguous_joins"])
        self.assertEqual(0, run["first_appearance"]["count"])


if __name__ == "__main__":
    unittest.main()
