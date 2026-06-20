"""Apply a CutPlan to a source video using ffmpeg.

Two render paths:
- `render_fast`: per-span re-encode + concat-demuxer. Used when the plan has
  no per-span effects, no overlays, no aspect change. Simple, low risk.
- `render_rich`: one-shot filter_complex with per-span trim/scale/setpts/
  drawtext/fade + concat. Used when the plan has overlays, per-span speed,
  aspect change, fades, etc.

`render` picks the right path automatically.
"""
from __future__ import annotations

import os
import platform
import shutil
import subprocess
import tempfile
from pathlib import Path

from vidcut.models import (
    AspectPreset,
    CutPlan,
    OverlayPosition,
    TextOverlay,
)


def _find_fontfile() -> str | None:
    """Locate a TrueType font on the host. Required for drawtext on Windows
    (no Fontconfig); nice-to-have on Linux/Mac. Override with VIDCUT_FONTFILE."""
    override = os.environ.get("VIDCUT_FONTFILE")
    if override and Path(override).exists():
        return override

    sys = platform.system()
    if sys == "Windows":
        candidates = [
            r"C:\Windows\Fonts\arial.ttf",
            r"C:\Windows\Fonts\segoeui.ttf",
            r"C:\Windows\Fonts\calibri.ttf",
        ]
    elif sys == "Darwin":
        candidates = [
            "/System/Library/Fonts/Helvetica.ttc",
            "/Library/Fonts/Arial.ttf",
            "/System/Library/Fonts/Supplemental/Arial.ttf",
        ]
    else:  # Linux
        candidates = [
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf",
        ]
    for c in candidates:
        if Path(c).exists():
            return c
    return None


_FONTFILE = _find_fontfile()


def _escape_fontfile(p: str) -> str:
    """Escape path for ffmpeg drawtext fontfile= (colon needs escape on Win)."""
    return p.replace("\\", "/").replace(":", r"\:")


# ---------- public API ----------

def have_ffmpeg() -> bool:
    return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


def render(
    source: Path,
    plan: CutPlan,
    output: Path,
    reencode: bool = True,
    dry_run: bool = False,
) -> Path | list[str]:
    """Render the plan. Returns the output path, or the ffmpeg argv if dry_run=True."""
    if not plan.spans:
        raise ValueError("CutPlan has no spans")
    if _needs_rich(plan):
        return render_rich(source, plan, output, dry_run=dry_run)
    return render_fast(source, plan, output, reencode=reencode, dry_run=dry_run)


def _needs_rich(plan: CutPlan) -> bool:
    if plan.aspect != AspectPreset.SOURCE:
        return True
    if plan.crossfade_ms > 0:
        return True
    if plan.global_overlays:
        return True
    return any(
        s.speed != 1.0 or s.fade_in_ms > 0 or s.fade_out_ms > 0 or s.mute or s.overlays
        for s in plan.spans
    )


# ---------- fast path ----------

def render_fast(
    source: Path,
    plan: CutPlan,
    output: Path,
    reencode: bool,
    dry_run: bool,
) -> Path | list[str]:
    if dry_run:
        first = plan.spans[0]
        return _cut_cmd(source, first.start_ms, first.end_ms, Path("[clip_0.mp4]"), reencode)

    with tempfile.TemporaryDirectory() as tmp:
        tmpdir = Path(tmp)
        clip_paths: list[Path] = []
        for i, span in enumerate(plan.spans):
            clip = tmpdir / f"clip_{i:03d}.mp4"
            _run_ffmpeg(_cut_cmd(source, span.start_ms, span.end_ms, clip, reencode))
            if clip.exists() and clip.stat().st_size > 0:
                clip_paths.append(clip)
        if not clip_paths:
            raise RuntimeError("No clips produced; ffmpeg failures during cutting")

        listfile = tmpdir / "concat.txt"
        listfile.write_text("\n".join(f"file '{p.as_posix()}'" for p in clip_paths))
        output.parent.mkdir(parents=True, exist_ok=True)
        if output.exists():
            output.unlink()
        _run_ffmpeg(_concat_cmd(listfile, output))
    return output


