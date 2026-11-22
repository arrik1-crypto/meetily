#!/usr/bin/env python3
"""Check the fetched sherpa-onnx libraries against the vendored Kotlin API.

fetch_sherpa_libs.sh installs prebuilt libsherpa-onnx-jni.so and
libonnxruntime.so; the Kotlin classes that call into them are vendored under
app/src/main/java/com/k2fsa/sherpa/onnx/. Nothing at build time checks that
the two halves agree, and every way they can disagree shows up on a phone:

  - an `external fun` with no exported Java_ symbol is an
    UnsatisfiedLinkError at its first call;
  - a LOAD segment aligned below 16 KB fails to load on 16 KB-page devices
    (and Play rejects such an app for Android 15+ targets);
  - a library that NEEDs something not packaged (libc++_shared.so, say)
    fails to load at all;
  - Kotlin classes from one tag with libraries from another.

So this fails when, for each ABI in ABIS:
  (a) a native method the vendored classes declare is not exported by
      libsherpa-onnx-jni.so (both ABIs, by .dynsym);
  (b) a LOAD segment of libsherpa-onnx-jni.so or libonnxruntime.so has
      p_align below 0x4000;
  (c) either library NEEDs anything but the Android system libraries and
      the two libraries installed beside it;
and, for the tree as a whole, when
  (d) jniLibs/sherpa-onnx.stamp does not record the VER and SHA256 pinned in
      fetch_sherpa_libs.sh, or a vendored file's header names another tag;
  (e) sherpa_jni_natives.txt (next to this script) differs from the natives
      read from the Kotlin sources. SherpaNativesTest keeps that list equal
      to the natives of the compiled classes, the exact JVM names, so a
      mistake in the source scan below cannot pass unnoticed.

The source scan resolves each `external fun` to its JVM class the way the
Kotlin compiler does: the enclosing class or object, "Outer$Companion" in a
companion object, the outer class itself for @JvmStatic in a companion, and
"<File>Kt" at top level. Names are mangled per the JNI specification.

With --jni-src DIR (the sherpa-onnx/jni directory of the same tag, fetched
by hand when bumping), it also audits every GetFieldID and constructor
GetMethodID the JNI sources use against the vendored classes: name and JVM
type. That catches the NoSuchFieldError class of mismatch, which no symbol
table shows.

    python3 android/scripts/check_sherpa_jni.py
    python3 android/scripts/check_sherpa_jni.py --print-natives
    python3 android/scripts/check_sherpa_jni.py --jni-src /path/to/sherpa-onnx/jni

Standard library only. Exit status: 0 when everything passes, 1 when a
check fails, 2 on a usage or environment error (a missing library, say).
"""

import argparse
import pathlib
import re
import struct
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
VENDORED = ROOT / "app" / "src" / "main" / "java" / "com" / "k2fsa" / "sherpa" / "onnx"
JNILIBS = ROOT / "app" / "src" / "main" / "jniLibs"
FETCH_SCRIPT = ROOT / "scripts" / "fetch_sherpa_libs.sh"
NATIVES_LIST = ROOT / "scripts" / "sherpa_jni_natives.txt"
PACKAGE = "com.k2fsa.sherpa.onnx"

ABIS = ("arm64-v8a", "x86_64")
JNI_LIB = "libsherpa-onnx-jni.so"
LIBS = (JNI_LIB, "libonnxruntime.so")
# Libraries every Android device provides (NDK stable system libraries).
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}
MIN_ALIGN = 0x4000


class UsageError(Exception):
    pass


# ---------------------------------------------------------------------------
# Kotlin source scanning


