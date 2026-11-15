#!/usr/bin/env python3
"""Vendor every Rekognition operation's request constraints for RequestConstraintCheck."""
from __future__ import annotations

from pathlib import Path

import request_shapes

MODEL = Path("rekognition/2016-06-27")
OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/rekognition-request-shapes.json"


def prepare(model: dict) -> dict:
    # Image bytes keep RekognitionImageValidator's decoding, decoded-size checks and aggregated
    # ValidationException. The generic request checker validates the remaining image members.
    model["shapes"]["Image"]["members"].pop("Bytes")
    return model


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, constraints=True, prepare=prepare)


if __name__ == "__main__":
    raise SystemExit(main())
