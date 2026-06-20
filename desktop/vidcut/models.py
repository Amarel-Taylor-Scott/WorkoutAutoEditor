"""Data models shared across CLI, pipeline, and Gradio UI."""
from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, Field, ValidationInfo, field_validator


# ---------- Scene / source ----------

class Scene(BaseModel):
    """A scene boundary detected from the source video."""
    start_ms: int
    end_ms: int
    label: str | None = None
    audio_rms: float = 0.0

    @property
    def duration_ms(self) -> int:
        return self.end_ms - self.start_ms


class VideoMeta(BaseModel):
    """ffprobe summary of the source."""
    path: str
    duration_ms: int
    width: int
    height: int
    fps: float
    has_audio: bool


# ---------- Overlays + style ----------

class OverlayPosition(str, Enum):
    TOP_LEFT = "top_left"
    TOP = "top"
    TOP_RIGHT = "top_right"
    LEFT = "left"
    CENTER = "center"
    RIGHT = "right"
    BOTTOM_LEFT = "bottom_left"
    BOTTOM = "bottom"
    BOTTOM_RIGHT = "bottom_right"


class TextOverlay(BaseModel):
    """A timed text overlay rendered via ffmpeg drawtext."""
    start_ms: int = Field(..., ge=0)
    end_ms: int = Field(..., gt=0)
    text: str = Field(..., min_length=1, max_length=200)
    position: OverlayPosition = OverlayPosition.TOP_LEFT
    # 0.0-1.0 of frame height. 0.05 ~ small, 0.10 ~ medium, 0.18 ~ large.
    size: float = Field(0.07, gt=0.0, le=0.4)
    color: str = "white"
    # Background box: None for no box, else "black", "auto" for semi-transparent.
    box: str | None = "auto"

    @field_validator("end_ms")
    @classmethod
    def _end_after_start(cls, v: int, info: ValidationInfo) -> int:
        start = info.data.get("start_ms", 0)
        if v <= start:
            raise ValueError(f"overlay end_ms ({v}) must be > start_ms ({start})")
        return v

    @field_validator("color")
    @classmethod
    def _color_safe(cls, v: str) -> str:
        # ffmpeg accepts named colors plus #RRGGBB. Reject anything weird so
        # we don't pass user-controlled chars into a filtergraph.
        import re
        if not re.fullmatch(r"[A-Za-z]+|#[0-9A-Fa-f]{6}", v):
            raise ValueError(f"color must be a CSS-style name or #RRGGBB, got {v!r}")
        return v


# ---------- Cut spans ----------

class CutSpan(BaseModel):
    """A single span the editor decided to keep, with optional per-span effects."""
    start_ms: int = Field(..., ge=0)
    end_ms: int = Field(..., ge=0)
    rationale: str = ""

    # New in v0.2 - all optional, default = no-op.
    speed: float = Field(1.0, gt=0.05, le=8.0)  # 0.5 = half-speed, 2.0 = 2x
    fade_in_ms: int = Field(0, ge=0, le=5000)
    fade_out_ms: int = Field(0, ge=0, le=5000)
    mute: bool = False
    overlays: list[TextOverlay] = Field(default_factory=list)

    @field_validator("end_ms")
    @classmethod
    def _end_after_start(cls, v: int, info: ValidationInfo) -> int:
        start = info.data.get("start_ms", 0)
        if v <= start:
            raise ValueError(f"end_ms ({v}) must be > start_ms ({start})")
        return v

    @property
    def duration_ms(self) -> int:
        return self.end_ms - self.start_ms

    @property
    def rendered_duration_ms(self) -> int:
        """Duration in the output, accounting for speed."""
        return int(self.duration_ms / self.speed)


# ---------- Output presets ----------

class AspectPreset(str, Enum):
    SOURCE = "source"           # keep input aspect
    WIDESCREEN = "16:9"         # YouTube
    VERTICAL = "9:16"           # Reels / Shorts / TikTok
    SQUARE = "1:1"              # Instagram feed


# ---------- Full plan ----------

class CutPlan(BaseModel):
    """The full plan: ordered spans + global style + a one-line summary."""
    summary: str = ""
    spans: list[CutSpan] = Field(default_factory=list)

    # Global overlays that ride on top of the whole output (e.g. an opening
    # title that spans the first 3 s regardless of which source span shows).
    global_overlays: list[TextOverlay] = Field(default_factory=list)

    aspect: AspectPreset = AspectPreset.SOURCE
    # Cross-fade transition between adjacent spans, ms. 0 = hard cuts.
    crossfade_ms: int = Field(0, ge=0, le=2000)

    @property
    def total_ms(self) -> int:
        return sum(s.rendered_duration_ms for s in self.spans)


# ---------- Edit policy (top-level rules the user wants applied) ----------

class TrimSilence(BaseModel):
    """Drop silent ranges of at least min_dur_s above noise_db."""
    enabled: bool = False
    min_dur_s: float = Field(1.5, gt=0.1, le=30.0)
    noise_db: int = Field(-30, ge=-60, le=-10)


class TitleEvery(BaseModel):
    """Emit a periodic title overlay at fixed intervals."""
    enabled: bool = False
    interval_s: int = Field(30, ge=5, le=600)
    template: str = "{time}"  # supports {time} placeholder


class EditPolicy(BaseModel):
    """User-facing top-level configuration the LLM is allowed to express."""
    target_total_s: int | None = Field(None, ge=5, le=3600)
    aspect: AspectPreset = AspectPreset.SOURCE
    crossfade_ms: int = Field(0, ge=0, le=2000)
    speed_default: float = Field(1.0, gt=0.05, le=8.0)
    trim_silence: TrimSilence = Field(default_factory=TrimSilence)
    title_every: TitleEvery = Field(default_factory=TitleEvery)
