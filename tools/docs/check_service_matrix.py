#!/usr/bin/env python3
"""Fail CI when a registered service has no row in the docs/services/index.md matrix.

`ResolvedServiceCatalog.java` is the source of truth for which AWS services Floci
emulates: every `descriptor("externalKey", "configKey", ...)` call registers one.
`docs/services/index.md` is the human-facing matrix users browse to find out what's
supported. Nothing previously connected the two, so a service could be registered,
enabled and routable while remaining absent from the matrix with every other check
green (see floci-io/floci#2454 - four services, and Bedrock AgentCore for five days,
shipped without a row).

This script closes that gap the same way regen_action_docs.py's unregistered-handler
sentinel closes the equivalent gap for action tables: extract descriptor keys from
Java source, extract documented filenames from the matrix, and fail on anything that
resolves to neither - unless tools/docs/service_matrix.yaml says otherwise (an alias
for a key whose AWS signing name differs from its doc filename, or a time-boxed
deferred entry for a service that is genuinely not documented yet).

The reverse direction is checked too: every docs/services/*.md page must have a
matrix row, unless listed under `facet_pages:` in the registry, so a page can no
longer exist in the nav with no row (elb-classic.md did, hidden by the `elb` key
exact-matching the v2 page).

Each row's operation count is checked as well: against the page's generated action
table, or against the dispatchers `action_count_sources:` names for it. A row neither
covers fails unless `hand_counted:` lists it with the reason it cannot be derived, so a
new row cannot quietly fall back to a hand-kept number.

Run from anywhere in the repo:
    python3 tools/docs/check_service_matrix.py            # print warnings only
    python3 tools/docs/check_service_matrix.py --strict   # exit non-zero on warnings
"""
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, replace
from datetime import date
from pathlib import Path

try:
    from tools.docs import regen_action_docs
except ModuleNotFoundError:
    import regen_action_docs

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from java_sources import PACKAGE_DIR, package_source  # noqa: E402

CATALOG_SOURCE = f"{PACKAGE_DIR}/core/common/ResolvedServiceCatalog.java"
MATRIX_DOC = "docs/services/index.md"
REGISTRY = "tools/docs/service_matrix.yaml"

# Keys an action_count_sources mapping entry may carry, beside the plain-path form,
# and the keys of one of its sources.
ACTION_COUNT_SOURCE_KEYS = frozenset({"sources", "rename_actions", "exclude_actions"})
SOURCE_KEYS = frozenset({"path", "mode", "route_prefix", "skip_route_prefix"})

MATRIX_HEADING = "## Service Matrix"
MATRIX_END_HEADING = "## Common Setup"

# The externalKey is the first string literal argument to descriptor(...). It's always
# a plain double-quoted literal (no concatenation, no constants) in current usage.
DESCRIPTOR_KEY_RE = re.compile(r'\bdescriptor\(\s*"([^"]+)"')

# Every descriptor(...) call site, literal-keyed or not - used only to catch a call this
# parser's literal-only DESCRIPTOR_KEY_RE would otherwise miss in total silence (a future
# externalKey built from a variable or constant instead of a plain string literal). The
# negative lookbehind excludes the `private static ServiceDescriptor descriptor(...)`
# definition itself, the one place "descriptor(" appears that isn't a call.
ALL_DESCRIPTOR_CALLS_RE = re.compile(r"(?<!ServiceDescriptor )\bdescriptor\(")

# A matrix row's first cell links its doc page: `[Label](slug.md)`, possibly formatted
# (`**[Label](slug.md)**`). The key is the slug plus any `#anchor`
# (`cloudwatch.md#metrics` -> `cloudwatch#metrics`); the slug alone is the doc filename.
MATRIX_CELL_LINK_RE = re.compile(r"\]\(([a-z0-9][a-z0-9\-]*)\.md(#[^)]*)?\)")
MATRIX_HEADER_CELL = "Service"
MATRIX_SEPARATOR_RE = re.compile(r"^\|[\s:|-]+\|$")

# The operation count closing a row: a number, optionally followed by one
# parenthetical note.
MATRIX_COUNT_RE = re.compile(r"\|\s*(\d+)(?:\s*\([^)]*\))?\s*\|$")

SERVICES_DIR = "docs/services"


@dataclass(frozen=True)
class DeferredEntry:
    key: str
    reason: str
    by: date


