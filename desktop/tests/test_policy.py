"""Tests for vidcut.policy - silence-trim, title-every, speed-default, aspect."""
from vidcut.models import (
    AspectPreset,
    CutPlan,
    CutSpan,
    EditPolicy,
    TitleEvery,
    TrimSilence,
)
from vidcut.policy import apply_policy


def _plan(*spans: tuple[int, int]) -> CutPlan:
    return CutPlan(spans=[CutSpan(start_ms=s, end_ms=e) for s, e in spans])


def test_no_op_policy_returns_equivalent_plan():
    plan = _plan((0, 5_000), (10_000, 15_000))
    out = apply_policy(plan, EditPolicy(), silences_ms=[])
    assert [(s.start_ms, s.end_ms) for s in out.spans] == [(0, 5_000), (10_000, 15_000)]
    assert out.aspect == AspectPreset.SOURCE
    assert out.global_overlays == []


def test_aspect_passes_through_to_plan():
    plan = _plan((0, 1_000))
    out = apply_policy(plan, EditPolicy(aspect=AspectPreset.VERTICAL), silences_ms=[])
    assert out.aspect == AspectPreset.VERTICAL


def test_speed_default_applies_to_every_span():
    plan = _plan((0, 1_000), (2_000, 3_000))
    out = apply_policy(plan, EditPolicy(speed_default=2.0), silences_ms=[])
    assert all(s.speed == 2.0 for s in out.spans)


def test_trim_silence_splits_a_span_around_silence():
    plan = _plan((0, 10_000))
    silences = [(3_000, 5_000)]  # 2 s silence
    policy = EditPolicy(trim_silence=TrimSilence(enabled=True, min_dur_s=1.0))
    out = apply_policy(plan, policy, silences_ms=silences)
    assert [(s.start_ms, s.end_ms) for s in out.spans] == [(0, 3_000), (5_000, 10_000)]


def test_trim_silence_ignores_short_silences():
    plan = _plan((0, 10_000))
    silences = [(3_000, 3_400)]  # 0.4 s silence - below threshold
    policy = EditPolicy(trim_silence=TrimSilence(enabled=True, min_dur_s=1.0))
    out = apply_policy(plan, policy, silences_ms=silences)
    assert [(s.start_ms, s.end_ms) for s in out.spans] == [(0, 10_000)]


def test_trim_silence_handles_multiple_silences_in_one_span():
    plan = _plan((0, 20_000))
    silences = [(3_000, 5_000), (10_000, 12_000), (15_000, 18_000)]
    policy = EditPolicy(trim_silence=TrimSilence(enabled=True, min_dur_s=1.0))
    out = apply_policy(plan, policy, silences_ms=silences)
    assert [(s.start_ms, s.end_ms) for s in out.spans] == [
        (0, 3_000), (5_000, 10_000), (12_000, 15_000), (18_000, 20_000),
    ]


def test_trim_silence_disabled_is_a_no_op():
    plan = _plan((0, 10_000))
    silences = [(3_000, 5_000)]
    out = apply_policy(plan, EditPolicy(trim_silence=TrimSilence(enabled=False)), silences_ms=silences)
    assert [(s.start_ms, s.end_ms) for s in out.spans] == [(0, 10_000)]


def test_title_every_emits_overlays_at_interval():
    plan = _plan((0, 60_000))  # 60 s output
    policy = EditPolicy(title_every=TitleEvery(enabled=True, interval_s=20))
    out = apply_policy(plan, policy, silences_ms=[])
    starts = [ov.start_ms for ov in out.global_overlays]
    assert starts == [0, 20_000, 40_000]


def test_title_every_template_uses_time_placeholder():
    plan = _plan((0, 30_000))
    policy = EditPolicy(title_every=TitleEvery(enabled=True, interval_s=15, template="t={time}"))
    out = apply_policy(plan, policy, silences_ms=[])
    texts = [ov.text for ov in out.global_overlays]
    assert texts == ["t=00:00", "t=00:15"]
