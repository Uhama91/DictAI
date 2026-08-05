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
ALLOWED_GGML_LIBRARY_NAMES = frozenset(
    {
        "libggml.so",
        "libggml-base.so",
        "libggml-cpu.so",
    }
)
REQUIRED_TRANSCRIBE_BUNDLE_NAMES = ALLOWED_GGML_LIBRARY_NAMES | {
    "libtranscribe.so",
    "libtranscribe_jni.so",
}


def apk_library_abi_and_name(path: str) -> tuple[str, str] | None:
    parts = path.split("/")
    if len(parts) != 3 or parts[0] != "lib":
        return None
    return parts[1], parts[2]


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

        library_names_by_abi: dict[str, set[str]] = {}
        for lib in libs:
            checked += 1
            info = apk.getinfo(lib)
            if info.compress_type != zipfile.ZIP_STORED:
                failures.append(f"{lib}: ZIP entry is not stored")

            abi_and_name = apk_library_abi_and_name(lib)
            if abi_and_name is not None:
                abi, name = abi_and_name
                library_names_by_abi.setdefault(abi, set()).add(name)
            else:
                name = lib.rsplit("/", 1)[-1]

            lowercase_path = lib.lower()
            if "llama" in lowercase_path:
                failures.append(f"{lib}: forbidden llama native library")
            elif "ggml" in lowercase_path and (
                abi_and_name is None or name not in ALLOWED_GGML_LIBRARY_NAMES
            ):
                failures.append(f"{lib}: forbidden ggml native library")
            try:
                alignments = load_alignments(apk.read(lib))
            except (ValueError, struct.error) as error:
                failures.append(f"{lib}: invalid ELF: {error}")
                continue
            if any(alignment < MIN_ALIGN for alignment in alignments):
                failures.append(f"{lib}: PT_LOAD p_align={alignments}")

        for abi, names in sorted(library_names_by_abi.items()):
            if names & ALLOWED_GGML_LIBRARY_NAMES:
                missing = sorted(REQUIRED_TRANSCRIBE_BUNDLE_NAMES - names)
                if missing:
                    failures.append(
                        f"lib/{abi}: incomplete transcribe GGML bundle; missing {', '.join(missing)}"
                    )

    if failures:
        print("Native library checks failed:")
        for failure in failures:
            print(f"  - {failure}")
        return 1

    print(f"All {checked} native libraries have 16 KB ELF alignment, are stored, and are allowed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
