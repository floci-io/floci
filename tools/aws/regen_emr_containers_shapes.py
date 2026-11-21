#!/usr/bin/env python3
"""Vendor the EMR on EKS (emr-containers) request constraints from botocore's Apache-2.0 service model.

EmrContainersRequestShapes checks every operation's request body, with the members its path binds,
through RequestConstraintCheck using the model's types, required members, bounds, patterns and enums.

Run from anywhere in the repo:
    python3 tools/aws/regen_emr_containers_shapes.py
    python3 tools/aws/regen_emr_containers_shapes.py --check
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes

OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/emr-containers-request-shapes.json"
MODEL = Path("emr-containers/2020-10-01")


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, constraints=True)


if __name__ == "__main__":
    sys.exit(main())
