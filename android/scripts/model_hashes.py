#!/usr/bin/env python3
"""Look up pinnable revisions and SHA-256s for every model the Android app downloads.

Reads the four model catalogues under android/app/src/main/java/com/meetily/mobile,
extracts each download URL by regex, and prints a table of

    catalogue file | model key | url | commit sha | sha256 | size | pinned sha256

For Hugging Face URLs (https://huggingface.co/<repo>/resolve/<rev>/<path>) the
commit comes from /api/models/<repo>/revision/<rev> and the file's LFS SHA-256
from /api/models/<repo>/tree/<commit>/<dir>?expand=true, falling back to the
X-Linked-Etag header of a HEAD on the resolve URL, and finally to hashing the
download when the file is small. Other URLs (GitHub releases) are hashed by
downloading when smaller than --max-download-mb, else reported as UNKNOWN.

The output is for a human to review and copy into the catalogues' `revision`
and `sha256` fields; this script never edits them.

Standard library only. Set HF_TOKEN to raise Hugging Face rate limits (it is
sent to huggingface.co only, never across a redirect to a CDN).

Exit status: 0 when every row resolved (UNKNOWN for a too-large non-HF file is
not an error), 1 when any lookup failed.
"""

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "meetily", "mobile")

CATALOGUES = {
    "llm/LocalLlmModels.kt": "llm",
    "whisper/WhisperModels.kt": "template",
    "whisper/NemoModels.kt": "nemo",
    "whisper/DiarizationModels.kt": "template",
}

HF_HOST = "huggingface.co"
HF_URL = re.compile(
    r"^https://huggingface\.co/(?P<repo>[^/]+/[^/]+)/resolve/(?P<rev>[^/]+)/(?P<path>.+)$"
)
SHA256_HEX = re.compile(r"^[0-9a-f]{64}$")
USER_AGENT = "meetily-model-hashes/1 (+https://github.com)"
TIMEOUT = 60


# --------------------------------------------------------------------------
# Kotlin source scanning
# --------------------------------------------------------------------------

