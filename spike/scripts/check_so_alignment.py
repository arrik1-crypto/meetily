#!/usr/bin/env python3
"""Is liblitertlm_jni.so loadable on a 16 KB page device?

Android 15+ devices can run a 16 KB page kernel, and a shared library whose
LOAD segments are aligned to the older 4 KB boundary will not dlopen there.
Recap's own native libraries pass -Wl,-z,max-page-size=16384 for exactly
that reason (see app/src/main/cpp/CMakeLists.txt), but liblitertlm_jni.so
is a Google prebuilt inside the AAR and nothing app-side controls it.

This matters because the LiteRT sandbox process dies silently on a Pixel 10
the moment the runtime is touched, with no exception reaching the app. A
4 KB-aligned library would explain that completely, and would be a hard
blocker rather than a bug to fix.

Reads the ELF program headers directly rather than shelling out to readelf,
so it does not depend on which binutils the runner happens to have.
"""

import struct
import sys
from pathlib import Path

PT_LOAD = 1


def load_alignments(path: Path):
    """Alignment of every PT_LOAD segment in a 64-bit little-endian ELF."""
    data = path.read_bytes()
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    if data[4] != 2:
        raise ValueError("not 64-bit")
    if data[5] != 1:
        raise ValueError("not little-endian")

    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum = struct.unpack_from("<H", data, 0x38)[0]

    alignments = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        if p_type != PT_LOAD:
            continue
        p_align = struct.unpack_from("<Q", data, off + 0x30)[0]
        alignments.append(p_align)
    return alignments


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: check_so_alignment.py <path-to-.so> [...]")
        return 2

    worst_ok = True
    for arg in sys.argv[1:]:
        path = Path(arg)
        print(f"=== {path} ===")
        if not path.is_file():
            print("  not found — nothing to check")
            continue
        try:
            aligns = load_alignments(path)
        except Exception as e:
            print(f"  could not read: {e}")
            continue
        if not aligns:
            print("  no PT_LOAD segments found")
            continue

        for a in aligns:
            print(f"  LOAD align {hex(a)} ({a // 1024} KB)")

        smallest = min(aligns)
        if smallest >= 0x4000:
            print(f"  OK: min alignment {hex(smallest)} — loads on 16 KB page devices")
        else:
            worst_ok = False
            print(f"  BLOCKER: min alignment {hex(smallest)} — will NOT dlopen on a")
            print("  16 KB page device. No app-side change works around this; it")
            print("  needs a rebuilt library from the publisher.")

    print()
    print("16 KB-safe" if worst_ok else "NOT 16 KB-safe")
    return 0


if __name__ == "__main__":
    sys.exit(main())
