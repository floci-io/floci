#!/usr/bin/env python3
"""Regenerate the action tables in docs/services/*.md from handler source.

The action LIST is derived from handler source (never hand-edited, never drifts).
The Description column is hand-written prose: regen PRESERVES existing descriptions
keyed by action name and leaves a "-" placeholder for new/unknown actions. The
generator only ever rewrites the bytes between the marker pair; everything else in
the file is byte-identical pre and post.

Assumption: a switch `case` label counts as supported purely by existing, so an arm
that is present but throws `UnsupportedOperation` in its body would still be listed.
Handlers here reject unsupported actions from the `default` arm, so this holds; a
per-action stub that throws would be a false positive.

Run from anywhere in the repo:
    python3 tools/docs/regen_action_docs.py            # rewrite docs in place
    python3 tools/docs/regen_action_docs.py --strict   # exit non-zero on warnings
"""
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

import yaml

MARKER_START = "<!-- floci:actions:start -->"
MARKER_END = "<!-- floci:actions:end -->"

PLACEHOLDER = "-"

# `case "ActionName" ->` switch arms (Query / JSON 1.1 protocols). A single arm may
# carry several labels: `case "Foo", "Bar", "Baz" ->`; all are extracted, in order.
# The label list may also wrap across lines (valid Java 14+ switch syntax), so the
# capture group excludes only the `{ } ;` statement/body boundaries rather than
# newlines: it spans line breaks between labels but stops at the arm's `->` and can't
# run into an arm body or a colon-style `case` that has no `->`. Without this a
# wrapped arm would be silently dropped, and `docs-check` would stay green because the
# doc and the tool's output would agree on the omission.
# The label head requires `[A-Z][a-z]` (PascalCase), which deliberately excludes
# SCREAMING_CASE enum labels in non-action switches a handler may also contain
# (e.g. `switch (state) { case "DISABLED" -> ... }`). AWS action names are always
# PascalCase, so no real action is lost.
SWITCH_CASE_RE = re.compile(r"^\s*case\s+([^{};]+?)\s*->", re.MULTILINE)
SWITCH_LABEL_RE = re.compile(r'"([A-Z][a-z][A-Za-z0-9]*)"')

# REST controllers: a JAX-RS verb annotation followed by the public method it decorates,
# inside a class that carries a class-level @Path. The action is ucfirst(methodName),
# corrected per-service via rename_actions / exclude_actions (method names are not
# always the canonical AWS action, e.g. updateV2Integration -> UpdateIntegration).
CLASS_PATH_RE = re.compile(r"^\s*@Path\b", re.MULTILINE)
HTTP_ANNO_RE = re.compile(r"^\s*@(?:GET|POST|PUT|DELETE|PATCH)\b")
ANNOTATION_LINE_RE = re.compile(r"^\s*@\w")
PUBLIC_METHOD_RE = re.compile(r"^\s*public\s+\S+(?:<[^>]*>)?\s+([a-z][A-Za-z0-9]*)\s*\(")
# Line/block-comment lines (Javadoc) that may sit between the verb annotation and the
# method signature; skipped so they don't reset the `pending` flag mid-lookahead.
COMMENT_LINE_RE = re.compile(r"^\s*(?://|/\*|\*)")


@dataclass(frozen=True)
class Source:
    path: Path
    mode: str  # 'switch' | 'rest'


@dataclass(frozen=True)
class ServiceEntry:
    service: str
    doc: Path
    sources: list[Source]
    rename_actions: dict[str, str] = field(default_factory=dict)
    exclude_actions: frozenset[str] = field(default_factory=frozenset)
    handler: Path | None = None  # optional explicit handler path for excluded services


# --------------------------------------------------------------------------- #
# Extraction (pure functions over Java source text)
# --------------------------------------------------------------------------- #
def extract_switch_actions(java_source: str) -> list[str]:
    """Action names from `case "X" ->` arms, in source order.

    Handles multi-label arms (`case "A", "B" ->`) by extracting every quoted
    PascalCase label up to the arm's `->`.
    """
    out: list[str] = []
    for arm in SWITCH_CASE_RE.finditer(java_source):
        out.extend(m.group(1) for m in SWITCH_LABEL_RE.finditer(arm.group(1)))
    return out


def extract_rest_actions(java_source: str) -> list[str]:
    """ucfirst(method name) of @GET/@POST/@PUT/@DELETE/@PATCH methods, source order.

    Only applies to classes containing a class-level @Path. Returns [] otherwise.
    """
    if not CLASS_PATH_RE.search(java_source):
        return []
    actions: list[str] = []
    pending = False
    for line in java_source.splitlines():
        if not line.strip():
            continue
        if HTTP_ANNO_RE.match(line):
            pending = True
            continue
        if not pending:
            continue
        if ANNOTATION_LINE_RE.match(line):
            continue
        if COMMENT_LINE_RE.match(line):
            continue
        m = PUBLIC_METHOD_RE.match(line)
        if m:
            name = m.group(1)
            actions.append(name[0].upper() + name[1:])
        pending = False
    return actions


