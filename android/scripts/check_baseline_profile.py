#!/usr/bin/env python3
"""Check that a built Recap APK carries its compiled Baseline Profile.

AGP compiles app/src/main/baseline-prof.txt, merged with the profiles the
AndroidX libraries ship, into assets/dexopt/baseline.prof and
assets/dexopt/baseline.profm. ART compiles the profile when Play installs
the app, and androidx.profileinstaller hands it to ART for a sideloaded
copy. A build that loses it still builds and runs, only slower to start
after every fresh install, so this fails instead. For each APK it checks
that:

  - both files are present and not empty;
  - baseline.prof is the format AGP writes ("pro" 010) and baseline.profm
    is a profile metadata file ("prm");
  - every dex the profile names is in the APK, with the same checksum and
    method count (ART ignores a profile that does not match its dex);
  - the profile holds the app's own rules, not just the libraries':
    MainActivity and RecapApp among its classes, and MainActivity.onCreate,
    RecapApp.onCreate and MeetingDetailActivity.onCreate among its hot
    methods (activity and Application names survive R8, which keeps
    manifest components).

    python3 android/scripts/check_baseline_profile.py app-arm64-v8a-release.apk

Prints what each profile holds. Exit status: 0 when every APK passes, 1
when any check fails, 2 on a usage error. Reads the files only; no network.

    python3 android/scripts/check_baseline_profile.py --self-test

checks the decoder itself against APKs it builds in memory: one dex, and
two dexes (the layout a multidex release writes, which the shipped APK does
not exercise), plus profiles that must fail.
"""

import contextlib
import io
import struct
import sys
import zipfile
import zlib

PROF = "assets/dexopt/baseline.prof"
PROFM = "assets/dexopt/baseline.profm"
APP = "Lcom/meetily/mobile/"

REQUIRED_CLASSES = [
    "Lcom/meetily/mobile/MainActivity;",
    "Lcom/meetily/mobile/RecapApp;",
]
REQUIRED_HOT = [
    "Lcom/meetily/mobile/MainActivity;->onCreate(Landroid/os/Bundle;)V",
    "Lcom/meetily/mobile/RecapApp;->onCreate()V",
    "Lcom/meetily/mobile/MeetingDetailActivity;->onCreate(Landroid/os/Bundle;)V",
]


class CheckError(Exception):
    pass


def uleb128(data, offset):
    result = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, offset
        shift += 7


class Dex:
    """Just enough of a dex file to name classes and methods by index."""

    def __init__(self, data):
        if data[:4] != b"dex\n":
            raise CheckError("not a dex file")
        self.data = data
        (self.string_ids_size, self.string_ids_off, self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off, _fields_size, _fields_off,
         self.method_ids_size, self.method_ids_off) = struct.unpack_from("<10I", data, 0x38)

    def string(self, index):
        offset = struct.unpack_from("<I", self.data, self.string_ids_off + 4 * index)[0]
        _, start = uleb128(self.data, offset)
        end = self.data.index(b"\0", start)
        return self.data[start:end].decode("utf-8", "replace")

    def type(self, index):
        return self.string(struct.unpack_from("<I", self.data, self.type_ids_off + 4 * index)[0])

    def method(self, index):
        cls, proto, name = struct.unpack_from("<HHI", self.data, self.method_ids_off + 8 * index)
        _shorty, ret, params = struct.unpack_from("<III", self.data, self.proto_ids_off + 12 * proto)
        types = ""
        if params:
            count = struct.unpack_from("<I", self.data, params)[0]
            for k in range(count):
                types += self.type(struct.unpack_from("<H", self.data, params + 4 + 2 * k)[0])
        return f"{self.type(cls)}->{self.string(name)}({types}){self.type(ret)}"


