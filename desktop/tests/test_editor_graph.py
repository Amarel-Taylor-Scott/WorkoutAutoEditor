"""Tests for vidcut.editor filter-complex generation - no ffmpeg invoked."""
from vidcut.editor import (
    _atempo_chain,
    _drawtext_escape,
    _drawtext_filter,
    _needs_rich,
    build_filter_complex,
)
from vidcut.models import (
    AspectPreset,
    CutPlan,
    CutSpan,
    OverlayPosition,
    TextOverlay,
)


# ---- _needs_rich ----

def test_plain_plan_does_not_need_rich():
    plan = CutPlan(spans=[CutSpan(start_ms=0, end_ms=1_000)])
    assert _needs_rich(plan) is False


def test_aspect_change_needs_rich():
    plan = CutPlan(
        spans=[CutSpan(start_ms=0, end_ms=1_000)],
        aspect=AspectPreset.VERTICAL,
    )
    assert _needs_rich(plan) is True


def test_overlay_needs_rich():
    plan = CutPlan(
        spans=[CutSpan(start_ms=0, end_ms=1_000)],
        global_overlays=[TextOverlay(start_ms=0, end_ms=500, text="x")],
    )
    assert _needs_rich(plan) is True


def test_per_span_speed_needs_rich():
    plan = CutPlan(spans=[CutSpan(start_ms=0, end_ms=1_000, speed=1.5)])
    assert _needs_rich(plan) is True


# ---- drawtext escape ----

def test_drawtext_escape_handles_special_chars():
    assert _drawtext_escape("hi:bye") == r"hi\:bye"
    assert _drawtext_escape("a,b") == r"a\,b"
    assert _drawtext_escape("100%") == r"100\%"
    assert _drawtext_escape("it's") == r"it\'s"


def test_drawtext_escape_handles_backslash_first():
    # Order matters: must not double-escape its own escapes.
    assert _drawtext_escape(r"a\b:c") == r"a\\b\:c"


# ---- atempo chain (audio speed) ----

def test_atempo_chain_one_filter_in_range():
    assert _atempo_chain(1.5) == "atempo=1.5000"
    assert _atempo_chain(0.75) == "atempo=0.7500"


def test_atempo_chain_speeds_up_beyond_2x():
    # 4x = 2.0 * 2.0
    assert _atempo_chain(4.0) == "atempo=2.0,atempo=2.0000"


def test_atempo_chain_slows_down_below_half():
    # 0.25x = 0.5 * 0.5
    assert _atempo_chain(0.25) == "atempo=0.5,atempo=0.5000"


# ---- drawtext filter ----

def test_drawtext_filter_includes_text_and_position():
    ov = TextOverlay(
        start_ms=0, end_ms=2_000,
        text="hello", position=OverlayPosition.CENTER,
    )
    f = _drawtext_filter(ov, span_start_ms=0)
    assert "drawtext=" in f
    assert "text='hello'" in f
    assert "(w-tw)/2" in f
    assert "between(t,0.000,2.000)" in f


def test_drawtext_filter_shifts_enable_window_for_span():
    # Overlay at absolute 12.5s-15s in a span that starts at 10s
    # -> local time 2.5s-5.0s
    ov = TextOverlay(start_ms=12_500, end_ms=15_000, text="x")
    f = _drawtext_filter(ov, span_start_ms=10_000)
    assert "between(t,2.500,5.000)" in f


# ---- filter_complex graph ----

def test_filter_complex_includes_concat_for_n_spans():
    plan = CutPlan(spans=[
        CutSpan(start_ms=0, end_ms=2_000),
        CutSpan(start_ms=5_000, end_ms=7_000),
    ])
    fc = build_filter_complex(plan)
    assert "[v0][a0][v1][a1]concat=n=2:v=1:a=1[vcat][acat]" in fc


def test_filter_complex_applies_aspect_scale_and_pad():
    plan = CutPlan(
        spans=[CutSpan(start_ms=0, end_ms=1_000)],
        aspect=AspectPreset.VERTICAL,
    )
    fc = build_filter_complex(plan)
    assert "scale=1080:1920" in fc
    assert "pad=1080:1920" in fc


def test_filter_complex_emits_mute_filter_when_requested():
    plan = CutPlan(spans=[CutSpan(start_ms=0, end_ms=1_000, mute=True)])
    fc = build_filter_complex(plan)
    assert "volume=0" in fc


def test_filter_complex_handles_speed_in_video_and_audio():
    plan = CutPlan(spans=[CutSpan(start_ms=0, end_ms=2_000, speed=2.0)])
    fc = build_filter_complex(plan)
    assert "setpts=PTS/2.000000-STARTPTS" in fc
    assert "atempo=2.0000" in fc


def test_filter_complex_includes_fade_in_and_out():
    plan = CutPlan(spans=[CutSpan(
        start_ms=0, end_ms=5_000,
        fade_in_ms=500, fade_out_ms=1_000,
    )])
    fc = build_filter_complex(plan)
    assert "fade=t=in:st=0:d=0.500" in fc
    assert "fade=t=out:" in fc
