#!/usr/bin/env python3
"""Check what Recap's JNI libraries export, in a built APK or AAB.

app/src/main/cpp/jni_exports.map hides every symbol of libmeetily_whisper
and libmeetily_llama (and their "_v82" builds) except the JNI entry points.
Getting that wrong fails in one of two ways, and neither shows up at build
time:

  - a Java_ function the Kotlin side calls is hidden: UnsatisfiedLinkError
    at its first call, on a phone;
  - the map stops being applied: thousands of ggml, llama and libc++
    symbols are exported again, and with them the slower load and the code
    --gc-sections cannot drop.

So for every lib<base>*.so found under lib/<abi>/ in each input, this fails
when (a) a symbol jni_exports.txt lists for <base> is not exported, or
(b) anything is exported that the map's global patterns do not allow.

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

--baseline adds the same figures for an earlier build, for a before/after
comparison in the log; it never affects the result. A library missing for an
ABI is reported, not failed (the "_v82" builds exist for arm64-v8a only);
an input with none of these libraries at all is an error.

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

ROOT = pathlib.Path(__file__).resolve().parent.parent
CPP = ROOT / "app" / "src" / "main" / "cpp"
DEFAULT_EXPECTED = CPP / "jni_exports.txt"
DEFAULT_MAP = CPP / "jni_exports.map"
GRADLE_FILE = ROOT / "app" / "build.gradle.kts"

# lib/<abi>/lib<name>.so anywhere in an archive or directory tree: APKs keep
# libraries at lib/, AABs at base/lib/.
LIB_PATH = re.compile(r"(?:^|/)lib/(?P<abi>[^/]+)/lib(?P<name>[^/]+)\.so$")


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
    of it with a suffix (meetily_llama_v82 -> meetily_llama)."""
    best = None
    for b in bases:
        if name == b or name.startswith(b + "_"):
            if best is None or len(b) > len(best):
                best = b
    return best


def libraries(path, bases):
    """[(abi, file name, base, bytes)] for our libraries in an APK/AAB/zip or
    a directory tree, plus the set of ABIs seen at all."""
    found, abis = [], set()

    def scan(names, read):
        for rel in names:
            m = LIB_PATH.search(rel)
            if not m:
                continue
            abis.add(m.group("abi"))
            base = base_of(m.group("name"), bases)
            if base:
                found.append((m.group("abi"), f"lib{m.group('name')}.so", base, read(rel)))

    p = pathlib.Path(path)
    if not p.exists():
        raise UsageError(f"{path}: not found")
    if p.is_dir():
        names = sorted(f.relative_to(p).as_posix() for f in p.rglob("*.so"))
        scan(names, lambda rel: (p / rel).read_bytes())
    elif zipfile.is_zipfile(p):
        with zipfile.ZipFile(p) as zf:
            scan(sorted(zf.namelist()), zf.read)
    else:
        raise UsageError(f"{path}: not an APK/AAB/zip or a directory")
    return found, abis


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


def fmt_row(label, exports, java, sizes, file_size):
    cols = [f"{label:<38}", f"{exports:>7}", f"{java:>6}"]
    cols += [f"{sizes[s]:>10}" if s in sizes else f"{'-':>10}" for s in SHOWN]
    cols.append(f"{file_size:>10}")
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
    cols += [f"{s:>10}" for s in SHOWN] + [f"{'file':>10}"]
    return "  " + " ".join(cols)


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("inputs", nargs="+", help="APK, AAB or zip files, or directories")
    ap.add_argument("--expected", type=pathlib.Path, default=DEFAULT_EXPECTED)
    ap.add_argument("--version-script", type=pathlib.Path, default=DEFAULT_MAP)
    ap.add_argument("--nm", help="llvm-nm to use (default: found automatically)")
    ap.add_argument("--baseline", action="append", default=[],
                    help="an earlier APK/AAB to compare against (report only)")
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

        baseline = {}
        with tempfile.TemporaryDirectory() as tmp:
            for b in args.baseline:
                if not os.path.exists(b):
                    print(f"check_jni_exports: baseline {b} not found, skipped")
                    continue
                for abi, fname, _base, blob in libraries(b, expected)[0]:
                    sizes, names = inspect(f"{b}: {abi}/{fname}", blob, nm, tmp)
                    baseline[(abi, fname)] = (os.path.basename(b), sizes, names, len(blob))

            failures = 0
            for path in args.inputs:
                libs, abis = libraries(path, expected)
                print(f"\n{path}")
                if not libs:
                    raise UsageError(f"{path}: no lib{{{','.join(sorted(expected))}}}*.so under lib/<abi>/")
                print(header())
                problems = []
                for abi in sorted(abis):
                    present = {base for a, _f, base, _b in libs if a == abi}
                    for base in sorted(set(expected) - present):
                        problems.append(f"note: {abi} has no lib{base}.so (not checked)")
                for abi, fname, base, blob in libs:
                    sizes, names = inspect(f"{path}: {abi}/{fname}", blob, nm, tmp)
                    java = sum(1 for n in names if n.startswith("Java_"))
                    print(fmt_row(f"{abi}/{fname}", len(names), java,
                                  shown_sizes(sizes), len(blob)))
                    old = baseline.get((abi, fname))
                    if old:
                        label, osizes, onames, olen = old
                        ojava = sum(1 for n in onames if n.startswith("Java_"))
                        print(fmt_row(f"  before ({label})", len(onames), ojava,
                                      shown_sizes(osizes), olen))
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
    except UsageError as e:
        print(f"check_jni_exports: {e}", file=sys.stderr)
        return 2

    if failures:
        print(f"\ncheck_jni_exports: {failures} librar{'y' if failures == 1 else 'ies'} failed. "
              f"Exports are set by app/src/main/cpp/jni_exports.map (applied in CMakeLists.txt); "
              f"the expected list is jni_exports.txt beside it.")
        return 1
    print("\ncheck_jni_exports: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
