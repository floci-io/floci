#!/usr/bin/env python3
"""Vendor STS request constraints from botocore's Apache-2.0 service model.

`StsRequestShapes` checks the operations listed here against the vendored file through the shared
`QueryConstraintCheck`, the same walk IAM reads its own file with: required members, lengths,
patterns, enum values and ranges, as the model states them. The list grows an operation at a time,
each one taken off the hand-written checks in the same change, so a wave is always reviewable
against the messages it replaces.

Run from anywhere in the repo:
    python3 tools/aws/regen_sts_shapes.py            # rewrite the vendored file in place
    python3 tools/aws/regen_sts_shapes.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes
from request_shapes import REPO_ROOT

OUTPUT = REPO_ROOT / "src/main/resources/aws/sts-request-shapes.json"
MODEL = Path("sts/2011-06-15")

# One wave per group, each chosen so that no operation in it answers a declared constraint in a
# wording of its own, which is what keeps the vendored shapes adding the refusals AWS answers
# rather than rewording an answer already given.
#
# `GetAccessKeyInfo` is the parity case: its hand-written check already answered `accessKeyId` in
# Smithy's form, from the same bounds and the same pattern. `GetSessionToken` and
# `GetWebIdentityToken` are the first read through the list walk. `SerialNumber`, `TokenCode` and
# `MinimumSessionTokenSize` are read by nothing at all today, and the audience and tag checks the
# token operation carried by hand named their members the way this file's walk does.
#
# The seven that are left all answer an absent member through `validateRequired`, which names the
# parameter as the request writes it rather than as a constraint message spells it, so taking one
# over rewords an answer already merged.
OPERATIONS = (
    "GetAccessKeyInfo",
    "GetSessionToken",
    "GetWebIdentityToken",
)


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, operations=OPERATIONS)


if __name__ == "__main__":
    sys.exit(main())
