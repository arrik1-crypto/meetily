#!/usr/bin/env python3
"""Check what Recap's JNI library exports, in a built APK or AAB.

app/src/main/cpp/jni_exports.map hides every symbol of libmeetily_native
(and of its "_v82" build) except the JNI entry points. Getting that wrong
fails in one of two ways, and neither shows up at build time:

  - a Java_ function the Kotlin side calls is hidden: UnsatisfiedLinkError
    at its first call, on a phone;
  - the map stops being applied: thousands of ggml, llama and libc++
    symbols are exported again, and with them the slower load and the code
    --gc-sections cannot drop.

So for every lib<base>*.so found under lib/<abi>/ in each input, this fails
when (a) a symbol jni_exports.txt lists for <base> is not exported, or
(b) anything is exported that the map's global patterns do not allow. It
also fails when an arm64-v8a input has lib<base>.so without its fast
lib<base>_v82.so: every phone would then run the baseline build, silently.

The expected list is checked in rather than parsed here from Kotlin or C.
NativeExportsTest keeps it equal to the natives of the compiled bridge
classes (exact JVM names, by reflection, which a regex over Kotlin source
cannot promise) and to the Java_ functions in the C sources. CI runs the
unit tests before assembleRelease, so by the time this runs the list is
known to match both sides.

Exports are listed with llvm-nm -D --defined-only: --nm, then $LLVM_NM, then
the NDK pinned in app/build.gradle.kts (under $ANDROID_HOME or
$ANDROID_SDK_ROOT), then $ANDROID_NDK_HOME / $ANDROID_NDK_ROOT, then any
installed NDK, then llvm-nm on PATH. Without one, a built-in .dynsym reader
gives the same list. Section sizes always come from the built-in reader.

    python3 android/scripts/check_jni_exports.py app-arm64-v8a-release.apk
    python3 android/scripts/check_jni_exports.py --baseline old.apk new.apk
    python3 android/scripts/check_jni_exports.py --symbols native-debug-symbols.zip new.apk

Every library's size is printed: "file" is the .so itself (what an install
extracts or maps) and "zipped" what it takes inside the archive (the
sideload APK compresses native code; the Play bundle stores it uncompressed
and Play compresses the download). Per ABI the script then totals our
libraries and the other native libraries beside them.

--baseline adds the same figures for an earlier build, for a before/after
comparison in the log; it never affects the result. Its totals also count
the two libraries the engines were built as until they were merged
(libmeetily_whisper and libmeetily_llama, and their "_v82" builds), so the
saving from linking ggml and libc++ once per CPU level can be read off the
log. Apart from the arm64-v8a fast build above, a library missing for an
ABI is reported, not failed (the "_v82" build exists for arm64-v8a only);
an input with none of these libraries at all is an error.

--symbols names the native-debug-symbols.zip of the same build. The
libraries export no engine function names, so a native crash is only
readable with it. Each of our libraries' symbol file is listed, and a
missing one is a warning, never a failure.

Exit status: 0 when every library passes, 1 when any check fails, 2 on a
usage or environment error.
"""

import argparse
import fnmatch
import glob
import os
import pathlib
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
CPP = ROOT / "app" / "src" / "main" / "cpp"
DEFAULT_EXPECTED = CPP / "jni_exports.txt"
DEFAULT_MAP = CPP / "jni_exports.map"
GRADLE_FILE = ROOT / "app" / "build.gradle.kts"

# lib/<abi>/lib<name>.so anywhere in an archive or directory tree: APKs keep
# libraries at lib/, AABs at base/lib/.
LIB_PATH = re.compile(r"(?:^|/)lib/(?P<abi>[^/]+)/lib(?P<name>[^/]+)\.so$")

# What the engines were built as before they became one library per CPU
# level. Only --baseline inputs are searched for these, for the size totals.
LEGACY_BASES = ("meetily_whisper", "meetily_llama")

# The build :native-v82 adds on arm64-v8a, for every base in jni_exports.txt.
FAST_ABI = "arm64-v8a"
FAST_SUFFIX = "_v82"


class UsageError(Exception):
    pass


# ---------------------------------------------------------------------------
# Inputs: the expected list and the version script