def extract_descriptor_keys(java_source: str) -> list[str]:
    """externalKey of every descriptor(...) call, in source order.

    May contain dupes only if the source itself registers the same externalKey twice
    (a copy-paste bug); the caller dedups via set() before comparing against the matrix.
    """
    return DESCRIPTOR_KEY_RE.findall(java_source)


def count_descriptor_calls(java_source: str) -> int:
    """Total descriptor(...) call sites, regardless of how the first argument is built."""
    return len(ALL_DESCRIPTOR_CALLS_RE.findall(java_source))


def _matrix_table(md_source: str) -> str:
    """The Service Matrix table, between MATRIX_HEADING and MATRIX_END_HEADING.

    Raises ValueError if either heading is missing, rather than letting a renamed
    heading silently empty the table (and every service look undocumented).
    """
    try:
        start = md_source.index(MATRIX_HEADING)
        end = md_source.index(MATRIX_END_HEADING, start)
    except ValueError:
        raise ValueError(
            f"{MATRIX_DOC}: could not find '{MATRIX_HEADING}' ... '{MATRIX_END_HEADING}' "
            "(has a heading been renamed or reordered?)"
        ) from None
    return md_source[start:end]


def _matrix_rows(md_source: str) -> list[tuple[str, str]]:
    """(key, line) of each Service Matrix row, the key being its first cell's link target.

    Presence, counts and coverage all read the rows from here, so a row cannot count as
    documented while escaping the count checks. A row whose first cell links no page
    is refused rather than skipped.
    """
    rows: list[tuple[str, str]] = []
    for line in _matrix_table(md_source).splitlines():
        line = line.strip()
        if not line.startswith("|") or MATRIX_SEPARATOR_RE.match(line):
            continue
        first_cell = line.split("|")[1].strip()
        link = MATRIX_CELL_LINK_RE.search(first_cell)
        if link:
            rows.append((link.group(1) + (link.group(2) or ""), line))
        elif first_cell != MATRIX_HEADER_CELL:
            raise ValueError(f"{MATRIX_DOC}: the Service Matrix row '{first_cell}' links no docs/services page "
                             "from its first cell")
    return rows


def extract_matrix_slugs(md_source: str) -> set[str]:
    """Doc filenames (no extension, no anchor) linked from the Service Matrix rows.

    Scoped to the table so an unrelated markdown link elsewhere on the page (prose,
    footer) can never be mistaken for a documented row.
    """
    return {key.split("#")[0] for key, _line in _matrix_rows(md_source)}


def extract_matrix_row_keys(md_source: str) -> list[str]:
    """Every Service Matrix row's link target, `slug` or `slug#anchor`, in table order,
    whether or not its count is numeric."""
    return [key for key, _line in _matrix_rows(md_source)]


def extract_matrix_action_counts(md_source: str) -> dict[str, int]:
    """Extract documented operation counts, keyed by the row's link target.

    Supports plain numeric counts and a count followed by a parenthetical note,
    such as ``71 (+ 3 tagging ...)`` or ``79 (of 161 recognized ...)``, whose
    leading number is the one checked. A row linking to a document anchor is
    keyed ``slug#anchor`` (``dynamodb#streams``), so a page documenting two
    services gives each row its own count.
    """
    counts: dict[str, int] = {}
    for key, line in _matrix_rows(md_source):
        count = MATRIX_COUNT_RE.search(line)
        if count:
            counts.setdefault(key, int(count.group(1)))
    return counts


def count_generated_actions(md_source: str) -> int | None:
    """Count action rows inside a generated action-table marker pair.

    Returns None when the document does not contain a generated action table.
    """
    start_marker = "<!-- floci:actions:start -->"
    end_marker = "<!-- floci:actions:end -->"

    try:
        start = md_source.index(start_marker) + len(start_marker)
        end = md_source.index(end_marker, start)
    except ValueError:
        return None

    section = md_source[start:end]

    count = 0

    for line in section.splitlines():
        line = line.strip()

        if not line.startswith("|") or line.count("|") < 3:
            continue

        # Skip the markdown header/separator rows.
        if re.match(r"^\|\s*Action\s*\|", line, re.IGNORECASE):
            continue

        if re.match(r"^\|\s*:?-{3,}", line):
            continue

        count += 1

    return count


