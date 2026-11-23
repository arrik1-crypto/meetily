#!/usr/bin/env bash
# Fetches the prebuilt sherpa-onnx Android JNI libraries (Apache-2.0) used
# for on-device speaker diarization, and installs the arm64-v8a and x86_64
# .so files into app/src/main/jniLibs (gitignored). Run before building.
#
# The vendored Kotlin API under app/src/main/java/com/k2fsa/sherpa/onnx/
# must come from the same tag: the JNI library reads its config objects
# field by field, so a mismatch is a runtime error, not a build error.
# scripts/check_sherpa_jni.py checks both after this script runs.
set -euo pipefail

VER="v2.0.3"
# SHA-256 of sherpa-onnx-$VER-android.tar.bz2: the digest GitHub publishes
# for the release asset, and that of the file as downloaded. A replaced or
# corrupted asset stops the build here instead of shipping.
SHA256="2a30b222abae6d5cf544df4a1c27944279449c25dbb9c755097189cb45db1df0"
DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$DIR/app/src/main/jniLibs"
# Records which tarball the installed libraries came from. Outside the ABI
# folders and not a .so, so it is never packaged.
STAMP="$DEST/sherpa-onnx.stamp"
WANT="$VER $SHA256"

have_stamp="$(cat "$STAMP" 2>/dev/null || true)"
if [ -f "$DEST/arm64-v8a/libsherpa-onnx-jni.so" ] && [ -f "$DEST/x86_64/libsherpa-onnx-jni.so" ]; then
  # Older copies of this script installed the unused C/C++ API libraries
  # too; clear them so they are not packaged.
  rm -f "$DEST"/*/libsherpa-onnx-c-api.so "$DEST"/*/libsherpa-onnx-cxx-api.so
  # Present only when the onnxruntime each JNI library NEEDs is there too:
  # otherwise check_sherpa_jni.py sends the developer back here, and this
  # must then fetch the pair again rather than report it present.
  if [ "$have_stamp" = "$WANT" ] &&
    [ -f "$DEST/arm64-v8a/libonnxruntime.so" ] && [ -f "$DEST/x86_64/libonnxruntime.so" ]; then
    echo "sherpa-onnx $VER jniLibs already present"
    exit 0
  fi
  if [ "$have_stamp" = "$WANT" ]; then
    echo "sherpa-onnx $VER jniLibs incomplete (libonnxruntime.so missing); fetching them again"
  else
    # Libraries from another version (or from before the stamp existed)
    # would pair the wrong native code with the vendored Kotlin classes.
    echo "Replacing sherpa-onnx jniLibs (installed: ${have_stamp:-unknown}, wanted: $WANT)"
  fi
  rm -f "$DEST"/*/libsherpa-onnx-jni.so "$DEST"/*/libonnxruntime.so "$STAMP"
fi

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

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

# Whichever URL served it, nothing is extracted until the bytes match.
GOT="$(sha256_of "$TMP/android.tar.bz2")"
if [ "$GOT" != "$SHA256" ]; then
  echo "ERROR: sherpa-onnx $VER android tarball has SHA-256 $GOT," >&2
  echo "       expected $SHA256 (pinned in $0)." >&2
  echo "       If upstream re-published the asset, confirm the new digest on the" >&2
  echo "       release page before changing the pin." >&2
  exit 1
fi
echo "Verified SHA-256 $GOT"

mkdir -p "$TMP/x"
tar xjf "$TMP/android.tar.bz2" -C "$TMP/x"

for abi in arm64-v8a x86_64; do
  SRC="$(find "$TMP/x" -type d -name "$abi" | head -1)"
  if [ -z "$SRC" ]; then
    echo "ERROR: ABI $abi not found in tarball" >&2
    exit 1
  fi
  mkdir -p "$DEST/$abi"
  # Only what the app loads: the JNI library and the onnxruntime it NEEDs.
  # The tarball's libsherpa-onnx-c-api.so and -cxx-api.so (~4.8 MB per ABI)
  # are for non-JNI users; its README says so, and nothing here loads them.
  cp "$SRC/libsherpa-onnx-jni.so" "$SRC/libonnxruntime.so" "$DEST/$abi/"
  rm -f "$DEST/$abi/libsherpa-onnx-c-api.so" "$DEST/$abi/libsherpa-onnx-cxx-api.so"
done
echo "$WANT" > "$STAMP"

echo "Installed sherpa-onnx $VER jniLibs:"
find "$DEST" -name '*.so' -exec ls -la {} \;
