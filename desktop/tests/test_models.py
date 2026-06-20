from vidcut.models import CutPlan, CutSpan, Scene


def test_cutspan_duration():
    s = CutSpan(start_ms=1000, end_ms=4500, rationale="x")
    assert s.duration_ms == 3500


def test_cutspan_rejects_zero_duration():
    import pytest
    with pytest.raises(ValueError):
        CutSpan(start_ms=1000, end_ms=1000)


def test_cutplan_total_ms():
    p = CutPlan(spans=[
        CutSpan(start_ms=0, end_ms=1000),
        CutSpan(start_ms=2000, end_ms=2750),
    ])
    assert p.total_ms == 1750


def test_scene_duration():
    s = Scene(start_ms=500, end_ms=1500)
    assert s.duration_ms == 1000
