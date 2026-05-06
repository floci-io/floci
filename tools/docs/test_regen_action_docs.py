"""Golden-file tests for regen_action_docs.

Each fixture under fixtures/<name>/ is a self-contained case:

  inputs.yaml          : { sources: [{ file, mode }, ...] }
  input/<file>.java    : Java source(s) referenced by inputs.yaml
  input/before.md      : doc page as it exists pre-regen
  expected/after.md    : doc page as it should be post-regen

The test loads each fixture, calls the pure function under test, and
asserts byte-equal output against expected/after.md.
"""
from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from regen_action_docs import (
    extract_actions_from_sources,
    regenerate_doc_content,
)

FIXTURES = Path(__file__).parent / "fixtures"


def _fixture_dirs() -> list[Path]:
    return sorted(p for p in FIXTURES.iterdir() if p.is_dir())


@pytest.mark.parametrize("fixture_dir", _fixture_dirs(), ids=lambda p: p.name)
def test_fixture_round_trips(fixture_dir: Path) -> None:
    inputs = yaml.safe_load((fixture_dir / "inputs.yaml").read_text())
    sources = [
        (
            (fixture_dir / "input" / s["file"]).read_text(),
            s["mode"],
        )
        for s in inputs["sources"]
    ]

    before = (fixture_dir / "input" / "before.md").read_text()
    expected = (fixture_dir / "expected" / "after.md").read_text()

    actions = extract_actions_from_sources(sources)
    actual, _orphans = regenerate_doc_content(actions, before)

    assert actual == expected