def extract_actions(entry: ServiceEntry) -> list[str]:
    """Merged, deduped, override-corrected action list for a service.

    Order: first appearance across the source list in registry order. rename_actions
    is applied before dedup (so a renamed collision dedupes correctly); excluded
    names are dropped after rename.
    """
    seen: set[str] = set()
    out: list[str] = []
    for source in entry.sources:
        text = source.path.read_text(encoding="utf-8")
        if source.mode == "switch":
            raw = extract_switch_actions(text)
        elif source.mode == "rest":
            raw = extract_rest_actions(text)
        else:
            raise ValueError(f"unknown mode: {source.mode!r}")
        for name in raw:
            name = entry.rename_actions.get(name, name)
            if name in entry.exclude_actions:
                continue
            if name not in seen:
                seen.add(name)
                out.append(name)
    return out


# --------------------------------------------------------------------------- #
# Markdown marker block: parse existing descriptions, render new table
# --------------------------------------------------------------------------- #
_SEPARATOR_ROW_RE = re.compile(r"^\|[\s:|-]+\|$")


def _split_row(row: str) -> list[str]:
    """Split a markdown table row into trimmed cells, honoring escaped pipes (\\|).

    A literal pipe inside a cell must be written as \\| in markdown; an unescaped |
    is always a column separator (including pipes inside inline code, which is why
    descriptions should escape them). The split is on unescaped pipes only.
    """
    cells: list[str] = []
    buf: list[str] = []
    i = 0
    n = len(row)
    while i < n:
        ch = row[i]
        if ch == "\\" and i + 1 < n and row[i + 1] == "|":
            buf.append("|")
            i += 2
            continue
        if ch == "|":
            cells.append("".join(buf).strip())
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    cells.append("".join(buf).strip())
    # A well-formed row starts and ends with |, producing empty edge cells; drop them.
    if cells and cells[0] == "":
        cells = cells[1:]
    if cells and cells[-1] == "":
        cells = cells[:-1]
    return cells


def _strip_action_cell(cell: str) -> str:
    """Normalize an action cell to the bare action name (handles `Backticked` form)."""
    return cell.strip().strip("`").strip()


def parse_marker_block(md: str) -> tuple[str, dict[str, str], str]:
    """Split a doc around the marker block.

    Returns (prefix_through_start_marker_line, {action: description}, suffix_from_end_marker_line).
    Raises ValueError if markers are missing, unbalanced, or the enclosed table is malformed.
    """
    if md.count(MARKER_START) != 1 or md.count(MARKER_END) != 1:
        raise ValueError(
            f"document must contain exactly one '{MARKER_START}' and one '{MARKER_END}'"
        )
    start_idx = md.index(MARKER_START)
    end_idx = md.index(MARKER_END)
    if end_idx < start_idx:
        raise ValueError("end marker appears before start marker")

    try:
        after_start = md.index("\n", start_idx) + 1
        before_end = md.rindex("\n", 0, end_idx) + 1
    except ValueError:
        raise ValueError(
            f"'{MARKER_START}' and '{MARKER_END}' must each sit on their own line"
        ) from None

    prefix = md[:after_start]
    suffix = md[before_end:]
    body = md[after_start:before_end]
    return prefix, _parse_table(body), suffix


def _parse_table(body: str) -> dict[str, str]:
    """Parse {action: description} from the table inside the marker block.

    Tolerant of a missing/extra Description column, padded alignment rows, and an
    empty block (first migration). Rejects rows that don't look like a table row
    once a table has started, loudly, rather than silently dropping content.
    """
    descriptions: dict[str, str] = {}
    seen_header = False
    for line in body.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if not stripped.startswith("|"):
            raise ValueError(f"unexpected non-table line inside marker block: {stripped!r}")
        if _SEPARATOR_ROW_RE.match(stripped):
            continue
        cells = _split_row(stripped)
        if not cells:
            continue
        if len(cells) > 2:
            raise ValueError(
                f"malformed table row, expected at most 2 columns "
                f"(escape literal pipes as '\\|'): {stripped!r}"
            )
        action = _strip_action_cell(cells[0])
        # Skip the header row (its first cell is the literal "Action").
        if not seen_header and action.lower() == "action":
            seen_header = True
            continue
        seen_header = True
        if not action:
            continue
        desc = cells[1].strip() if len(cells) > 1 else ""
        if desc == PLACEHOLDER:
            desc = ""
        descriptions[action] = desc
    return descriptions


