# R8 rules for the release build (isMinifyEnabled = true).

# Shrink and optimise, but keep names. CrashLog writes stack traces the user
# can share, and sideloaded builds have no mapping file to decode renamed
# frames with. Most of R8's saving is dead-code removal, which this keeps.
-dontobfuscate

# sherpa-onnx (vendored Kotlin wrappers under com.k2fsa.sherpa.onnx).
# Its native library reads the config objects' fields by NAME through JNI
# (GetFieldID "modelConfig", "featConfig", ...), constructs result objects
# from native code, and calls methods R8 cannot see being used. None of that
# is visible to R8, which would otherwise strip or merge those members as
# unused.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# First-party JNI bridges. The native side never looks up Kotlin members by
# name (no FindClass/GetFieldID/GetMethodID in cpp/), so keeping the native
# methods and their classes is enough. The default optimize rules already
# keep native method names; this also stops R8 from merging or inlining the
# bridge objects away.
-keep class com.meetily.mobile.whisper.WhisperBridge { native <methods>; }
-keep class com.meetily.mobile.llm.LlamaBridge { native <methods>; }

# Annotation-only packages that libraries reference without shipping at
# runtime. Missing classes here are harmless, and R8 fails the build on them
# otherwise.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.annotations.**
