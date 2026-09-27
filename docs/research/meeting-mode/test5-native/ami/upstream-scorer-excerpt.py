Pinned source: nemo-speech/tests/ci/model_smoke.py
Revision: 97a15afa5caa9bce5baaa86c1184103877af4101
SHA-256: f7feeb2b71d9912c411dcb446e5bb2f9422cbc2b2c89fb9b2246217f28ff61e1
Function excerpt follows (Apache-2.0; see the pinned repository for the complete file).
def diarization_errors(reference, hypothesis) -> tuple[float, float]:
    """Frame-level DER and speaker confusion (no collar) under the best speaker mapping."""
    ref, hyp = speaker_frames(reference), speaker_frames(hypothesis)
    frames = set().union(*ref.values(), *hyp.values())
    ref_count = {f: sum(f in s for s in ref.values()) for f in frames}
    hyp_count = {f: sum(f in s for s in hyp.values()) for f in frames}
    ref_ids, hyp_ids = list(ref), list(hyp) + [None] * max(0, len(ref) - len(hyp))
    best = max(
        (
            sum(len(ref[r] & hyp[h]) for r, h in zip(ref_ids, perm) if h is not None)
            for perm in itertools.permutations(hyp_ids, len(ref_ids))
        ),
        default=0,
    )
    total = sum(ref_count.values())
    miss = sum(max(ref_count[f] - hyp_count[f], 0) for f in frames)
    false_alarm = sum(max(hyp_count[f] - ref_count[f], 0) for f in frames)
    confusion = sum(min(ref_count[f], hyp_count[f]) for f in frames) - best
    return (miss + false_alarm + confusion) / total, confusion / total
