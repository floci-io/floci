#!/usr/bin/env python3
"""Vendor the CloudFront KeyValueStore data plane request constraints from botocore's Apache-2.0 service model.

`CloudFrontKeyValueStoreRequestShapes` checks the data plane's actions against the vendored file
through the shared `RequestConstraintCheck`: member types, required members, lengths and counts,
patterns and enum values, as the model states them. The request it checks is the whole request as
the model reads it: the members the path, the `If-Match` header, the query and the body carry.
The quotas the model does not state (the size of a key in bytes, of a store, of an update) stay
in `CloudFrontKeyValueStoreService`.

The data plane is a service of its own, so its constraints are vendored in a file of their own.

Run from anywhere in the repo:
    python3 tools/aws/regen_cloudfront_keyvaluestore_shapes.py            # rewrite the vendored file in place
    python3 tools/aws/regen_cloudfront_keyvaluestore_shapes.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes

OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/cloudfront-keyvaluestore-request-shapes.json"
MODEL = Path("cloudfront-keyvaluestore/2022-07-26")


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__,
                               constraints=True,
                               prepare=request_shapes.widen_partition_and_region)


if __name__ == "__main__":
    sys.exit(main())
