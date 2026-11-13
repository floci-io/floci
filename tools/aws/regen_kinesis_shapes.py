#!/usr/bin/env python3
"""Vendor every Kinesis operation's request constraints for RequestConstraintCheck."""
from __future__ import annotations

from pathlib import Path

import request_shapes

MODEL = Path("kinesis/2013-12-02")
OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/kinesis-request-shapes.json"


def prepare(model: dict) -> dict:
    # The shared checker does not parse blob or timestamp wire types. Keep their required
    # markers while leaving present values to the existing handlers (including PutRecords'
    # per-record error path for malformed Data).
    for shape in model["shapes"].values():
        if shape["type"] != "structure":
            continue
        for member, spec in list(shape.get("members", {}).items()):
            if model["shapes"][spec["shape"]]["type"] in {"blob", "timestamp"}:
                del shape["members"][member]
    return model


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, constraints=True, prepare=prepare)


if __name__ == "__main__":
    raise SystemExit(main())
