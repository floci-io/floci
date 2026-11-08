#!/usr/bin/env python3
"""Regenerate src/test/resources/cloudformation/schema-surface.tsv from CloudFormation's resource schemas.

`CfnSchemaCoverageTest` compares what each supported resource type's registry schema declares with
what its provisioner sets and reads. It used to read the schemas from a gitignored `local/` checkout,
so the comparison ran only on a machine that had one. This file is the part of the schemas the
comparison uses, committed, so it runs on every pull request. One row per resource type in
`supported-resource-types.tsv`:

    <type><TAB><read-only attributes, comma separated><TAB><writable properties, comma separated>

Read-only attributes are the top-level `readOnlyProperties` pointers, which are the `Fn::GetAtt`
names. Writable properties are the top-level `properties` keys that are neither read-only nor
deprecated; write-only ones are writable. Nested members are out. Names are sorted. A type with no
schema in the archive has no row, and the test accepts that only for the types it declares schema-less.

The schemas are the ones AWS publishes for us-east-1, the same `describe-type` returns:

    https://schema.cloudformation.us-east-1.amazonaws.com/CloudformationSchema.zip

The archive is live and keeps no older copy, so, as with `regen_cfn_property_types.py`, `--verify` is
online and exits 2 when the archive cannot be read.

Run from anywhere in the repo:
    python3 tools/aws/regen_cfn_schema_surface.py            # fetch and rewrite the committed file
    python3 tools/aws/regen_cfn_schema_surface.py --verify   # online: the committed file matches AWS
    python3 tools/aws/regen_cfn_schema_surface.py --source-dir <dir of aws-<service>-<type>.json>
"""
from __future__ import annotations

import argparse
import http.client
import io
import json
import sys
import urllib.request
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/test/resources/cloudformation/schema-surface.tsv"
INVENTORY = REPO_ROOT / "src/test/resources/cloudformation/supported-resource-types.tsv"
ARCHIVE_URL = "https://schema.cloudformation.us-east-1.amazonaws.com/CloudformationSchema.zip"
FETCH_TIMEOUT_SECONDS = 60
# Inventory types that have no schema in the archive; the same set as TYPES_WITHOUT_SCHEMA in
# CfnSchemaCoverageTest. Any other type without a schema fails the run by name.
SCHEMALESS = frozenset({"AWS::CDK::Metadata", "Custom::DynamoDBReplica"})


def file_name(resource_type: str) -> str:
    """The archive's file for a resource type: AWS::Events::Rule is aws-events-rule.json."""
    return resource_type.lower().replace("::", "-") + ".json"


def inventory_types(inventory: Path) -> list[str]:
    return sorted(line.split("\t", 1)[0] for line in inventory.read_text(encoding="utf-8").splitlines()
                  if line.strip())


def top_level_names(pointers: list[str]) -> set[str]:
    """The name of each `/properties/<name>` pointer; a nested pointer is not a top-level property."""
    names = set()
    for pointer in pointers:
        if not isinstance(pointer, str):
            raise ValueError(f"expected a pointer string, found {pointer!r}")
        parts = pointer.split("/")
        # "/properties/Name" only: a trailing slash or a nested pointer is not a top-level name.
        if len(parts) == 3 and parts[0] == "" and parts[1] == "properties" and parts[2] != "":
            names.add(parts[2])
    return names


def surface(schema: dict) -> tuple[list[str], list[str]]:
    """The read-only attributes and the writable properties of one schema, each sorted."""
    properties = schema.get("properties", {})
    if not isinstance(properties, dict):
        raise ValueError("'properties' is not an object")
    for key in ("readOnlyProperties", "deprecatedProperties"):
        if not isinstance(schema.get(key, []), list):
            raise ValueError(f"'{key}' is not a list")
    read_only = top_level_names(schema.get("readOnlyProperties", []))
    excluded = read_only | top_level_names(schema.get("deprecatedProperties", []))
    writable = {name for name in properties if name not in excluded}
    return sorted(read_only), sorted(writable)


def fetch_schemas(resource_types: list[str], source_dir: Path | None) -> tuple[dict[str, dict], str]:
    """The schema of every resource type that has one, and where they came from."""
    schemas: dict[str, dict] = {}
    if source_dir is not None:
        for resource_type in resource_types:
            path = source_dir / file_name(resource_type)
            if path.exists():
                schemas[resource_type] = json.loads(path.read_text(encoding="utf-8"))
        return schemas, f"--source-dir {source_dir}"
    with urllib.request.urlopen(ARCHIVE_URL, timeout=FETCH_TIMEOUT_SECONDS) as response:
        archive = zipfile.ZipFile(io.BytesIO(response.read()))
    names = set(archive.namelist())
    for resource_type in resource_types:
        if file_name(resource_type) in names:
            schemas[resource_type] = json.loads(archive.read(file_name(resource_type)).decode("utf-8"))
    return schemas, ARCHIVE_URL


def render(schemas: dict[str, dict]) -> str:
    rows = []
    for resource_type in sorted(schemas):
        read_only, writable = surface(schemas[resource_type])
        rows.append(f"{resource_type}\t{','.join(read_only)}\t{','.join(writable)}\n")
    return "".join(rows)


def _shown(path: Path) -> Path:
    try:
        return path.resolve().relative_to(REPO_ROOT)
    except ValueError:
        return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verify", action="store_true", help="online: the committed file matches AWS's schemas")
    parser.add_argument("--source-dir", type=Path, default=None,
                        help="read saved aws-<service>-<type>.json schemas instead of fetching the archive")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    parser.add_argument("--inventory", type=Path, default=INVENTORY, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    resource_types = inventory_types(args.inventory)
    try:
        schemas, provenance = fetch_schemas(resource_types, args.source_dir)
    except (OSError, ValueError, zipfile.BadZipFile, http.client.HTTPException) as e:
        # The gate could not run, which CI has to tell apart from having found a difference.
        print(f"error: could not read the CloudFormation resource schemas ({e})", file=sys.stderr)
        return 2
    schemaless = [t for t in resource_types if t not in schemas]
    undeclared = [t for t in schemaless if t not in SCHEMALESS]
    if undeclared:
        shown = ", ".join(undeclared[:5]) + (f" and {len(undeclared) - 5} more" if len(undeclared) > 5 else "")
        print(f"error: no schema for {shown} in {provenance}; nothing was written or verified. "
              f"Fix the directory, or declare a type with no schema in SCHEMALESS.", file=sys.stderr)
        return 1
    try:
        document = render(schemas)
    except (AttributeError, TypeError, ValueError) as e:
        print(f"error: could not read the CloudFormation resource schemas ({e})", file=sys.stderr)
        return 2

    if args.verify:
        if not args.output.exists() or args.output.read_text(encoding="utf-8") != document:
            print(f"error: {_shown(args.output)} no longer matches {provenance}; run "
                  f"'make cfn-schema-surface-sync' and commit the result.", file=sys.stderr)
            return 1
        print(f"{_shown(args.output)} matches {provenance}.")
        return 0

    args.output.write_text(document, encoding="utf-8")
    print(f"wrote {_shown(args.output)} ({len(schemas)} types, from {provenance}); "
          f"no schema: {', '.join(schemaless) if schemaless else 'none'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