def _cut_cmd(src: Path, start_ms: int, end_ms: int, dst: Path, reencode: bool) -> list[str]:
    start_s = start_ms / 1000.0
    duration_s = (end_ms - start_ms) / 1000.0
    base = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-ss", f"{start_s:.3f}",
        "-i", str(src),
        "-t", f"{duration_s:.3f}",
    ]
    if reencode:
        base += [
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart",
        ]
    else:
        base += ["-c", "copy"]
    base += [str(dst)]
    return base


def _concat_cmd(listfile: Path, dst: Path) -> list[str]:
    return [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-f", "concat", "-safe", "0",
        "-i", str(listfile),
        "-c", "copy", "-movflags", "+faststart",
        str(dst),
    ]


# ---------- rich path: filter_complex ----------

ASPECT_TO_WH: dict[AspectPreset, tuple[int, int] | None] = {
    AspectPreset.SOURCE: None,
    AspectPreset.WIDESCREEN: (1920, 1080),
    AspectPreset.VERTICAL: (1080, 1920),
    AspectPreset.SQUARE: (1080, 1080),
}


def render_rich(
    source: Path,
    plan: CutPlan,
    output: Path,
    dry_run: bool,
) -> Path | list[str]:
    fc = build_filter_complex(plan)
    output.parent.mkdir(parents=True, exist_ok=True)
    if not dry_run and output.exists():
        output.unlink()

    cmd: list[str] = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(source),
        "-filter_complex", fc,
        "-map", "[outv]",
        "-map", "[outa]",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
        "-c:a", "aac", "-b:a", "128k",
        "-movflags", "+faststart",
        str(output),
    ]
    if dry_run:
        return cmd
    _run_ffmpeg(cmd)
    return output


def build_filter_complex(plan: CutPlan) -> str:
    """Build the filter_complex string for the rich render path."""
    aspect_wh = ASPECT_TO_WH.get(plan.aspect)
    parts: list[str] = []

    for i, span in enumerate(plan.spans):
        # Per-span video
        v: list[str] = []
        v.append(f"trim=start={span.start_ms / 1000:.3f}:end={span.end_ms / 1000:.3f}")
        if span.speed != 1.0:
            v.append(f"setpts=PTS/{span.speed:.6f}-STARTPTS")
        else:
            v.append("setpts=PTS-STARTPTS")
        if aspect_wh is not None:
            tw, th = aspect_wh
            v.append(
                f"scale={tw}:{th}:force_original_aspect_ratio=decrease,"
                f"pad={tw}:{th}:(ow-iw)/2:(oh-ih)/2:black,setsar=1"
            )
        for ov in span.overlays:
            v.append(_drawtext_filter(ov, span.start_ms))
        rendered_dur = span.rendered_duration_ms / 1000
        if span.fade_in_ms > 0:
            v.append(f"fade=t=in:st=0:d={span.fade_in_ms / 1000:.3f}")
        if span.fade_out_ms > 0:
            fo = span.fade_out_ms / 1000
            v.append(f"fade=t=out:st={max(0.0, rendered_dur - fo):.3f}:d={fo:.3f}")
        parts.append(f"[0:v]{','.join(v)}[v{i}]")

        # Per-span audio
        a: list[str] = []
        a.append(f"atrim=start={span.start_ms / 1000:.3f}:end={span.end_ms / 1000:.3f}")
        a.append("asetpts=PTS-STARTPTS")
        if span.speed != 1.0:
            a.append(_atempo_chain(span.speed))
        if span.mute:
            a.append("volume=0")
        parts.append(f"[0:a]{','.join(a)}[a{i}]")

    n = len(plan.spans)
    concat_inputs = "".join(f"[v{i}][a{i}]" for i in range(n))
    parts.append(f"{concat_inputs}concat=n={n}:v=1:a=1[vcat][acat]")

    # Global overlays on the concatenated stream (positioned in output time).
    if plan.global_overlays:
        global_filters = [_drawtext_filter(ov, 0) for ov in plan.global_overlays]
        parts.append(f"[vcat]{','.join(global_filters)}[outv]")
    else:
        parts.append("[vcat]null[outv]")
    parts.append("[acat]anull[outa]")

    return ";".join(parts)


