#!/usr/bin/env python3
"""Regenerate the action tables in docs/services/*.md from handler source.

Spec: ideas/auto-generated-action-tables.spec.md

Run from anywhere in the repo:
    python3 tools/docs/regen_action_docs.py            # rewrite docs in place
    python3 tools/docs/regen_action_docs.py --strict   # exit non-zero on warnings
"""
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import yaml

MARKER_START = "<!-- floci:actions:start -->"
MARKER_END = "<!-- floci:actions:end -->"

SWITCH_ACTION_RE = re.compile(r'^\s*case\s+"([A-Z][A-Za-z0-9]+)"\s*->', re.MULTILINE)


@dataclass(frozen=True)
class Source:
    path: Path
    mode: str  # 'switch' | 'rest'


@dataclass(frozen=True)
class ServiceEntry:
    service: str
    doc: Path
    sources: list[Source]


def extract_switch_actions(java_source: str) -> list[str]:
    """Action names from `case "X" ->` arms, in source order."""
    return [m.group(1) for m in SWITCH_ACTION_RE.finditer(java_source)]


def extract_rest_actions(java_source: str) -> list[str]:
    raise NotImplementedError("rest mode is not implemented in Slice 1")


def extract_actions_from_sources(sources: Iterable[tuple[str, str]]) -> list[str]:
    """Given an iterable of (java_text, mode), return the merged action list.

    Dedup preserves first appearance across the source list.
    """
    seen: set[str] = set()
    out: list[str] = []
    for text, mode in sources:
        if mode == "switch":
            actions = extract_switch_actions(text)
        elif mode == "rest":
            actions = extract_rest_actions(text)
        else:
            raise ValueError(f"unknown mode: {mode!r}")
        for a in actions:
            if a not in seen:
                seen.add(a)
                out.append(a)
    return out


def parse_marker_block(md: str) -> tuple[str, dict[str, str], str]:
    """Split a markdown document around the action-marker block.

    Returns (prefix_through_start_marker_line, prior_descriptions, suffix_from_end_marker_line).
    Raises ValueError if markers are missing or in the wrong order.
    """
    if md.count(MARKER_START) != 1 or md.count(MARKER_END) != 1:
        raise ValueError(
            f"document must contain exactly one '{MARKER_START}' and one '{MARKER_END}'"
        )
    start_idx = md.index(MARKER_START)
    end_idx = md.index(MARKER_END)
    if end_idx < start_idx:
        raise ValueError("end marker appears before start marker")

    after_start = md.index("\n", start_idx) + 1
    before_end = md.rindex("\n", 0, end_idx) + 1

    prefix = md[:after_start]
    suffix = md[before_end:]
    body = md[after_start:before_end]

    return prefix, _parse_table(body), suffix


_TABLE_ROW_RE = re.compile(r"^`([A-Z][A-Za-z0-9]+)`$")
_SEPARATOR_ROW_RE = re.compile(r"^\|\s*[-:|\s]+\|\s*$")


def _parse_table(body: str) -> dict[str, str]:
    """Parse the action-name | description table inside the marker block."""
    out: dict[str, str] = {}
    for line in body.splitlines():
        stripped = line.strip()
        if not stripped.startswith("|"):
            continue
        if _SEPARATOR_ROW_RE.match(stripped):
            continue
        cells = [c.strip() for c in stripped.strip("|").split("|")]
        if len(cells) < 2:
            continue
        m = _TABLE_ROW_RE.match(cells[0])
        if not m:
            continue
        out[m.group(1)] = cells[1]
    return out


def render_marker_block(actions: list[str], descriptions: dict[str, str]) -> str:
    """Render the table contents that go between the marker lines."""
    lines = ["| Action | Description |", "| --- | --- |"]
    for a in actions:
        desc = descriptions.get(a, "")
        lines.append(f"| `{a}` | {desc} |")
    return "\n".join(lines) + "\n"


def regenerate_doc_content(
    actions: list[str], doc_content: str
) -> tuple[str, list[str]]:
    """Pure function. Given the new action list and the current doc, return the new doc.

    Also returns orphan action names — actions that were described in the doc's old
    marker block but are no longer in `actions`.
    """
    prefix, prior_descriptions, suffix = parse_marker_block(doc_content)
    orphans = [a for a in prior_descriptions if a not in actions]
    body = render_marker_block(actions, prior_descriptions)
    return prefix + body + suffix, orphans


def _repo_root() -> Path:
    return Path(__file__).resolve().parent.parent.parent


def _load_registry(repo_root: Path) -> list[ServiceEntry]:
    registry_path = repo_root / "tools" / "docs" / "services.yaml"
    raw = yaml.safe_load(registry_path.read_text()) or []
    entries: list[ServiceEntry] = []
    for item in raw:
        sources = [
            Source(path=repo_root / s["path"], mode=s["mode"])
            for s in item.get("sources") or []
        ]
        entries.append(
            ServiceEntry(
                service=item["service"],
                doc=repo_root / item["doc"],
                sources=sources,
            )
        )
    return entries


def _process_entry(entry: ServiceEntry) -> tuple[bool, list[str]]:
    """Regenerate one service's doc. Returns (changed, orphans)."""
    if not entry.sources:
        return False, []
    sources = [(s.path.read_text(), s.mode) for s in entry.sources]
    actions = extract_actions_from_sources(sources)
    doc_content = entry.doc.read_text()
    new_content, orphans = regenerate_doc_content(actions, doc_content)
    if new_content != doc_content:
        entry.doc.write_text(new_content)
        return True, orphans
    return False, orphans


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--strict",
        action="store_true",
        help="treat warnings (orphan actions, unregistered handlers) as errors",
    )
    args = parser.parse_args(argv)

    repo_root = _repo_root()
    entries = _load_registry(repo_root)

    warnings: list[str] = []
    for entry in entries:
        try:
            changed, orphans = _process_entry(entry)
        except (ValueError, FileNotFoundError) as exc:
            print(f"error: {entry.service}: {exc}", file=sys.stderr)
            return 1
        if changed:
            print(f"updated {entry.doc.relative_to(repo_root)}")
        for orphan in orphans:
            warnings.append(
                f"{entry.service}: action '{orphan}' in marker block but not in source"
            )

    for w in warnings:
        print(f"warning: {w}", file=sys.stderr)

    if args.strict and warnings:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
