#!/usr/bin/env bash
# Does adopting LiteRT-LM really force an app-wide Kotlin upgrade?
#
# Spike run 2 failed on Kotlin 2.0.21 with a metadata-version error: the AAR
# carries Kotlin 2.2.21 metadata. The spike was bumped to 2.2.21 and moved on,
# and "forces an app-wide Kotlin upgrade" has been quoted as an adoption cost
# ever since. Recap is on 2.0.21, and upgrading Kotlin across a paid app that
# is about to ship is a real blast radius — so the claim is worth testing
# rather than repeating.
#
# Note the spike binds by REFLECTION and never names a LiteRT class at compile
# time, yet still failed. That says the check is classpath-wide, not
# reference-driven, which is exactly what -Xskip-metadata-version-check exists
# to relax.
#
# Builds throwaway copies under /tmp so the real spike config is untouched.
set -u

SRC=spike/litert-lm
ROOT=$(pwd)

probe() {
  local name="$1" kotlin="$2" extra_args="$3"
  local dir="/tmp/compat-$name"

  echo "=============================================="
  echo "PROBE: $name  (kotlin $kotlin, args: ${extra_args:-none})"
  echo "=============================================="

  rm -rf "$dir"
  cp -r "$SRC" "$dir"

  sed -i "s/org.jetbrains.kotlin.android\") version \"[^\"]*\"/org.jetbrains.kotlin.android\") version \"$kotlin\"/" \
    "$dir/build.gradle.kts"
  echo "  root plugin line now:"
  grep 'kotlin.android' "$dir/build.gradle.kts" | sed 's/^/    /'

  if [ -n "$extra_args" ]; then
    # Appended inside android{} via a top-level kotlin compile task config,
    # which applies regardless of how kotlinOptions is declared.
    cat >> "$dir/app/build.gradle.kts" <<EOF

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions { freeCompilerArgs.addAll("$extra_args") }
}
EOF
    echo "  appended freeCompilerArgs: $extra_args"
  fi

  if "$ROOT/android/gradlew" -p "$dir" assembleDebug --no-daemon -q 2>&1 | tail -25; then
    echo "  RESULT: $name BUILT"
  else
    echo "  RESULT: $name FAILED"
  fi
  echo
}

chmod +x android/gradlew

# 1. The claim under test. Expect failure; if this BUILDS, the whole
#    "forces a Kotlin upgrade" cost was wrong and should stop being quoted.
probe "k2021-plain" "2.0.21" ""

# 2. The escape hatch. If this builds, adopting LiteRT costs one compiler
#    flag instead of an app-wide Kotlin upgrade.
probe "k2021-skipcheck" "2.0.21" "-Xskip-metadata-version-check"

echo "=============================================="
echo "How to read this"
echo "=============================================="
echo "k2021-plain BUILT      -> no upgrade needed at all; the cost was wrong."
echo "plain FAILED, skip OK  -> cost is one compiler flag, not an upgrade."
echo "both FAILED            -> cost is real: either upgrade Kotlin app-wide,"
echo "                          or put the binding behind a Java shim, which"
echo "                          javac compiles without reading Kotlin metadata."
