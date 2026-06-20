"""Apply EditPolicy to a CutPlan produced by the LLM.

The LLM picks WHICH segments of the source to keep. Policy then enriches
the plan with style (aspect ratio), feature toggles (silence-trim,
title-every), and global defaults (default speed, crossfade).

Keeping these out of the LLM keeps the prompt small and the schema
deterministic - the LLM never has to know about ffmpeg specifics.
"""
from __future__ import annotations

from vidcut.models import (
    AspectPreset,
    CutPlan,
    CutSpan,
    EditPolicy,
    OverlayPosition,
    TextOverlay,
    TitleEvery,
    TrimSilence,
)


def apply_policy(
    plan: CutPlan,
    policy: EditPolicy,
    silences_ms: list[tuple[int, int]] | None = None,
) -> CutPlan:
    """Return a new CutPlan with policy applied. Pure function, no IO."""
    spans = list(plan.spans)

    if policy.trim_silence.enabled and silences_ms:
        spans = _trim_silences_from_spans(spans, silences_ms, policy.trim_silence)

    if policy.speed_default != 1.0:
        spans = [s.model_copy(update={"speed": policy.speed_default}) for s in spans]

    global_overlays = list(plan.global_overlays)
    if policy.title_every.enabled:
        global_overlays += _title_every_overlays(spans, policy.title_every)

    return plan.model_copy(update={
        "spans": spans,
        "global_overlays": global_overlays,
        "aspect": policy.aspect,
        "crossfade_ms": policy.crossfade_ms,
    })


def _trim_silences_from_spans(
    spans: list[CutSpan],
    silences_ms: list[tuple[int, int]],
    cfg: TrimSilence,
) -> list[CutSpan]:
    """For each span, cut out any silence segment of >= cfg.min_dur_s
    that falls inside the span. May increase the span count, never decrease."""
    threshold = int(cfg.min_dur_s * 1000)
    out: list[CutSpan] = []
    for span in spans:
        # Silences that overlap this span
        overlapping = [
            (max(s, span.start_ms), min(e, span.end_ms))
            for (s, e) in silences_ms
            if s < span.end_ms and e > span.start_ms and (e - s) >= threshold
        ]
        if not overlapping:
            out.append(span)
            continue

        # Walk the span, emitting sub-spans between silences
        cursor = span.start_ms
        for sil_start, sil_end in sorted(overlapping):
            if sil_start - cursor >= 100:  # keep pieces >= 0.1 s
                out.append(span.model_copy(update={
                    "start_ms": cursor,
                    "end_ms": sil_start,
                }))
            cursor = sil_end
        if span.end_ms - cursor >= 100:
            out.append(span.model_copy(update={
                "start_ms": cursor,
                "end_ms": span.end_ms,
            }))
    return out


def _title_every_overlays(
    spans: list[CutSpan],
    cfg: TitleEvery,
) -> list[TextOverlay]:
    """Emit a global overlay every cfg.interval_s seconds of OUTPUT time."""
    overlays: list[TextOverlay] = []
    total_ms = sum(s.rendered_duration_ms for s in spans)
    interval_ms = cfg.interval_s * 1000
    t = 0
    while t < total_ms:
        mm = t // 60_000
        ss = (t % 60_000) // 1000
        time_str = f"{mm:02d}:{ss:02d}"
        text = cfg.template.replace("{time}", time_str)
        overlays.append(TextOverlay(
            start_ms=t,
            end_ms=min(t + 3_000, total_ms),
            text=text,
            position=OverlayPosition.TOP_RIGHT,
            size=0.05,
            color="white",
            box="auto",
        ))
        t += interval_ms
    return overlays
