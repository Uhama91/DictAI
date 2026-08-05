#!/usr/bin/env python3
"""Check APK native libraries for 16 KB ELF alignment, ZIP_STORED entries, and denylisted names."""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile


ELF_MAGIC = b"\x7fELF"
PT_LOAD = 1
MIN_ALIGN = 16 * 1024
FORBIDDEN_NAME_PARTS = ("ggml", "llama")


def load_alignments(data: bytes) -> list[int]:
    if len(data) < 64:
        raise ValueError("truncated ELF header")
    if not data.startswith(ELF_MAGIC):
        raise ValueError("not an ELF file")
    if data[4] != 2:
        raise ValueError("not an ELF64 file")

    if data[5] == 1:
        endian = "<"
    elif data[5] == 2:
        endian = ">"
    else:
        raise ValueError("unsupported ELF byte order")

    e_phoff = struct.unpack_from(endian + "Q", data, 32)[0]
    e_phentsize = struct.unpack_from(endian + "H", data, 54)[0]
    e_phnum = struct.unpack_from(endian + "H", data, 56)[0]
    if e_phentsize < 56:
        raise ValueError("invalid program header size")

    alignments: list[int] = []
    for i in range(e_phnum):
        offset = e_phoff + i * e_phentsize
        if offset > len(data) - 56:
            raise ValueError(f"truncated program header {i}")
        p_type = struct.unpack_from(endian + "I", data, offset)[0]
        if p_type == PT_LOAD:
            alignments.append(struct.unpack_from(endian + "Q", data, offset + 48)[0])
    if not alignments:
        raise ValueError("no PT_LOAD segment")
    return sorted(set(alignments))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", help="APK to inspect")
    args = parser.parse_args()

    failures: list[str] = []
    checked = 0

    with zipfile.ZipFile(args.apk) as apk:
        libs = sorted(n for n in apk.namelist() if n.startswith("lib/") and n.endswith(".so"))
        if not libs:
            failures.append("APK contains no native libraries")
        for lib in libs:
            checked += 1
            info = apk.getinfo(lib)
            if info.compress_type != zipfile.ZIP_STORED:
                failures.append(f"{lib}: ZIP entry is not stored")
            if any(part in lib.lower() for part in FORBIDDEN_NAME_PARTS):
                failures.append(f"{lib}: forbidden ggml/llama native library")
            try:
                alignments = load_alignments(apk.read(lib))
            except (ValueError, struct.error) as error:
                failures.append(f"{lib}: invalid ELF: {error}")
                continue
            if any(alignment < MIN_ALIGN for alignment in alignments):
                failures.append(f"{lib}: PT_LOAD p_align={alignments}")

    if failures:
        print("Native library checks failed:")
        for failure in failures:
            print(f"  - {failure}")
        return 1

    print(f"All {checked} native libraries have 16 KB ELF alignment, are stored, and are allowed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
