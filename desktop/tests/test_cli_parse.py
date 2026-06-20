"""Tests for the CLI argument parsers (overlay spec, timestamp)."""
import pytest
import typer

from vidcut.cli import _parse_overlay, _ts_to_ms
from vidcut.models import OverlayPosition


# ---- _ts_to_ms ----

def test_ts_seconds_only():
    assert _ts_to_ms("5") == 5_000


def test_ts_mm_ss():
    assert _ts_to_ms("1:30") == 90_000


def test_ts_with_decimal_seconds():
    assert _ts_to_ms("0:01.5") == 1_500


# ---- _parse_overlay ----

def test_parse_overlay_minimal():
    ov = _parse_overlay("Set 1@0:05")
    assert ov.text == "Set 1"
    assert ov.start_ms == 5_000
    assert ov.end_ms == 8_000  # default 3 s
    assert ov.position == OverlayPosition.TOP_LEFT


def test_parse_overlay_with_end_time():
    ov = _parse_overlay("Heavy set@1:30-1:35")
    assert ov.start_ms == 90_000
    assert ov.end_ms == 95_000


def test_parse_overlay_with_position():
    ov = _parse_overlay("Title@0:00-0:08#center")
    assert ov.position == OverlayPosition.CENTER


def test_parse_overlay_rejects_bad_format():
    with pytest.raises(typer.BadParameter):
        _parse_overlay("no separator")


def test_parse_overlay_rejects_bad_position():
    with pytest.raises(typer.BadParameter):
        _parse_overlay("Text@0:00#nowhere")
