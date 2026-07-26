#!/usr/bin/env python3
"""Regenerates app/src/main/assets/notices.txt (the Open-source licences screen).

The licence bodies are fetched from upstream and concatenated verbatim rather
than retyped: Apache-2.0 §4(a) conditions binary redistribution on shipping a
copy of the License, and MIT on reproducing its notice exactly. Retyping is
how those stop being verbatim.

Run this after bumping a native pin in app/src/main/cpp/CMakeLists.txt or the
sherpa-onnx version in scripts/fetch_sherpa_libs.sh, then commit the result.
Needs network access.

    python3 android/scripts/build_notices.py

The MODELS section is maintained by hand below — it tracks the model
registries (WhisperModels, NemoModels, DiarizationModels, LocalLlmModels),
not anything fetchable.
"""
import pathlib
import sys
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE.parent / "app" / "src" / "main" / "assets" / "notices.txt"

# Keep these in step with app/src/main/cpp/CMakeLists.txt and
# scripts/fetch_sherpa_libs.sh.
SHERPA_VER = "v1.13.4"
WHISPER_VER = "v1.8.6"
LLAMA_VER = "b10089"

SOURCES = {
    "apache": f"https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/{SHERPA_VER}/LICENSE",
    "mit_ggml": f"https://raw.githubusercontent.com/ggml-org/whisper.cpp/{WHISPER_VER}/LICENSE",
    "mit_ort": "https://raw.githubusercontent.com/microsoft/onnxruntime/main/LICENSE",
    "mit_openai": "https://raw.githubusercontent.com/openai/whisper/main/LICENSE",
}


def fetch(url: str) -> str:
    with urllib.request.urlopen(url, timeout=60) as response:
        if response.status != 200:
            raise SystemExit(f"{url} returned HTTP {response.status}")
        return response.read().decode("utf-8").rstrip("\n")


def rule(title: str) -> str:
    return "\n\n" + "-" * 80 + "\n" + title + "\n" + "-" * 80 + "\n\n"


