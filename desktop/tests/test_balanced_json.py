"""Edge cases for the string-aware balanced-JSON extractor."""
from vidcut.llm import _balanced_json


def test_braces_inside_strings_dont_confuse_depth():
    # The opening { inside the string was previously a false depth increment.
    raw = '{"summary": "cut {intro} and outro", "spans": []}'
    extracted = _balanced_json(raw)
    assert extracted == raw


def test_brackets_inside_strings_dont_confuse_depth():
    raw = '{"a": "x [1, 2]", "b": [3, 4]}'
    extracted = _balanced_json(raw)
    assert extracted == raw


def test_escaped_quote_inside_string():
    raw = r'{"q": "he said \"hi\"", "n": 1}'
    extracted = _balanced_json(raw)
    assert extracted == raw


def test_huge_input_bails_to_avoid_pathological_parse():
    raw = "{" + "x" * 1_100_000 + "}"
    assert _balanced_json(raw) is None


def test_preamble_and_trailing_text_stripped():
    raw = 'Here is your JSON: {"a": 1} -- end'
    assert _balanced_json(raw) == '{"a": 1}'
