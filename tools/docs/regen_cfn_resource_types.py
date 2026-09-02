#!/usr/bin/env python3
"""Regenerate the "Supported Resource Types" table in docs/services/cloudformation.md.

The set of types comes from src/test/resources/cloudformation/supported-resource-types.tsv,
which CfnResourceInventoryTest pins to the CDI-resolved provisioner registry plus the legacy
switch. Presentation (row order, service labels, notes) comes from cfn_resource_types.yaml.

Deliberately reads the TSV rather than parsing the Java: several provisioners return
`Set.of(CONSTANT, CONSTANT)` from resourceTypes(), so a source regex sees only a subset. The
TSV is the one representation that is both machine-checked against the running registry and
readable without a JVM.

    python3 tools/docs/regen_cfn_resource_types.py            # rewrite in place
    python3 tools/docs/regen_cfn_resource_types.py --check    # exit 1 if stale
    python3 tools/docs/regen_cfn_resource_types.py --strict    # also fail on warnings
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
INVENTORY = REPO_ROOT / "src/test/resources/cloudformation/supported-resource-types.tsv"
CONFIG = Path(__file__).resolve().parent / "cfn_resource_types.yaml"
DOC = REPO_ROOT / "docs/services/cloudformation.md"
PROVISIONER_DIR = REPO_ROOT / "src/main/java/io/github/hectorvent/floci/services/cloudformation/provisioners"

MARKER_START = "<!-- floci:cfn-types:start -->"
MARKER_END = "<!-- floci:cfn-types:end -->"


def load_inventory(path: Path = INVENTORY) -> list[tuple[str, str]]:
    """[(resource_type, owner)] in file order."""
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != 2:
            raise ValueError(f"{path.name}: expected 'type<TAB>owner', got: {line!r}")
        rows.append((parts[0], parts[1]))
    return rows


def namespace_of(resource_type: str) -> str:
    """AWS::SQS::Queue -> AWS::SQS; Custom::DynamoDBReplica -> Custom."""
    parts = resource_type.split("::")
    return "::".join(parts[:2]) if len(parts) == 3 else parts[0]


def leaf_of(resource_type: str) -> str:
    """AWS::SQS::Queue -> Queue; Custom::DynamoDBReplica -> Custom::DynamoDBReplica.

    A two-segment type has no namespace to strip, so it is shown whole; abbreviating
    Custom::DynamoDBReplica to DynamoDBReplica would read as an AWS type that does not exist.
    """
    parts = resource_type.split("::")
    return parts[2] if len(parts) == 3 else resource_type


def render_rows(inventory, config) -> tuple[list[str], list[str]]:
    """Returns (table_lines, warnings)."""
    warnings: list[str] = []
    labels = config.get("service_labels", {}) or {}
    notes = config.get("notes", {}) or {}
    row_notes = config.get("row_notes", {}) or {}
    extras = config.get("extra_types", {}) or {}
    order = config.get("order", []) or []

    merges = config.get("merge_namespaces", {}) or {}
    displays = config.get("display_names", {}) or {}
    type_order = config.get("type_order", {}) or {}

    by_namespace: dict[str, list[str]] = {}
    for resource_type, _owner in inventory:
        ns = namespace_of(resource_type)
        by_namespace.setdefault(merges.get(ns, ns), []).append(resource_type)

    # Curated order first (it reads by importance, not alphabet), then anything new
    # alphabetically so a type added without touching this config still lands somewhere sane.
    for ns, types in by_namespace.items():
        wanted = type_order.get(ns, [])
        rank = {name: i for i, name in enumerate(wanted)}
        types.sort(key=lambda t: (rank.get(leaf_of(t), len(rank)), leaf_of(t)))
        for leaf in wanted:
            if leaf not in {leaf_of(t) for t in types}:
                warnings.append(
                    f"type_order for {ns} lists {leaf}, which nothing provisions; "
                    f"remove it or fix the spelling"
                )

    namespaces = list(by_namespace) + [ns for ns in extras if ns not in by_namespace]
    ranked = [ns for ns in order if ns in namespaces]
    ranked += sorted(ns for ns in namespaces if ns not in order)

    for ns in namespaces:
        if ns not in labels:
            warnings.append(
                f"no service_labels entry for {ns}; add one to cfn_resource_types.yaml "
                f"so the row gets a readable service name"
            )
        if ns not in order:
            warnings.append(f"{ns} is not in the order list; appended alphabetically")

    lines = ["| Service | Resource types |", "|---|---|"]
    for ns in ranked:
        cells = []
        for resource_type in by_namespace.get(ns, []):
            note = notes.get(resource_type)
            shown = displays.get(resource_type, leaf_of(resource_type))
            cells.append(f"`{shown}`" + (f" ({note})" if note else ""))
        for extra in extras.get(ns, []):
            if not extra.get("reason"):
                warnings.append(
                    f"extra_types entry {ns} / {extra.get('name')} has no reason; "
                    f"an entry exempt from the inventory check must say why"
                )
            note = extra.get("note")
            cells.append(f"`{extra['name']}`" + (f" ({note})" if note else ""))
        if not cells:
            continue
        types = ", ".join(cells)
        trailing = (row_notes.get(ns) or "").strip()
        if trailing:
            types = f"{types}. {trailing}"
        lines.append(f"| {labels.get(ns, ns)} | {types} |")
    return lines, warnings


def check_provisioner_annotations() -> list[str]:
    """A CfnResourceProvisioner without @ApplicationScoped is never discovered by CDI.

    CfnResourceInventoryTest catches this at runtime; flagging it here too means the cheap
    check runs in docs-check, before anyone waits on a Quarkus test boot.
    """
    warnings = []
    if not PROVISIONER_DIR.is_dir():
        return warnings
    for path in sorted(PROVISIONER_DIR.glob("*CfnProvisioner.java")):
        src = path.read_text(encoding="utf-8")
        if "implements CfnResourceProvisioner" not in src:
            continue
        if "@ApplicationScoped" not in src:
            warnings.append(
                f"{path.name} implements CfnResourceProvisioner without @ApplicationScoped, "
                f"so CDI never registers it and its types are silently stubbed"
            )
    return warnings


def check_no_double_ownership(inventory) -> list[str]:
    """A type served by a provisioner must not also sit in the legacy switch."""
    owners: dict[str, set[str]] = {}
    for resource_type, owner in inventory:
        owners.setdefault(resource_type, set()).add(owner)
    return [
        f"{resource_type} is claimed by both the legacy switch and {sorted(o - {'LEGACY_SWITCH'})[0]}; "
        f"the registry wins, leaving the switch arm as dead code"
        for resource_type, o in sorted(owners.items())
        if len(o) > 1 and "LEGACY_SWITCH" in o
    ]


def splice(md: str, table: list[str]) -> str:
    if md.count(MARKER_START) != 1 or md.count(MARKER_END) != 1:
        raise ValueError(
            f"{DOC.name} must contain exactly one {MARKER_START} and one {MARKER_END}"
        )
    start, end = md.index(MARKER_START), md.index(MARKER_END)
    if end < start:
        raise ValueError("end marker appears before start marker")
    after_start = md.index("\n", start) + 1
    before_end = md.rindex("\n", 0, end) + 1
    return md[:after_start] + "\n".join(table) + "\n" + md[before_end:]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="exit 1 if the doc is stale")
    parser.add_argument("--strict", action="store_true", help="also exit 1 on warnings")
    args = parser.parse_args(argv)

    inventory = load_inventory()
    config = yaml.safe_load(CONFIG.read_text(encoding="utf-8")) or {}
    table, warnings = render_rows(inventory, config)
    warnings += check_no_double_ownership(inventory)
    warnings += check_provisioner_annotations()

    for warning in warnings:
        print(f"warning: {warning}", file=sys.stderr)

    current = DOC.read_text(encoding="utf-8")
    updated = splice(current, table)
    stale = updated != current

    if args.check or args.strict:
        if stale:
            print(
                f"error: {DOC.relative_to(REPO_ROOT)} resource-type table is stale. "
                f"Run 'make docs-sync' and commit the result.",
                file=sys.stderr,
            )
        return 1 if (stale or (args.strict and warnings)) else 0

    if stale:
        DOC.write_text(updated, encoding="utf-8")
        print(f"updated {DOC.relative_to(REPO_ROOT)} ({len(table) - 2} rows)")
    else:
        print(f"{DOC.relative_to(REPO_ROOT)} already up to date ({len(table) - 2} rows)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
