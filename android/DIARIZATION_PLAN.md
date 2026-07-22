# Acoustic Speaker Diarization — Implementation Plan

Status: **implemented (experimental)** in v1.1.0-beta1 — Settings → Whisper
section → "Auto-detect speakers". (Follows the shipped "diarization-lite"
layer: sticky speakers, live speaker chips, and LLM speaker suggestions.)

## Goal

Automatic "Speaker 1 / Speaker 2 / …" labels on transcript segments as the
meeting runs, with **rename-once**: the user renames a cluster to a real name
and every segment in that cluster — past and future — updates. Fully
on-device.

## Approach

Speaker-embedding diarization piggybacking on the existing Whisper audio
pipeline (`WhisperRecorder` already owns raw 16 kHz mono float PCM and cuts
speech chunks at silences):

```
mic PCM ──► WhisperRecorder chunker ──┬──► whisper.cpp ──► text
                                      └──► embedding model ──► 256-d voice vector
                                                    │
                                        online clustering (cosine ≥ τ)
                                                    │
                                        cluster id ──► segment.speaker = "Speaker N"
```

### Library: sherpa-onnx (Apache-2.0)

Rather than hand-rolling ONNX inference, use [sherpa-onnx], which ships
Android support (JNI + arm64/x86_64) and a ready speaker-diarization stack:

- **Segmentation**: pyannote `segmentation-3.0` exported to ONNX (~6 MB, MIT).
  Optional for us — our energy-based chunker already segments; pyannote adds
  overlap detection later.
- **Speaker embeddings**: a WeSpeaker/3D-Speaker ResNet or ECAPA-TDNN model
  (~25–70 MB ONNX, Apache-2.0). This is the required piece.

Models are downloaded on-device through the existing `WhisperModels`-style
manager (new `DiarizationModels`), not bundled, keeping APK size flat.

### Clustering (the part we own)

Online, incremental, main-thread-free:

1. For each speech chunk ≥ ~1.5 s, compute an embedding on the transcriber
   executor (embedding cost is small next to Whisper itself).
2. Compare (cosine) against existing cluster centroids:
   - best similarity ≥ τ (start at 0.6, tune on-device): assign to that
     cluster, update centroid with a running mean;
   - otherwise: new cluster ("Speaker N+1"), but cap clusters at ~8 and
     require 2 consecutive chunks before promoting a tentative cluster —
     prevents noise/TV/hallway voices from minting speakers.
3. Short chunks (< 1.5 s) inherit the previous chunk's cluster (turn
   continuity) rather than getting their own unreliable embedding.
4. At `finish()`, run one agglomerative merge pass over centroids (merge any
   pair ≥ τ+0.05) and relabel — fixes early-meeting fragmentation.

### Data model & UX

- `TranscriptSegment` gains `clusterId: Int?` (JSON-compatible, optional).
- Auto labels render as "Speaker 1" styled like speaker tags but dimmed;
  tapping a segment keeps today's picker, plus "Rename Speaker 1 everywhere…"
  which maps cluster → real name (updates all segments with that clusterId,
  past and future — the service holds a clusterId→name map).
- Speaker chips: a chip tap while a cluster is active binds that cluster to
  the chip's name (manual signal beats acoustic guess).
- Engine constraint: **Whisper engine only** (the system recognizer exposes
  no audio). The Settings toggle lives under the Whisper section; recording
  with the system engine simply never shows auto labels.

### Precedence rules (manual always wins)

1. Explicit per-segment tag (dialog) — never overwritten.
2. Sticky speaker / chip selection — overrides cluster label at append time.
3. Cluster auto label — fills anything untagged.
4. LLM suggestions — offered only for segments with neither.

## Phases & effort

| Phase | Scope | Estimate |
|---|---|---|
| 1 | sherpa-onnx dependency + DiarizationModels download UI | 1–2 days |
| 2 | Embedding per chunk + online clustering in WhisperRecorder | 2–3 days |
| 3 | clusterId in model + auto-label rendering + rename-once | 2 days |
| 4 | Finish-time merge pass + thresholds tuning on real recordings | 2+ days (open-ended) |
| 5 | CI: none extra (AAR dependency, no NDK changes) | — |

## Risks & mitigations

- **Overlapping speech**: single-label-per-chunk model; overlaps get the
  dominant voice. Mitigation later via pyannote segmentation. Accept for v1.
- **Similar voices merge**: cap damage with the finish-time merge being
  conservative and rename-once making corrections cheap.
- **Thermal/battery**: embeddings add ~5–10% over Whisper; measure on a
  mid-range device before enabling by default (ship behind a toggle, off by
  default initially).
- **Phone-speaker calls**: far-end voices are compressed/band-limited;
  clusters still form but τ may need loosening. Test explicitly.

## Explicitly out of scope (v1)

**Cross-meeting voice profiles** ("this is Alice from last week"). Stored
voiceprints are biometric data (GDPR Art. 9, Illinois BIPA). If ever built:
explicit opt-in per person, on-device only, excluded from backups by default,
one-tap deletion. Not before the core diarization has proven itself.

[sherpa-onnx]: https://github.com/k2-fsa/sherpa-onnx
