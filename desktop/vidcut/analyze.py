"""Video analysis via ffmpeg/ffprobe. No ML, just signal extraction."""
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

from vidcut.models import Scene, VideoMeta


def probe(video: Path) -> VideoMeta:
    """Parse format/streams via ffprobe."""
    cmd = [
        "ffprobe", "-v", "error",
        "-print_format", "json",
        "-show_format", "-show_streams",
        str(video),
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, check=True)
    except FileNotFoundError as e:
        raise RuntimeError("ffprobe not found on PATH. Install ffmpeg.") from e
    except subprocess.CalledProcessError as e:
        msg = (e.stderr or "").strip().splitlines()[-1] if e.stderr else f"exit {e.returncode}"
        raise RuntimeError(f"ffprobe failed on {video}: {msg}") from e
    data = json.loads(proc.stdout)
    fmt = data.get("format", {})
    streams = data.get("streams", [])
    v = next((s for s in streams if s.get("codec_type") == "video"), None)
    a = next((s for s in streams if s.get("codec_type") == "audio"), None)
    if v is None:
        raise RuntimeError(f"No video stream in {video}")
    duration_s = float(fmt.get("duration", 0))
    fps = _parse_fps(v.get("avg_frame_rate") or v.get("r_frame_rate") or "30/1")
    return VideoMeta(
        path=str(video),
        duration_ms=int(duration_s * 1000),
        width=int(v.get("width", 0)),
        height=int(v.get("height", 0)),
        fps=fps,
        has_audio=a is not None,
    )


def _parse_fps(s: str) -> float:
    if "/" in s:
        n, d = s.split("/")
        if float(d) == 0:
            raise ValueError(f"invalid frame rate: {s}")
        return float(n) / float(d)
    return float(s)


def detect_scenes(video: Path, threshold: float = 0.30) -> list[Scene]:
    """Run ffmpeg's scene-change filter; return contiguous spans between cuts.

    threshold ~ 0.3 catches obvious cuts; 0.15 catches subtler ones at the
    cost of false positives.
    """
    meta = probe(video)
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "info",
        "-i", str(video),
        "-vf", f"select='gt(scene,{threshold})',showinfo",
        "-f", "null", "-",
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        last_err = (proc.stderr or "").strip().splitlines()[-1:] or [f"exit {proc.returncode}"]
        raise RuntimeError(f"ffmpeg scene detection failed: {last_err[0]}")
    times_ms = sorted(_parse_showinfo_times(proc.stderr))
    boundaries = [0] + [int(t * 1000) for t in times_ms] + [meta.duration_ms]
    scenes: list[Scene] = []
    for i in range(len(boundaries) - 1):
        s, e = boundaries[i], boundaries[i + 1]
        if e - s >= 250:
            scenes.append(Scene(start_ms=s, end_ms=e))
    return scenes


_SHOWINFO_RE = re.compile(r"pts_time:([0-9.]+)")


def _parse_showinfo_times(stderr: str) -> list[float]:
    return [float(m.group(1)) for m in _SHOWINFO_RE.finditer(stderr)]


def detect_silences(video: Path, noise_db: int = -30, min_dur: float = 0.5) -> list[tuple[int, int]]:
    """Return [(start_ms, end_ms), ...] of detected silence runs."""
    cmd = [
        "ffmpeg", "-hide_banner", "-i", str(video),
        "-af", f"silencedetect=noise={noise_db}dB:d={min_dur}",
        "-f", "null", "-",
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    starts = [float(m.group(1)) for m in re.finditer(r"silence_start: ([0-9.]+)", proc.stderr)]
    ends = [float(m.group(1)) for m in re.finditer(r"silence_end: ([0-9.]+)", proc.stderr)]
    out: list[tuple[int, int]] = []
    for s, e in zip(starts, ends):
        out.append((int(s * 1000), int(e * 1000)))
    return out


def annotate_audio_rms(video: Path, scenes: list[Scene]) -> list[Scene]:
    """Approximate per-scene audio activity using silence detection inverted."""
    if not scenes:
        return scenes
    silent = detect_silences(video)
    out: list[Scene] = []
    for sc in scenes:
        silent_overlap = sum(
            max(0, min(e, sc.end_ms) - max(s, sc.start_ms))
            for s, e in silent
        )
        active_ratio = 1.0 - (silent_overlap / max(1, sc.duration_ms))
        out.append(sc.model_copy(update={"audio_rms": round(active_ratio, 3)}))
    return out
