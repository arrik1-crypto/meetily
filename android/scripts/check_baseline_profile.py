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
"""

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
    offset = 0
    headers = []
    for _ in range(dex_count):
        headers.append(struct.unpack_from("<HHIII", body, offset))
        offset += 16
    names = set(apk.namelist())
    result = []
    for key_size, class_count, hot_size, checksum, method_count in headers:
        key = body[offset:offset + key_size].decode()
        offset += key_size
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


def main(argv):
    if not argv or any(a.startswith("-") for a in argv):
        print(__doc__.strip().split("\n\n")[0], file=sys.stderr)
        print("usage: check_baseline_profile.py <apk>...", file=sys.stderr)
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