def strip_comments(src):
    """Removes // and /* */ comments, leaving string literals intact."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            out.append(src[i:j + 1])
            i = j + 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def call_blocks(src, name):
    """Argument text of every `name(...)` call (not the class declaration)."""
    blocks = []
    for m in re.finditer(r"\b" + re.escape(name) + r"\s*\(", src):
        before = src[max(0, m.start() - 20):m.start()]
        if re.search(r"\bclass\s+$", before):
            continue
        depth, i = 1, m.end()
        while i < len(src) and depth:
            c = src[i]
            if c == '"':
                i += 1
                while i < len(src) and src[i] != '"':
                    i += 2 if src[i] == "\\" else 1
            elif c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
            i += 1
        blocks.append(src[m.end():i - 1])
    return blocks


def join_concatenations(text):
    """`"a" +\\n "b"` -> `"ab"`."""
    prev = None
    while prev != text:
        prev = text
        text = re.sub(r'"\s*\+\s*"', "", text)
    return text


LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')


def literals(block):
    return LITERAL.findall(join_concatenations(block))


def arg(block, name, position=None):
    """Named string argument `name = "..."`, else the positional literal."""
    joined = join_concatenations(block)
    m = re.search(r"\b" + re.escape(name) + r'\s*=\s*"((?:[^"\\]|\\.)*)"', joined)
    if m:
        return m.group(1)
    if position is None:
        return None
    # Positional literals only: drop named ones so indices match the call.
    positional = re.sub(r'\b\w+\s*=\s*"(?:[^"\\]|\\.)*"', "", joined)
    lits = LITERAL.findall(positional)
    return lits[position] if position < len(lits) else None


def parse_llm(src):
    entries = []
    for block in call_blocks(src, "LocalLlmModel"):
        key = arg(block, "key", 0)
        url = arg(block, "url")
        if url is None:
            url = next((s for s in literals(block) if s.startswith("https://")), None)
        if key and url:
            entries.append({
                "key": key,
                "url": url,
                "revision": arg(block, "revision"),
                "sha256": arg(block, "sha256"),
            })
    return entries


def parse_template(src):
    """Catalogues whose URL is a `...$fileName` template and entries are (key, name, fileName, ...)."""
    m = re.search(r'"(https://[^"]*?)\$fileName"', join_concatenations(src))
    if not m:
        raise ValueError("no $fileName URL template found")
    base = m.group(1)
    cls = re.search(r"data\s+class\s+(\w+)\s*\(", src).group(1)
    entries = []
    for block in call_blocks(src, cls):
        key = arg(block, "key", 0)
        file_name = arg(block, "fileName", 2)
        if key and file_name:
            entries.append({
                "key": key,
                "url": base + file_name,
                "revision": arg(block, "revision"),
                "sha256": arg(block, "sha256"),
            })
    return entries


def parse_nemo_files(block):
    files = []
    for fb in call_blocks(block, "NemoFile"):
        name = arg(fb, "name", 0)
        if name:
            files.append((name, arg(fb, "sha256", 1)))
    return files


def parse_nemo(src):
    lists = {}
    for m in re.finditer(r"\bval\s+(\w+)\s*=\s*listOf\s*\(", src):
        depth, i = 1, m.end()
        while i < len(src) and depth:
            depth += {"(": 1, ")": -1}.get(src[i], 0)
            i += 1
        lists[m.group(1)] = parse_nemo_files(src[m.end():i - 1])
    entries = []
    for block in call_blocks(src, "NemoModel"):
        key = arg(block, "key", 0)
        repo = arg(block, "repo", 2)
        fm = re.search(r"\bfiles\s*=\s*(\w+)\b", block)
        if fm and fm.group(1) != "listOf":
            files = lists.get(fm.group(1), [])
        else:
            files = parse_nemo_files(block)
        revision = arg(block, "revision")
        for name, sha in files:
            entries.append({
                "key": "%s/%s" % (key, name),
                "url": "https://huggingface.co/%s/resolve/main/%s" % (repo, name),
                "revision": revision,
                "sha256": sha,
            })
    return entries


PARSERS = {"llm": parse_llm, "template": parse_template, "nemo": parse_nemo}


# --------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_NO_REDIRECT = urllib.request.build_opener(_NoRedirect)


def _request(url, method="GET"):
    req = urllib.request.Request(url, method=method)
    req.add_header("User-Agent", USER_AGENT)
    req.add_header("Accept-Encoding", "identity")
    token = os.environ.get("HF_TOKEN", "").strip()
    if token and urllib.parse.urlsplit(url).hostname == HF_HOST:
        # Unredirected: never forwarded to the CDN a resolve URL redirects to.
        req.add_unredirected_header("Authorization", "Bearer " + token)
    return req


def get_json(url):
    """(parsed JSON, next-page URL or None)."""
    with urllib.request.urlopen(_request(url), timeout=TIMEOUT) as r:
        data = json.load(r)
        nxt = None
        link = r.headers.get("Link") or ""
        m = re.search(r'<([^>]+)>\s*;\s*rel="next"', link)
        if m:
            nxt = urllib.parse.urljoin(url, m.group(1))
        return data, nxt


def head_no_redirect(url):
    """Headers of a HEAD, without following a redirect (HF puts X-Linked-* on the 302)."""
    try:
        with _NO_REDIRECT.open(_request(url, "HEAD"), timeout=TIMEOUT) as r:
            return r.headers
    except urllib.error.HTTPError as e:
        if 300 <= e.code < 400:
            return e.headers
        raise


def head_content_length(url):
    try:
        with urllib.request.urlopen(_request(url, "HEAD"), timeout=TIMEOUT) as r:
            value = r.headers.get("Content-Length")
            return int(value) if value and value.isdigit() else None
    except urllib.error.HTTPError:
        return None


def hash_download(url, limit_bytes):
    """(sha256, size) by streaming, or (None, size-so-far) once past limit_bytes."""
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(_request(url), timeout=TIMEOUT) as r:
        while True:
            chunk = r.read(1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
            if size > limit_bytes:
                return None, size
            digest.update(chunk)
    return digest.hexdigest(), size


# --------------------------------------------------------------------------
# Lookups
# --------------------------------------------------------------------------

_commit_cache = {}
_tree_cache = {}


def hf_commit(repo, rev):
    key = (repo, rev)
    if key not in _commit_cache:
        url = "https://huggingface.co/api/models/%s/revision/%s" % (
            repo, urllib.parse.quote(rev, safe=""))
        data, _ = get_json(url)
        _commit_cache[key] = data["sha"]
    return _commit_cache[key]


def hf_tree(repo, commit, directory):
    key = (repo, commit, directory)
    if key not in _tree_cache:
        url = "https://huggingface.co/api/models/%s/tree/%s" % (repo, commit)
        if directory:
            url += "/" + urllib.parse.quote(directory)
        url += "?expand=true"
        entries = {}
        while url:
            data, url = get_json(url)
            for item in data:
                if item.get("type") == "file":
                    entries[item["path"]] = item
        _tree_cache[key] = entries
    return _tree_cache[key]


def clean_etag(value):
    if not value:
        return None
    value = value.strip()
    if value.startswith("W/"):
        value = value[2:]
    return value.strip('"').lower()


def lookup_hf(entry, match, limit_bytes):
    repo = match.group("repo")
    path = urllib.parse.unquote(match.group("path"))
    rev = entry["revision"] or match.group("rev")
    commit = hf_commit(repo, rev)
    sha, size = None, None

    directory = path.rsplit("/", 1)[0] if "/" in path else ""
    item = hf_tree(repo, commit, directory).get(path)
    if item is not None:
        lfs = item.get("lfs") or {}
        sha = (lfs.get("oid") or lfs.get("sha256") or "").lower() or None
        size = lfs.get("size") or item.get("size")

    pinned_url = "https://huggingface.co/%s/resolve/%s/%s" % (repo, commit, match.group("path"))
    if not sha or not SHA256_HEX.match(sha):
        headers = head_no_redirect(pinned_url)
        linked = clean_etag(headers.get("X-Linked-Etag"))
        if linked and SHA256_HEX.match(linked):
            sha = linked
            linked_size = headers.get("X-Linked-Size")
            if linked_size and linked_size.isdigit():
                size = int(linked_size)
        else:
            sha = None

    if not sha:
        # Not in LFS (e.g. tokens.txt): its git oid is SHA-1, so hash the bytes.
        if size is not None and size > limit_bytes:
            return commit, "UNKNOWN", size
        sha, got = hash_download(pinned_url, limit_bytes)
        size = size if size is not None else got
        if sha is None:
            return commit, "UNKNOWN", size
    return commit, sha, size


def lookup_other(entry, limit_bytes):
    url = entry["url"]
    size = head_content_length(url)
    if size is not None and size > limit_bytes:
        return "-", "UNKNOWN", size
    sha, got = hash_download(url, limit_bytes)
    if sha is None:
        return "-", "UNKNOWN", size if size is not None else got
    return "-", sha, got


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------

def fmt_size(size):
    if size is None:
        return "?"
    return "%d (%.1f MB)" % (size, size / (1024.0 * 1024.0))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--max-download-mb", type=int, default=300,
                        help="largest file hashed by downloading (default 300)")
    args = parser.parse_args()
    limit = args.max_download_mb * 1024 * 1024

    rows, errors = [], 0
    for rel, kind in CATALOGUES.items():
        path = os.path.join(SRC, rel)
        with open(path, encoding="utf-8") as f:
            src = strip_comments(f.read())
        entries = PARSERS[kind](src)
        if not entries:
            print("ERROR: no entries parsed from %s" % rel, file=sys.stderr)
            errors += 1
        for entry in entries:
            url = entry["url"]
            try:
                m = HF_URL.match(url)
                if m:
                    commit, sha, size = lookup_hf(entry, m, limit)
                else:
                    commit, sha, size = lookup_other(entry, limit)
            except Exception as e:  # report and keep going
                commit, sha, size = "ERROR", "ERROR: %s" % e, None
                errors += 1
            pinned = entry.get("sha256")
            if not pinned:
                status = "-"
            elif SHA256_HEX.match(sha or "") and pinned.strip().lower() == sha:
                status = "ok"
            else:
                status = "MISMATCH (%s)" % pinned
            rows.append((os.path.basename(rel), entry["key"], url, commit, sha, fmt_size(size), status))
            print("%-24s %-40s %s" % (os.path.basename(rel), entry["key"], sha), file=sys.stderr)

    header = ("catalogue file", "model key", "url", "commit sha", "sha256", "size", "pinned sha256")
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    for row in rows:
        lines.append("| " + " | ".join(str(c).replace("|", "\\|") for c in row) + " |")
    table = "\n".join(lines)
    print(table)

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("## Model hashes\n\n" + table + "\n")

    if errors:
        print("\n%d lookup(s) failed" % errors, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
