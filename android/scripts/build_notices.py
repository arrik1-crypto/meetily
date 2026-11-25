#!/usr/bin/env python3
"""Regenerates app/src/main/assets/notices.txt (the Open-source licences screen).

The licence bodies are fetched from upstream and concatenated verbatim rather
than retyped: Apache-2.0 §4(a) conditions binary redistribution on shipping a
copy of the License, and MIT on reproducing its notice exactly. Retyping is
how those stop being verbatim.

Run this after bumping a native pin in app/src/main/cpp/CMakeLists.txt, the
sherpa-onnx version in scripts/fetch_sherpa_libs.sh, the bundled Silero VAD
model (SILERO_VER below) or the bundled GTCRN speech enhancer (GTCRN_REV), then
commit the result. Needs network access.

    python3 android/scripts/build_notices.py

The MODELS section is maintained by hand below — it tracks the model
registries (WhisperModels, NemoModels, DiarizationModels, LocalLlmModels,
AlignerModels), not anything fetchable. Every licence it names must agree with
app/src/main/java/com/meetily/mobile/licences/ModelLicences.kt, which labels
the model pickers and decides which downloads ask for acceptance first.
ModelLicencesTest finds each model's entry by its heading and checks that the
entry's "Licence:" line names the same licence, so a renamed heading or a new
model means updating that test's heading map too. A licence is named only
when a primary source for it was read; anything else says "Licence: see
model page" and links the publisher's page.

The model agreements in part three are the Llama 3.2 and 3.1 Community
License Agreements (from Meta's llama-models repository), and copies of the
Gemma Terms of Use and the Health AI Developer Foundations Terms of Use taken
from the ScanCode LicenseDB, because Google's pages are HTML. Long lines in
those four are re-wrapped to 80 columns for the screen; the words are checked
to be unchanged. The notice sentences those agreements require are checked
to appear in part two exactly as the agreement states them.
"""
import pathlib
import re
import sys
import textwrap
import urllib.request
from typing import Optional

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE.parent / "app" / "src" / "main" / "assets" / "notices.txt"

# Keep these in step with app/src/main/cpp/CMakeLists.txt and
# scripts/fetch_sherpa_libs.sh.
SHERPA_VER = "v2.0.3"
# Bundled in that sherpa-onnx release's libonnxruntime.so (its version string
# reads 1.28.2), not chosen by us; update it with SHERPA_VER.
ORT_VER = "1.28.2"
WHISPER_VER = "b5907"
LLAMA_VER = "v1.0.0"
# Pinned by ggml at LLAMA_VER (ggml/src/ggml-cpu/CMakeLists.txt), not by us.
KLEIDIAI_VER = "v1.32.0"
# Vendored by llama.cpp at LLAMA_VER (vendor/nlohmann/json.hpp, "version
# 3.12.0"), not chosen by us; llama-common links it, and the chat-template
# code built from it is compiled into the JNI library. Update with LLAMA_VER.
NLOHMANN_VER = "3.12.0"
# The Silero VAD release whose weights app/src/main/assets/ggml-silero-v6.2.0.bin
# carries (whisper.cpp's ggml conversion; SileroAssetTest pins its bytes).
SILERO_VER = "v6.2"
# The GTCRN commit whose LICENSE covers app/src/main/assets/gtcrn_simple.onnx
# (the model from that repository's stream/onnx_models, with the metadata
# sherpa-onnx adds; SpeechEnhanceAssetTest pins its bytes). The only commit
# that has touched LICENSE.
GTCRN_REV = "faf210c9309fcf11c150f8732c40551b202a5838"

# ScanCode keeps plain-text copies of licences that are only published as web
# pages. Each file opens with YAML front matter, stripped before use.
SCANCODE = (
    "https://raw.githubusercontent.com/aboutcode-org/scancode-toolkit/develop/"
    "src/licensedcode/data/licenses/"
)

