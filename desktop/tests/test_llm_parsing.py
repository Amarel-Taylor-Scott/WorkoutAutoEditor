"""Test the JSON extraction + parsing in vidcut.llm without hitting Ollama."""
from vidcut.llm import _balanced_json, _fallback_plan, _parse_plan
from vidcut.models import Scene


def _scenes() -> list[Scene]:
    return [
        Scene(start_ms=0,    end_ms=2_000, audio_rms=0.1),
        Scene(start_ms=2_000, end_ms=8_000, audio_rms=0.8),
        Scene(start_ms=8_000, end_ms=10_000, audio_rms=0.2),
    ]


def test_balanced_json_extracts_first_object():
    raw = 'preamble {"a": 1} trailing {"b": 2}'
    assert _balanced_json(raw) == '{"a": 1}'


def test_balanced_json_handles_arrays():
    raw = 'noise [1, 2, 3] more'
    assert _balanced_json(raw) == '[1, 2, 3]'


def test_balanced_json_returns_none_when_unbalanced():
    assert _balanced_json("just text") is None
    assert _balanced_json("{ unbalanced") is None


def test_parse_plan_with_scene_id_references():
    raw = '''{
      "summary": "kept the loud middle",
      "spans": [
        {"scene_id": 1, "start_ms": 2000, "end_ms": 8000, "rationale": "active audio"}
      ]
    }'''
    plan = _parse_plan(raw, _scenes())
    assert plan.summary == "kept the loud middle"
    assert len(plan.spans) == 1
    assert plan.spans[0].duration_ms == 6000


def test_parse_plan_skips_invalid_spans():
    raw = '''{"spans": [
      {"start_ms": 100, "end_ms": 50},
      {"start_ms": 1000, "end_ms": 2000, "rationale": "ok"}
    ]}'''
    plan = _parse_plan(raw, _scenes())
    assert len(plan.spans) == 1
    assert plan.spans[0].rationale == "ok"


def test_parse_plan_falls_back_when_no_json():
    plan = _parse_plan("the model said something useless", _scenes())
    assert "fallback" in plan.summary
    assert len(plan.spans) >= 1


def test_fallback_returns_temporal_order_when_under_budget():
    # All three scenes total 10s, well under the 60s budget, so all survive.
    # _fallback_plan sorts by start_ms before returning.
    plan = _fallback_plan(_scenes(), "test")
    assert [s.start_ms for s in plan.spans] == [0, 2_000, 8_000]


def test_fallback_caps_at_60s_picking_loudest():
    long_scenes = [
        Scene(start_ms=0,       end_ms=40_000, audio_rms=0.1),
        Scene(start_ms=40_000,  end_ms=80_000, audio_rms=0.9),
        Scene(start_ms=80_000,  end_ms=120_000, audio_rms=0.2),
    ]
    plan = _fallback_plan(long_scenes, "test")
    # 40-80s is loudest and exactly 40s long; takes 40s of the 60s budget.
    # 80-120s would push total to 80s > 60s, so it does NOT fit.
    assert len(plan.spans) == 1
    assert plan.spans[0].start_ms == 40_000
