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

# Discover the android tarball asset via the release API rather than
# hardcoding the asset name.
URL="$(curl -sL "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/$VER" |
  grep -o '"browser_download_url": *"[^"]*android[^"]*\.tar\.bz2"' |
  head -1 | sed 's/.*"\(https[^"]*\)"/\1/')"
if [ -z "$URL" ]; then
  echo "ERROR: could not find an android .tar.bz2 asset on sherpa-onnx release $VER" >&2
  exit 1
fi

echo "Downloading $URL"
curl -sL "$URL" -o "$TMP/android.tar.bz2"
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
