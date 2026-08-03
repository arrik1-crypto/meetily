#!/usr/bin/env python3
"""Catch unbalanced Kotlin block comments before CI does.

Kotlin nests block comments, unlike Java. So a "/*" written inside a KDoc —
which is easy to do when documenting a glob or a path, e.g. naming the repos
under `litert-community/*` — opens an INNER comment, and the "*/" that was
meant to close the doc closes that instead. The rest of the file becomes a
comment.

The failure is badly disguised: the compiler reports "Unclosed comment" at
the LAST line of the file, and every declaration in it comes back as
"Unresolved reference" from every other file that uses it. It reads like a
missing import in a dozen places rather than one stray character in a
comment. That is exactly what it looked like when it cost a CI round trip.

Naively counting "/*" and "*/" produces false positives: MIME type literals
like "audio/*" and "*/*" appear all over this codebase. So string and
character literals are stripped first.

Exit 1 on any imbalance, so it can gate a push.
"""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "app" / "src"


def strip_literals(text: str) -> str:
    """Blank out string/char literals so their contents cannot look like
    comment markers. Comments themselves are left intact — they are what we
    are here to measure."""
    out = []
    i = 0
    n = len(text)
    in_line_comment = False
    in_block_comment = 0

    while i < n:
        ch = text[i]
        two = text[i:i + 2]

        if in_line_comment:
            if ch == "\n":
                in_line_comment = False
            out.append(ch)
            i += 1
            continue

        if in_block_comment:
            if two == "/*":
                in_block_comment += 1
                out.append(two)
                i += 2
                continue
            if two == "*/":
                in_block_comment -= 1
                out.append(two)
                i += 2
                continue
            out.append(ch)
            i += 1
            continue

        if two == "//":
            in_line_comment = True
            out.append(two)
            i += 2
            continue
        if two == "/*":
            in_block_comment = 1
            out.append(two)
            i += 2
            continue

        # Raw string: no escapes, ends at the next triple quote.
        if text[i:i + 3] == '"""':
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(" " * (end - i))
            i = end
            continue

        if ch in ('"', "'"):
            quote = ch
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == quote or text[j] == "\n":
                    j += 1
                    break
                j += 1
            out.append(" " * (j - i))
            i = j
            continue

        out.append(ch)
        i += 1

    return "".join(out)


def depth_of(text: str) -> int:
    """Net block-comment nesting. Nonzero means the file does not close."""
    cleaned = strip_literals(text)
    depth = 0
    i = 0
    n = len(cleaned)
    while i < n - 1:
        two = cleaned[i:i + 2]
        if two == "//":
            newline = cleaned.find("\n", i)
            i = n if newline < 0 else newline
            continue
        if two == "/*":
            depth += 1
            i += 2
            continue
        if two == "*/":
            depth -= 1
            i += 2
            continue
        i += 1
    return depth


def main() -> int:
    if not ROOT.is_dir():
        print(f"check_kotlin_comments: {ROOT} not found", file=sys.stderr)
        return 1

    bad = []
    count = 0
    for path in sorted(ROOT.rglob("*.kt")):
        count += 1
        depth = depth_of(path.read_text(encoding="utf-8"))
        if depth != 0:
            bad.append((path, depth))

    for path, depth in bad:
        rel = path.relative_to(ROOT.parent.parent)
        if depth > 0:
            print(f"{rel}: {depth} block comment(s) never closed — a '/*' "
                  f"inside a KDoc opens a NESTED comment in Kotlin")
        else:
            print(f"{rel}: {-depth} stray '*/' with no opener")

    if bad:
        print(f"\ncheck_kotlin_comments FAILED — {len(bad)} of {count} files")
        return 1

    print(f"check_kotlin_comments OK — {count} kt files, comments balanced")
    return 0


if __name__ == "__main__":
    sys.exit(main())
