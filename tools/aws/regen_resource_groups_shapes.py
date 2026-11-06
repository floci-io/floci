#!/usr/bin/env python3
"""Vendor Resource Groups request constraints from botocore's Apache-2.0 service model."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from regen_partitions import resolve_botocore_data, strip_source
from request_shapes import REPO_ROOT, build_constraints, load_model

OUTPUT = REPO_ROOT / "src/main/resources/aws/resource-groups-request-shapes.json"
OPERATIONS = ("CreateGroup", "GetGroup", "DeleteGroup", "GroupResources", "ListGroupResources")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--botocore-data", type=Path)
    args = parser.parse_args()
    data, provenance = resolve_botocore_data(args.botocore_data)
    doc = build_constraints(load_model(data, Path("resource-groups/2017-11-27")), OPERATIONS, provenance,
                            "tools/aws/regen_resource_groups_shapes.py")
    fresh = json.dumps(doc, indent=2) + "\n"
    if args.check:
        return int(not OUTPUT.exists() or strip_source(OUTPUT.read_text()) != strip_source(fresh))
    OUTPUT.write_text(fresh)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
