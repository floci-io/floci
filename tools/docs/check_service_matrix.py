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

Run from anywhere in the repo:
    python3 tools/docs/check_service_matrix.py            # print warnings only
    python3 tools/docs/check_service_matrix.py --strict   # exit non-zero on warnings
"""
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path

try:
    from tools.docs import regen_action_docs
except ModuleNotFoundError:
    import regen_action_docs

import yaml

CATALOG_SOURCE = "src/main/java/io/github/hectorvent/floci/core/common/ResolvedServiceCatalog.java"
MATRIX_DOC = "docs/services/index.md"
REGISTRY = "tools/docs/service_matrix.yaml"

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

# A matrix row's first cell is a markdown link to its doc page: `[Label](slug.md...)`.
# The slug is everything up to `.md`; a trailing `#anchor` (e.g. `cloudwatch.md#metrics`)
# is not part of the filename and is dropped by stopping the match at `.md` itself.
MATRIX_LINK_RE = re.compile(r"\]\(([a-z0-9][a-z0-9\-]*)\.md")

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


def extract_matrix_slugs(md_source: str) -> set[str]:
    """Doc filenames (no extension, no anchor) linked from the Service Matrix table.

    Scoped to the table between MATRIX_HEADING and MATRIX_END_HEADING so an unrelated
    markdown link elsewhere on the page (prose, footer) can never be mistaken for a
    documented row. Raises ValueError if either heading is missing, rather than letting
    a renamed heading silently empty the table (and every service look undocumented).
    """
    try:
        start = md_source.index(MATRIX_HEADING)
        end = md_source.index(MATRIX_END_HEADING, start)
    except ValueError:
        raise ValueError(
            f"{MATRIX_DOC}: could not find '{MATRIX_HEADING}' ... '{MATRIX_END_HEADING}' "
            "(has a heading been renamed or reordered?)"
        ) from None
    table = md_source[start:end]
    return set(MATRIX_LINK_RE.findall(table))

def extract_matrix_action_counts(md_source: str) -> dict[str, int]:
    """Extract documented operation counts for base service-doc rows.

    Supports both plain numeric counts and composite counts such as
    ``61 (+ 3 tagging ...)``.
    Rows linking to a document anchor are ignored because the linked
    document may contain multiple distinct action sections.
    """
    try:
        start = md_source.index(MATRIX_HEADING)
        end = md_source.index(MATRIX_END_HEADING, start)
    except ValueError:
        raise ValueError(
            f"{MATRIX_DOC}: could not find '{MATRIX_HEADING}' ... "
            f"'{MATRIX_END_HEADING}' (has a heading been renamed or reordered?)"
        ) from None

    counts: dict[str, int] = {}

    for line in md_source[start:end].splitlines():
        match = re.match(
            r"^\|\s*\[[^\]]+\]\(([a-z0-9][a-z0-9\-]*)\.md"
            r"(#[^)]*)?\)\s*\|.*\|\s*(\d+)"
            r"(?:\s*\(\+\s*(\d+)[^)]*\))?\s*\|$",
            line,
        )
        if not match:
            continue

        slug, anchor, base_count, extra_count = match.groups()

        if anchor:
            continue

        counts.setdefault(slug, int(base_count))

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


def extract_action_counts(repo_root: Path) -> dict[str, int]:
    """Extract mechanically verifiable action counts.
    Generated service documents are counted from their generated action-table
    markers. IAM is a deferred non-tabular document, so its Query switch is
    parsed directly using the same action parser as regen_action_docs.py.
    Other services without generated action-table markers are intentionally
    skipped here because their handlers use non-tabular or mixed dispatch
    patterns that cannot be counted safely by the generic registry parser.
    """
    md_source = (repo_root / MATRIX_DOC).read_text(encoding="utf-8")
    matrix_counts = extract_matrix_action_counts(md_source)

    counts: dict[str, int] = {}

    # Generated service documents.
    for slug in matrix_counts:
        doc_path = repo_root / SERVICES_DIR / f"{slug}.md"

        if not doc_path.exists():
            continue

        doc_source = doc_path.read_text(encoding="utf-8")
        generated_count = count_generated_actions(doc_source)

        if generated_count is not None:
            counts[slug] = generated_count

    # IAM is intentionally non-tabular, so validate it from its handler.
    iam_source = (
        repo_root
        / "src/main/java/io/github/hectorvent/floci/services/iam/IamQueryHandler.java"
    )

    counts["iam"] = len(
        regen_action_docs.extract_switch_actions(
            iam_source.read_text(encoding="utf-8")
        )
    )

    return counts

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
        matrix_action_counts = extract_matrix_action_counts(md_source)
        source_action_counts = extract_action_counts(repo_root)
    except (ValueError, OSError) as exc:
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
                f"service '{slug}' documents {documented_count} supported operations "
                f"in {MATRIX_DOC}, but the action source contains {source_count}; "
                "update the matrix count"
            )
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
