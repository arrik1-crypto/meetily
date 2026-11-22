cmake_minimum_required(VERSION 3.22)
project(meetily_native C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# whisper.cpp and llama.cpp are fetched at configure time (pinned tag
# tarballs), so the repo does not vendor them. Requires network access on the
# build machine (github.com; with KleidiAI on, see below, also its release
# tarball, which ggml MD5-pins).
#
# Both projects bundle ggml, and both guard with `if (NOT TARGET ggml)`.
# llama.cpp is added FIRST so its ggml becomes the shared one and whisper.cpp
# reuses it instead of adding its own. The two tags embed the same released
# ggml, 1.0.0 (see the FetchContent_Declare comment below).
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
# The unified `llama` binary, the server Web UI, llama-connect (downloads a
# prebuilt binary) and the standalone mtmd library. All already default OFF
# under FetchContent at v1.0.0; pinned here so a future default cannot add a
# target or a download to this build.
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_CONNECT OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD OFF CACHE BOOL "" FORCE)
# No TLS stack. llama-common links vendor/cpp-httplib, which adds HTTPS when
# LLAMA_OPENSSL (default ON at v1.0.0: find_package(OpenSSL)) finds one, or
# fetches and builds BoringSSL / LibreSSL when asked to. Recap never
# downloads anything through llama.cpp, and nothing here may reach the
# network. LLAMA_CURL is gone at v1.0.0 (only a deprecation shim is left),
# so it is no longer set. LLGuidance (a cargo build) and subprocess spawning
# (OFF by default on Android) stay off.
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_BORINGSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_LIBRESSL OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)

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
# same option, built without TLS: see LLAMA_OPENSSL above; nothing here calls
# its download code, and --gc-sections drops what is unreachable). At v1.0.0
# llama.cpp also adds vendor/ unconditionally: header-only interface targets
# (nlohmann, sheredom, stb, miniaudio) plus a static vendor-hash library
# that nothing here links. Worth it: the alternative is transcribing each new
# model's turn format into C by hand, forever.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)

set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# whisper.cpp b5907 also defines a `parakeet` library (its own NeMo port) on
# every configure, with no option to drop it. Nothing here links it, and
# both Gradle modules name the two JNI targets in externalNativeBuild
# `targets`, so it is never built.

