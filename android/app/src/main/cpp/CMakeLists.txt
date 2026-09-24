cmake_minimum_required(VERSION 3.22)
project(meetily_native C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# whisper.cpp and llama.cpp are fetched at configure time (pinned release
# tarballs), so the repo does not vendor them. Requires network access on the
# build machine.
#
# Both projects bundle ggml, and both guard with `if (NOT TARGET ggml)`.
# llama.cpp is added FIRST so its ggml (b4783, late Feb 2025) becomes the
# shared one; whisper.cpp v1.7.5 (Feb 27 2025, same ggml sync window) reuses
# it instead of adding its own.
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# The `llama-common` target, ON deliberately (it defaults to OFF under
# FetchContent, since LLAMA_STANDALONE is OFF).
#
# It carries the Jinja chat-template renderer. The core library's
# llama_chat_apply_template does NOT evaluate Jinja — it substring-matches
# against a hard-coded list in src/llama-chat.cpp and returns -1 for anything
# absent from it, which is every model whose turn format is newer than the
# snapshot. Gemma 4 is one: its markers are <|turn>role / <turn|>, and the
# only Gemma branch there tests for "<start_of_turn>".
#
# Costs roughly a megabyte and pulls in vendor/cpp-httplib (added under the
# same option). Worth it: the alternative is transcribing each new model's
# turn format into C by hand, forever.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)

set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# llama.cpp is fetched FIRST and provides the shared ggml target;
# whisper.cpp reuses it via its `if (NOT TARGET ggml)` guard. The two tags
# must come from a close ggml sync window: b10089 (2026-07-22) pairs with
# whisper v1.8.6 (2026-06-01). b10089 is the floor for Gemma 4 + Qwen3.5.
FetchContent_Declare(
    llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/b10089.tar.gz
)
FetchContent_Declare(
    whisper_cpp
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.8.6.tar.gz
)
# CPU features. ggml picks its kernels at COMPILE time and this build leaves
# GGML_NATIVE off, so by default it targets plain armv8-a: no dotprod, no
# fp16 vector arithmetic, and the dotprod/i8mm repack GEMMs are compiled out.
# The app module keeps that baseline, because minSdk 26 still admits
# Cortex-A53/A73 phones that would die with SIGILL on anything newer.
#
# The ":native-v82" Gradle module builds this same file a second time with
# -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 and -DMEETILY_LIB_SUFFIX=_v82,
# and CpuFeatures.kt loads that pair instead on phones whose every core
# reports asimddp/asimdhp/fphp. Runtime-selected ggml variants
# (GGML_BACKEND_DL + GGML_CPU_ALL_VARIANTS) would be neater, but need ggml,
# whisper and llama as shared libraries and the variant MODULE libraries
# packaged into the APK — too much to get right without a device to test on.
set(MEETILY_LIB_SUFFIX "" CACHE STRING "Suffix for the JNI library names")
if (GGML_CPU_ARM_ARCH AND NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "GGML_CPU_ARM_ARCH is an arm64-v8a-only setting (got ${ANDROID_ABI})")
endif()

FetchContent_MakeAvailable(llama_cpp whisper_cpp)

set(WHISPER_JNI meetily_whisper${MEETILY_LIB_SUFFIX})
set(LLAMA_JNI meetily_llama${MEETILY_LIB_SUFFIX})
add_library(${WHISPER_JNI} SHARED whisper_jni.c)
add_library(${LLAMA_JNI} SHARED llama_jni.c chat_render.cpp)

# 16 KB page-size alignment for Android 15+ devices.
target_link_options(${WHISPER_JNI} PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(${LLAMA_JNI} PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(${WHISPER_JNI} PRIVATE whisper log)
# llama-common (not "common") is the target name at b10089.
target_link_libraries(${LLAMA_JNI} PRIVATE llama llama-common log)
