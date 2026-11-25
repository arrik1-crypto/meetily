#!/usr/bin/env python3
"""Check that the native libraries in a built APK or AAB load on 16 KB pages.

Google Play requires apps targeting Android 15+ to support devices with a
16 KB memory page size. A shared library whose LOAD segments are aligned
below 16 KB (ELF p_align < 0x4000) fails to load on such a device: for Recap
that is an UnsatisfiedLinkError the first time an engine is used, not a build
error. check_sherpa_jni.py checks the prebuilt sherpa-onnx libraries before
the build; this checks every .so that actually went into the bundle or APK,
the app's own engine libraries and other libraries' (ML Kit) included.

For each input, every lib/<abi>/*.so (APKs) or base/lib/<abi>/*.so (AABs) is
read, and each PT_LOAD segment must have a power-of-two p_align of at least
0x4000 and a file offset congruent to its address modulo p_align.

--abi limits the check to the ABIs named (16 KB pages exist only on 64-bit
devices, so arm64-v8a and x86_64), and each of them must have at least one
library across all inputs of the run, so a bundle that lost an ABI fails
too. Libraries of other ABIs are then listed, not checked: a 32-bit ABI in
the bundle comes from a library's AAR, and Recap's own engines are not built
for it. Without --abi every library is checked.

    python3 android/scripts/check_16kb_alignment.py --self-test
    python3 android/scripts/check_16kb_alignment.py \\
        --abi arm64-v8a --abi x86_64 app-release.aab

Exit status: 0 when every checked library passes, 1 when any check fails, 2
on a usage error (no input, an unreadable archive, no libraries at all).
"""

import argparse
import io
import re
import struct
import sys
import zipfile

MIN_ALIGN = 0x4000
PT_LOAD = 1

# lib/<abi>/<file>.so anywhere in the archive: APKs keep libraries at lib/,
# AABs at base/lib/ (and a feature module at <module>/lib/).
LIB_PATH = re.compile(r"(?:^|/)lib/(?P<abi>[^/]+)/(?P<file>[^/]+\.so)$")


class UsageError(Exception):
    pass


def load_segments(data):
    """(p_offset, p_vaddr, p_align) of each PT_LOAD segment of an ELF image."""
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    if data[5] != 1:
        raise ValueError("big-endian ELF is not supported")
    if data[4] == 2:
        phoff = struct.unpack_from("<Q", data, 0x20)[0]
        phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
        fmt, fields = "<IIQQQQQQ", (2, 3, 7)
    elif data[4] == 1:
        phoff = struct.unpack_from("<I", data, 0x1C)[0]
        phentsize, phnum = struct.unpack_from("<HH", data, 0x2A)
        fmt, fields = "<IIIIIIII", (1, 2, 7)
    else:
        raise ValueError("unknown ELF class")
    segments = []
    for k in range(phnum):
        entry = struct.unpack_from(fmt, data, phoff + k * phentsize)
        if entry[0] == PT_LOAD:
            segments.append(tuple(entry[i] for i in fields))
    return segments


def problems_in(name, data):
    """Why the library [name] would not load on 16 KB pages; empty when it would."""
    try:
        segments = load_segments(data)
    except (ValueError, struct.error) as e:
        return [f"FAIL: {name}: {e}"]
    if not segments:
        return [f"FAIL: {name}: no LOAD segments"]
    found = []
    for k, (offset, vaddr, align) in enumerate(segments):
        if align < MIN_ALIGN or align & (align - 1):
            found.append(f"FAIL: {name}: LOAD segment {k} has p_align {align:#x}; "
                         f"16 KB pages need >= {MIN_ALIGN:#x}")
        elif (offset - vaddr) % align:
            found.append(f"FAIL: {name}: LOAD segment {k}: offset and address "
                         f"disagree modulo p_align")
    return found


def check_archive(path, abis, seen, label=None):
    """
    Problems in the libraries of the APK or AAB at [path], of the ABIs in
    [abis] (every ABI when empty). Adds each ABI checked to [seen]. Returns
    (problems, libraries checked).
    """
    label = label or str(path)
    try:
        archive = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as e:
        raise UsageError(f"{label}: {e}")
    found = []
    count = 0
    with archive:
        for entry in sorted(archive.namelist()):
            match = LIB_PATH.search(entry)
            if not match:
                continue
            abi = match.group("abi")
            if abis and abi not in abis:
                print(f"  --  {entry} (not checked: {abi} is not a 16 KB-page ABI here)")
                continue
            count += 1
            seen.add(abi)
            data = archive.read(entry)
            issues = problems_in(f"{label}: {entry}", data)
            if not issues:
                aligns = ", ".join(hex(a) for _, _, a in load_segments(data))
                print(f"  ok  {entry} (LOAD p_align {aligns})")
            found += issues
    print(f"{label}: {count} native libraries checked")
    return found, count


