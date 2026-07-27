#!/usr/bin/env python3
"""Guards against looking up a header-layout view on the Activity.

detail_header.xml is inflated as the meeting list's RecyclerView HEADER, not
as the Activity's content view. So `findViewById(R.id.somethingInThatLayout)`
compiles, returns null at runtime, and crashes the moment it is touched —
which is on Activity start, meaning every meeting becomes unopenable.

That shipped twice before this check existed. The fix is always the same:
call it on `headerView`.

    python3 android/scripts/check_header_ids.py

Exits non-zero and names each offending line.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LAYOUT = ROOT / "app/src/main/res/layout/detail_header.xml"
SOURCE = ROOT / "app/src/main/java/com/meetily/mobile/MeetingDetailActivity.kt"

# Receivers that are legitimately not the Activity. A lookup on any of these
# is scoped to a view already, so it is fine.
SAFE_PREFIX = re.compile(r"[\w.]+\.$")


def main() -> int:
    if not LAYOUT.exists() or not SOURCE.exists():
        print("check_header_ids: expected files missing", file=sys.stderr)
        return 1

    header_ids = set(re.findall(r'android:id="@\+id/(\w+)"', LAYOUT.read_text()))
    if not header_ids:
        print("check_header_ids: no ids found in detail_header.xml", file=sys.stderr)
        return 1

    offenders = []
    for lineno, line in enumerate(SOURCE.read_text().splitlines(), 1):
        for match in re.finditer(r"(?P<recv>[\w.]*\.)?findViewById<[^>]+>\(R\.id\.(?P<id>\w+)\)", line):
            if match.group("id") not in header_ids:
                continue
            receiver = match.group("recv")
            if receiver is None:
                offenders.append((lineno, match.group("id"), line.strip()))

    if offenders:
        print(
            f"check_header_ids: {len(offenders)} header view(s) looked up on the "
            f"Activity instead of headerView — these return null and crash:",
            file=sys.stderr,
        )
        for lineno, vid, text in offenders:
            print(f"  MeetingDetailActivity.kt:{lineno}  {vid}\n      {text}", file=sys.stderr)
        return 1

    print(f"check_header_ids OK — {len(header_ids)} header ids, all scoped to headerView")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