# llama.cpp is fetched FIRST and provides the shared ggml target;
# whisper.cpp reuses it via its `if (NOT TARGET ggml)` guard (still there at
# b5907). The two must embed the same ggml: llama.cpp v1.0.0 and
# whisper.cpp b5907 both carry ggml 1.0.0, and their ggml headers,
# ggml/CMakeLists.txt, ggml-cpu/CMakeLists.txt and ggml.c are identical
# (the only ggml difference is one MATH_LIBRARY test in ggml/src, unused
# here because whisper.cpp's copy is never added). whisper.cpp has no
# release tag on ggml 1.0.0 yet (no v1.9.6), hence the build tag.
# llama.cpp v1.0.0 carries the gemma4 and qwen35 architectures. If this pair
# ever fails, the fallback is llama.cpp v0.6.0 + whisper.cpp v1.9.5 (both
# ggml 0.26.0).
FetchContent_Declare(
    llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/v1.0.0.tar.gz
)
FetchContent_Declare(
    whisper_cpp
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/b5907.tar.gz
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

# Arm KleidiAI micro-kernels, arm64 only: ggml-cpu stops with FATAL_ERROR on
# any other target, and the app module also builds x86_64 (emulators).
#
# Unlike the rest of ggml, these are chosen at RUN time, so they are on in
# both CPU builds (the app's armv8-a baseline and :native-v82, which share
# this file). ggml fetches KleidiAI v1.32.0 (URL and MD5 pinned in
# ggml/src/ggml-cpu/CMakeLists.txt) and builds it with KleidiAI's own CMake,
# which compiles each kernel file with that kernel's -march (+dotprod,
# +i8mm, +sve, +sme...) and nothing else with it. ggml's glue
# (ggml-cpu/kleidiai/*.cpp) keeps ggml's flags, reads the CPU's features
# from getauxval(AT_HWCAP/AT_HWCAP2) once, and only calls a kernel whose
# required features are all present. On a Cortex-A53/A73 (no dotprod) no
# kernel qualifies and every matmul stays on ggml's own path, so the
# baseline library cannot SIGILL because of it.
#
# What it takes at v1.0.0 (ggml-cpu/kleidiai/kernels.cpp and kleidiai.cpp):
# weight kernels for Q4_0 and Q8_0 (the Q8_0 ones also serve Q6_K) with
# dotprod, i8mm or SME/SME2, and for Q4_K only with SME2+FP16. Its SVE
# kernels need 256-bit vectors; phone cores have 128-bit SVE2. The
# SME-family kernels are off here (MEETILY_KLEIDIAI_SME below), so the Q4_K
# bulk of the shipped Q4_K_M GGUFs stays where it was on every phone
# (CPU_REPACK on :native-v82, the plain mmap'd CPU buffer on the baseline).
#
# What it does take from them, on any phone with dotprod (all of
# :native-v82, and baseline-tier phones that have it), is every Q6_K
# matmul weight: the output head (a tied one too: llama.cpp loads a second
# copy of token_embd for the output matmul, and that copy goes here) and
# about half the ffn_down/attn_v tensors. It dequantises Q6_K (and Q8_0)
# and requantises it to int8 with one scale per row. That is lossy, where
# CPU_REPACK on :native-v82 ran exact Q6_K kernels. Memory, read from
# kleidiai.cpp's repack() and get_alloc_size(), not measured:
#   - Steady state: those tensors grow 8/6.5625 = 1.22x, as anonymous
#     memory in a "CPU_KLEIDIAI" buffer, while their file pages are no
#     longer read: about +270 MB for Llama 3.1 8B, up to about 9% of the
#     file across the catalogue (estimated from tensor shapes).
#   - At load: each one is requantised through a temporary int8 buffer of
#     rows x cols bytes, freed before the next, on top of the CPU_KLEIDIAI
#     buffer written so far. The output head is the largest: 128256 x 4096
#     = 501 MiB for Llama 3.1 8B, 262144 x 2560 = 640 MiB for Gemma 3 4B
#     and MedGemma. It comes before llama_init_from_model allocates the KV
#     cache and compute buffers, so it competes with those for room instead
#     of adding to them.
#   - On an SME/SME2 CPU (Arm C1-class flagships, 2025 on), with the
#     SME-family kernels on: every Q6_K/Q8_0 (and Q4_0) weight is packed
#     twice, for the SME kernel and for the NEON i8mm/dotprod one that the
#     threads past the SME thread cap run. That cap is the SMCU count (1 when
#     sysfs does not say), so any multi-threaded run packs both, about 2.44x
#     their Q6_K size; GGML_TOTAL_THREADS only drops the second copy when it
#     is at or below the cap. Q4_K also moves from the evictable mmap into
#     the anonymous buffer. For Llama 3.1 8B the Q6_K copies alone are about
#     +1.8 GB over the file.
# DeviceFit.llmWorkingSetMb (file + 10% + LLM_RUNTIME_MB) is meant to cover
# the first two; the third is far past it. So MEETILY_KLEIDIAI_SME is OFF:
# kleidiai_env.h sets GGML_KLEIDIAI_SME=0 before either engine loads a
# model, and SME phones run the same i8mm/dotprod kernels as other phones.
# Configure with -DMEETILY_KLEIDIAI_SME=ON to measure them on an SME2 phone;
# turn it on for good only with DeviceFit's budget raised to what that
# shows. Neither the precision nor the memory cost has been measured:
# ACCELERATION_FINDINGS.md has the device check (same meeting and model,
# this build against -DGGML_CPU_KLEIDIAI=OFF, peak PSS across load and
# generation). If either is worse, set GGML_CPU_KLEIDIAI OFF in the arm64
# branch below too; with the SME kernels off there is no Q4_K speed-up to
# trade against it.
#
# llama.cpp names the buffer in its "model buffer size" log line, and
# KleidiAI logs its kernel choice once per process ("kleidiai: primary q8
# kernel feature DOTPROD" or "I8MM", "kleidiai: SME disabled", and for q4_k
# "no compatible q4_k kernels found"); llama_jni.c forwards those to
# logcat. whisper.cpp offers its weights to the same extra buffer types,
# but the shipped Whisper models are F16, q5_0 and q5_1. KleidiAI has
# nothing for q5_0/q5_1, and runs F16 matmuls only on SME2 (without
# repacking), which MEETILY_KLEIDIAI_SME also keeps off, so transcription
# stays on ggml's own kernels. GGML_LLAMAFILE keeps llama.cpp's default.
option(MEETILY_KLEIDIAI_SME "Let KleidiAI use its SME/SME2 kernels (memory cost above)" OFF)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()

FetchContent_MakeAvailable(llama_cpp whisper_cpp)

set(WHISPER_JNI meetily_whisper${MEETILY_LIB_SUFFIX})
set(LLAMA_JNI meetily_llama${MEETILY_LIB_SUFFIX})
# vad_guard.cpp: the speech gate's whisper_vad_* calls, where a C++
# exception from whisper.cpp is caught before it can reach a JNI frame.
add_library(${WHISPER_JNI} SHARED whisper_jni.c vad_guard.cpp)
add_library(${LLAMA_JNI} SHARED llama_jni.c chat_render.cpp)

# 16 KB page-size alignment for Android 15+ devices.
target_link_options(${WHISPER_JNI} PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(${LLAMA_JNI} PRIVATE "-Wl,-z,max-page-size=16384")

# Export only the JNI entry points (see jni_exports.map); everything else in
# each library becomes local. This file is built twice (here and by
# :native-v82, with MEETILY_LIB_SUFFIX), and both pairs come through here.
#
# --no-undefined-version is already on in the NDK toolchain; it is repeated
# so that a future exact name in the map that matches nothing fails the link
# rather than silently exporting nothing. The rest of what the map needs is
# also already set by the NDK r27 toolchain file, for every target including
# the fetched ggml/whisper/llama ones: -ffunction-sections -fdata-sections
# when compiling and --gc-sections when linking release builds. Do not add
# compile flags here; ggml's CPU flags decide which phones can run it.
set(MEETILY_JNI_EXPORTS "${CMAKE_CURRENT_SOURCE_DIR}/jni_exports.map")
foreach(jni_lib ${WHISPER_JNI} ${LLAMA_JNI})
    target_link_options(${jni_lib} PRIVATE
        "-Wl,--version-script=${MEETILY_JNI_EXPORTS}"
        "-Wl,--no-undefined-version")
    # Without this an edit to the map does not relink the library.
    set_property(TARGET ${jni_lib} APPEND PROPERTY LINK_DEPENDS "${MEETILY_JNI_EXPORTS}")
    # Read by kleidiai_env.h (see the KleidiAI notes above).
    if (MEETILY_KLEIDIAI_SME)
        target_compile_definitions(${jni_lib} PRIVATE MEETILY_KLEIDIAI_SME=1)
    endif()
endforeach()

target_link_libraries(${WHISPER_JNI} PRIVATE whisper log)
# llama-common (not "common") is the target name, still at v1.0.0.
target_link_libraries(${LLAMA_JNI} PRIVATE llama llama-common log)