# ---------- drawtext helpers ----------

_DRAWTEXT_POSITIONS: dict[OverlayPosition, tuple[str, str]] = {
    OverlayPosition.TOP_LEFT:     ("20",            "20"),
    OverlayPosition.TOP:          ("(w-tw)/2",      "20"),
    OverlayPosition.TOP_RIGHT:    ("w-tw-20",       "20"),
    OverlayPosition.LEFT:         ("20",            "(h-th)/2"),
    OverlayPosition.CENTER:       ("(w-tw)/2",      "(h-th)/2"),
    OverlayPosition.RIGHT:        ("w-tw-20",       "(h-th)/2"),
    OverlayPosition.BOTTOM_LEFT:  ("20",            "h-th-20"),
    OverlayPosition.BOTTOM:       ("(w-tw)/2",      "h-th-20"),
    OverlayPosition.BOTTOM_RIGHT: ("w-tw-20",       "h-th-20"),
}


def _drawtext_escape(s: str) -> str:
    """Escape user-supplied text for ffmpeg drawtext."""
    return (
        s.replace("\\", "\\\\")
         .replace(":", r"\:")
         .replace("'", r"\'")
         .replace("%", r"\%")
         .replace(",", r"\,")
    )


def _drawtext_filter(ov: TextOverlay, span_start_ms: int) -> str:
    """Build a single drawtext filter clause for an overlay.

    span_start_ms is the absolute ms where the span starts in the SOURCE.
    We shift the overlay's enable window into span-local time so the same
    schema works on both per-span (relative to source time) and global
    (relative to output time) overlays.
    """
    x, y = _DRAWTEXT_POSITIONS[ov.position]
    enable_start = max(0.0, (ov.start_ms - span_start_ms) / 1000)
    enable_end = max(enable_start + 0.05, (ov.end_ms - span_start_ms) / 1000)

    parts = [
        f"text='{_drawtext_escape(ov.text)}'",
        f"x={x}",
        f"y={y}",
        f"fontsize=h*{ov.size:.3f}",
        f"fontcolor={ov.color}",
        f"enable='between(t,{enable_start:.3f},{enable_end:.3f})'",
    ]
    if _FONTFILE:
        parts.insert(0, f"fontfile='{_escape_fontfile(_FONTFILE)}'")
    if ov.box == "auto":
        parts += ["box=1", "boxcolor=black@0.5", "boxborderw=12"]
    elif ov.box:
        parts += ["box=1", f"boxcolor={ov.box}", "boxborderw=12"]
    return "drawtext=" + ":".join(parts)


def _atempo_chain(speed: float) -> str:
    """Chain atempo filters - one filter only handles 0.5-2.0 range."""
    parts: list[str] = []
    remaining = speed
    while remaining > 2.0:
        parts.append("atempo=2.0")
        remaining /= 2.0
    while remaining < 0.5:
        parts.append("atempo=0.5")
        remaining /= 0.5
    parts.append(f"atempo={remaining:.4f}")
    return ",".join(parts)


def _run_ffmpeg(cmd: list[str]) -> None:
    """Run ffmpeg with captured stderr so failures surface a useful message."""
    try:
        subprocess.run(cmd, check=True, capture_output=True, text=True)
    except FileNotFoundError as e:
        raise RuntimeError("ffmpeg not found on PATH. Install ffmpeg.") from e
    except subprocess.CalledProcessError as e:
        tail = (e.stderr or "").strip().splitlines()[-3:]
        raise RuntimeError(
            "ffmpeg failed (exit " + str(e.returncode) + "): " + " | ".join(tail)
        ) from e
