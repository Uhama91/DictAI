# Gemma 4 Q6 evaluation package

This package contains the frozen native evaluation client, its V6 source-last prompt
helpers and the exact synthetic selection/valid inputs. It contains no model weights,
GGUF, train/test/holdout data, private recordings or secrets. The client is invoked
with explicit paths and SHA-256 values by the Linux wrapper; it is byte-identical to
the reviewed ASR client.

The tokenizer archive and Q6 model parts are staged outside Git under the ASR report
area by the distribution preparer. The workflow verifies their manifests before
assembly and never creates a release or uploads an asset.