def _action_count_entry(repo_root: Path, key: str, spec: object) -> regen_action_docs.ServiceEntry:
    """The regen_action_docs entry an action_count_sources value describes.

    A value is either one source path, read as a switch dispatcher, or a mapping with
    `sources:` (paths, or `{path, mode}` for another regen_action_docs mode), an
    optional `rename_actions:` mapping for a controller method not named after its
    AWS action, and an optional `exclude_actions:` list for labels or methods that are
    not actions of the row.
    """
    if isinstance(spec, str):
        spec = {"sources": [spec]}
    if not isinstance(spec, dict):
        raise ValueError(f"{REGISTRY}: action count source '{key}' needs a source path")
    unknown = spec.keys() - ACTION_COUNT_SOURCE_KEYS
    if unknown:
        raise ValueError(f"{REGISTRY}: action count source '{key}' has unknown key(s): {', '.join(sorted(unknown))}")
    raw_sources = spec.get("sources")
    if not isinstance(raw_sources, list) or not raw_sources:
        raise ValueError(f"{REGISTRY}: action count source '{key}' needs a non-empty sources list")
    sources = []
    for raw in raw_sources:
        if isinstance(raw, str):
            raw = {"path": raw, "mode": "switch"}
        if not isinstance(raw, dict) or not isinstance(raw.get("path"), str) or not isinstance(raw.get("mode"), str):
            raise ValueError(f"{REGISTRY}: action count source '{key}' has a source without a path and mode: {raw!r}")
        unknown = raw.keys() - SOURCE_KEYS
        if unknown:
            raise ValueError(f"{REGISTRY}: action count source '{key}' has a source with unknown key(s): "
                             f"{', '.join(sorted(unknown))}")
        sources.append(regen_action_docs.Source(
            path=repo_root / package_source(raw["path"]),
            mode=raw["mode"],
            route_prefix=raw.get("route_prefix"),
            skip_route_prefix=raw.get("skip_route_prefix"),
        ))
    renamed = spec.get("rename_actions") or {}
    if not isinstance(renamed, dict):
        raise ValueError(f"{REGISTRY}: action count source '{key}' needs rename_actions as a mapping")
    excluded = spec.get("exclude_actions") or []
    if not isinstance(excluded, list):
        raise ValueError(f"{REGISTRY}: action count source '{key}' needs exclude_actions as a list")
    return regen_action_docs.ServiceEntry(
        service=key,
        doc=repo_root / MATRIX_DOC,
        sources=sources,
        rename_actions=dict(renamed),
        exclude_actions=frozenset(excluded),
    )


def count_source_actions(repo_root: Path, key: str, spec: object) -> int:
    """Distinct actions the sources of one action_count_sources entry dispatch.

    Every source has to contribute at least one action, so a path that moved or a
    wrong mode fails here instead of quietly lowering the count.
    """
    entry = _action_count_entry(repo_root, key, spec)
    try:
        for source in entry.sources:
            if not regen_action_docs.extract_actions(replace(entry, sources=[source])):
                raise ValueError(
                    f"has no actions in {source.path.relative_to(repo_root)} ({source.mode})"
                )
        return len(regen_action_docs.extract_actions(entry))
    except ValueError as exc:
        raise ValueError(f"{REGISTRY}: action count source '{key}' {exc}") from None


def extract_action_counts(repo_root: Path) -> dict[str, int]:
    """Extract mechanically verifiable action counts.
    Generated service documents are counted from their generated action-table
    markers. Rows mapped in action_count_sources are counted from their
    dispatchers with the same action parser as regen_action_docs.py, so a
    grouped or multi-table page keeps its layout. A mapped key is a matrix row's
    link target, `slug` or `slug#anchor`.
    """
    md_source = (repo_root / MATRIX_DOC).read_text(encoding="utf-8")
    matrix_counts = extract_matrix_action_counts(md_source)

    counts: dict[str, int] = {}

    # Generated service documents.
    for slug in sorted(extract_matrix_slugs(md_source)):
        doc_path = repo_root / SERVICES_DIR / f"{slug}.md"

        if not doc_path.exists():
            continue

        doc_source = doc_path.read_text(encoding="utf-8")
        generated_count = count_generated_actions(doc_source)

        if generated_count is not None:
            if slug not in matrix_counts:
                raise ValueError(
                    f"{MATRIX_DOC}: '{slug}' needs a numeric matrix row for its generated action table"
                )
            counts[slug] = generated_count

    registry = yaml.safe_load((repo_root / REGISTRY).read_text(encoding="utf-8")) or {}
    sources = registry.get("action_count_sources")
    if not isinstance(sources, dict) or not sources:
        raise ValueError(f"{REGISTRY}: action_count_sources must be a non-empty mapping")
    for key, spec in sources.items():
        if key not in matrix_counts:
            raise ValueError(f"{REGISTRY}: action count source '{key}' has no numeric matrix row")
        if key in counts:
            raise ValueError(f"{REGISTRY}: '{key}' already has a generated action table")
        counts[key] = count_source_actions(repo_root, key, spec)

    return counts


