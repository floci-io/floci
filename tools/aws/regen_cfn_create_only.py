#!/usr/bin/env python3
"""Regenerate src/main/resources/cloudformation/create-only-properties.json from CloudFormation's resource schemas.

`CfnCreateOnlyProperties` answers, for a change set, whether editing a property replaces the resource.
For a resource type with an entry the answer is the type's `createOnlyProperties` in the CloudFormation
registry, so the file states, for every resource type in `supported-resource-types.tsv`, that list with
the `/properties/` prefix stripped:

    { "<resource type>": ["<property or nested path such as HealthCheckConfig/Type>", ...] }

Types are sorted, each list keeps the order the schema gives it, and an empty list means the schema has
no create-only property (the class treats an empty entry as authoritative, not as a missing one). A type
with no schema in the archive has no entry and is declared in SCHEMALESS, which is the set
`regen_cfn_schema_surface.py` holds; any other inventory type without a schema fails the run by name.

The schemas are the ones AWS publishes for us-east-1, the same `describe-type` returns:

    https://schema.cloudformation.us-east-1.amazonaws.com/CloudformationSchema.zip

The archive is live and keeps no older copy, so, as with `regen_cfn_property_types.py`, `--verify` is
online and exits 2 when the archive cannot be read.

Run from anywhere in the repo:
    python3 tools/aws/regen_cfn_create_only.py            # fetch and rewrite the vendored file
    python3 tools/aws/regen_cfn_create_only.py --verify   # online: the vendored file matches AWS
    python3 tools/aws/regen_cfn_create_only.py --source-dir <dir of aws-<service>-<type>.json>
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

from regen_cfn_schema_surface import SCHEMALESS, file_name, inventory_types

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/cloudformation/create-only-properties.json"
INVENTORY = REPO_ROOT / "src/test/resources/cloudformation/supported-resource-types.tsv"
ARCHIVE_URL = "https://schema.cloudformation.us-east-1.amazonaws.com/CloudformationSchema.zip"
FETCH_TIMEOUT_SECONDS = 60
POINTER_PREFIX = "/properties/"


def create_only(schema: dict) -> list[str]:
    """The schema's `createOnlyProperties` without the `/properties/` prefix, in the schema's order."""
    pointers = schema.get("createOnlyProperties", [])
    if not isinstance(pointers, list):
        raise ValueError("'createOnlyProperties' is not a list")
    names = []
    for pointer in pointers:
        if not isinstance(pointer, str) or not pointer.startswith(POINTER_PREFIX) \
                or pointer == POINTER_PREFIX or pointer.endswith("/"):
            raise ValueError(f"expected a '{POINTER_PREFIX}<name>' pointer, found {pointer!r}")
        names.append(pointer[len(POINTER_PREFIX):])
    return names


def check_schema(resource_type: str, schema: object) -> None:
    """Refuse a schema that is not the one for `resource_type`, so a stub or renamed file cannot pass as empty."""
    if not isinstance(schema, dict):
        raise ValueError(f"the schema for {resource_type} is not an object")
    if schema.get("typeName") != resource_type:
        raise ValueError(f"the schema for {resource_type} has typeName {schema.get('typeName')!r}")
    if not isinstance(schema.get("properties"), dict):
        raise ValueError(f"the schema for {resource_type} has no 'properties' object")


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
    document = {resource_type: create_only(schemas[resource_type]) for resource_type in sorted(schemas)}
    return json.dumps(document, indent=2) + "\n"


def _shown(path: Path) -> Path:
    try:
        return path.resolve().relative_to(REPO_ROOT)
    except ValueError:
        return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verify", action="store_true", help="online: the vendored file matches AWS's schemas")
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
        for resource_type in sorted(schemas):
            check_schema(resource_type, schemas[resource_type])
        document = render(schemas)
    except (AttributeError, TypeError, ValueError) as e:
        print(f"error: could not read the CloudFormation resource schemas ({e})", file=sys.stderr)
        return 2

    if args.verify:
        if not args.output.exists() or args.output.read_text(encoding="utf-8") != document:
            print(f"error: {_shown(args.output)} no longer matches {provenance}; run "
                  f"'make cfn-create-only-sync' and commit the result.", file=sys.stderr)
            return 1
        print(f"{_shown(args.output)} matches {provenance}.")
        return 0

    args.output.write_text(document, encoding="utf-8")
    print(f"wrote {_shown(args.output)} ({len(schemas)} types, from {provenance}); "
          f"no schema: {', '.join(schemaless) if schemaless else 'none'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
