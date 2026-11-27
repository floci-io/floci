#!/usr/bin/env python3
"""Vendor the Kinesis Video Streams request constraints from botocore's Apache-2.0 service model.

KinesisVideoRequestShapes checks the stream lifecycle operations listed here through
RequestConstraintCheck using the model's types, required members, bounds, patterns and enums.

Run from anywhere in the repo:
    python3 tools/aws/regen_kinesisvideo_shapes.py
    python3 tools/aws/regen_kinesisvideo_shapes.py --check
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes

OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/kinesisvideo-request-shapes.json"
MODEL = Path("kinesisvideo/2017-09-30")

OPERATIONS = (
    "CreateStream",
    "DeleteStream",
    "DescribeStream",
    "ListStreams",
)


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, operations=OPERATIONS)


if __name__ == "__main__":
    sys.exit(main())
