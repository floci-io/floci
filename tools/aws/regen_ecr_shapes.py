#!/usr/bin/env python3
"""Vendor ECR request constraints from botocore's Apache-2.0 service model.

`EcrRequestShapes` checks the registry setting and repository creation template actions against
the vendored file through the shared `RequestConstraintCheck`: member types, required members, lengths and counts, patterns and
enum values, as the model states them.

Run from anywhere in the repo:
    python3 tools/aws/regen_ecr_shapes.py            # rewrite the vendored file in place
    python3 tools/aws/regen_ecr_shapes.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from regen_partitions import resolve_botocore_data, strip_source
from request_shapes import REPO_ROOT, build_constraints, load_model

OUTPUT = REPO_ROOT / "src/main/resources/aws/ecr-request-shapes.json"
MODEL = Path("ecr/2015-09-21")
OPERATIONS = (
    "PutRegistryPolicy", "GetRegistryPolicy", "DeleteRegistryPolicy",
    "PutReplicationConfiguration", "DescribeRegistry",
    "PutRegistryScanningConfiguration", "GetRegistryScanningConfiguration",
    "BatchGetRepositoryScanningConfiguration",
    "CreateRepositoryCreationTemplate", "UpdateRepositoryCreationTemplate",
    "DescribeRepositoryCreationTemplates", "DeleteRepositoryCreationTemplate",
)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 when the vendored file differs from a fresh generation")
    parser.add_argument("--botocore-data", type=Path, help="botocore data directory")
    args = parser.parse_args(argv)
    data, provenance = resolve_botocore_data(args.botocore_data)
    doc = build_constraints(load_model(data, MODEL), OPERATIONS, provenance, "tools/aws/regen_ecr_shapes.py")
    fresh = json.dumps(doc, indent=2) + "\n"
    if args.check:
        if not OUTPUT.exists() or strip_source(OUTPUT.read_text(encoding="utf-8")) != strip_source(fresh):
            print(f"{OUTPUT} is stale (generated from {provenance}).", file=sys.stderr)
            return 1
        return 0
    OUTPUT.write_text(fresh, encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)} from {provenance}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
