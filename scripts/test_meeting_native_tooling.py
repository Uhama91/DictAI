import hashlib
import os
import re
import subprocess
import unittest
import wave
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MEETING_CPP = ROOT / "app/src/main/cpp/meeting"
SCRIPTS = ROOT / "scripts"


def staged_sources(script: str) -> list[str]:
    match = re.search(r"for source_file in(.*?)\bdone", script, re.S)
    if not match:
        return []
    return re.findall(r"\b[A-Za-z0-9_.-]+\.(?:cpp|h|txt|exports)\b", match.group(1))


class MeetingNativeToolingTest(unittest.TestCase):
    def test_probe_builder_stages_every_source_used_by_jni_builder(self):
        jni_script = (SCRIPTS / "build_meeting_jni.sh").read_text()
        probe_script = (SCRIPTS / "build_meeting_android.sh").read_text()
        expected = staged_sources(jni_script)
        actual = staged_sources(probe_script)
        self.assertEqual(
            ["CMakeLists.txt", "meeting_probe.cpp", "meeting_jni.cpp",
             "meeting_native_logic.cpp", "meeting_native_logic.h", "meeting_jni.exports"],
            expected,
        )
        self.assertEqual(expected, actual)

    def test_coexistence_manifest_pins_exactly_the_nine_historical_libraries(self):
        manifest = SCRIPTS / "native/meeting-existing-runtime.manifest"
        rows = [line.split() for line in manifest.read_text().splitlines() if line.strip()]
        self.assertEqual(9, len(rows))
        self.assertTrue(all(len(row) == 3 for row in rows))
        self.assertNotIn("libdictai_meeting.so", [row[0] for row in rows])
        self.assertEqual(9, len({row[0] for row in rows}))
        for filename, expected_size, expected_sha in rows:
            path = ROOT / "app/src/main/jniLibs/arm64-v8a" / filename
            self.assertTrue(path.is_file(), filename)
            payload = path.read_bytes()
            self.assertEqual(int(expected_size), len(payload), filename)
            self.assertEqual(expected_sha, hashlib.sha256(payload).hexdigest(), filename)

    def test_coexistence_checker_uses_manifest_instead_of_library_wildcard(self):
        script = (SCRIPTS / "check_meeting_native.sh").read_text()
        self.assertIn("meeting-existing-runtime.manifest", script)
        self.assertNotIn('"$EXISTING_RUNTIME"/*.so', script)

    def test_coexistence_checker_reads_the_manifest_file(self):
        script = (SCRIPTS / "check_meeting_native.sh").read_text()
        loop_start = script.index("while IFS=' ' read -r runtime_name")
        loop_end = script.index('done < "$RUNTIME_MANIFEST"', loop_start)
        self.assertRegex(script[loop_start:loop_end + len('done < "$RUNTIME_MANIFEST"')],
                         r'done\s*<\s*"\$RUNTIME_MANIFEST"')

        result = subprocess.run(
            ["bash", str(SCRIPTS / "check_meeting_native.sh"), "--verify-manifest"],
            cwd=ROOT,
            input="",
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("RUNTIME_MANIFEST_OK libraries=9", result.stdout)
        entries = [line for line in result.stdout.splitlines()
                   if line.startswith("RUNTIME_MANIFEST_ENTRY ")]
        self.assertEqual(9, len(entries))

    def test_benchmark_dispatch_precedes_the_legacy_t1_asr_run(self):
        source = (MEETING_CPP / "meeting_probe.cpp").read_text()
        main = source[source.index("main(int argc, char** argv)"):]
        self.assertLess(main.index("if (benchmark_pass)"), main.index("run_asr_only(asr_path, input)"))

    def test_benchmark_deadlines_are_end_of_chunk_and_arithmetic_is_checked(self):
        source = (MEETING_CPP / "meeting_probe.cpp").read_text()
        self.assertIn("(offset + count)", source)
        self.assertIn("stream_clock=audio_capture_origin", source)
        self.assertIn("BENCH_TIMING_CHECK", source)
        self.assertIn("first_due_us", source)
        self.assertIn("last_due_us", source)

        fixture = ROOT / "app/src/androidTest/assets/meeting/synthetic-fr-ABCA-16k-mono.wav"
        self.assertEqual(
            "196439ff592c6e6a794c1914804dd996c428f378ab7dd0ff372bfb55baa521d9",
            hashlib.sha256(fixture.read_bytes()).hexdigest(),
        )
        with wave.open(str(fixture), "rb") as audio:
            self.assertEqual(16000, audio.getframerate())
            self.assertEqual(1, audio.getnchannels())
            frame_count = audio.getnframes()
        first_chunk_frames = min(16000 * 160 // 1000, frame_count)
        self.assertEqual(160000, first_chunk_frames * 1000000 // 16000)
        self.assertEqual(12780250, frame_count * 1000000 // 16000)

    def test_finish_final_identity_is_deduplicated_even_without_diarization(self):
        source = (MEETING_CPP / "meeting_probe.cpp").read_text()
        finish = source[source.index("const auto& finish_words = result_words(finish_result);"):]
        self.assertNotIn("if (diarized && !finish_words.empty())", finish[:finish.index("refresh_pending();")])
        self.assertNotIn("if (diarized && !words.empty())", source)
        self.assertIn("same_benchmark_word_identity", finish[:finish.index("refresh_pending();")])
        self.assertIn("state.pending_finals.rbegin()", finish[:finish.index("refresh_pending();")])

    def test_benchmark_capacity_and_probe_errors_propagate_to_host(self):
        source = (MEETING_CPP / "meeting_probe.cpp").read_text()
        benchmark = source[source.index("run_benchmark_pass("):source.index("run_asr_only(")]
        self.assertIn("return 4;", benchmark)
        self.assertIn("return run_benchmark_pass(pass_id, asr_path, diar_path, input);", benchmark)
        remote = (SCRIPTS / "native/meeting-benchmark-pass-remote.sh").read_text()
        self.assertIn('if [ "$probe_status" -ne 0 ]; then', remote)

    def test_remote_sampler_has_valid_awk_programs_and_fails_on_invalid_samples(self):
        script = (SCRIPTS / "native/meeting-benchmark-pass-remote.sh").read_text()
        self.assertNotIn(r'\"%d\"', script)
        self.assertIn("samples_valid=1", script)
        self.assertIn("[ \"$samples_valid\" -eq 1 ]", script)

    def test_final_runner_is_gated_and_pins_the_six_pass_fixture(self):
        script = (SCRIPTS / "run_meeting_benchmark.sh").read_text()
        self.assertIn('MEETING_T8_APPROVED:-}', script)
        self.assertIn("196439ff592c6e6a794c1914804dd996c428f378ab7dd0ff372bfb55baa521d9", script)
        self.assertIn("variants=(asr both both asr asr both)", script)
        self.assertIn("analyze_meeting_benchmark.py", script)

    def test_runner_refuses_measurement_before_explicit_approval(self):
        env = os.environ.copy()
        env.pop("MEETING_T8_APPROVED", None)
        result = subprocess.run(
            ["bash", str(SCRIPTS / "run_meeting_benchmark.sh")],
            cwd=ROOT,
            env=env,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn("requires explicit review authorization", result.stderr)


if __name__ == "__main__":
    unittest.main()