HEADER = f"""\
OPEN-SOURCE NOTICES

Recap is built on open-source software, and transcribes and summarises using
speech and language models published by others. This screen lists them, their
licences, and where they came from.

Recap's own source is available under the MIT License.

Models are not shipped inside the app. They are downloaded from the sources
listed in part two only when you choose to install them. A model's licence is
set by its publisher and can change; the linked page is authoritative.


================================================================================
PART ONE - SOFTWARE INCLUDED IN THIS APP
================================================================================

sherpa-onnx {SHERPA_VER}
    Apache License 2.0
    Copyright (c) 2023 Xiaomi Corporation
    https://github.com/k2-fsa/sherpa-onnx
    Included as the native library libsherpa-onnx-jni.so, and as the Kotlin
    API vendored under com.k2fsa.sherpa.onnx. Runs Parakeet, Nemotron and the
    speaker-embedding models.

ONNX Runtime
    MIT License
    Copyright (c) Microsoft Corporation
    https://github.com/microsoft/onnxruntime
    Included as the native runtime distributed with sherpa-onnx.

whisper.cpp {WHISPER_VER}
    MIT License
    Copyright (c) 2023-2026 The ggml authors
    https://github.com/ggml-org/whisper.cpp
    Compiled into the native library libmeetily_whisper.so.

llama.cpp {LLAMA_VER}
    MIT License
    Copyright (c) 2023-2026 The ggml authors
    https://github.com/ggml-org/llama.cpp
    Compiled into the native library libmeetily_llama.so.

ggml
    MIT License
    Copyright (c) 2023-2026 The ggml authors
    https://github.com/ggml-org/ggml
    The tensor library underneath whisper.cpp and llama.cpp; built as part of
    both.

AndroidX (core-ktx, appcompat, constraintlayout, recyclerview, biometric)
    Apache License 2.0
    Copyright (c) The Android Open Source Project
    https://developer.android.com/jetpack/androidx

Material Components for Android 1.12.0
    Apache License 2.0
    Copyright (c) Google LLC
    https://github.com/material-components/material-components-android

ML Kit Text Recognition 16.0.1
    Google APIs Terms of Service
    Copyright (c) Google LLC
    https://developers.google.com/ml-kit
    On-device Latin text recognition, used to read text out of photos you
    attach. No image leaves the device.

Kotlin Standard Library
    Apache License 2.0
    Copyright (c) JetBrains s.r.o. and Kotlin Programming Language contributors
    https://github.com/JetBrains/kotlin


================================================================================
PART TWO - MODELS DOWNLOADED ON DEMAND
================================================================================

--- Speech recognition: Whisper ---

Whisper (tiny, base, small, large-v3-turbo; several quantised)
    MIT License
    Copyright (c) 2022 OpenAI
    https://github.com/openai/whisper
    Downloaded in ggml form from the whisper.cpp model repository:
    https://huggingface.co/ggerganov/whisper.cpp

--- Speech recognition: NVIDIA NeMo, converted for sherpa-onnx ---

Parakeet TDT 0.6B v2 (English)
    Creative Commons Attribution 4.0 International (CC BY 4.0)
    NVIDIA Corporation
    https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2
    Downloaded from the sherpa-onnx conversion:
    https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8

Parakeet TDT 0.6B v3 (25 languages)
    Creative Commons Attribution 4.0 International (CC BY 4.0)
    NVIDIA Corporation
    https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3
    Downloaded from the sherpa-onnx conversion:
    https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8

Nemotron 3.5 ASR Streaming 0.6B (40 language-locales)
    OpenMDW-1.1 - https://openmdw.ai/license/1-1/
    NVIDIA Corporation
    https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b
    Downloaded from the sherpa-onnx conversion:
    https://huggingface.co/csukuangfj2/
    sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11

Nemotron Speech Streaming 0.6B (English)
    NVIDIA Corporation
    https://huggingface.co/nvidia/nemotron-speech-streaming-en-0.6b
    Downloaded from the sherpa-onnx conversion:
    https://huggingface.co/csukuangfj/
    sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14
    Superseded by Nemotron 3.5; kept for installs that already have it.

    The ONNX conversions above are republished by third parties and do not
    themselves carry licence files. The governing terms are the ones on the
    NVIDIA model pages linked for each entry.

--- Speaker recognition (used to tell voices apart) ---

WeSpeaker ResNet34 / ResNet152 / ResNet293 (VoxCeleb)
    Creative Commons Attribution 4.0 International (CC BY 4.0), inherited
    from the VoxCeleb training data
    https://github.com/wenet-e2e/wespeaker
    Downloaded from the sherpa-onnx speaker-recognition model release:
    https://github.com/k2-fsa/sherpa-onnx/releases

NeMo TitaNet large / small
    NVIDIA Corporation
    https://catalog.ngc.nvidia.com/orgs/nvidia/teams/nemo/models/titanet_large
    Downloaded from the sherpa-onnx speaker-recognition model release:
    https://github.com/k2-fsa/sherpa-onnx/releases

3D-Speaker CAM++ (English + Chinese)
    Alibaba DAMO Academy / 3D-Speaker
    https://github.com/modelscope/3D-Speaker
    Downloaded from the sherpa-onnx speaker-recognition model release:
    https://github.com/k2-fsa/sherpa-onnx/releases

--- Summarisation: local language models (GGUF) ---

Qwen 2.5 0.5B / 1.5B Instruct, Qwen 3.5 9B
    Alibaba Cloud
    https://huggingface.co/Qwen

Llama 3.2 3B Instruct, Llama 3.1 8B Instruct
    Meta Platforms, Inc. - Llama Community License
    https://www.llama.com/llama-downloads/
    Llama is a trademark of Meta Platforms, Inc.

Gemma 3 1B / 3 4B / 4 E2B / 4 E4B Instruct, MedGemma 4B
    Google LLC - Gemma Terms of Use
    https://ai.google.dev/gemma/terms
    Gemma is provided under and subject to the Gemma Terms of Use.

Mistral 7B Instruct v0.3
    Mistral AI
    https://huggingface.co/mistralai/Mistral-7B-Instruct-v0.3

SaulLM 7B Instruct
    Equall
    https://huggingface.co/Equall/Saul-7B-Instruct-v1

    GGUF conversions of the above are downloaded from the quantisation
    repositories they name in the app's model list (bartowski, unsloth,
    tensorblock and the publishers' own repositories).

Summaries produced by these models are drafts. They are not medical, legal or
professional advice, whichever model made them.


================================================================================
PART THREE - LICENCE TEXTS
================================================================================
"""

CC_BY = """\
Full text: https://creativecommons.org/licenses/by/4.0/legalcode
Summary:   https://creativecommons.org/licenses/by/4.0/

You are free to share and adapt the material for any purpose, including
commercially, provided you give appropriate credit, provide a link to the
licence, and indicate if changes were made. The attributions above are
given for that purpose.
"""


def main() -> int:
    try:
        texts = {name: fetch(url) for name, url in SOURCES.items()}
    except Exception as error:  # noqa: BLE001 - the message is the point
        print(f"could not fetch a licence: {error}", file=sys.stderr)
        return 1

    for name in ("apache", "mit_ggml", "mit_ort", "mit_openai"):
        if len(texts[name]) < 500:
            print(f"{name} looks truncated ({len(texts[name])} bytes)", file=sys.stderr)
            return 1

    body = [
        HEADER,
        rule("MIT License - whisper.cpp, llama.cpp, ggml"),
        texts["mit_ggml"],
        rule("MIT License - ONNX Runtime"),
        texts["mit_ort"],
        rule("MIT License - OpenAI Whisper models"),
        texts["mit_openai"],
        rule(
            "Apache License 2.0 - sherpa-onnx, AndroidX, Material Components, Kotlin"
        ).rstrip("\n") + "\n",
        texts["apache"],
        rule("Creative Commons Attribution 4.0 International (CC BY 4.0)"),
        CC_BY,
    ]

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("".join(body))
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
