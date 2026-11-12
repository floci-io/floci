#!/usr/bin/env python3
"""Vendor every Athena operation's request constraints from botocore's Apache-2.0 service model.

AthenaRequestShapes checks each request through RequestConstraintCheck using the model's
types, required members, bounds, patterns and enums.

Run from anywhere in the repo:
    python3 tools/aws/regen_athena_shapes.py
    python3 tools/aws/regen_athena_shapes.py --check
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes

OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/athena-request-shapes.json"
MODEL = Path("athena/2017-05-18")


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, constraints=True)


if __name__ == "__main__":
    sys.exit(main())
