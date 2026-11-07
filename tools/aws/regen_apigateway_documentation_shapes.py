#!/usr/bin/env python3
"""Vendor API Gateway documentation-part request constraints from botocore's service model.

Unlike the body-only API Gateway shape file, this includes URI and query members so the
documentation-part validator can check required members and query enums as modeled.
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes

OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/apigateway-documentation-request-shapes.json"
MODEL = Path("apigateway/2015-07-09")
OPERATIONS = ("CreateDocumentationPart", "GetDocumentationParts")


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, operations=OPERATIONS)


if __name__ == "__main__":
    sys.exit(main())
