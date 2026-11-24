#!/usr/bin/env python3
"""Vendor the EventBridge V2 event source operations' request constraints for RequestConstraintCheck.

The shapes the model marks `sensitive` keep that mark, so a violation does not quote their value.
"""
from __future__ import annotations

from pathlib import Path

import request_shapes

MODEL = Path("eventbridgev2/2025-05-15")
OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/eventbridgev2-request-shapes.json"

OPERATIONS = ("CreateEventSource", "DeleteEventSource", "DescribeEventSource", "UpdateEventSource")


def sort_enums(model: dict) -> dict:
    """The model, with every enum's values sorted.

    AWS lists the values of a violated enum sorted for this service. `LogLevel` is `[OFF, ERROR, INFO]`
    in the model and `[ERROR, INFO, OFF]` in the message.
    """
    for shape in model["shapes"].values():
        if "enum" in shape:
            shape["enum"] = sorted(shape["enum"])
    return model


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, operations=OPERATIONS, sensitive=True,
                               prepare=sort_enums)


if __name__ == "__main__":
    raise SystemExit(main())
