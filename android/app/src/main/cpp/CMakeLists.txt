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

set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/b4783.tar.gz
)
FetchContent_Declare(
    whisper_cpp
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.7.5.tar.gz
)
FetchContent_MakeAvailable(llama_cpp whisper_cpp)

add_library(meetily_whisper SHARED whisper_jni.c)
add_library(meetily_llama SHARED llama_jni.c)

# 16 KB page-size alignment for Android 15+ devices.
target_link_options(meetily_whisper PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(meetily_llama PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(meetily_whisper PRIVATE whisper log)
target_link_libraries(meetily_llama PRIVATE llama log)
