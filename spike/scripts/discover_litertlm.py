#!/usr/bin/env python3
"""What .litertlm models exist, and can a phone actually download them?

The second question is the one that decides the feature. Recap's GGUF
catalogue routes through unsloth/ and bartowski/ mirrors, not Google's own
repos, because Gemma's official repos are licence-gated: a gated repo needs
an authenticated request, and a one-tap in-app download button has no token
to send. If every .litertlm publisher is gated the same way, an opt-in
LiteRT engine cannot ship a working download button, whatever the model
listings say.

So this reports three things per repo: what it publishes, what its `gated`
flag claims, and — decisively — what an unauthenticated range request
actually returns. Only the third is evidence.

Runs in CI because dl.google.com and huggingface.co are both denied by the
dev container's network policy.
"""

import json
import sys
import urllib.error
import urllib.request

API = "https://huggingface.co/api"
TIMEOUT = 30


def get(url, headers=None):
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, b""
    except Exception as e:  # network shape varies by runner; keep going
        print(f"    (request failed: {e})")
        return 0, b""


def get_json(url):
    status, body = get(url)
    if status != 200 or not body:
        return None
    try:
        return json.loads(body)
    except ValueError:
        return None


def candidate_repos():
    """Union of a few searches — one query will not find them all."""
    found = {}
    queries = [
        f"{API}/models?search=litert-lm&limit=200",
        f"{API}/models?search=litertlm&limit=200",
        f"{API}/models?author=litert-community&limit=1000",
    ]
    for q in queries:
        data = get_json(q) or []
        for m in data:
            mid = m.get("id")
            if mid:
                found[mid] = m
    return found


def model_files(repo):
    """Every .litertlm/.task file with its real (LFS) size."""
    entries = get_json(f"{API}/models/{repo}/tree/main") or []
    out = []
    for e in entries:
        path = e.get("path", "")
        if path.endswith((".litertlm", ".task")):
            size = (e.get("lfs") or {}).get("size") or e.get("size") or 0
            out.append((path, size))
    return out


def unauthenticated_fetch(repo, path):
    """The decisive test. 200/206 means a phone can download it; 401/403 means
    the download button would fail for every user without a token."""
    url = f"https://huggingface.co/{repo}/resolve/main/{path}"
    status, _ = get(url, {"Range": "bytes=0-1023"})
    return status, url


def main():
    repos = candidate_repos()
    print(f"{len(repos)} candidate repos\n")
    if not repos:
        print("No repos found. Either the search terms miss, or the runner "
              "cannot reach huggingface.co — check before concluding the "
              "models do not exist.")
        return 0

    usable, gated, unknown = [], [], []

    for repo in sorted(repos):
        info = get_json(f"{API}/models/{repo}") or {}
        gated_flag = info.get("gated")
        card = info.get("cardData") or {}
        files = model_files(repo)
        if not files:
            continue

        print(f"=== {repo} ===")
        print(f"  gated={gated_flag}  private={info.get('private')}  "
              f"license={card.get('license')}  downloads={info.get('downloads')}")
        for path, size in sorted(files, key=lambda f: f[1]):
            print(f"  {size / 1048576:8.0f} MB  {path}")

        path = files[0][0]
        status, url = unauthenticated_fetch(repo, path)
        verdict = {200: "OK", 206: "OK"}.get(status)
        if verdict:
            print(f"  UNAUTH FETCH: HTTP {status} -> downloadable in-app")
            usable.append((repo, path, files[0][1], url))
        elif status in (401, 403):
            print(f"  UNAUTH FETCH: HTTP {status} -> GATED, no in-app download")
            gated.append(repo)
        else:
            print(f"  UNAUTH FETCH: HTTP {status} -> inconclusive")
            unknown.append(repo)
        print()

    print("=" * 60)
    print("VERDICT")
    print("=" * 60)
    print(f"\nDownloadable without auth ({len(usable)}) — these can back a "
          f"one-tap button:")
    for repo, path, size, url in usable:
        print(f"  {size / 1048576:8.0f} MB  {repo}/{path}")
        print(f"            {url}")
    print(f"\nGated ({len(gated)}) — cannot ship as a download button:")
    for repo in gated:
        print(f"  {repo}")
    if unknown:
        print(f"\nInconclusive ({len(unknown)}):")
        for repo in unknown:
            print(f"  {repo}")
    if not usable:
        print("\nNOTHING is fetchable unauthenticated. An opt-in LiteRT engine "
              "would need the user to sideload a model by hand, which is not a "
              "shippable feature for a paid app.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
