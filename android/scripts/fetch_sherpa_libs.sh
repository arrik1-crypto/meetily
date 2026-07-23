#!/usr/bin/env bash
# Fetches the prebuilt sherpa-onnx Android JNI libraries (Apache-2.0) used
# for on-device speaker diarization, and installs the arm64-v8a and x86_64
# .so files into app/src/main/jniLibs (gitignored). Run before building.
set -euo pipefail

VER="v1.13.4"
DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$DIR/app/src/main/jniLibs"

if [ -f "$DEST/arm64-v8a/libsherpa-onnx-jni.so" ] && [ -f "$DEST/x86_64/libsherpa-onnx-jni.so" ]; then
  echo "sherpa-onnx jniLibs already present"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# The android tarball has followed this exact naming for years
# (verified for $VER): try it directly — the release API is
# rate-limited for unauthenticated runners and has failed builds.
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/$VER/sherpa-onnx-$VER-android.tar.bz2"
echo "Downloading $URL"
if ! curl -fsSL "$URL" -o "$TMP/android.tar.bz2"; then
  echo "Direct asset URL failed; falling back to release API discovery" >&2
  AUTH=()
  if [ -n "${GH_TOKEN:-${GITHUB_TOKEN:-}}" ]; then
    AUTH=(-H "Authorization: Bearer ${GH_TOKEN:-$GITHUB_TOKEN}")
  fi
  # `|| true` guards: under set -e/pipefail an empty grep would kill the
  # script before the diagnostic below prints.
  URL="$(curl -sL "${AUTH[@]}" "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/$VER" |
    grep -o '"browser_download_url": *"[^"]*android\.tar\.bz2"' |
    head -1 | sed 's/.*"\(https[^"]*\)"/\1/' || true)"
  if [ -z "$URL" ]; then
    echo "ERROR: could not find the android .tar.bz2 asset on sherpa-onnx release $VER" >&2
    exit 1
  fi
  echo "Downloading $URL"
  curl -fsSL "$URL" -o "$TMP/android.tar.bz2"
fi
mkdir -p "$TMP/x"
tar xjf "$TMP/android.tar.bz2" -C "$TMP/x"

for abi in arm64-v8a x86_64; do
  SRC="$(find "$TMP/x" -type d -name "$abi" | head -1)"
  if [ -z "$SRC" ]; then
    echo "ERROR: ABI $abi not found in tarball" >&2
    exit 1
  fi
  mkdir -p "$DEST/$abi"
  cp "$SRC"/*.so "$DEST/$abi/"
done

echo "Installed sherpa-onnx jniLibs:"
find "$DEST" -name '*.so' -exec ls -la {} \;
