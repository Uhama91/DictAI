# Native meeting runtime sources

The meeting binary statically links the following pinned source trees. The
included license files are copied from the exact checked out paths listed
below; NeMo-Speech.cpp and SentencePiece are Apache-2.0, GGML is MIT, and the
SentencePiece bundled components retain their upstream license files.

| Component | Source revision | Included notice |
| --- | --- | --- |
| NVIDIA NeMo-Speech.cpp (ASR/diarization) | `97a15afa5caa9bce5baaa86c1184103877af4101` | `nemo-speech-LICENSE.txt`, `nemo-speech-NOTICE.txt`, `nemo-speech-THIRD_PARTY_NOTICES.md` from `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md` |
| GGML submodule | `c03b4e2bcece5134827881af90242086daf75be5` | `ggml-LICENSE.txt` from `ggml/LICENSE` |
| Google SentencePiece | `17d7580d6407802f85855d2cc9190634e2c95624` | `sentencepiece-LICENSE.txt` from `LICENSE` |
| Abseil (SentencePiece bundled source) | pinned by SentencePiece revision above | `absl-LICENSE.txt` from `third_party/absl/LICENSE` |
| Darts Clone (SentencePiece bundled source) | pinned by SentencePiece revision above | `darts-clone-LICENSE.txt` from `third_party/darts_clone/LICENSE` |
| esaxx (SentencePiece bundled source) | pinned by SentencePiece revision above | `esaxx-LICENSE.txt` from `third_party/esaxx/LICENSE` |
| protobuf-lite (SentencePiece bundled source) | pinned by SentencePiece revision above | `protobuf-lite-LICENSE.txt` from `third_party/protobuf-lite/LICENSE` |

The complete upstream third-party notice preserves the attribution for
portions of the runtime and FastConformer encoder derived from parakeet.cpp
(Copyright 2025 Jason Ni, MIT). It also lists optional upstream components
that are not selected by this Android build; the table above identifies the
source dependencies used here.

The model weights are separate artifacts, fetched at the listed Hugging Face
revisions and verified by size and SHA-256. They are not bundled in the APK
and are not covered by the code licenses above.

| Model | Source revision | Verified artifact | Model license |
| --- | --- | --- | --- |
| NVIDIA Nemotron 3.5 ASR Streaming 0.6B Q8_0 | [`README.md`](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/raw/1c8deaecc64b91f034d73e08dd8b64625eb3395d/README.md), revision `1c8deaecc64b91f034d73e08dd8b64625eb3395d` | `nemotron-3.5-asr-streaming-0.6b.q8_0.gguf`, 741548352 bytes, SHA-256 `a5c435f294eea8f88ce68dd27b8c3bfea7f777cb2fbba04fcd30eaa555f429ae` | OpenMDW-1.1 |
| NVIDIA Nemotron 3 Diarization Q8_0 | [`README.md`](https://huggingface.co/nvidia/Nemotron-3-Diarization/raw/f667ed73aee57d40cc39428eb768b4fd87a0a29e/README.md), revision `f667ed73aee57d40cc39428eb768b4fd87a0a29e` | `Nemotron-3-Diarization.q8_0.gguf`, 107012128 bytes, SHA-256 `08456d9e22cd9a323c0364d98375f3746d6e68507ebb705cd46438c534c7a3a1` | OpenMDW-1.1 |

`OpenMDW-1.1.txt` contains the agreement text from the official page
[`https://openmdw.ai/license/1-1/`](https://openmdw.ai/license/1-1/). The model
cards identify OpenMDW-1.1 at the exact revisions above. This model-weight
license statement applies to those two model artifacts only.

No existing Dictation `.so` is a dependency of `libdictai_meeting.so`.
