plugins {
    id("com.android.library")
}

// A second build of the whisper/llama JNI libraries, compiled for
// armv8.2-a+dotprod+fp16 and named with a "_v82" suffix
// (libmeetily_whisper_v82.so, libmeetily_llama_v82.so). It shares the app's
// CMakeLists; only the ggml CPU target and the library names differ.
//
// ggml selects its kernels at compile time, so the app's baseline build runs
// emulated dot products and f32-converted F16 maths even on phones that have
// the instructions. This build uses them, but would crash (SIGILL) on the
// Cortex-A53/A73 phones minSdk 26 still admits, so it is never loaded
// blindly: CpuFeatures.loadEngineLibrary checks /proc/cpuinfo first and
// falls back to the baseline pair.
//
// arm64 only: x86_64 is emulators, where the baseline is fine.
android {
    namespace = "com.meetily.mobile.nativev82"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                    "-DMEETILY_LIB_SUFFIX=_v82"
                )
                // Only the two JNI libraries; nothing else in the tree
                // needs building twice.
                targets += listOf("meetily_whisper_v82", "meetily_llama_v82")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../app/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
