"""Ollama HTTP client for local Gemma (or any other model)."""
from __future__ import annotations

import json
import logging
import os
import re
from typing import Any

import requests

from vidcut.models import CutPlan, CutSpan, Scene, VideoMeta

log = logging.getLogger(__name__)

OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434")
# Gemma 4 is the only Gemma 4 tag in the Ollama library right now (8B params,
# 4.7 GB on disk). Smaller distilled variants are not yet published. Override
# with VIDCUT_MODEL or --model on the CLI if you want gemma3:1b or similar.
DEFAULT_MODEL = os.environ.get("VIDCUT_MODEL", "gemma4:latest")


def _post(prompt: str, model: str, temperature: float = 0.2) -> str:
    """Send a prompt to Ollama, return the raw text response."""
    try:
        resp = requests.post(
            f"{OLLAMA_URL}/api/generate",
            json={
                "model": model,
                "prompt": prompt,
                "stream": False,
                "options": {"temperature": temperature, "num_predict": 1024},
            },
            timeout=180,
        )
    except requests.exceptions.ConnectionError as e:
        raise RuntimeError(
            f"Cannot reach Ollama at {OLLAMA_URL}. Run `ollama serve` in another terminal, "
            f"and `ollama pull {model}` if you have not yet."
        ) from e
    except requests.exceptions.Timeout as e:
        raise RuntimeError(f"Ollama request timed out after 180 s. Try a smaller model.") from e
    except requests.exceptions.RequestException as e:
        raise RuntimeError(f"Ollama HTTP error: {e}") from e
    if resp.status_code != 200:
        raise RuntimeError(f"Ollama returned HTTP {resp.status_code}: {resp.text[:300]}")
    return resp.json().get("response", "")


def _balanced_json(raw: str) -> str | None:
    """Extract the first balanced {...} or [...] from a model response.

    String-aware: braces inside JSON string literals do not affect depth.
    Bails out for very large blobs (>1 MB) to avoid pathological inputs.
    """
    if len(raw) > 1_000_000:
        return None
    depth = 0
    start = -1
    in_string = False
    escape = False
    for i, c in enumerate(raw):
        if in_string:
            if escape:
                escape = False
            elif c == "\\":
                escape = True
            elif c == '"':
                in_string = False
            continue
        if c == '"':
            in_string = True
            continue
        if c in "{[":
            if depth == 0:
                start = i
            depth += 1
        elif c in "}]":
            depth -= 1
            if depth == 0 and start >= 0:
                return raw[start:i + 1]
    return None


def plan_cuts(
    meta: VideoMeta,
    scenes: list[Scene],
    instruction: str,
    model: str = DEFAULT_MODEL,
) -> CutPlan:
    """Ask the LLM to pick which scenes to keep, given a free-text instruction.

    On any LLM/network failure we degrade to `_fallback_plan` so the caller
    always receives a usable plan.
    """
    if not scenes:
        return CutPlan(summary="no scenes detected", spans=[])

    scene_lines = "\n".join(
        f"  {{\"id\": {i}, \"start_ms\": {s.start_ms}, \"end_ms\": {s.end_ms}, "
        f"\"duration_ms\": {s.duration_ms}, \"audio_active\": {s.audio_rms}}}"
        for i, s in enumerate(scenes)
    )
    prompt = f"""You are a video editor. The user gave a video and an instruction.
Pick which scene IDs to keep, in order. Output STRICT JSON with this schema:
{{
  "summary": "<one short sentence>",
  "spans": [
    {{ "scene_id": <int>, "start_ms": <int>, "end_ms": <int>, "rationale": "<short>" }}
  ]
}}

The video is {meta.duration_ms} ms long ({meta.width}x{meta.height} at {meta.fps:.1f} fps).
Has audio: {meta.has_audio}.

Available scenes:
[
{scene_lines}
]

User instruction: "{instruction}"

Reply with ONLY the JSON object, no commentary."""
    try:
        raw = _post(prompt, model=model)
    except RuntimeError as e:
        log.warning("LLM call failed, using fallback plan: %s", e)
        return _fallback_plan(scenes, f"LLM unavailable: {str(e).split('.', 1)[0]}")
    plan = _parse_plan(raw, scenes)
    if plan.summary.startswith("fallback"):
        log.warning("LLM response unusable, fell back: %s", plan.summary)
    return plan


def _parse_plan(raw: str, scenes: list[Scene]) -> CutPlan:
    blob = _balanced_json(raw)
    if blob is None:
        return _fallback_plan(scenes, "no JSON in model response")
    try:
        data: dict[str, Any] = json.loads(blob)
    except json.JSONDecodeError:
        return _fallback_plan(scenes, "JSON parse failed")

    spans_in = data.get("spans") or []
    spans_out: list[CutSpan] = []
    for s in spans_in:
        try:
            sid = int(s.get("scene_id", -1)) if "scene_id" in s else None
            if sid is not None and 0 <= sid < len(scenes):
                src = scenes[sid]
                start = int(s.get("start_ms", src.start_ms))
                end = int(s.get("end_ms", src.end_ms))
            else:
                start = int(s["start_ms"])
                end = int(s["end_ms"])
            if end > start:
                spans_out.append(CutSpan(
                    start_ms=start,
                    end_ms=end,
                    rationale=str(s.get("rationale", ""))[:200],
                ))
        except (KeyError, ValueError, TypeError):
            continue

    if not spans_out:
        return _fallback_plan(scenes, "model returned no usable spans")

    return CutPlan(summary=str(data.get("summary", ""))[:200], spans=spans_out)


def _fallback_plan(scenes: list[Scene], reason: str, budget_ms: int = 60_000) -> CutPlan:
    """When the model fails, keep the loudest scenes that fit a duration budget.

    Walks scenes by audio activity (then duration) and only takes a scene
    if adding it keeps total within budget. Never overshoots; may skip a
    long loud scene and take a shorter one that fits.
    """
    ranked = sorted(scenes, key=lambda s: (s.audio_rms, s.duration_ms), reverse=True)
    out: list[CutSpan] = []
    total = 0
    for s in ranked:
        if total + s.duration_ms > budget_ms:
            continue
        out.append(CutSpan(start_ms=s.start_ms, end_ms=s.end_ms, rationale="fallback"))
        total += s.duration_ms
    out.sort(key=lambda c: c.start_ms)
    return CutPlan(summary=f"fallback ({reason})", spans=out)