def kotlin_code(text):
    """The code of a Kotlin file with every comment and every literal's
    contents blanked to spaces (newlines kept), so braces, keywords and
    declarations inside them cannot be mistaken for code. Block comments
    nest; string templates (${...}) are scanned as code."""
    out = []
    n = len(text)

    def blank(s):
        return "".join("\n" if c == "\n" else " " for c in s)

    def code(i, stop_at_brace):
        depth = 0
        while i < n:
            c = text[i]
            if text.startswith("//", i):
                j = text.find("\n", i)
                j = n if j < 0 else j
                out.append(blank(text[i:j]))
                i = j
            elif text.startswith("/*", i):
                j, level = i, 0
                while j < n:
                    if text.startswith("/*", j):
                        level += 1
                        j += 2
                    elif text.startswith("*/", j):
                        level -= 1
                        j += 2
                        if level == 0:
                            break
                    else:
                        j += 1
                out.append(blank(text[i:j]))
                i = j
            elif text.startswith('"""', i):
                out.append('"')
                i = string(i + 3, '"""')
                out.append('"')
            elif c == '"':
                out.append('"')
                i = string(i + 1, '"')
                out.append('"')
            elif c == "'":
                j = i + 1
                while j < n and text[j] != "'" and text[j] != "\n":
                    j += 2 if text[j] == "\\" else 1
                j = min(j + 1, n)
                out.append("'" + blank(text[i + 1:j - 1]) + ("'" if j - i > 1 else ""))
                i = j
            elif c == "{" and stop_at_brace:
                depth += 1
                out.append(c)
                i += 1
            elif c == "}" and stop_at_brace:
                if depth == 0:
                    return i
                depth -= 1
                out.append(c)
                i += 1
            else:
                out.append(c)
                i += 1
        return i

    def string(i, quote):
        raw = quote == '"""'
        while i < n:
            if text.startswith(quote, i):
                if raw:
                    # A raw string ends at the LAST quote of a run of quotes.
                    while text.startswith('"', i + 3):
                        out.append(" ")
                        i += 1
                return i + len(quote)
            if not raw and text[i] == "\\":
                out.append(blank(text[i:i + 2]))
                i += 2
            elif text.startswith("${", i):
                out.append("  ")
                i = code(i + 2, stop_at_brace=True)
                if i < n:
                    out.append(" ")
                    i += 1
            elif not raw and text[i] == "\n":
                return i  # unterminated: stop at the line end
            else:
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
        return i

    code(0, stop_at_brace=False)
    return "".join(out)


_HEADER_WORD = re.compile(
    r"\b(?:(?P<companion>companion\s+object)(?:\s+(?P<cname>\w+))?"
    r"|(?P<object>object)\s+(?P<oname>\w+)"
    r"|(?P<class>class|interface)\s+(?P<kname>\w+)"
    r"|(?P<other>fun|init|get|set|if|else|when|for|while|do|try|catch|finally|constructor)\b)"
)
_EXTERNAL = re.compile(r"\bexternal\b(?P<mods>[\w\s]*?)\bfun\s+(?:<[^>]*>\s*)?(?P<name>\w+|`[^`]+`)\s*\(")


def _scope_kind(header):
    """What a `{` opens, from the text since the previous statement: a
    (kind, name) with kind "class", "object", "companion" or "other"."""
    last = None
    for m in _HEADER_WORD.finditer(header):
        last = m
    if last is None or last.group("other"):
        return ("other", None)
    if last.group("companion"):
        return ("companion", last.group("cname") or "Companion")
    if last.group("object"):
        return ("object", last.group("oname"))
    return ("class", last.group("kname"))


def _jvm_owner(stack, file_stem, jvm_static):
    """JVM class (binary name, '$' for nesting) a member declared in [stack] lands in."""
    named = [(kind, name) for kind, name in stack if kind != "other"]
    if not named:
        return f"{file_stem}Kt"
    parts = [name for _kind, name in named]
    if named[-1][0] == "companion" and jvm_static and len(parts) > 1:
        parts = parts[:-1]  # @JvmStatic: a static native on the outer class
    return "$".join(parts)


def kotlin_natives(path):
    """[(jvm class binary name, method name)] for every `external fun` in a file."""
    text = path.read_text(encoding="utf-8")
    code = kotlin_code(text)
    if re.search(r"@file\s*:\s*JvmName", code):
        raise UsageError(f"{path.name}: @file:JvmName is not modelled by this check")
    found = []
    stack = []  # (kind, name) per open brace
    paren = 0
    seg = 0  # start of the current statement
    externals = {m.start(): m for m in _EXTERNAL.finditer(code)}
    i = 0
    while i < len(code):
        c = code[i]
        m = externals.get(i)
        if m:
            before = code[seg:i]
            jvm_static = "@JvmStatic" in before or "JvmStatic" in m.group("mods")
            name = m.group("name").strip("`")
            found.append((_jvm_owner(stack, path.stem, jvm_static), name))
        if c == "(":
            paren += 1
        elif c == ")":
            paren = max(0, paren - 1)
        elif c == "{":
            if paren:
                stack.append(("other", None))  # a lambda in an argument list
            else:
                stack.append(_scope_kind(code[seg:i]))
                seg = i + 1
        elif c == "}":
            if stack:
                stack.pop()
            if not paren:
                seg = i + 1
        elif c == ";" and not paren:
            seg = i + 1
        i += 1
    return found


