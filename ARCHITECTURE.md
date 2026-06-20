# Architecture

## Why this design

The naive approach - feed every frame of a 10-minute workout to a vision
language model - fails on three counts:

1. **Battery and heat**: continuous VLM inference would drain a Pixel in
   under 20 minutes and trigger thermal throttle within 5.
2. **Latency**: even at 30 tok/s on the Tensor NPU, per-frame Q&A takes
   400-800 ms. A 10-min clip at 5 fps is 3000 frames; that's 20-40 minutes
   of pure inference, before pose, audio, or render.
3. **Marginal accuracy**: for the questions we actually need answered
   ("what exercise was that?", "is this a working set?", "is the person
   in frame?") cheap signals - pose landmarks, audio energy, motion
   vectors - give 80% of the answer at 1% of the cost. The VLM should
   be reserved for the 20% of cases the cheap signals can't decide.

So the architecture is a four-layer cascade. Cheap signals do structural
work; the LLM is called surgically on a handful of ambiguous segments;
the final composition uses the platform's hardware-accelerated encoder.

## The four layers

### 1. Cheap signals
- **MediaPipe Pose Landmarker** at 5 fps in VIDEO mode. 33 landmarks per
  frame with visibility + presence scores. Runs offline on a saved clip,
  not live during recording - recording itself stays at 1080p H.264 30 fps
  with no per-frame analysis.
- **Audio RMS envelope** at 100 ms resolution. Used as a corroborating
  signal for set boundaries (loud rep grunts vs. quiet rest).

### 2. Mid-level interpretation
- **Rep state machine** per exercise (`RepDetector.kt`). Joint-angle
  thresholds with hysteresis to prevent phantom counts. Configs for
  squat (knee angle), pushup (elbow angle), curl, press.
- **Pose embeddings** (`PoseEmbedder.kt`). 40-dim normalized vector per
  frame. Translation invariance via hip-midpoint origin, scale invariance
  via torso-size normalization. Output is (dx, dy) for 20 hand-picked
  joint pairs.
- **k-NN classifier** (`KnnClassifier.kt`). Weighted vote, k=10,
  inverse-distance weighting, returns UNKNOWN when no training sample is
  close enough. Schema-versioned so old training samples are invalidated
  when joint pairs change.
- **Rule-based classifier** (`ExerciseClassifier.kt`, fallback path).
  Body orientation + motion profile + wrist-above-shoulder ratio.
- **Timeline builder** (`TimelineBuilder.kt`). Windows the per-frame
  classifications, runs the rep detector, emits Segments.

### 3. LLM, surgically applied
- **Instruction parsing** (`InstructionParser.kt`). User's free-text
  instruction -> structured `EditPolicy` JSON. Single Gemma text call,
  ~3 seconds, balanced-brace JSON extraction with retry, falls back to
  `EditPolicy.DEFAULT_TIGHT` if both fail.
- **Keyframe annotation** (`KeyframeAnnotator.kt`). Picks ~10-15 of the
  most uncertain segments, serializes their features as text, and asks
  Gemma to label warmup vs working_set vs rest. v1 is text-only;
  vision modality requires a newer MediaPipe tasks-genai (0.10.16+).

### 4. Composition
- **Cut-list builder** (`CutListBuilder.kt`). Six stages: filter by class,
  drop low-quality, cap per-exercise, merge nearby, trim to target.
- **Video editor** (`VideoEditor.kt`). Media3 Transformer Composition.
  Each surviving segment becomes a `MediaItem` with `ClippingConfiguration`.

## State machine

The pipeline is split into two phases so the UI can gate on a confirmation
step. Cheap parse first; user confirms; heavy work follows.

```
IDLE
  | startParsing(source, instruction, model, output)
  v
PARSING_INSTRUCTION
  | Gemma text -> EditPolicy
  v
AWAITING_CONFIRMATION
  | user reviews policy in ReviewScreen,
  | optionally tweaks toggles / sliders,
  | taps Confirm -> confirmAndProceed(override?)
  v
PROCESSING
  | pose -> audio -> timeline -> keyframes -> cut -> render
  v
DONE | FAILED
  | acknowledgeTerminal()
  v
IDLE
```

`cancel()` from any phase returns to IDLE.

`EditPipelineService` exposes `Phase`, `parsedPolicy`, `Stage`, `outputFile`,
and `error` as `StateFlow`s. UI screens bind via `rememberPipelineService()`
and observe whatever they need. The service is a foreground service with a
low-priority notification so the system doesn't kill it during long pose passes.

## Resource lifecycle

Three large models can't co-exist in memory on most devices. The pipeline
opens and closes them in sequence:

1. Open Gemma -> parse instruction -> close.
2. Open MediaPipe Pose -> analyze video -> close.
3. Open Gemma -> annotate keyframes -> close.
4. Open MediaCodec via Transformer -> render -> close.

This keeps peak memory under ~3 GB, comfortably below the per-app limit
on most devices.

## Training data

Stored as JSON in app-specific external storage at
`getExternalFilesDir(null)/training/samples.json`. Each sample is
`{exercise, embedding[40], schemaVersion, state?, sourceClip?, createdAtMs}`.
The schema version is bumped in `TrainingDataStore.SCHEMA_VERSION` whenever
`PoseEmbedder.PAIRS` changes; on read, samples from older schemas are
filtered out.

## Performance budget (Pixel 8 Pro, plugged in)

| Stage | Time for 10-min clip |
|---|---|
| Instruction parse | ~3 s |
| Pose pass | ~90 s |
| Audio pass | ~5 s |
| Timeline build | ~1 s |
| Keyframe annotation | ~30-60 s |
| Cut-list build | <1 s |
| Render | ~60 s |
| **Total** | **~3-5 min** |

## Soft spots

1. The MediaPipe `tasks-genai` API surface shifts between releases. Pinned
   to 0.10.14 for stability; if you bump, verify `LlmInference` constructor
   names and `generateResponse` signature in `GemmaService.kt`.
2. `flow{}` in `EditPipeline.runFromPolicy` doesn't surface intra-stage
   progress (e.g., per-frame pose progress) because `flow{}` restricts
   `emit` to the producer coroutine. A future change to `channelFlow`
   would let the pose analyzer's `onProgress` callback bubble up.
3. Gemma keyframe selection is currently uncertainty-driven (low-confidence
   segments). A learned policy over which segments actually benefit from
   LLM review would cut Gemma calls further.
4. The KNN classifier scans linearly. With a few thousand samples this is
   under a millisecond per query, so a KD-tree isn't needed yet.
