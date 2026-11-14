#!/usr/bin/env python3
"""Vendor every AWS Config operation's request constraints for RequestConstraintCheck."""
from __future__ import annotations

from pathlib import Path

import request_shapes

MODEL = Path("config/2014-11-12")
OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/config-request-shapes.json"


def prepare(model: dict) -> dict:
    # The shared checker does not parse timestamps. Keep their required markers and leave present
    # values, such as an evaluation's OrderingTimestamp, to the handlers.
    for shape in model["shapes"].values():
        if shape["type"] != "structure":
            continue
        for member, spec in list(shape.get("members", {}).items()):
            if model["shapes"][spec["shape"]]["type"] == "timestamp":
                del shape["members"][member]
    return model


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, constraints=True, prepare=prepare)


if __name__ == "__main__":
    raise SystemExit(main())