SOURCES = {
    "apache": f"https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/{SHERPA_VER}/LICENSE",
    "mit_ggml": f"https://raw.githubusercontent.com/ggml-org/whisper.cpp/{WHISPER_VER}/LICENSE",
    "mit_nlohmann": f"https://raw.githubusercontent.com/nlohmann/json/v{NLOHMANN_VER}/LICENSE.MIT",
    "mit_ort": "https://raw.githubusercontent.com/microsoft/onnxruntime/main/LICENSE",
    "mit_openai": "https://raw.githubusercontent.com/openai/whisper/main/LICENSE",
    "mit_silero": f"https://raw.githubusercontent.com/snakers4/silero-vad/{SILERO_VER}/LICENSE",
    "mit_gtcrn": f"https://raw.githubusercontent.com/Xiaobin-Rong/gtcrn/{GTCRN_REV}/LICENSE",
    "llama3_2": "https://raw.githubusercontent.com/meta-llama/llama-models/main/models/llama3_2/LICENSE",
    "llama3_1": "https://raw.githubusercontent.com/meta-llama/llama-models/main/models/llama3_1/LICENSE",
    "gemma": SCANCODE + "gemma-tou-2025-03-24.LICENSE",
    "hai_def": SCANCODE + "health-ai-developer-tou-2024.LICENSE",
}

# Bodies under these keys are model agreements: re-wrapped for the screen
# (see wrap_for_screen) and checked for the notice they require.
AGREEMENTS = ("llama3_2", "llama3_1", "gemma", "hai_def")


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

Apart from the small Silero speech detector, the small GTCRN speech enhancer
and ML Kit's text-recognition model, all listed in part one, models are not
shipped inside the app. They are downloaded from the sources listed in part
two only when you choose to install them. A model's licence is set by its
publisher and can change; the linked page is authoritative.


Built with Llama

    Recap can summarise on the phone with Meta's Llama 3.2 3B Instruct and
    Llama 3.1 8B Instruct, which you can choose to download in Settings.
    Their licence agreements are reproduced in part three, and the notices
    they require are in part two.


Model use restrictions

    The licences of the Llama, Gemma 3 and MedGemma models restrict what
    the models may be used for, and require those restrictions to be passed
    on to you. They are part of Recap's Terms of Use (Settings > Terms of use),
    and Recap asks you to accept them once, before the first download of a
    model under each licence.


================================================================================
PART ONE - SOFTWARE INCLUDED IN THIS APP
================================================================================

sherpa-onnx {SHERPA_VER}
    Apache License 2.0
    Copyright (c) 2023 Xiaomi Corporation
    https://github.com/k2-fsa/sherpa-onnx
    Included as the native library libsherpa-onnx-jni.so, and as the Kotlin
    API vendored under com.k2fsa.sherpa.onnx. Runs Parakeet, Nemotron, the
    speaker-embedding models and the GTCRN speech enhancer.

ONNX Runtime {ORT_VER}
    MIT License
    Copyright (c) Microsoft Corporation
    https://github.com/microsoft/onnxruntime
    Included as the native runtime distributed with sherpa-onnx.

