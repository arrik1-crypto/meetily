#!/usr/bin/env bash
# Generates Recap's upload signing key and prints the four values that go into
# GitHub repository secrets.
#
# Run this on YOUR OWN machine, never on a build runner or in a container: the
# private key it produces is the app's identity, and anyone holding it can
# build an APK that installs over yours as an update. Nothing here is written
# to the repository (android/.gitignore excludes *.keystore).
#
# Needs a JDK for keytool — Android Studio bundles one, or `brew install
# openjdk` / `apt install default-jdk`.
#
#   bash android/scripts/make_upload_key.sh [output-dir]
#
set -euo pipefail

OUT_DIR="${1:-$PWD}"
ALIAS="recap-upload"
KEYSTORE="$OUT_DIR/recap-upload.keystore"

if [ -e "$KEYSTORE" ]; then
    echo "refusing to overwrite an existing key: $KEYSTORE" >&2
    echo "Move it aside first — regenerating changes the app's identity and" >&2
    echo "every installed copy would need uninstalling again." >&2
    exit 1
fi

command -v keytool >/dev/null || {
    echo "keytool not found — install a JDK first." >&2
    exit 1
}

# PKCS12 (keytool's default since JDK 9) does not support a key password that
# differs from the store password, so both are the same value throughout.
if command -v openssl >/dev/null; then
    PASSWORD="$(openssl rand -base64 24 | tr -d '\n/+=' | cut -c1-28)"
else
    PASSWORD="$(head -c 32 /dev/urandom | base64 | tr -d '\n/+=' | cut -c1-28)"
fi

# 10000 days ≈ 27 years. Play requires an upload key valid past 2033-10-22.
keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storetype PKCS12 \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -sigalg SHA384withRSA \
    -validity 10000 \
    -dname "CN=Recap Mobile, OU=Recap, O=Recap, C=US" \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" >/dev/null 2>&1

# base64 with no line wrapping: GNU coreutils wants -w0, BSD/macOS wraps only
# when asked, so strip newlines either way.
base64 < "$KEYSTORE" | tr -d '\n' > "$KEYSTORE.base64"

FINGERPRINT="$(keytool -list -v -keystore "$KEYSTORE" -storepass "$PASSWORD" 2>/dev/null \
    | awk '/SHA256:/ { print $2; exit }')"

cat <<EOF

Key written to: $KEYSTORE
Base64 written to: $KEYSTORE.base64
Certificate SHA-256: $FINGERPRINT

BACK BOTH UP NOW, somewhere private (a password manager). Losing the key
means sideload updates can never install in place again, and the Play upload
key has to be reset through support.

Add these four repository secrets — GitHub → Settings → Secrets and
variables → Actions → New repository secret:

  ANDROID_KEYSTORE_BASE64     the whole one-line contents of
                              $KEYSTORE.base64
  ANDROID_KEYSTORE_PASSWORD   $PASSWORD
  ANDROID_KEY_ALIAS           $ALIAS
  ANDROID_KEY_PASSWORD        $PASSWORD

Then run the Build Android APK workflow twice and confirm both APKs report
the certificate SHA-256 above; that is what proves signing is stable.
EOF
