# Building the Android app locally

The cloud container this app has been built in is ephemeral, and it has no
Android SDK — every APK so far came from GitHub Actions. Moving to a local
machine changes two things: builds stop depending on CI, and you get the
things a container cannot give you at all — an emulator, a device over USB,
`logcat`, and a debugger.

Nothing needs exporting. Every source file is in git; the only things not in
the repo are fetched by a script or belong to you already (see §5).

## 1. Get the code

```bash
git clone https://github.com/arrik1-crypto/meetily.git
cd meetily
git checkout claude/meeting-summarizer-android-apk-41ewdo
```

A full clone is ~67 MiB packed — the history carries a built APK per
release. If you only want to build, `git clone --depth 20` is much smaller
and loses nothing you need.

Everything below runs from the `android/` directory.

## 2. Prerequisites

Exact versions, taken from the build files rather than guessed:

| Tool | Version | Notes |
|---|---|---|
| JDK | **17** (Temurin) | 21 will not work — AGP 8.7 targets 17 |
| Gradle | 8.14.3 | Do not install it; `./gradlew` fetches it |
| Android Gradle Plugin | 8.7.3 | from `build.gradle.kts` |
| Kotlin | 2.0.21 | |
| Android SDK Platform | **35** | compileSdk and targetSdk |
| Build-tools | 35.x | |
| NDK | **27.0.12077973** | exact — whisper.cpp/llama.cpp are compiled |
| CMake | **3.22.1** | exact |

Android Studio (Ladybug or newer) installs all of it through
**SDK Manager → SDK Tools**; tick *NDK (Side by side)* and *CMake* and pick
those versions. Command-line only:

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0" \
           "ndk;27.0.12077973" "cmake;3.22.1"
```

Then point Gradle at the SDK — create `android/local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

That file is gitignored and must stay that way.

## 3. Fetch the native libraries

sherpa-onnx ships as prebuilt `.so` files that are **not** in the repo:

```bash
bash android/scripts/fetch_sherpa_libs.sh
```

Downloads the v1.13.4 Android release and installs `arm64-v8a` and `x86_64`
into `app/src/main/jniLibs/`. Run once; it no-ops if they are present.

## 4. Build

```bash
cd android
./gradlew testReleaseUnitTest     # the same tests CI gates on
./gradlew assembleRelease         # release APK
./gradlew installDebug            # build + install to a connected device
```

The first build compiles whisper.cpp and llama.cpp from source via CMake and
downloads both at configure time — expect 10–20 minutes and a working
network. Later builds are incremental and quick.

Output: `android/app/build/outputs/apk/release/`.

## 5. Signing

Release builds are signed from **your** keystore, which is deliberately not
in this repository and never should be. Create `android/keystore.properties`:

```properties
storeFile=upload.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

`storeFile` is resolved relative to `android/`. The same four values can be
supplied as `MEETILY_KEYSTORE_FILE`, `MEETILY_KEYSTORE_PASSWORD`,
`MEETILY_KEY_ALIAS`, `MEETILY_KEY_PASSWORD` instead — the properties file
wins where both exist.

`.gitignore` already excludes `keystore.properties`, `*.keystore`, `*.jks`
and `upload.keystore`. Do not relax that.

**With no keystore configured, AGP signs with a debug key** and the result
cannot be installed over an existing build — Android rejects it as a
certificate mismatch and the only way through is uninstalling, which erases
the app's data. Verify what you built before sideloading:

```bash
python3 android/scripts/apk_cert.py path/to/app-release.apk
```

The upload certificate's SHA-256 must match the one you generated. Anything
else means the debug fallback was used.

## 6. What this buys you that CI cannot

- **Run on a real device**: `./gradlew installDebug`, then
  `adb logcat -s meetily` (or `adb logcat --pid=$(adb shell pidof com.recap.mobile)`).
  Whisper's encoder-pass counter and the audio pipeline log here — the only
  way to see them.
- **Screenshots**: `adb exec-out screencap -p > screen.png`.
- **Profiling**: Android Studio's profiler for the ANR-prone paths.
- **Fast iteration**: no push, no CI wait, no APK download.

Keep pushing to the same branch — the CI workflow still builds and publishes
`android/dist/meetily-android.apk` on every push touching `android/**`, which
stays useful for sharing installable builds.

## 7. Running Claude Code locally

```bash
npm install -g @anthropic-ai/claude-code
cd meetily
claude
```

It picks up `CLAUDE.md` and the repo automatically. The practical difference
from the web session: a local run can execute the Gradle build, install to a
device and read `logcat`, so it can verify a change actually works instead of
inferring it from a green CI run.
