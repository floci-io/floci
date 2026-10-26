#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/emr-serverless-shapes.json from botocore's EMR Serverless model.

The Step Functions `emr-serverless:` integrations take and return the API's members in PascalCase,
where the EMR Serverless API writes them in camelCase. Their keys cannot all be recased: a tag map,
`initialCapacity` or a configuration's `properties` keep the keys their caller wrote. The Java side
(`EmrServerlessTaskInvoker`) walks a request or response against the shapes to tell the two apart,
so this script keeps the input shape of every operation and, unlike the request checks, its output
shape too; for every shape reachable from one, its type and the shapes of its members, list elements
or map values.

The model is botocore's `emr-serverless/2021-07-13/service-2.json` (Apache-2.0,
https://github.com/boto/botocore). It is located, in order, at `--botocore-data`, the
`local/aws/botocore` checkout, then the installed `botocore` package, which `requirements.txt`
pins so CI regenerates the same bytes.

Run from anywhere in the repo:
    python3 tools/aws/regen_emr_serverless_shapes.py            # rewrite the vendored file in place
    python3 tools/aws/regen_emr_serverless_shapes.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes

OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/emr-serverless-shapes.json"
MODEL = Path("emr-serverless/2021-07-13")


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT, description=__doc__,
                               outputs=True)


if __name__ == "__main__":
    sys.exit(main())