def read_profile(prof, apk):
    """Decodes baseline.prof (format 010) against the APK's dex files.

    Returns [(dex name, class descriptors, hot method signatures, rule count)].
    """
    if len(prof) < 17 or prof[:4] != b"pro\0":
        raise CheckError(f"{PROF} does not start with the profile magic")
    if prof[4:8] != b"010\0":
        raise CheckError(f"{PROF} is version {prof[4:7]!r}, not the 010 that AGP writes")
    dex_count = prof[8]
    uncompressed, compressed = struct.unpack_from("<II", prof, 9)
    body = zlib.decompress(prof[17:17 + compressed])
    if len(body) != uncompressed:
        raise CheckError(f"{PROF} body is {len(body)} bytes, its header says {uncompressed}")
    # Every dex's line header first, each followed straight away by its own
    # key, and only then every dex's data (androidx ProfileTranscoder:
    # writeLineHeader ends with the key; readUncompressedBody reads it in the
    # same loop). With one dex the two orders are the same bytes.
    offset = 0
    headers = []
    for _ in range(dex_count):
        key_size, class_count, hot_size, checksum, method_count = struct.unpack_from(
            "<HHIII", body, offset)
        offset += 16
        key = body[offset:offset + key_size].decode()
        offset += key_size
        headers.append((key, class_count, hot_size, checksum, method_count))
    names = set(apk.namelist())
    result = []
    for key, class_count, hot_size, checksum, method_count in headers:
        dex_name = key.split("!")[-1] if "!" in key else key
        if dex_name in ("", "base.apk"):
            dex_name = "classes.dex"
        if dex_name not in names:
            raise CheckError(f"the profile names {key!r}, which is not in the APK")
        dex = Dex(apk.read(dex_name))
        # The dex's CRC-32 (its zip entry's), which ART compares the
        # profile's checksum with; not the adler32 in the dex header.
        crc = apk.getinfo(dex_name).CRC
        if crc != checksum:
            raise CheckError(f"{dex_name}: profile checksum {checksum:08x}, dex CRC-32 {crc:08x}")
        if dex.method_ids_size != method_count:
            raise CheckError(
                f"{dex_name}: profile counts {method_count} methods, dex has {dex.method_ids_size}")
        hot = []
        end = offset + hot_size
        index = 0
        while offset < end:
            delta, inline_caches = struct.unpack_from("<HH", body, offset)
            offset += 4
            if inline_caches:
                raise CheckError(f"{dex_name}: inline caches in a baseline profile")
            index += delta
            hot.append(dex.method(index))
        classes = []
        index = 0
        for _ in range(class_count):
            index += struct.unpack_from("<H", body, offset)[0]
            offset += 2
            classes.append(dex.type(index))
        bitmap = body[offset:offset + (2 * method_count + 7) // 8]
        offset += len(bitmap)
        flagged = sum(bin(b).count("1") for b in bitmap)
        result.append((dex_name, classes, hot, flagged))
    if offset != len(body):
        raise CheckError(f"{PROF}: {len(body) - offset} bytes left over after the last dex")
    return result


def check_apk(path):
    """Returns a list of failures for one APK, printing what it holds."""
    try:
        apk = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as e:
        return [f"cannot open: {e}"]
    with apk:
        failures = []
        for name in (PROF, PROFM):
            try:
                info = apk.getinfo(name)
            except KeyError:
                failures.append(f"{name} is missing")
                continue
            if info.file_size == 0:
                failures.append(f"{name} is empty")
            how = "stored" if info.compress_type == zipfile.ZIP_STORED else "compressed"
            print(f"  {name}: {info.file_size} bytes, {how}")
        if failures:
            return failures
        if apk.read(PROFM)[:4] != b"prm\0":
            failures.append(f"{PROFM} does not start with the metadata magic")
        try:
            dexes = read_profile(apk.read(PROF), apk)
        except (CheckError, struct.error, zlib.error, IndexError, ValueError) as e:
            return failures + [f"{PROF}: {e}"]
        classes = set()
        hot = set()
        for dex_name, dex_classes, dex_hot, flagged in dexes:
            app_classes = sum(1 for c in dex_classes if c.startswith(APP))
            app_hot = sum(1 for m in dex_hot if m.startswith(APP))
            print(f"  {dex_name}: {len(dex_classes)} classes ({app_classes} the app's), "
                  f"{len(dex_hot)} hot methods ({app_hot} the app's), "
                  f"{flagged} start-up and post-start-up flags")
            classes.update(dex_classes)
            hot.update(dex_hot)
        for cls in REQUIRED_CLASSES:
            if cls not in classes:
                failures.append(f"{cls} is not among the profile's classes")
        for method in REQUIRED_HOT:
            if method not in hot:
                failures.append(f"{method} is not a hot method in the profile")
        return failures


def _synthetic_dex(methods):
    """A dex holding just what Dex reads, for [methods]: (class, name,
    parameter types, return type) descriptors, given method index order.

    Returns the bytes and each type descriptor's index."""
    types = sorted({t for cls, _, params, ret in methods for t in (cls, ret, *params)})
    strings = sorted(set(types) | {name for _, name, _, _ in methods})
    string_index = {s: i for i, s in enumerate(strings)}
    type_index = {t: i for i, t in enumerate(types)}
    protos = sorted({(ret, tuple(params)) for _, _, params, ret in methods})
    proto_index = {p: i for i, p in enumerate(protos)}
    string_ids_off = 0x70
    type_ids_off = string_ids_off + 4 * len(strings)
    proto_ids_off = type_ids_off + 4 * len(types)
    method_ids_off = proto_ids_off + 12 * len(protos)
    data_off = method_ids_off + 8 * len(methods)
    data = bytearray()
    params_off = {}
    for proto in protos:
        if proto[1]:
            data.extend(b"\0" * (-(data_off + len(data)) % 4))
            params_off[proto] = data_off + len(data)
            data += struct.pack("<I", len(proto[1]))
            data += b"".join(struct.pack("<H", type_index[t]) for t in proto[1])
    string_off = []
    for s in strings:
        string_off.append(data_off + len(data))
        raw = s.encode()
        data += bytes([len(raw)]) + raw + b"\0"
    out = bytearray(0x70)
    out[:8] = b"dex\n035\0"
    struct.pack_into("<10I", out, 0x38, len(strings), string_ids_off, len(types), type_ids_off,
                     len(protos), proto_ids_off, 0, 0, len(methods), method_ids_off)
    out += b"".join(struct.pack("<I", o) for o in string_off)
    out += b"".join(struct.pack("<I", string_index[t]) for t in types)
    for ret, params in protos:
        out += struct.pack("<III", 0, type_index[ret], params_off.get((ret, params), 0))
    for cls, name, params, ret in methods:
        out += struct.pack("<HHI", type_index[cls], proto_index[(ret, tuple(params))],
                           string_index[name])
    return bytes(out + data), type_index


def _synthetic_profile(dexes, keys_with_data=False):
    """A "pro" 010 profile for [dexes]: (key, dex bytes, hot method
    indices, class type indices). Laid out as androidx ProfileTranscoder
    writes it: every line header, each with its key, then every dex's
    data. [keys_with_data] puts each key in front of its dex's data
    instead, after every header: the order this script once read, which
    misreads every profile of more than one dex."""
    headers = []
    keys = []
    bodies = []
    for key, dex, hot, classes in dexes:
        method_count = struct.unpack_from("<I", dex, 0x58)[0]
        region = b""
        last = 0
        for index in sorted(hot):
            region += struct.pack("<HH", index - last, 0)
            last = index
        class_data = b""
        last = 0
        for index in sorted(classes):
            class_data += struct.pack("<H", index - last)
            last = index
        bitmap = bytearray((2 * method_count + 7) // 8)
        for index in hot:
            bitmap[index // 8] |= 1 << (index % 8)
        raw_key = key.encode()
        headers.append(struct.pack("<HHIII", len(raw_key), len(classes), len(region),
                                   zlib.crc32(dex), method_count))
        keys.append(raw_key)
        bodies.append(region + class_data + bytes(bitmap))
    if keys_with_data:
        body = b"".join(headers) + b"".join(k + d for k, d in zip(keys, bodies))
    else:
        body = b"".join(h + k for h, k in zip(headers, keys)) + b"".join(bodies)
    packed = zlib.compress(body)
    return (b"pro\0" + b"010\0" + bytes([len(dexes)])
            + struct.pack("<II", len(body), len(packed)) + packed)


def _synthetic_apk(dex_files, prof):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as apk:
        for name, dex in dex_files:
            apk.writestr(name, dex)
        apk.writestr(PROF, prof)
        apk.writestr(PROFM, b"prm\0" + b"002\0")
    buffer.seek(0)
    return buffer


def self_test():
    """Checks the decoder against APKs built in memory. Returns failures."""
    main_create = ("Lcom/meetily/mobile/MainActivity;", "onCreate", ["Landroid/os/Bundle;"], "V")
    app_create = ("Lcom/meetily/mobile/RecapApp;", "onCreate", [], "V")
    detail_create = ("Lcom/meetily/mobile/MeetingDetailActivity;", "onCreate",
                     ["Landroid/os/Bundle;"], "V")
    library = ("Lcom/example/Library;", "run", ["I", "J"], "Z")
    one, one_types = _synthetic_dex([library, main_create, app_create, detail_create])
    first, first_types = _synthetic_dex([main_create, library])
    second, second_types = _synthetic_dex([app_create, detail_create])
    main_cls = "Lcom/meetily/mobile/MainActivity;"
    app_cls = "Lcom/meetily/mobile/RecapApp;"
    detail_cls = "Lcom/meetily/mobile/MeetingDetailActivity;"
    two_dexes = [
        ("classes.dex", first, [0, 1], [first_types[main_cls]]),
        ("base.apk!classes2.dex", second, [0, 1],
         [second_types[app_cls], second_types[detail_cls]]),
    ]
    two_files = [("classes.dex", first), ("classes2.dex", second)]
    good = {
        "one dex": _synthetic_apk(
            [("classes.dex", one)],
            _synthetic_profile([("classes.dex", one, [1, 2, 3],
                                 [one_types[main_cls], one_types[app_cls]])])),
        "two dexes": _synthetic_apk(two_files, _synthetic_profile(two_dexes)),
    }
    stale = [two_dexes[0], ("classes2.dex", first, [0], [])]
    bad = {
        "two dexes, each key with its data": _synthetic_apk(
            two_files, _synthetic_profile(two_dexes, keys_with_data=True)),
        "two dexes, the second out of step with its dex": _synthetic_apk(
            two_files, _synthetic_profile(stale)),
        "two dexes, only the first in the APK": _synthetic_apk(
            two_files[:1], _synthetic_profile(two_dexes)),
    }
    failures = []
    for name, apk in good.items():
        with contextlib.redirect_stdout(io.StringIO()):
            found = check_apk(apk)
        if found:
            failures.append(f"{name}: {found}")
    for name, apk in bad.items():
        with contextlib.redirect_stdout(io.StringIO()):
            found = check_apk(apk)
        if not found:
            failures.append(f"{name}: passed")
    return failures


def main(argv):
    if argv == ["--self-test"]:
        failures = self_test()
        for failure in failures:
            print(f"self-test FAIL {failure}")
        if not failures:
            print("self-test ok")
        return 1 if failures else 0
    if not argv or any(a.startswith("-") for a in argv):
        print(__doc__.strip().split("\n\n")[0], file=sys.stderr)
        print("usage: check_baseline_profile.py <apk>... | --self-test", file=sys.stderr)
        return 2
    failed = False
    for path in argv:
        print(path)
        failures = check_apk(path)
        for failure in failures:
            print(f"  FAIL {failure}")
        if failures:
            failed = True
        else:
            print("  ok")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
