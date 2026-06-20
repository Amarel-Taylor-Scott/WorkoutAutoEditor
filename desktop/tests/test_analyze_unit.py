"""Pure-Python tests for analyze.py helpers - no ffmpeg required."""
import pytest

from vidcut.analyze import _parse_fps, _parse_showinfo_times


def test_parse_fps_simple_fraction():
    assert _parse_fps("30/1") == 30.0


def test_parse_fps_fractional():
    assert abs(_parse_fps("30000/1001") - 29.97002997) < 1e-4


def test_parse_fps_raises_on_zero_denominator():
    with pytest.raises(ValueError):
        _parse_fps("30/0")


def test_parse_fps_handles_plain_number():
    assert _parse_fps("24") == 24.0


def test_parse_showinfo_times_extracts_floats():
    stderr = (
        "[Parsed_showinfo @ 0x...] n: 0 pts_time:0.000000 ...\n"
        "[Parsed_showinfo @ 0x...] n: 1 pts_time:3.041667 ...\n"
        "noise pts_time:5.083333 between\n"
    )
    assert _parse_showinfo_times(stderr) == [0.0, 3.041667, 5.083333]


def test_parse_showinfo_times_empty():
    assert _parse_showinfo_times("no matches") == []
