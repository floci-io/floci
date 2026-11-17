#!/usr/bin/env python3
"""Vendor MicroVM request-body constraints from the pinned botocore service model.

URI, query and header members are transport inputs, outside the JSON body checked by
LambdaMicrovmRequestShapes. All modeled body constraints remain in the generated resource.
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes

OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/lambda-microvm-request-shapes.json"
MODEL = Path("lambda-microvms/2025-09-09")
OPERATIONS = ("CreateMicrovmImage", "UpdateMicrovmImage", "RunMicrovm")


def body_members(model: dict) -> dict:
    for operation in OPERATIONS:
        shape = model["shapes"][model["operations"][operation]["input"]["shape"]]
        shape["members"] = {name: member for name, member in shape["members"].items()
                            if "location" not in member}
        shape["required"] = [name for name in shape.get("required", []) if name in shape["members"]]
    return model


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT, description=__doc__,
                               operations=OPERATIONS, prepare=body_members)


if __name__ == "__main__":
    sys.exit(main())