# ---------------------------------------------------------------------------
# Self-test: hand-built ELF images, good and bad, in a zip laid out like an AAB.


def fake_elf(aligns, elf64=True, skew=0):
    """A minimal little-endian ELF with one PT_LOAD per entry of [aligns]."""
    if elf64:
        header_size, ph_size, ph_fmt = 64, 56, "<IIQQQQQQ"
    else:
        header_size, ph_size, ph_fmt = 52, 32, "<IIIIIIII"
    out = bytearray(header_size + ph_size * len(aligns))
    out[:4] = b"\x7fELF"
    out[4] = 2 if elf64 else 1
    out[5] = 1
    if elf64:
        struct.pack_into("<Q", out, 0x20, header_size)
        struct.pack_into("<HH", out, 0x36, ph_size, len(aligns))
    else:
        struct.pack_into("<I", out, 0x1C, header_size)
        struct.pack_into("<HH", out, 0x2A, ph_size, len(aligns))
    for k, align in enumerate(aligns):
        offset = k * 0x10000
        vaddr = offset + skew
        if elf64:
            entry = (PT_LOAD, 5, offset, vaddr, vaddr, 0x100, 0x100, align)
        else:
            entry = (PT_LOAD, offset, vaddr, vaddr, 0x100, 0x100, 5, align)
        struct.pack_into(ph_fmt, out, header_size + k * ph_size, *entry)
    return bytes(out)


def self_test():
    cases = [
        ("16 KB, 64-bit", fake_elf([0x4000, 0x4000]), True),
        ("64 KB, 64-bit", fake_elf([0x10000]), True),
        ("16 KB, 32-bit", fake_elf([0x4000], elf64=False), True),
        ("4 KB", fake_elf([0x4000, 0x1000]), False),
        ("not a power of two", fake_elf([0x6000]), False),
        ("offset skewed", fake_elf([0x4000], skew=0x1000), False),
        ("not ELF", b"MZ" + bytes(100), False),
        ("no LOAD", fake_elf([]), False),
    ]
    failed = []
    for label, data, passes in cases:
        if (not problems_in(label, data)) != passes:
            failed.append(label)
    # An AAB-shaped archive: a good arm64 library, a bad x86_64 one, and a
    # 4 KB 32-bit one that is listed but not checked.
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        z.writestr("base/lib/arm64-v8a/libgood.so", fake_elf([0x4000]))
        z.writestr("base/lib/x86_64/libbad.so", fake_elf([0x1000]))
        z.writestr("base/lib/armeabi-v7a/libold.so", fake_elf([0x1000], elf64=False))
        z.writestr("base/manifest/AndroidManifest.xml", b"")
    seen = set()
    buffer.seek(0)
    issues, count = check_archive(buffer, {"arm64-v8a", "x86_64"}, seen, "self-test.aab")
    if count != 2 or len(issues) != 1 or "libbad.so" not in issues[0] \
            or seen != {"arm64-v8a", "x86_64"}:
        failed.append("archive, 64-bit ABIs")
    buffer.seek(0)
    issues, count = check_archive(buffer, set(), set(), "self-test.aab")
    if count != 3 or len(issues) != 2:
        failed.append("archive, every ABI")
    if failed:
        print("self-test FAILED: " + ", ".join(failed))
        return 1
    print(f"self-test passed ({len(cases)} images, 1 archive)")
    return 0


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("inputs", nargs="*", help="APK or AAB files")
    parser.add_argument("--abi", action="append", default=[],
                        help="an ABI to check, which must have libraries across the inputs "
                             "(repeatable; default: every ABI, none required)")
    parser.add_argument("--self-test", action="store_true",
                        help="check the checker against built-in ELF images")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.inputs:
        print("error: no APK or AAB given", file=sys.stderr)
        return 2
    abis = set(args.abi)
    problems = []
    total = 0
    seen = set()
    try:
        for path in args.inputs:
            issues, count = check_archive(path, abis, seen)
            problems += issues
            total += count
    except UsageError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    for abi in sorted(abis - seen):
        problems.append(f"FAIL: no {abi} libraries in the inputs")
    if total == 0 and not problems:
        print("error: no native libraries found in the inputs", file=sys.stderr)
        return 2
    for line in problems:
        print(line)
    if problems:
        print(f"{len(problems)} problem(s): a library above would not load on a "
              f"16 KB-page device, or an ABI is missing")
        return 1
    print(f"all {total} libraries checked are 16 KB-aligned ({', '.join(sorted(seen))})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