def load_hand_counted(repo_root: Path) -> dict[str, str]:
    """Rows `hand_counted:` exempts from the count check, each with the reason why."""
    raw = yaml.safe_load((repo_root / REGISTRY).read_text(encoding="utf-8")) or {}
    hand_counted = raw.get("hand_counted") or {}
    if not isinstance(hand_counted, dict):
        raise ValueError(f"{REGISTRY}: hand_counted must map a row to the reason it is counted by hand")
    for key, reason in hand_counted.items():
        if not isinstance(reason, str) or not reason.strip():
            raise ValueError(f"{REGISTRY}: hand_counted row '{key}' needs the reason it is counted by hand")
    return hand_counted


def find_unchecked_rows(row_keys: list[str], matrix_counts: dict[str, int],
                        checked: set[str], hand_counted: dict[str, str]) -> list[str]:
    """Warnings for rows whose count nothing checks, and for stale hand_counted entries."""
    warnings: list[str] = []
    seen: set[str] = set()
    for key in row_keys:
        if key in seen:
            warnings.append(
                f"two rows in {MATRIX_DOC} link to '{key}'; give one an anchor so each row has its own count"
            )
            continue
        seen.add(key)
        if key not in matrix_counts:
            warnings.append(f"row '{key}' in {MATRIX_DOC} has no numeric operation count")
        elif key in checked and key in hand_counted:
            warnings.append(f"row '{key}' is checked, so drop it from hand_counted in {REGISTRY}")
        elif key not in checked and key not in hand_counted:
            warnings.append(
                f"nothing checks the count of row '{key}' in {MATRIX_DOC}; map its dispatchers under "
                f"action_count_sources in {REGISTRY}, or list it under hand_counted with the reason"
            )
    for key in sorted(hand_counted.keys() - seen):
        warnings.append(f"hand_counted row '{key}' in {REGISTRY} is not a row of {MATRIX_DOC}")
    return warnings


def find_unlinked_pages(services_dir: Path, slugs: set[str], facet_pages: set[str]) -> list[str]:
    """docs/services pages (by slug) that no matrix row links and no facet entry excuses.

    index.md is the matrix page itself and is never expected to link to itself.
    """
    pages = sorted(p.stem for p in services_dir.glob("*.md") if p.name != "index.md")
    return [page for page in pages if page not in slugs and page not in facet_pages]


def _load_registry(repo_root: Path) -> tuple[dict[str, str], list[DeferredEntry], set[str]]:
    """Load service_matrix.yaml. Returns (aliases, deferred entries, facet pages).

    Schema: `aliases:` maps an externalKey to the doc slug that documents it;
    `deferred:` lists externalKeys with no row yet, each requiring `reason` and a
    `by` expiry date (see service_matrix.yaml's header comment for why expiry is
    mandatory rather than a permanent bypass); `facet_pages:` lists docs/services
    slugs that deliberately have no matrix row of their own.
    """
    raw = yaml.safe_load((repo_root / REGISTRY).read_text(encoding="utf-8")) or {}
    aliases = dict(raw.get("aliases") or {})
    facet_pages = set(raw.get("facet_pages") or [])
    deferred: list[DeferredEntry] = []
    for item in raw.get("deferred") or []:
        missing = [f for f in ("key", "reason", "by") if f not in item]
        if missing:
            raise ValueError(
                f"{REGISTRY}: deferred entry {item!r} is missing required field(s): {missing}"
            )
        deferred.append(
            DeferredEntry(
                key=item["key"],
                reason=item["reason"],
                by=date.fromisoformat(str(item["by"])),
            )
        )
    return aliases, deferred, facet_pages


