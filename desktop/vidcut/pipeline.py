"""End-to-end pipeline: probe, scenes, plan, policy, render."""
from __future__ import annotations

from dataclasses import dataclass, replace
from pathlib import Path

from vidcut import analyze, editor, llm
from vidcut.models import CutPlan, EditPolicy, Scene, VideoMeta
from vidcut.policy import apply_policy


@dataclass
class PipelineResult:
    meta: VideoMeta
    scenes: list[Scene]
    plan: CutPlan
    output: Path | None = None
    silences_ms: list[tuple[int, int]] | None = None


def plan_only(
    source: Path,
    instruction: str,
    model: str = llm.DEFAULT_MODEL,
    scene_threshold: float = 0.30,
    policy: EditPolicy | None = None,
) -> PipelineResult:
    """Probe + scene detect + LLM plan + apply policy. No render."""
    meta = analyze.probe(source)
    scenes = analyze.detect_scenes(source, threshold=scene_threshold)

    silences_ms: list[tuple[int, int]] = []
    if meta.has_audio:
        silences_ms = analyze.detect_silences(source)
        scenes = analyze.annotate_audio_rms(source, scenes)

    raw_plan = llm.plan_cuts(meta, scenes, instruction, model=model)
    final_plan = apply_policy(raw_plan, policy or EditPolicy(), silences_ms=silences_ms)
    return PipelineResult(
        meta=meta,
        scenes=scenes,
        plan=final_plan,
        silences_ms=silences_ms,
    )


def edit(
    source: Path,
    instruction: str,
    output: Path,
    model: str = llm.DEFAULT_MODEL,
    scene_threshold: float = 0.30,
    reencode: bool = True,
    policy: EditPolicy | None = None,
    dry_run: bool = False,
) -> PipelineResult:
    """Plan + render. With dry_run=True returns the plan but does not write."""
    result = plan_only(
        source, instruction,
        model=model, scene_threshold=scene_threshold, policy=policy,
    )
    rendered_or_cmd = editor.render(source, result.plan, output, reencode=reencode, dry_run=dry_run)
    if dry_run:
        return replace(result, output=None)
    assert isinstance(rendered_or_cmd, Path)
    return replace(result, output=rendered_or_cmd)