whisper.cpp {WHISPER_VER}
    MIT License
    Copyright (c) 2023-2026 The ggml authors
    https://github.com/ggml-org/whisper.cpp
    Compiled, with llama.cpp, into the native library libmeetily_native.so
    (libmeetily_native_v82.so on arm64 phones with dot-product and
    half-precision instructions). The speech detector's self-test clip
    vad_probe.pcm, included in the app, is 1.5 seconds of the project's
    samples/jfk.wav (President John F. Kennedy's inaugural address, 1961).

llama.cpp {LLAMA_VER}
    MIT License
    Copyright (c) 2023-2026 The ggml authors
    https://github.com/ggml-org/llama.cpp
    Compiled, with whisper.cpp, into the native library libmeetily_native.so
    (or libmeetily_native_v82.so).

JSON for Modern C++ (nlohmann/json) {NLOHMANN_VER}
    MIT License
    Copyright (c) 2013-2025 Niels Lohmann
    https://github.com/nlohmann/json
    Carried by llama.cpp and compiled with it into that native library,
    where its chat-template code uses it. It contains Bjoern Hoehrmann's
    UTF-8 decoder and Florian Loitsch's Grisu2 (both MIT License), a copy
    of Hedley by Evan Nemerson (CC0-1.0) and parts of Google Abseil (Apache
    License 2.0).

ggml
    MIT License
    Copyright (c) 2023-2026 The ggml authors
    https://github.com/ggml-org/ggml
    The tensor library underneath whisper.cpp and llama.cpp; built once into
    that library and shared by both.

Arm KleidiAI {KLEIDIAI_VER}
    Apache License 2.0
    Copyright (c) 2024-2026 Arm Limited and/or its affiliates
    Copyright (c) 2026 Fujitsu Limited
    Copyright (c) 2026 Meta Platforms, Inc. and affiliates
    https://github.com/ARM-software/kleidiai
    Optimised matrix-multiplication kernels, compiled into that native
    library on arm64 phones and used by ggml when the processor supports
    them.

Silero VAD {SILERO_VER}
    MIT License
    Copyright (c) 2020-present Silero Team
    https://github.com/snakers4/silero-vad
    Included in the app as the speech-detection model ggml-silero-v6.2.0.bin,
    in the ggml form the whisper.cpp project publishes at
    https://huggingface.co/ggml-org/whisper-vad. Run on the phone through
    whisper.cpp to skip silence and noise before transcription.

GTCRN (gtcrn_simple)
    MIT License
    Copyright (c) 2024 Rong Xiaobin
    https://github.com/Xiaobin-Rong/gtcrn
    Included in the app as the speech-enhancement model gtcrn_simple.onnx,
    with the metadata the sherpa-onnx project adds, as published at
    https://github.com/k2-fsa/sherpa-onnx/releases/tag/speech-enhancement-models
    Run on the phone through sherpa-onnx when you ask for a meeting's clear
    voice copy, for playback and clips.

AndroidX (core-ktx, appcompat, constraintlayout, recyclerview, biometric, profileinstaller)
    Apache License 2.0
    Copyright (c) The Android Open Source Project
    https://developer.android.com/jetpack/androidx

Material Components for Android 1.12.0
    Apache License 2.0
    Copyright (c) Google LLC
    https://github.com/material-components/material-components-android

Material Design Icons
    Apache License 2.0
    Copyright (c) Google LLC
    https://github.com/google/material-design-icons
    Some of the app's icons are drawn from this set, such as the player's
    back and forward 15 seconds.

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

Each entry names the model, its licence, and where it is downloaded from.
"Licence: see model page" means the licence was not confirmed from a primary
source for this release. The publisher's page linked in the entry sets the
terms; read it before you download the model.

--- Speech recognition: Whisper ---

Whisper (tiny, base, small, large-v3-turbo; several quantised)
    Licence: MIT License (code and model weights)
    Copyright (c) 2022 OpenAI
    https://github.com/openai/whisper
    Downloaded in ggml form from the whisper.cpp model repository:
    https://huggingface.co/ggerganov/whisper.cpp

--- Speech recognition: NVIDIA NeMo, converted for sherpa-onnx ---

Parakeet TDT 0.6B v2 (English)
    Licence: Creative Commons Attribution 4.0 International (CC BY 4.0)
    Trained by NVIDIA Corporation
    https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2
    Converted to ONNX and quantised to int8 by the sherpa-onnx project, and
    downloaded from that conversion:
    https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8

Parakeet TDT 0.6B v3 (25 languages)
    Licence: Creative Commons Attribution 4.0 International (CC BY 4.0)
    Trained by NVIDIA Corporation
    https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3
    Converted to ONNX and quantised to int8 by the sherpa-onnx project, and
    downloaded from that conversion:
    https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8

Parakeet Ultra 0.6B (25 languages)
    Licence: Creative Commons Attribution 4.0 International (CC BY 4.0)
    Trained by Moondream as a post-training of NVIDIA's Parakeet TDT 0.6B v3
    https://huggingface.co/moondream/parakeet-ultra
    https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3
    Converted to ONNX and quantised to int8 by the sherpa-onnx project, and
    downloaded from that conversion:
    https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-ultra-int8
    Neither NVIDIA nor Moondream endorses Recap.

Nemotron 3.5 ASR Streaming 0.6B (40 language-locales)
    Licence: see model page
    NVIDIA Corporation
    https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b
    Downloaded from the sherpa-onnx conversion:
    https://huggingface.co/csukuangfj2/
    sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11

Nemotron Speech Streaming 0.6B (English)
    Licence: see model page
    NVIDIA Corporation
    https://huggingface.co/nvidia/nemotron-speech-streaming-en-0.6b
    Downloaded from the sherpa-onnx conversion:
    https://huggingface.co/csukuangfj/
    sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14
    Superseded by Nemotron 3.5; kept for installs that already have it.

--- Speaker recognition (used to tell voices apart) ---

WeSpeaker ResNet34 / ResNet152 / ResNet293 (VoxCeleb)
    Licence: Creative Commons Attribution 4.0 International (CC BY 4.0),
    which WeSpeaker applies because the models are trained on VoxCeleb
    https://github.com/wenet-e2e/wespeaker/blob/master/docs/pretrained.md
    Model metadata added by the sherpa-onnx project; downloaded from its
    speaker-recognition model release:
    https://github.com/k2-fsa/sherpa-onnx/releases

NeMo TitaNet large / small
    Licence: see model page
    NVIDIA Corporation
    https://catalog.ngc.nvidia.com/orgs/nvidia/teams/nemo/models/titanet_large
    https://catalog.ngc.nvidia.com/orgs/nvidia/teams/nemo/models/titanet_small
    Downloaded from the sherpa-onnx speaker-recognition model release:
    https://github.com/k2-fsa/sherpa-onnx/releases

3D-Speaker CAM++ (English + Chinese)
    Licence: Apache License 2.0
    Alibaba DAMO Academy / 3D-Speaker
    https://github.com/modelscope/3D-Speaker
    Downloaded from the sherpa-onnx speaker-recognition model release:
    https://github.com/k2-fsa/sherpa-onnx/releases

--- Word re-timing (forced alignment, English) ---

English MFA acoustic model and dictionary v3.0.0
    Licence: Creative Commons Attribution 4.0 International (CC BY 4.0)
    Michael McAuliffe and Morgan Sonderegger, Montreal Forced Aligner:
    English MFA acoustic model v3.0.0 and English MFA dictionary v3.0.0
    https://github.com/MontrealCorpusTools/mfa-models
    Changed by the sherpa-onnx project: repackaged with only the files
    alignment needs, and a pronunciation lexicon generated from the
    dictionary for the words of its English word list (lexicon-en-us.txt,
    from sherpa-onnx's tts-models release), keeping that list's 688
    multi-word phrase readings. The word list's header says it was
    generated by build_lexicon.py from CMUDict, en_list and compounds;
    its phrase readings are eSpeak NG output, some of them entries of
    eSpeak NG's English exceptions list (dictsource/en_list; eSpeak NG is
    GPL-3.0-or-later). sherpa-onnx states no licence for the word list.
    Downloaded from the sherpa-onnx fa-models release:
    https://github.com/k2-fsa/sherpa-onnx/releases/tag/fa-models

--- Summarisation: local language models (GGUF) ---

Qwen 2.5 0.5B Instruct, Qwen 2.5 1.5B Instruct
    Licence: Apache License 2.0
    Alibaba Cloud (Qwen team)
    https://github.com/QwenLM/Qwen3/blob/v2.5/README.md
    Downloaded from the Qwen team's own GGUF repositories:
    https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF
    https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF

Qwen 3.5 2B, Qwen 3.5 4B
    Licence: Apache License 2.0
    Alibaba Cloud (Qwen team)
    https://huggingface.co/Qwen/Qwen3.5-2B
    https://huggingface.co/Qwen/Qwen3.5-4B
    GGUF conversions downloaded from:
    https://huggingface.co/bartowski/Qwen_Qwen3.5-2B-GGUF
    https://huggingface.co/bartowski/Qwen_Qwen3.5-4B-GGUF

Qwen 3.5 9B
    Licence: Apache License 2.0
    Alibaba Cloud (Qwen team)
    https://huggingface.co/Qwen/Qwen3.5-9B
    GGUF conversion downloaded from:
    https://huggingface.co/bartowski/Qwen_Qwen3.5-9B-GGUF

Llama 3.2 3B Instruct - Built with Llama
    Licence: Llama 3.2 Community License Agreement (full text in part three)
    Acceptable Use Policy: https://www.llama.com/llama3_2/use-policy
    Llama 3.2 is licensed under the Llama 3.2 Community License,
    Copyright © Meta Platforms, Inc. All Rights Reserved.
    https://www.llama.com/llama-downloads
    GGUF conversion downloaded from:
    https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF

Llama 3.1 8B Instruct - Built with Llama
    Licence: Llama 3.1 Community License Agreement (full text in part three)
    Acceptable Use Policy: https://llama.meta.com/llama3_1/use-policy
    Llama 3.1 is licensed under the Llama 3.1 Community License,
    Copyright © Meta Platforms, Inc. All Rights Reserved.
    https://llama.meta.com/llama-downloads
    GGUF conversion downloaded from:
    https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF

    Llama is a trademark of Meta Platforms, Inc.

Gemma 3 1B Instruct, Gemma 3 4B Instruct
    Licence: Gemma Terms of Use (copy in part three)
    Prohibited Use Policy: https://ai.google.dev/gemma/prohibited_use_policy
    Gemma is provided under and subject to the Gemma Terms of Use found at
    ai.google.dev/gemma/terms
    Google LLC
    GGUF conversions downloaded from:
    https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF
    https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF

Gemma 4 E2B Instruct, Gemma 4 E4B Instruct
    Licence: Apache License 2.0
    Google LLC
    https://ai.google.dev/gemma/docs/gemma_4_license
    https://ai.google.dev/gemma/docs/core/model_card_4
    GGUF conversions downloaded from:
    https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF
    https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF
    Quantisation-aware-trained Q4_0 builds downloaded from Google's own
    repositories:
    https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf
    https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf

MedGemma 4B
    Licence: Health AI Developer Foundations Terms of Use (copy in part
    three). MedGemma is built on Gemma 3, but these terms govern it, not the
    Gemma Terms of Use.
    Prohibited Use Policy:
    https://developers.google.com/health-ai-developer-foundations/prohibited-use-policy
    HAI-DEF is provided under and subject to the Health AI Developer
    Foundations Terms of Use found at
    https://developers.google.com/health-ai-developer-foundations/terms
    Google LLC
    https://github.com/Google-Health/medgemma
    GGUF conversion downloaded from:
    https://huggingface.co/unsloth/medgemma-4b-it-GGUF

Mistral 7B Instruct v0.3
    Licence: see model page
    Mistral AI
    https://huggingface.co/mistralai/Mistral-7B-Instruct-v0.3
    GGUF conversion downloaded from:
    https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF

    The GGUF files above are quantised conversions made by the repositories
    named for each model (bartowski, unsloth and the publishers' own). They
    are modified versions of the original weights, and the original licence
    applies to them.

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


AGREEMENT_TITLES = {
    "llama3_2": "Llama 3.2 Community License Agreement",
    "llama3_1": "Llama 3.1 Community License Agreement",
    "gemma": "Gemma Terms of Use",
    "hai_def": "Health AI Developer Foundations Terms of Use",
}

AGREEMENT_SOURCES = {
    "llama3_2": "https://github.com/meta-llama/llama-models/blob/main/models/llama3_2/LICENSE",
    "llama3_1": "https://github.com/meta-llama/llama-models/blob/main/models/llama3_1/LICENSE",
    "gemma": "https://ai.google.dev/gemma/terms",
    "hai_def": "https://developers.google.com/health-ai-developer-foundations/terms",
}

# Where each agreement states the notice it requires. Llama's sits in curly
# quotes; the Google terms use straight quotes.
NOTICE_PATTERNS = {
    "llama3_2": r"following attribution notice within a “Notice” text file "
                r"distributed as a part of such copies: “(.+?)”",
    "llama3_1": r"following attribution notice within a “Notice” text file "
                r"distributed as a part of such copies: “(.+?)”",
    "gemma": r'contains the following notice: "(.+?)"',
    "hai_def": r'contains the following notice: "(.+?)"',
}


def squash(text: str) -> str:
    """Whitespace-insensitive form, for comparing re-wrapped text."""
    return " ".join(text.split())


def strip_front_matter(text: str) -> str:
    """Drops the YAML block a ScanCode licence file opens with."""
    if not text.startswith("---\n"):
        return text
    end = text.find("\n---\n", 4)
    if end < 0:
        raise SystemExit("unterminated front matter in a ScanCode licence file")
    return text[end + len("\n---\n"):].strip("\n")


def plain_markdown(text: str) -> str:
    """Bold markers, heading hashes and link syntax out; words untouched."""
    text = text.replace("**", "")
    text = re.sub(r"^#+ ", "", text, flags=re.M)
    text = re.sub(r"\[([^\]]+)\]\((https?://[^)]+)\)",
                  lambda m: m.group(1) if m.group(1) in m.group(2)
                  else f"{m.group(1)} ({m.group(2)})", text)
    return re.sub(r"<(https?://[^>]+)>", r"\1", text)


def wrap_for_screen(text: str, width: int = 80) -> str:
    """Re-wraps lines longer than [width], keeping each line's indent.

    The Llama and Google agreements put a whole paragraph on one line, and
    the licences screen does not reflow (the MIT and Apache texts above are
    hard-wrapped and must stay that way), so a 700-character line would make
    every paragraph a sideways scroll.
    """
    out = []
    for line in text.split("\n"):
        if len(line) <= width:
            out.append(line.rstrip())
            continue
        indent = line[:len(line) - len(line.lstrip())]
        out.append(textwrap.fill(
            line.strip(), width=width, initial_indent=indent,
            subsequent_indent=indent, break_long_words=False,
            break_on_hyphens=False,
        ))
    wrapped = "\n".join(out)
    if squash(wrapped) != squash(text):
        raise SystemExit("re-wrapping changed the words of an agreement")
    return wrapped


def last_modified(text: str) -> str:
    match = re.search(r"Last modified: ([A-Z][a-z]+ \d{1,2}, \d{4})", text)
    if not match:
        raise SystemExit("no 'Last modified' date in a Google terms copy")
    return match.group(1)


def agreement_preamble(name: str, text: str) -> str:
    if name in ("gemma", "hai_def"):
        return (
            f"Copy of the terms as last modified {last_modified(text)}, from the\n"
            f"ScanCode LicenseDB. The current version is at\n"
            f"{AGREEMENT_SOURCES[name]}\n\n"
        )
    return f"From {AGREEMENT_SOURCES[name]}\n\n"


def check_notices(texts: dict) -> Optional[str]:
    """Every notice an agreement requires must appear in part two as stated."""
    header = squash(HEADER)
    for name, pattern in NOTICE_PATTERNS.items():
        match = re.search(pattern, texts[name], re.S)
        if not match:
            return f"{name}: could not find the notice the agreement requires"
        notice = squash(match.group(1))
        if notice not in header:
            return f"{name}: part two lacks the required notice: {notice}"
    for name in ("llama3_2", "llama3_1"):
        if "Built with Llama" not in texts[name]:
            return f"{name}: no longer asks for 'Built with Llama'; review part two"
    if "Built with Llama" not in HEADER:
        return "the header must display 'Built with Llama'"
    return None


def main() -> int:
    try:
        texts = {name: fetch(url) for name, url in SOURCES.items()}
    except Exception as error:  # noqa: BLE001 - the message is the point
        print(f"could not fetch a licence: {error}", file=sys.stderr)
        return 1

    for name in SOURCES:
        if len(texts[name]) < 500:
            print(f"{name} looks truncated ({len(texts[name])} bytes)", file=sys.stderr)
            return 1

    for name in ("gemma", "hai_def"):
        texts[name] = plain_markdown(strip_front_matter(texts[name]))

    problem = check_notices(texts)
    if problem:
        print(problem, file=sys.stderr)
        return 1

    agreements = []
    for name in AGREEMENTS:
        agreements += [
            rule(AGREEMENT_TITLES[name]),
            agreement_preamble(name, texts[name]),
            wrap_for_screen(texts[name]),
        ]

    body = [
        HEADER,
        rule("MIT License - whisper.cpp, llama.cpp, ggml"),
        texts["mit_ggml"],
        rule("MIT License - JSON for Modern C++ (nlohmann/json)"),
        texts["mit_nlohmann"],
        rule("MIT License - ONNX Runtime"),
        texts["mit_ort"],
        rule("MIT License - OpenAI Whisper models"),
        texts["mit_openai"],
        rule("MIT License - Silero VAD"),
        texts["mit_silero"],
        rule("MIT License - GTCRN"),
        texts["mit_gtcrn"],
        rule(
            "Apache License 2.0 - sherpa-onnx, Arm KleidiAI, AndroidX, Material Components,"
            " Material Design Icons, Kotlin, Google Abseil (in nlohmann/json)"
        ).rstrip("\n") + "\n",
        texts["apache"],
        rule("Creative Commons Attribution 4.0 International (CC BY 4.0)"),
        CC_BY.rstrip("\n"),
        *agreements,
    ]

    OUT.parent.mkdir(parents=True, exist_ok=True)
    # Bytes, not write_text: the app reads the asset as UTF-8 and the
    # agreements carry "©" and curly quotes, which the platform's default
    # encoding (cp1252 on Windows) would corrupt; bytes also keep LF endings.
    OUT.write_bytes(("".join(body).rstrip("\n") + "\n").encode("utf-8"))
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