def jni_mangle(s):
    out = []
    for ch in s:
        if ch in "./":
            out.append("_")
        elif ch == "_":
            out.append("_1")
        elif ch == ";":
            out.append("_2")
        elif ch == "[":
            out.append("_3")
        elif ch.isascii() and ch.isalnum():
            out.append(ch)
        else:
            # Each UTF-16 code unit as _0xxxx (lower-case hex).
            units = ch.encode("utf-16-be")
            for k in range(0, len(units), 2):
                out.append("_0" + units[k:k + 2].hex())
    return "".join(out)


def jni_short_name(jvm_class, method):
    return "Java_" + jni_mangle(f"{PACKAGE}.{jvm_class}") + "_" + jni_mangle(method)


def vendored_natives(vendored):
    """{JNI short name: "Class.method (File.kt)"} for the whole package."""
    files = sorted(vendored.glob("*.kt"))
    if not files:
        raise UsageError(f"no Kotlin files under {vendored}")
    natives, problems = {}, []
    per_class = {}
    for f in files:
        for owner, method in kotlin_natives(f):
            per_class.setdefault(owner, []).append(method)
            natives[jni_short_name(owner, method)] = f"{owner}.{method} ({f.name})"
    for owner, methods in sorted(per_class.items()):
        dup = sorted({m for m in methods if methods.count(m) > 1})
        if dup:
            # The VM tries the short name first; an overload needs the long,
            # signature-mangled name, which this check does not model.
            problems.append(f"FAIL: {owner} overloads native(s) {', '.join(dup)}")
    return natives, problems


def read_natives_list(path):
    names = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            names.add(line)
    return names


# ---------------------------------------------------------------------------
# ELF


