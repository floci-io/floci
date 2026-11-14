#!/usr/bin/env python3
"""Vendor every AWS Config operation's request constraints for RequestConstraintCheck."""
from __future__ import annotations

from pathlib import Path

import request_shapes

MODEL = Path("config/2014-11-12")
OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/config-request-shapes.json"


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, constraints=True,
                               prepare=request_shapes.drop_blob_and_timestamp_members)


if __name__ == "__main__":
    raise SystemExit(main())