def read_expected(path):
    """{library base name: {symbol}} from jni_exports.txt."""
    expected = {}
    for lineno, raw in enumerate(path.read_text().splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 2 or not parts[1].startswith("Java_"):
            raise UsageError(f"{path}:{lineno}: expected '<library> Java_...', got {raw!r}")
        expected.setdefault(parts[0], set()).add(parts[1])
    if not expected:
        raise UsageError(f"{path}: no symbols listed")
    return expected


def read_version_script(path):
    """The global patterns of a single-node version script.

    Supports what jni_exports.map uses: one node, anonymous or named, with
    global: and local: lists of names or glob patterns. Anything fancier
    (extern "C++" blocks, several nodes) is refused rather than misread.
    """
    text = path.read_text()
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"#[^\n]*", " ", text)
    if "extern" in text:
        raise UsageError(f"{path}: extern blocks are not supported by this check")
    nodes = re.findall(r"\{(.*?)\}", text, flags=re.S)
    if len(nodes) != 1:
        raise UsageError(f"{path}: expected exactly one version node, found {len(nodes)}")
    body = nodes[0]
    m = re.search(r"\bglobal\s*:(.*?)(?:\blocal\s*:|$)", body, flags=re.S)
    if not m:
        raise UsageError(f"{path}: no global: section")
    patterns = [p.strip() for p in m.group(1).split(";") if p.strip()]
    if not patterns:
        raise UsageError(f"{path}: empty global: section")
    return patterns


# ---------------------------------------------------------------------------
# Finding llvm-nm


def pinned_ndk_version():
    try:
        m = re.search(r'ndkVersion\s*=\s*"([^"]+)"', GRADLE_FILE.read_text())
    except OSError:
        return None
    return m.group(1) if m else None


def nm_in_ndk(ndk_dir):
    if not ndk_dir:
        return None
    hits = sorted(glob.glob(os.path.join(ndk_dir, "toolchains", "llvm", "prebuilt", "*",
                                         "bin", "llvm-nm*")))
    hits = [h for h in hits if os.path.basename(h) in ("llvm-nm", "llvm-nm.exe")]
    return hits[0] if hits else None


