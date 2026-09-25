#!/system/bin/sh
set -u

pass_id="$1"
variant="$2"
remote_dir="$3"
case "$pass_id:$variant" in
    1:asr|2:both|3:both|4:asr|5:asr|6:both) ;;
    *) printf 'FAIL: invalid T8 pass/variant %s:%s\n' "$pass_id" "$variant" >&2; exit 2 ;;
esac

probe_log="$remote_dir/pass-$pass_id-native.log"
set -- --benchmark-pass "$pass_id" \
    --asr "$remote_dir/asr.gguf" \
    --diar "$remote_dir/diar.gguf" \
    --wav "$remote_dir/synthetic-fr.wav"
coexist_count=0
for library in "$remote_dir"/existing-runtime/*.so; do
    [ -f "$library" ] || continue
    set -- "$@" --coexist-lib "$library"
    coexist_count=$((coexist_count + 1))
done
[ "$coexist_count" -eq 9 ] || {
    printf 'FAIL: expected exactly nine existing runtime libraries, got %s\n' "$coexist_count" >&2
    exit 3
}
LD_LIBRARY_PATH="$remote_dir:$remote_dir/existing-runtime" \
    "$remote_dir/meeting_probe" "$@" >"$probe_log" 2>&1 &
probe_pid=$!

(
    cadence_ms=250
    sample_count=0
    samples_valid=1
    first_sample_ms=-1
    last_sample_ms=-1
    next_sample_ms="$(awk '{ printf "%d", $1 * 1000 }' /proc/uptime)"
    printf 'PSS_SAMPLING_BEGIN pass=%s interval_ms=%s source=proc-smaps_rollup+status overhead=proc_scan_and_adb_stream\n' \
        "$pass_id" "$cadence_ms"
    while [ -r "/proc/$probe_pid/stat" ]; do
        process_state="$(awk '{ print $3 }' "/proc/$probe_pid/stat" 2>/dev/null)"
        [ "$process_state" = "Z" ] && break
        now_ms="$(awk '{ printf "%d", $1 * 1000 }' /proc/uptime)"
        if [ "$now_ms" -lt "$next_sample_ms" ]; then
            wait_ms=$((next_sample_ms - now_ms))
            sleep "$(awk -v ms="$wait_ms" 'BEGIN { printf "%.3f", ms / 1000 }')"
            continue
        fi
        pss_kb="$(awk '$1 == "Pss:" { print $2; exit }' "/proc/$probe_pid/smaps_rollup" 2>/dev/null)"
        rss_kb="$(awk '$1 == "VmRSS:" { print $2; exit }' "/proc/$probe_pid/status" 2>/dev/null)"
        [ -n "$pss_kb" ] || pss_kb=-1
        [ -n "$rss_kb" ] || rss_kb=-1
        case "$pss_kb" in ''|*[!0-9]*) samples_valid=0 ;; esac
        case "$rss_kb" in ''|*[!0-9]*) samples_valid=0 ;; esac
        printf 'PSS_SAMPLE pass=%s sample=%s mono_ms=%s pss_kb=%s rss_kb=%s\n' \
            "$pass_id" "$sample_count" "$now_ms" "$pss_kb" "$rss_kb"
        if [ "$sample_count" -eq 0 ]; then
            first_sample_ms="$now_ms"
        fi
        last_sample_ms="$now_ms"
        sample_count=$((sample_count + 1))
        next_sample_ms=$((next_sample_ms + cadence_ms))
        now_ms="$(awk '{ printf "%d", $1 * 1000 }' /proc/uptime)"
        while [ "$next_sample_ms" -le "$now_ms" ]; do
            next_sample_ms=$((next_sample_ms + cadence_ms))
        done
    done
    sample_duration_ms=0
    [ "$first_sample_ms" -ge 0 ] && sample_duration_ms=$((last_sample_ms - first_sample_ms))
    printf 'PSS_SAMPLING_END pass=%s interval_ms=%s samples=%s duration_ms=%s\n' \
        "$pass_id" "$cadence_ms" "$sample_count" "$sample_duration_ms"
    [ "$sample_count" -ge 2 ] && [ "$samples_valid" -eq 1 ]
) &
sampler_pid=$!

set +e
wait "$probe_pid"
probe_status=$?
wait "$sampler_pid"
sampler_status=$?
set -e

cat "$probe_log"
if [ "$probe_status" -ne 0 ]; then
    exit "$probe_status"
fi
if [ "$sampler_status" -ne 0 ]; then
    printf 'FAIL: PSS sampling did not produce at least two valid samples\n' >&2
    exit 4
fi
