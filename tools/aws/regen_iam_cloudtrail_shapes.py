#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/iam-cloudtrail-shapes.json from botocore's IAM model.

CloudTrail records every IAM API call, and `IamCloudTrailEvents` writes each record's
`requestParameters` and `responseElements` from the call's request and result. What the record
says of a member is what the model says of it, so it is read from here rather than guessed. The
walk is `request_shapes.build`'s, as for every other vendored shape file:

- `operations`: each operation's input shape, and `results` each one's output shape and the element
  its result is wrapped in on the wire (`resultWrapper`, `CreateUserResult`).
- `shapes`: every shape reachable from them, with its type and the shapes its members, list
  elements and map values have. A number, a boolean or a timestamp is typed as one in the record
  because its shape says so. A shape the model marks `sensitive` (a password, a secret access key,
  a private key, a virtual MFA device's seed) carries `"sensitive": true`, and the record leaves it
  out.
- `errors`: every error IAM models, from the code it answers with to its shape's name.
  CloudTrail records an IAM error under the shape's name (`NoSuchEntity` as
  `NoSuchEntityException`).

The model is botocore's `iam/2010-05-08/` (Apache-2.0, https://github.com/boto/botocore), located,
in order, at `--botocore-data`, the `local/aws/botocore` checkout, then the installed `botocore`
package, which `requirements.txt` pins so CI regenerates the same bytes.

Run from anywhere in the repo:
    python3 tools/aws/regen_iam_cloudtrail_shapes.py            # rewrite the vendored file in place
    python3 tools/aws/regen_iam_cloudtrail_shapes.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes
from request_shapes import REPO_ROOT

OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-cloudtrail-shapes.json"
MODEL = Path("iam/2010-05-08")

# The shape types `IamCloudTrailShapes` reads, and the member traits it reads beside the shape a
# member names. Any other is one it would pass over or misread, so the generation stops rather than
# vendoring it.
TYPES = {"structure", "list", "map", "string", "integer", "long", "boolean", "timestamp", "blob",
         "double", "float"}
MEMBER_TRAITS = {"shape", "documentation"}


def readable(name: str, shape: dict) -> None:
    """Raises for a shape the record cannot read: a type it does not know, or a member trait, such as
    a `locationName`, that would rename the member on the wire."""
    if shape["type"] not in TYPES:
        raise ValueError(f"{name} is a {shape['type']}, which the record does not read")
    for member, spec in shape.get("members", {}).items():
        extra = set(spec) - MEMBER_TRAITS
        if extra:
            raise ValueError(f"{name}.{member} carries {sorted(extra)}, which the record does not read")


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT, description=__doc__,
                               results=True, sensitive=True, errors=True, check=readable)


if __name__ == "__main__":
    sys.exit(main())
