#!/usr/bin/env python3
"""Vendor Resource Groups request constraints from botocore's Apache-2.0 service model."""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes
from request_shapes import REPO_ROOT

OUTPUT = REPO_ROOT / "src/main/resources/aws/resource-groups-request-shapes.json"
MODEL = Path("resource-groups/2017-11-27")
OPERATIONS = ("CreateGroup", "GetGroup", "DeleteGroup", "GroupResources", "ListGroupResources")


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, operations=OPERATIONS)


if __name__ == "__main__":
    sys.exit(main())