def find_nm(explicit):
    if explicit:
        if not (os.path.isfile(explicit) and os.access(explicit, os.X_OK)):
            raise UsageError(f"--nm {explicit}: not an executable file")
        return explicit
    env = os.environ.get("LLVM_NM")
    if env and os.path.isfile(env):
        return env
    sdks = [os.environ.get(k) for k in ("ANDROID_HOME", "ANDROID_SDK_ROOT")]
    sdks = [s for s in sdks if s]
    version = pinned_ndk_version()
    candidates = []
    if version:
        candidates += [os.path.join(s, "ndk", version) for s in sdks]
    candidates += [os.environ.get(k) for k in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT")]
    for s in sdks:
        candidates += sorted(glob.glob(os.path.join(s, "ndk", "*")), reverse=True)
    for ndk in candidates:
        hit = nm_in_ndk(ndk)
        if hit:
            return hit
    return shutil.which("llvm-nm")


def nm_exports(nm, so_path):
    """Defined dynamic symbols, as llvm-nm -D --defined-only lists them."""
    proc = subprocess.run([nm, "-D", "--defined-only", so_path],
                          capture_output=True, text=True)
    if proc.returncode != 0:
        raise UsageError(f"{nm} failed on {so_path}: {proc.stderr.strip()}")
    names = set()
    for line in proc.stdout.splitlines():
        parts = line.split()
        if len(parts) < 2:
            continue
        # "<address> <type> <name>[@version]"; versions only appear when a
        # named version node is used, and are not part of the name.
        names.add(parts[-1].split("@", 1)[0])
    return names


# ---------------------------------------------------------------------------
# A minimal ELF reader: section sizes, and the fallback symbol list


def read_elf(data):
    """(section sizes by name, defined .dynsym names) of an ELF shared object."""
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    if data[5] != 1:
        raise ValueError("big-endian ELF is not supported")
    if data[4] == 2:
        hdr = struct.unpack_from("<16sHHIQQQIHHHHHH", data, 0)
        sh_fmt, sym_fmt = "<IIQQQQIIQQ", "<IBBHQQ"
        sym_name, sym_shndx = 0, 3
    elif data[4] == 1:
        hdr = struct.unpack_from("<16sHHIIIIIHHHHHH", data, 0)
        sh_fmt, sym_fmt = "<IIIIIIIIII", "<IIIBBH"
        sym_name, sym_shndx = 0, 5
    else:
        raise ValueError("unknown ELF class")
    shoff, shentsize, shnum, shstrndx = hdr[6], hdr[11], hdr[12], hdr[13]
    if not shoff or not shnum:
        raise ValueError("no section headers")
    sections = [struct.unpack_from(sh_fmt, data, shoff + i * shentsize) for i in range(shnum)]

    def cstr(table_off, off):
        end = data.index(b"\0", table_off + off)
        return data[table_off + off:end].decode("utf-8", "replace")

    shstr = sections[shstrndx]
    sizes, dynsym = {}, None
    for sh in sections:
        name = cstr(shstr[4], sh[0])
        sizes[name] = sh[5]
        if sh[1] == 11:  # SHT_DYNSYM
            dynsym = sh
    names = set()
    if dynsym is not None:
        strtab = sections[dynsym[6]]
        entsize = dynsym[9] or struct.calcsize(sym_fmt)
        for i in range(1, dynsym[5] // entsize):
            sym = struct.unpack_from(sym_fmt, data, dynsym[4] + i * entsize)
            if sym[sym_shndx] != 0:  # SHN_UNDEF: imported, not exported
                names.add(cstr(strtab[4], sym[sym_name]))
    return sizes, names


# ---------------------------------------------------------------------------
# Enumerating libraries


def base_of(name, bases):
    """The expected base library a file name belongs to: itself, or a build
    of it with a suffix (meetily_native_v82 -> meetily_native)."""
    best = None
    for b in bases:
        if name == b or name.startswith(b + "_"):
            if best is None or len(b) > len(best):
                best = b
    return best


def libraries(path, bases):
    """Our libraries in an APK/AAB/zip or a directory tree, as
    [(abi, file name, base, bytes, zipped size)]; the set of ABIs seen at
    all; and every other native library, as [(abi, file name, size, zipped
    size)]. The zipped size is None for a directory."""
    found, abis, others = [], set(), []

    def scan(names, read, size, zipped):
        for rel in names:
            m = LIB_PATH.search(rel)
            if not m:
                continue
            abi, fname = m.group("abi"), f"lib{m.group('name')}.so"
            abis.add(abi)
            base = base_of(m.group("name"), bases)
            if base:
                found.append((abi, fname, base, read(rel), zipped(rel)))
            else:
                others.append((abi, fname, size(rel), zipped(rel)))

    p = pathlib.Path(path)
    if not p.exists():
        raise UsageError(f"{path}: not found")
    if p.is_dir():
        names = sorted(f.relative_to(p).as_posix() for f in p.rglob("*.so"))
        scan(names, lambda rel: (p / rel).read_bytes(),
             lambda rel: (p / rel).stat().st_size, lambda rel: None)
    elif zipfile.is_zipfile(p):
        with zipfile.ZipFile(p) as zf:
            scan(sorted(zf.namelist()), zf.read,
                 lambda rel: zf.getinfo(rel).file_size,
                 lambda rel: zf.getinfo(rel).compress_size)
    else:
        raise UsageError(f"{path}: not an APK/AAB/zip or a directory")
    return found, abis, others


def inspect(label, blob, nm, tmp):
    try:
        sizes, elf_names = read_elf(blob)
    except (ValueError, struct.error) as e:
        raise UsageError(f"{label}: {e}")
    if nm is None:
        return sizes, elf_names
    so = os.path.join(tmp, "lib.so")
    with open(so, "wb") as f:
        f.write(blob)
    return sizes, nm_exports(nm, so)


# ---------------------------------------------------------------------------
# Report


SHOWN = [".dynsym", ".dynstr", ".gnu.hash", "relocs", ".text"]


def fmt_row(label, exports, java, sizes, file_size, zipped):
    cols = [f"{label:<38}", f"{exports:>7}", f"{java:>6}"]
    cols += [f"{sizes[s]:>10}" if s in sizes else f"{'-':>10}" for s in SHOWN]
    cols.append(f"{file_size:>10}")
    cols.append(f"{zipped:>10}" if zipped is not None else f"{'-':>10}")
    return "  " + " ".join(cols)


def shown_sizes(sizes):
    """The reported columns; "relocs" sums whichever relocation sections the
    linker emitted (.rela.dyn, .relr.dyn, .rela.plt, or their REL forms)."""
    out = {k: sizes[k] for k in (".dynsym", ".dynstr", ".gnu.hash", ".text") if k in sizes}
    relocs = [sizes[k] for k in (".rela.dyn", ".rel.dyn", ".relr.dyn", ".rela.plt", ".rel.plt")
              if k in sizes]
    if relocs:
        out["relocs"] = sum(relocs)
    return out


def header():
    cols = [f"{'library (sizes in bytes)':<38}", f"{'exports':>7}", f"{'Java_':>6}"]
    cols += [f"{s:>10}" for s in SHOWN] + [f"{'file':>10}", f"{'zipped':>10}"]
    return "  " + " ".join(cols)


def totals(rows):
    """{abi: (count, file bytes, zipped bytes or None)} over (abi, file size,
    zipped size) rows."""
    out = {}
    for abi, size, zipped in rows:
        n, f, z = out.get(abi, (0, 0, 0))
        z = None if (z is None or zipped is None) else z + zipped
        out[abi] = (n + 1, f + size, z)
    return out


def change(new, old):
    """'+1,234 (+5.0%)', or '-' when either side is unknown."""
    if old is None or new is None:
        return "-"
    pct = f" ({(new - old) * 100.0 / old:+.1f}%)" if old else ""
    return f"{new - old:+,}{pct}"


def count(n):
    return f"{n} librar{'y' if n == 1 else 'ies'}"


def report_totals(ours, others, before, label):
    """Per-ABI totals of our libraries, the baseline's, and the others'."""
    print("  per ABI, bytes (zipped = as stored in the archive):")
    mine, rest = totals(ours), totals(others)
    for abi in sorted(set(mine) | set(rest)):
        n, f, z = mine.get(abi, (0, 0, None))
        print(f"    {abi}: ours {count(n)}, file {f:,}, zipped "
              f"{'-' if z is None else f'{z:,}'}")
        old = before.get(abi)
        if old:
            on, of, oz = old
            print(f"      before ({label}): {count(on)}, file {of:,}, zipped "
                  f"{'-' if oz is None else f'{oz:,}'}")
            print(f"      change: file {change(f, of)}, zipped {change(z, oz)}")
        if abi in rest:
            rn, rf, rz = rest[abi]
            print(f"      other native code: {count(rn)}, file {rf:,}, zipped "
                  f"{'-' if rz is None else f'{rz:,}'}")


def symbol_files(path):
    """{(abi, file name): size} in a native-debug-symbols.zip, or in a
    directory laid out like one (<abi>/lib<name>.so.sym, or .so.dbg)."""
    p = pathlib.Path(path)
    out = {}
    if p.is_dir():
        for f in p.rglob("*"):
            parts = f.relative_to(p).parts
            if f.is_file() and len(parts) >= 2:
                out[(parts[-2], parts[-1])] = f.stat().st_size
    elif zipfile.is_zipfile(p):
        with zipfile.ZipFile(p) as zf:
            for info in zf.infolist():
                parts = info.filename.rstrip("/").split("/")
                if not info.is_dir() and len(parts) >= 2:
                    out[(parts[-2], parts[-1])] = info.file_size
    else:
        raise UsageError(f"{path}: not a zip or a directory")
    return out


def report_symbols(paths, libs):
    """The symbol file found for each (abi, library file name) in [libs]."""
    seen = {}
    for path in paths:
        if not os.path.exists(path):
            print(f"\ncheck_jni_exports: symbols {path} not found")
            continue
        seen.update(symbol_files(path))
    if not paths:
        return
    print("\nnative debug symbols")
    missing = []
    for abi, fname in sorted(libs):
        hits = sorted((n, size) for (a, n), size in seen.items()
                      if a == abi and n in (fname, fname + ".sym", fname + ".dbg"))
        if hits:
            print("  " + ", ".join(f"{abi}/{n} {size:,}" for n, size in hits))
        else:
            missing.append(f"{abi}/{fname}")
    for m in missing:
        print(f"  note: no symbol file for {m}")
    if missing and os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::warning::check_jni_exports: no native debug symbols for "
              f"{', '.join(missing)}; their crashes cannot be symbolicated")


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("inputs", nargs="+", help="APK, AAB or zip files, or directories")
    ap.add_argument("--expected", type=pathlib.Path, default=DEFAULT_EXPECTED)
    ap.add_argument("--version-script", type=pathlib.Path, default=DEFAULT_MAP)
    ap.add_argument("--nm", help="llvm-nm to use (default: found automatically)")
    ap.add_argument("--baseline", action="append", default=[],
                    help="an earlier APK/AAB to compare against (report only)")
    ap.add_argument("--symbols", action="append", default=[],
                    help="the build's native-debug-symbols.zip (report only)")
    args = ap.parse_args(argv)

    try:
        expected = read_expected(args.expected)
        allowed = read_version_script(args.version_script)
        nm = find_nm(args.nm)
        print(f"check_jni_exports: allowed exports {' '.join(allowed)}")
        print(f"check_jni_exports: listing with {nm or 'the built-in .dynsym reader (no llvm-nm found)'}")
        if nm is None and os.environ.get("GITHUB_ACTIONS") == "true":
            # Same result (the reader matches llvm-nm -D --defined-only), but
            # a runner without the NDK's llvm-nm is worth knowing about.
            print("::warning::check_jni_exports: no llvm-nm found under ANDROID_HOME/ndk; "
                  "used the built-in .dynsym reader")

        baseline, before, before_label = {}, {}, None
        with tempfile.TemporaryDirectory() as tmp:
            for b in args.baseline:
                if not os.path.exists(b):
                    print(f"check_jni_exports: baseline {b} not found, skipped")
                    continue
                # A baseline only adds "before" rows, so one that cannot be
                # read (a truncated committed APK, an ELF llvm-nm rejects) is
                # skipped, never a reason to fail a good build.
                rows, sized = {}, []
                try:
                    found = libraries(b, set(expected) | set(LEGACY_BASES))[0]
                    for abi, fname, _base, blob, zipped in found:
                        sizes, names = inspect(f"{b}: {abi}/{fname}", blob, nm, tmp)
                        rows[(abi, fname)] = (os.path.basename(b), sizes, names, len(blob), zipped)
                        sized.append((abi, len(blob), zipped))
                except (UsageError, OSError, EOFError, zipfile.BadZipFile, zlib.error) as e:
                    print(f"check_jni_exports: baseline skipped: {e}")
                    continue
                baseline.update(rows)
                # The totals compare against the first readable baseline.
                if before_label is None:
                    before, before_label = totals(sized), os.path.basename(b)

            failures = 0
            checked = set()
            for path in args.inputs:
                libs, abis, others = libraries(path, expected)
                print(f"\n{path}")
                if not libs:
                    raise UsageError(f"{path}: no lib{{{','.join(sorted(expected))}}}*.so under lib/<abi>/")
                print(header())
                problems = []
                for abi in sorted(abis):
                    files = {f for a, f, _base, _b, _z in libs if a == abi}
                    present = {base for a, _f, base, _b, _z in libs if a == abi}
                    for base in sorted(set(expected) - present):
                        problems.append(f"note: {abi} has no lib{base}.so (not checked)")
                    if abi != FAST_ABI:
                        continue
                    for base in sorted(present):
                        fast = f"lib{base}{FAST_SUFFIX}.so"
                        if f"lib{base}.so" in files and fast not in files:
                            problems.append(f"FAIL: {abi} has lib{base}.so but no {fast}: "
                                            f"every phone would run the baseline build")
                            failures += 1
                for abi, fname, base, blob, zipped in libs:
                    checked.add((abi, fname))
                    sizes, names = inspect(f"{path}: {abi}/{fname}", blob, nm, tmp)
                    java = sum(1 for n in names if n.startswith("Java_"))
                    print(fmt_row(f"{abi}/{fname}", len(names), java,
                                  shown_sizes(sizes), len(blob), zipped))
                    old = baseline.get((abi, fname))
                    if old:
                        label, osizes, onames, olen, ozipped = old
                        ojava = sum(1 for n in onames if n.startswith("Java_"))
                        print(fmt_row(f"  before ({label})", len(onames), ojava,
                                      shown_sizes(osizes), olen, ozipped))
                    missing = sorted(expected[base] - names)
                    leaked = sorted(n for n in names
                                    if not any(fnmatch.fnmatchcase(n, p) for p in allowed))
                    unlisted = sorted(n for n in names
                                      if n.startswith("Java_") and n not in expected[base])
                    for n in missing:
                        problems.append(f"FAIL: {abi}/{fname} does not export {n}")
                    if leaked:
                        shown = ", ".join(leaked[:10]) + (" ..." if len(leaked) > 10 else "")
                        problems.append(f"FAIL: {abi}/{fname} exports {len(leaked)} symbol(s) "
                                        f"the version script does not allow: {shown}")
                    for n in unlisted:
                        problems.append(f"note: {abi}/{fname} exports {n}, which "
                                        f"jni_exports.txt does not list")
                    if missing or leaked:
                        failures += 1
                for p in problems:
                    print(f"  {p}")
                report_totals([(a, len(b), z) for a, _f, _base, b, z in libs],
                              [(a, size, z) for a, _f, size, z in others],
                              before, before_label)
            report_symbols(args.symbols, checked)
    except UsageError as e:
        print(f"check_jni_exports: {e}", file=sys.stderr)
        return 2

    if failures:
        print(f"\ncheck_jni_exports: {failures} check{'' if failures == 1 else 's'} failed. "
              f"Exports are set by app/src/main/cpp/jni_exports.map (applied in CMakeLists.txt); "
              f"the expected list is jni_exports.txt beside it; the {FAST_SUFFIX} build comes "
              f"from the :native-v82 module.")
        return 1
    print("\ncheck_jni_exports: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