def find_undocumented(
    keys: list[str],
    slugs: set[str],
    aliases: dict[str, str],
    deferred: list[DeferredEntry],
    today: date,
) -> tuple[list[str], list[DeferredEntry]]:
    """Classify descriptor keys against the matrix.

    Returns (undocumented, expired_deferred):
      - undocumented: keys that resolve to no matrix row by exact match, alias, or an
        unexpired deferred entry. Includes keys whose only deferred entry has expired.
      - expired_deferred: the deferred entries (still present in the registry) whose
        `by` date has passed - reported separately so the warning names the date and
        reason instead of reading like a brand-new, unexplained gap.
    """
    active_deferred = {d.key: d for d in deferred if d.by >= today}
    expired = [d for d in deferred if d.by < today]

    undocumented: list[str] = []
    for key in sorted(set(keys)):
        target = aliases.get(key, key)
        if target in slugs:
            continue
        if key in active_deferred:
            continue
        undocumented.append(key)

    expired_deferred = [d for d in expired if d.key in set(keys) and aliases.get(d.key, d.key) not in slugs]
    return undocumented, expired_deferred


def _repo_root() -> Path:
    return Path(__file__).resolve().parent.parent.parent


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--strict",
        action="store_true",
        help="treat warnings (undocumented services, expired deferrals) as errors",
    )
    args = parser.parse_args(argv)

    repo_root = _repo_root()

    try:
        aliases, deferred, facet_pages = _load_registry(repo_root)
    except (ValueError, yaml.YAMLError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    java_source = (repo_root / CATALOG_SOURCE).read_text(encoding="utf-8")
    keys = extract_descriptor_keys(java_source)
    if not keys:
        print(f"error: no descriptor(...) calls found in {CATALOG_SOURCE}", file=sys.stderr)
        return 1

    total_calls = count_descriptor_calls(java_source)
    if total_calls != len(keys):
        # DESCRIPTOR_KEY_RE only matches a plain double-quoted externalKey. A call built
        # from a variable or constant would be silently invisible to every check below -
        # this is the one place that gap gets caught instead of just under-counting.
        print(
            f"error: {CATALOG_SOURCE} has {total_calls} descriptor(...) call(s) but only "
            f"{len(keys)} have a plain string-literal externalKey as the first argument; "
            "the rest are invisible to this script and need a parser update",
            file=sys.stderr,
        )
        return 1

    md_source = (repo_root / MATRIX_DOC).read_text(encoding="utf-8")
    try:
        slugs = extract_matrix_slugs(md_source)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    try:
        matrix_row_keys = extract_matrix_row_keys(md_source)
        matrix_action_counts = extract_matrix_action_counts(md_source)
        source_action_counts = extract_action_counts(repo_root)
        hand_counted = load_hand_counted(repo_root)
    except (ValueError, OSError, yaml.YAMLError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    undocumented, expired_deferred = find_undocumented(
        keys, slugs, aliases, deferred, date.today()
    )

    warnings: list[str] = []

    for slug, documented_count in matrix_action_counts.items():
        source_count = source_action_counts.get(slug)
        if source_count is None:
            continue
        if documented_count != source_count:
            warnings.append(
                f"row '{slug}' documents {documented_count} supported operations "
                f"in {MATRIX_DOC}, but the action source contains {source_count}; "
                "update the matrix count"
            )
    warnings.extend(find_unchecked_rows(
        matrix_row_keys, matrix_action_counts, set(source_action_counts), hand_counted))
    for d in expired_deferred:
        warnings.append(
            f"deferred entry '{d.key}' expired on {d.by.isoformat()} ({d.reason}); "
            f"add its docs/services/index.md row or renew the expiry in {REGISTRY}"
        )
    still_undocumented = set(undocumented) - {d.key for d in expired_deferred}
    for key in sorted(still_undocumented):
        warnings.append(
            f"service '{key}' is registered in {CATALOG_SOURCE} but has no row in "
            f"{MATRIX_DOC} (add a row, an alias, or a time-boxed entry in {REGISTRY})"
        )

    for page in find_unlinked_pages(repo_root / SERVICES_DIR, slugs, facet_pages):
        warnings.append(
            f"{SERVICES_DIR}/{page}.md has no row in {MATRIX_DOC} (add a row, or list it "
            f"under facet_pages in {REGISTRY} if it is a facet of another service's page)"
        )

    for w in warnings:
        print(f"warning: {w}", file=sys.stderr)

    if args.strict and warnings:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