def render_marker_block(actions: list[str], descriptions: dict[str, str]) -> str:
    """Render the table that goes between the marker lines (two columns).

    Preserves a known description; emits the "-" placeholder for an unknown action.
    """
    lines = ["| Action | Description |", "| --- | --- |"]
    for action in actions:
        desc = descriptions.get(action) or PLACEHOLDER
        # Descriptions are held unescaped in memory; re-escape literal pipes so the
        # cell round-trips through _split_row on the next regen instead of splitting.
        desc = desc.replace("|", r"\|")
        lines.append(f"| `{action}` | {desc} |")
    return "\n".join(lines) + "\n"


def regenerate_doc_content(
    actions: list[str], doc_content: str
) -> tuple[str, list[str]]:
    """Pure: given the new action list and the current doc, return (new_doc, orphans).

    Orphans are actions present in the doc's old block but no longer in source; their
    descriptions are dropped from the new block (the row goes away).
    """
    prefix, prior, suffix = parse_marker_block(doc_content)
    keep = set(actions)
    orphans = [a for a in prior if a not in keep]
    body = render_marker_block(actions, prior)
    return prefix + body + suffix, orphans


# --------------------------------------------------------------------------- #
# Registry + driver
# --------------------------------------------------------------------------- #
def _repo_root() -> Path:
    return Path(__file__).resolve().parent.parent.parent


def _load_registry(repo_root: Path) -> tuple[list[ServiceEntry], set[Path]]:
    """Load services.yaml. Returns (service entries, acknowledged deferred handler paths).

    Schema: a top-level mapping with `services:` (the registered entries) and
    `deferred_handlers:` (switch handlers known to exist but intentionally not yet
    covered, so the unregistered-handler sentinel doesn't flag them every run).
    """
    registry_path = repo_root / "tools" / "docs" / "services.yaml"
    raw = yaml.safe_load(registry_path.read_text(encoding="utf-8")) or {}
    entries: list[ServiceEntry] = []
    for item in raw.get("services") or []:
        sources = [
            Source(path=repo_root / s["path"], mode=s["mode"])
            for s in item.get("sources") or []
        ]
        handler = item.get("handler")
        entries.append(
            ServiceEntry(
                service=item["service"],
                doc=repo_root / item["doc"],
                sources=sources,
                rename_actions=dict(item.get("rename_actions") or {}),
                exclude_actions=frozenset(item.get("exclude_actions") or []),
                handler=repo_root / handler if handler else None,
            )
        )
    deferred = {
        (repo_root / d["path"]).resolve()
        for d in raw.get("deferred_handlers") or []
    }
    return entries, deferred


def _process_entry(entry: ServiceEntry) -> tuple[bool, list[str]]:
    """Regenerate one service's doc. Returns (changed, orphans). Skip-mode is a no-op."""
    if not entry.sources:
        return False, []
    actions = extract_actions(entry)
    doc_content = entry.doc.read_text(encoding="utf-8")
    new_content, orphans = regenerate_doc_content(actions, doc_content)
    if new_content != doc_content:
        entry.doc.write_text(new_content, encoding="utf-8")
        return True, orphans
    return False, orphans


def _find_unregistered_handlers(
    repo_root: Path, entries: list[ServiceEntry], deferred: set[Path]
) -> list[str]:
    """Top-level *Handler.java files that produce switch actions but aren't registered
    and aren't on the deferred allowlist.

    New-service drift sentinel: a brand-new switch handler must be either registered
    or added to deferred_handlers (with a reason), or this fails --strict. REST
    controllers are opt-in (per-judgment) and not flagged here.
    """
    services_root = repo_root / "src/main/java/io/github/hectorvent/floci/services"
    registered = {s.path.resolve() for entry in entries for s in entry.sources}
    registered |= {entry.handler.resolve() for entry in entries if entry.handler}
    registered |= deferred
    unregistered: list[str] = []
    # Recursive: handlers live at varying depths (e.g. cloudwatch/logs/, cloudwatch/metrics/).
    for path in sorted(services_root.glob("**/[A-Z]*Handler.java")):
        if path.resolve() in registered:
            continue
        if not extract_switch_actions(path.read_text(encoding="utf-8")):
            continue
        unregistered.append(str(path.relative_to(repo_root)))
    return unregistered


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--strict",
        action="store_true",
        help="treat warnings (orphan actions, unregistered handlers) as errors",
    )
    args = parser.parse_args(argv)

    repo_root = _repo_root()
    entries, deferred = _load_registry(repo_root)

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
                f"{entry.service}: action '{orphan}' was in the doc but not in source (row dropped)"
            )

    for handler in _find_unregistered_handlers(repo_root, entries, deferred):
        warnings.append(
            f"unregistered handler '{handler}' produces actions but is not in "
            f"tools/docs/services.yaml (register it or add to deferred_handlers)"
        )

    for w in warnings:
        print(f"warning: {w}", file=sys.stderr)

    if args.strict and warnings:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
