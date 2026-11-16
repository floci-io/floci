#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/organizations-request-shapes.json from botocore's Organizations model.

`OrganizationsRequestShapes` checks an Organizations request against its operation's input shape
through the shared `RequestShapeCheck` before the operation reads it, so a member of the wrong JSON
type fails the request. That check needs the body shapes, so this script keeps only what it reads:
the input shape of every operation and, for every shape reachable from one, its type and the shapes
of its members, list elements or map values.

The model is botocore's `organizations/2016-11-28/service-2.json` (Apache-2.0,
https://github.com/boto/botocore). It is located, in order, at `--botocore-data`, the
`local/aws/botocore` checkout, then the installed `botocore` package, which `requirements.txt`
pins so CI regenerates the same bytes.

Run from anywhere in the repo:
    python3 tools/aws/regen_organizations_shapes.py            # rewrite the vendored file in place
    python3 tools/aws/regen_organizations_shapes.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes

OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/organizations-request-shapes.json"
MODEL = Path("organizations/2016-11-28")


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT, description=__doc__)


if __name__ == "__main__":
    sys.exit(main())
