#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/dynamodb-request-shapes.json from botocore's DynamoDB model.

DynamoDB rejects a request member of the wrong JSON type with a SerializationException before
it runs the operation. The Java side (`DynamoDbRequestShapes`) needs each operation's input
shapes to do the same, so this script keeps only what that check reads: the input shape of
every operation, and for every shape reachable from one, its type and the shapes of its
members, list elements or map values.

The model is botocore's `dynamodb/2012-08-10/service-2.json` (Apache-2.0,
https://github.com/boto/botocore). It is located, in order, at `--botocore-data`, the
`local/aws/botocore` checkout, then the installed `botocore` package, which
`requirements.txt` pins so CI regenerates the same bytes.

Run from anywhere in the repo:
    python3 tools/aws/regen_dynamodb_shapes.py            # rewrite the vendored file in place
    python3 tools/aws/regen_dynamodb_shapes.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes

OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/dynamodb-request-shapes.json"
MODEL = Path("dynamodb/2012-08-10")


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT, description=__doc__)


if __name__ == "__main__":
    sys.exit(main())
