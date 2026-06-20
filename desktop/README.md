# vidcut

Local Gemma-powered video editor. Takes a video and a plain-English instruction,
plans cuts with a small LLM running on your laptop (via Ollama), renders the
edit with ffmpeg.

Sibling project to the WorkoutAutoEditor Android app — same idea, but
cross-platform, faster to iterate, and not limited to workouts.

## Install

Prereqs:

```bash
# 1. Python 3.10+ (you have 3.14)
python --version

# 2. ffmpeg + ffprobe on PATH
ffmpeg -version

# 3. Ollama, with Gemma 4
ollama serve            # in one terminal
ollama pull gemma4      # 8B params, ~4.7 GB. Only Gemma 4 variant in
                        # the Ollama library today; smaller distilled
                        # versions are not yet published. For fast
                        # iteration without the Gemma 4 quality hit,
                        # use --model gemma3:1b (~800 MB) instead.
```

Install vidcut itself:

```bash
cd desktop
pip install -e ".[ui]"   # `ui` extra installs Gradio
```

## Use

### CLI

```bash
# Just plan, don't render (cheap, ~3 s on a small video)
vidcut plan myworkout.mp4 -p "Keep heavy sets, drop warmups, max 60s"

# Plan + render
vidcut edit myworkout.mp4 -o edited.mp4 -p "Keep heavy sets, drop warmups, max 60s"

# Use a bigger model
vidcut edit input.mp4 -o out.mp4 -p "..." -m gemma3:4b

# Faster (stream-copy, only safe on keyframe boundaries)
vidcut edit input.mp4 -o out.mp4 -p "..." --fast
```

### Browser UI

```bash
vidcut serve
# Open http://localhost:7860/
```

### As a library

```python
from pathlib import Path
from vidcut import pipeline

result = pipeline.edit(
    Path("input.mp4"),
    instruction="Keep the heaviest squats and presses",
    output=Path("out.mp4"),
)
print(result.plan.summary)
for span in result.plan.spans:
    print(f"  {span.start_ms}-{span.end_ms}ms: {span.rationale}")
```

## How it works

1. **ffprobe**: get duration, fps, dimensions, audio presence.
2. **ffmpeg scene detect**: find shot boundaries (`select='gt(scene,T)'`).
3. **ffmpeg silencedetect**: per-scene audio activity ratio.
4. **Ollama / Gemma**: send the scene table + your instruction; get back a JSON
   plan of which scenes to keep.
5. **ffmpeg cut + concat**: trim each kept scene, concatenate to one MP4.

If the LLM call fails or the JSON is malformed, we fall back to a heuristic
that keeps the loudest scenes up to 60 s. So the tool always produces *something*
even when Ollama is down.

## Configuration

Environment variables:

| Var | Default | Purpose |
|---|---|---|
| `OLLAMA_URL` | `http://localhost:11434` | Where Ollama is running |
| `VIDCUT_MODEL` | `gemma4:latest` | Default model name |

## Tests

```bash
cd desktop
pip install pytest
pytest
```

Tests don't hit Ollama or ffmpeg — they exercise the JSON parsing,
fallback policy, and pydantic schemas.

## Roadmap

- Per-mode prompt templates (`workout`, `interview`, `tutorial`)
- MediaPipe pose detection for workout-specific rep counting
- Whisper transcription for talking-head cleanup
- Segment-level vision via Gemma 4 multimodal once it lands in Ollama
- Optional speed-up sections instead of just keep/drop