class Elf:
    """The parts of an ELF shared object this check needs."""

    def __init__(self, path):
        data = path.read_bytes()
        if data[:4] != b"\x7fELF":
            raise UsageError(f"{path}: not an ELF file")
        if data[5] != 1:
            raise UsageError(f"{path}: big-endian ELF is not supported")
        if data[4] == 2:
            hdr = struct.unpack_from("<16sHHIQQQIHHHHHH", data, 0)
            ph_fmt, sh_fmt, sym_fmt, dyn_fmt = "<IIQQQQQQ", "<IIQQQQIIQQ", "<IBBHQQ", "<qQ"
            self.ph = lambda t: {"type": t[0], "offset": t[2], "vaddr": t[3],
                                 "filesz": t[5], "memsz": t[6], "align": t[7]}
            self.sym = lambda t: {"name": t[0], "info": t[1], "other": t[2], "shndx": t[3]}
        elif data[4] == 1:
            hdr = struct.unpack_from("<16sHHIIIIIHHHHHH", data, 0)
            ph_fmt, sh_fmt, sym_fmt, dyn_fmt = "<IIIIIIII", "<IIIIIIIIII", "<IIIBBH", "<iI"
            self.ph = lambda t: {"type": t[0], "offset": t[1], "vaddr": t[2],
                                 "filesz": t[4], "memsz": t[5], "align": t[7]}
            self.sym = lambda t: {"name": t[0], "info": t[3], "other": t[4], "shndx": t[5]}
        else:
            raise UsageError(f"{path}: unknown ELF class")
        self.machine = hdr[2]
        phoff, shoff = hdr[5], hdr[6]
        phentsize, phnum, shentsize, shnum = hdr[9], hdr[10], hdr[11], hdr[12]
        self.segments = [self.ph(struct.unpack_from(ph_fmt, data, phoff + k * phentsize))
                         for k in range(phnum)]
        self.loads = [s for s in self.segments if s["type"] == 1]  # PT_LOAD

        def cstr(off):
            return data[off:data.index(b"\0", off)].decode("utf-8", "replace")

        def file_offset(vaddr):
            for s in self.loads:
                if s["vaddr"] <= vaddr < s["vaddr"] + s["filesz"]:
                    return vaddr - s["vaddr"] + s["offset"]
            raise UsageError(f"{path}: address {vaddr:#x} is outside every LOAD segment")

        # DT_NEEDED from the dynamic segment.
        dynamic = [s for s in self.segments if s["type"] == 2]  # PT_DYNAMIC
        if not dynamic:
            raise UsageError(f"{path}: no dynamic segment")
        entsize = struct.calcsize(dyn_fmt)
        tags = []
        off = dynamic[0]["offset"]
        for k in range(dynamic[0]["filesz"] // entsize):
            tag, val = struct.unpack_from(dyn_fmt, data, off + k * entsize)
            if tag == 0:
                break
            tags.append((tag, val))
        strtab = [v for t, v in tags if t == 5]  # DT_STRTAB
        if not strtab:
            raise UsageError(f"{path}: no DT_STRTAB")
        dynstr = file_offset(strtab[0])
        self.needed = [cstr(dynstr + v) for t, v in tags if t == 1]  # DT_NEEDED

        # Exported symbols from .dynsym: defined, global or weak, not hidden.
        sections = [struct.unpack_from(sh_fmt, data, shoff + k * shentsize)
                    for k in range(shnum)] if shoff and shnum else []
        dynsym = [sh for sh in sections if sh[1] == 11]  # SHT_DYNSYM
        if not dynsym:
            raise UsageError(f"{path}: no .dynsym section")
        sh = dynsym[0]
        names_off = sections[sh[6]][4]
        size = sh[9] or struct.calcsize(sym_fmt)
        self.exports = set()
        for k in range(1, sh[5] // size):
            s = self.sym(struct.unpack_from(sym_fmt, data, sh[4] + k * size))
            bind, visibility = s["info"] >> 4, s["other"] & 3
            if s["shndx"] != 0 and bind in (1, 2) and visibility in (0, 3):
                self.exports.add(cstr(names_off + s["name"]))


# ---------------------------------------------------------------------------
# Version pins


def pinned_version(fetch_script):
    text = fetch_script.read_text(encoding="utf-8")
    ver = re.search(r'^VER="([^"]+)"', text, re.M)
    sha = re.search(r'^SHA256="([0-9a-f]{64})"', text, re.M)
    if not ver or not sha:
        raise UsageError(f"{fetch_script}: VER= or SHA256= not found")
    return ver.group(1), sha.group(1)


def check_versions(ver, sha, jnilibs, vendored, problems):
    stamp = jnilibs / "sherpa-onnx.stamp"
    want = f"{ver} {sha}"
    have = stamp.read_text(encoding="utf-8").strip() if stamp.exists() else None
    if have != want:
        problems.append(f"FAIL: {stamp} records {have or 'nothing'}, fetch_sherpa_libs.sh "
                        f"pins {want}; run scripts/fetch_sherpa_libs.sh")
    for f in sorted(vendored.glob("*.kt")):
        first = f.read_text(encoding="utf-8").splitlines()[:1]
        if not first or f"(tag {ver})" not in first[0]:
            problems.append(f"FAIL: {f.name} is not marked as vendored from {ver} "
                            f"(first line: {first[0] if first else '<empty>'!r}); "
                            f"re-vendor it from sherpa-onnx/kotlin-api at {ver}")


# ---------------------------------------------------------------------------
# Optional: field and constructor audit against the JNI sources


_PRIMITIVES = {"Int": "I", "Long": "J", "Float": "F", "Double": "D", "Boolean": "Z",
               "Short": "S", "Byte": "B", "Char": "C"}
_ARRAYS = {"IntArray": "[I", "LongArray": "[J", "FloatArray": "[F", "DoubleArray": "[D",
           "BooleanArray": "[Z", "ShortArray": "[S", "ByteArray": "[B", "CharArray": "[C"}
_KNOWN = {"String": "Ljava/lang/String;", "AssetManager": "Landroid/content/res/AssetManager;",
          "List": "Ljava/util/List;", "Map": "Ljava/util/Map;", "Any": "Ljava/lang/Object;"}
_BOXED = {"Int": "Ljava/lang/Integer;", "Long": "Ljava/lang/Long;", "Float": "Ljava/lang/Float;",
          "Double": "Ljava/lang/Double;", "Boolean": "Ljava/lang/Boolean;"}


def jvm_type(kotlin_type, local_classes):
    t = kotlin_type.strip()
    nullable = t.endswith("?")
    t = t.rstrip("?").strip()
    m = re.fullmatch(r"Array<(.+)>", t)
    if m:
        inner = jvm_type(m.group(1), local_classes)
        return None if inner is None else "[" + inner
    base = re.sub(r"<.*>", "", t)
    if base in _PRIMITIVES:
        return _BOXED.get(base) if nullable else _PRIMITIVES[base]
    if base in _ARRAYS:
        return _ARRAYS[base]
    if base in _KNOWN:
        return _KNOWN[base]
    if base in local_classes:
        return "L" + PACKAGE.replace(".", "/") + "/" + base + ";"
    return None


def _split_top(s):
    parts, depth, cur = [], 0, []
    for ch in s:
        if ch in "(<[{":
            depth += 1
        elif ch in ")>]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    if "".join(cur).strip():
        parts.append("".join(cur))
    return parts


def kotlin_classes(vendored):
    """{class: {"fields": {name: kotlin type}, "ctor": [kotlin types]}} from
    primary constructors and class-body properties."""
    raw = {}
    for f in sorted(vendored.glob("*.kt")):
        code = kotlin_code(f.read_text(encoding="utf-8"))
        for m in re.finditer(r"\bclass\s+(\w+)\s*(?:\(|\{)", code):
            name, i = m.group(1), m.end() - 1
            fields, ctor = {}, []
            if code[i] == "(":
                depth, j = 0, i
                while j < len(code):
                    depth += {"(": 1, ")": -1}.get(code[j], 0)
                    if depth == 0:
                        break
                    j += 1
                for p in _split_top(code[i + 1:j]):
                    pm = re.match(r"\s*(?:@\w+\s+)*(?:(?:private|public|internal)\s+)?"
                                  r"(val|var)?\s*(\w+)\s*:\s*([^=]+?)\s*(?:=.*)?$", p, re.S)
                    if pm:
                        ctor.append(pm.group(3))
                        if pm.group(1):
                            fields[pm.group(2)] = pm.group(3)
                i = j + 1
                k = i
                while k < len(code) and code[k] in " \t\r\n":
                    k += 1
                i = k if k < len(code) and code[k] == "{" else -1
            if i >= 0 and code[i] == "{":
                depth, j = 0, i
                while j < len(code):
                    if code[j] == "{":
                        depth += 1
                    elif code[j] == "}":
                        depth -= 1
                        if depth == 0:
                            break
                    j += 1
                body = code[i + 1:j]
                # Only depth-0 properties of the body.
                flat, d = [], 0
                for ch in body:
                    if ch == "{":
                        d += 1
                    elif ch == "}":
                        d -= 1
                    elif d == 0:
                        flat.append(ch)
                for pm in re.finditer(r"\b(?:val|var)\s+(\w+)\s*:\s*([\w<>?, \[\]]+?)\s*(?:=|\n)",
                                      "".join(flat)):
                    fields.setdefault(pm.group(1), pm.group(2))
            raw[name] = {"fields": fields, "ctor": ctor}
    return raw


_READ_MACRO = re.compile(
    r"SHERPA_ONNX_JNI_READ_(STRING|INT|FLOAT|BOOL)\s*\(\s*[^,]+,\s*(\w+)\s*,\s*(\w+)\s*,\s*(\w+)\s*\)")
_MACRO_SIG = {"STRING": "Ljava/lang/String;", "INT": "I", "FLOAT": "F", "BOOL": "Z"}
_GET_FIELD = re.compile(r"(?:\b(?:auto|jfieldID)\s+)?\w+\s*=\s*env->GetFieldID\(\s*(\w+)\s*,\s*"
                        r"\"(\w+)\"\s*,\s*\"([^\"]+)\"\s*\)")
_GET_OBJ = re.compile(r"(?:\bjobject\s+)?(\w+)\s*=\s*env->GetObjectField\(\s*(\w+)\s*,\s*\w+\s*\)")
_GET_CLS = re.compile(r"(?:\bjclass\s+)?(\w+)\s*=\s*env->GetObjectClass\(\s*(\w+)\s*\)")
_FIND_CLS = re.compile(r"(?:\bjclass\s+)?(\w+)\s*=\s*env->FindClass\(\s*\"([^\"]+)\"\s*\)")
_GET_METHOD = re.compile(r"env->GetMethodID\(\s*(\w+)\s*,\s*\"([^\"]+)\"\s*,\s*((?:\"[^\"]*\"\s*)+)\)")

# The class each JNI file's entry objects are, by parameter name. Everything
# else is followed through GetFieldID -> GetObjectField -> GetObjectClass.
_SEEDS = {
    "offline-recognizer.cc": {"config": "OfflineRecognizerConfig",
                              "_config": "OfflineRecognizerConfig"},
    "online-recognizer.cc": {"config": "OnlineRecognizerConfig",
                             "_config": "OnlineRecognizerConfig",
                             "model_config": "OnlineModelConfig"},
    "speaker-embedding-extractor.cc": {"config": "SpeakerEmbeddingExtractorConfig",
                                       "_config": "SpeakerEmbeddingExtractorConfig"},
}
_SEED_CLASSES = {"model_config_cls": ("online-recognizer.cc", "OnlineModelConfig")}


def audit_jni_sources(jni_src, vendored):
    """(checks made, problems) for the JNI files in _SEEDS."""
    classes = kotlin_classes(vendored)
    local = set(classes)
    pkg = PACKAGE.replace(".", "/") + "/"
    problems, checks = [], 0

    def field_check(where, cls, field, sig):
        nonlocal checks
        checks += 1
        if cls is None:
            problems.append(f"FAIL: {where}: field {field!r} read from an object of unknown class")
            return
        info = classes.get(cls)
        if info is None:
            problems.append(f"FAIL: {where}: class {cls} is not vendored (field {field!r})")
            return
        ktype = info["fields"].get(field)
        if ktype is None:
            problems.append(f"FAIL: {where}: {cls} has no field {field!r} (JNI wants {sig})")
            return
        have = jvm_type(ktype, local)
        if have != sig:
            problems.append(f"FAIL: {where}: {cls}.{field} is {ktype} ({have}), JNI wants {sig}")

    for fname in sorted(_SEEDS):
        path = jni_src / fname
        if not path.exists():
            raise UsageError(f"{path} not found")
        src = re.sub(r"//[^\n]*", "", path.read_text(encoding="utf-8"))
        obj_class = dict(_SEEDS[fname])
        cls_class = {var: c for var, (f, c) in _SEED_CLASSES.items() if f == fname}
        pending = None
        events = []
        for rx, kind in ((_READ_MACRO, "macro"), (_GET_FIELD, "fid"), (_GET_OBJ, "obj"),
                         (_GET_CLS, "cls"), (_FIND_CLS, "find"), (_GET_METHOD, "method")):
            for m in rx.finditer(src):
                events.append((m.start(), kind, m))
        for pos, kind, m in sorted(events, key=lambda e: e[0]):
            where = f"{fname}:{src.count(chr(10), 0, pos) + 1}"
            if kind == "macro":
                field_check(where, cls_class.get(m.group(3)), m.group(2), _MACRO_SIG[m.group(1)])
            elif kind == "fid":
                field_check(where, cls_class.get(m.group(1)), m.group(2), m.group(3))
                sig = m.group(3)
                pending = sig[1 + len(pkg):-1] if sig.startswith("L" + pkg) else None
            elif kind == "obj":
                obj_class[m.group(1)] = pending
            elif kind == "cls":
                cls_class[m.group(1)] = obj_class.get(m.group(2))
            elif kind == "find":
                name = m.group(2)
                cls_class[m.group(1)] = name[len(pkg):] if name.startswith(pkg) else None
            elif kind == "method":
                checks += 1
                cls = cls_class.get(m.group(1))
                sig = "".join(re.findall(r"\"([^\"]*)\"", m.group(3)))
                if m.group(2) != "<init>" or cls is None:
                    continue
                info = classes.get(cls)
                if info is None:
                    problems.append(f"FAIL: {where}: constructor of unvendored class {cls}")
                    continue
                types = [jvm_type(t, local) for t in info["ctor"]]
                have = "(" + "".join(t or "?" for t in types) + ")V"
                if have != sig:
                    problems.append(f"FAIL: {where}: {cls} constructor is {have}, JNI wants {sig}")
    return checks, problems


# ---------------------------------------------------------------------------


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--jnilibs", type=pathlib.Path, default=JNILIBS)
    ap.add_argument("--vendored", type=pathlib.Path, default=VENDORED)
    ap.add_argument("--natives-list", type=pathlib.Path, default=NATIVES_LIST)
    ap.add_argument("--fetch-script", type=pathlib.Path, default=FETCH_SCRIPT)
    ap.add_argument("--jni-src", type=pathlib.Path,
                    help="sherpa-onnx/jni sources of the pinned tag: also audit fields")
    ap.add_argument("--print-natives", action="store_true",
                    help="print the natives read from the Kotlin sources (the list file's body) and exit")
    args = ap.parse_args(argv)

    try:
        natives, problems = vendored_natives(args.vendored)
        if args.print_natives:
            for name in sorted(natives):
                print(name)
            return 0
        ver, sha = pinned_version(args.fetch_script)
        print(f"check_sherpa_jni: sherpa-onnx {ver}, {len(natives)} natives in the vendored classes")

        listed = read_natives_list(args.natives_list) if args.natives_list.exists() else None
        if listed is None:
            problems.append(f"FAIL: {args.natives_list} is missing")
        elif listed != set(natives):
            for n in sorted(set(natives) - listed):
                problems.append(f"FAIL: {natives[n]} -> {n} is not in {args.natives_list.name}")
            for n in sorted(listed - set(natives)):
                problems.append(f"FAIL: {args.natives_list.name} lists {n}, which no vendored "
                                f"external fun declares")
            problems.append("      regenerate the list with --print-natives, and let "
                            "SherpaNativesTest confirm it against the compiled classes")

        check_versions(ver, sha, args.jnilibs, args.vendored, problems)

        for abi in ABIS:
            for lib in LIBS:
                path = args.jnilibs / abi / lib
                if not path.exists():
                    raise UsageError(f"{path} not found; run scripts/fetch_sherpa_libs.sh first")
                elf = Elf(path)
                aligns = [s["align"] for s in elf.loads]
                print(f"  {abi}/{lib}: {path.stat().st_size} bytes, LOAD p_align "
                      f"{', '.join(hex(a) for a in aligns)}, NEEDED {', '.join(elf.needed)}")
                for k, s in enumerate(elf.loads):
                    if s["align"] < MIN_ALIGN or s["align"] & (s["align"] - 1):
                        problems.append(f"FAIL: {abi}/{lib} LOAD segment {k} has p_align "
                                        f"{s['align']:#x}; 16 KB pages need >= {MIN_ALIGN:#x}")
                    elif (s["offset"] - s["vaddr"]) % s["align"]:
                        problems.append(f"FAIL: {abi}/{lib} LOAD segment {k}: offset and "
                                        f"address disagree modulo p_align")
                for needed in elf.needed:
                    if needed not in SYSTEM_LIBS and needed not in LIBS:
                        problems.append(f"FAIL: {abi}/{lib} NEEDs {needed}, which is neither "
                                        f"a system library nor installed by fetch_sherpa_libs.sh")
                if lib == JNI_LIB:
                    java = {n for n in elf.exports if n.startswith("Java_")}
                    print(f"    {len(java)} Java_ exports")
                    if "JNI_OnLoad" in elf.exports:
                        print(f"    note: {abi}/{lib} exports JNI_OnLoad; natives it registers "
                              f"need no symbol, but every vendored native is still required here")
                    for n in sorted(set(natives) - elf.exports):
                        problems.append(f"FAIL: {abi}/{lib} does not export {n} "
                                        f"(declared by {natives[n]})")

        if args.jni_src:
            checks, audit = audit_jni_sources(args.jni_src, args.vendored)
            print(f"  JNI source audit: {checks} field and constructor lookups checked")
            problems += audit
    except UsageError as e:
        print(f"check_sherpa_jni: {e}", file=sys.stderr)
        return 2

    for p in problems:
        print(f"  {p}")
    if any(p.startswith("FAIL") for p in problems):
        print("\ncheck_sherpa_jni: FAILED. The libraries come from scripts/fetch_sherpa_libs.sh; "
              "the Kotlin classes under app/src/main/java/com/k2fsa/sherpa/onnx/ must be "
              "vendored from the same tag.")
        return 1
    print("\ncheck_sherpa_jni: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
