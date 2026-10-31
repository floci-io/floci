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

# Every operation STS models with a constraint of its own, which is all eleven but
# `GetCallerIdentity`, whose request carries no member at all.
#
# `GetAccessKeyInfo` was the parity case: its hand-written check already answered `accessKeyId` in
# Smithy's form, from the same bounds and the same pattern. `GetSessionToken` and
# `GetWebIdentityToken` came next, for members read by nothing at all. The seven below answered an
# absent member through `validateRequired`, which named the parameter as the request writes it and
# read a blank value as an absent one, so taking them over rewords those answers onto the form the
# model and the rest of this package give, which is what `docs/services/sts.md` records.
OPERATIONS = (
    "GetAccessKeyInfo",
    "GetSessionToken",
    "GetWebIdentityToken",
    "AssumeRole",
    "AssumeRoleWithSAML",
    "AssumeRoleWithWebIdentity",
    "AssumeRoot",
    "DecodeAuthorizationMessage",
    "GetDelegatedAccessToken",
    "GetFederationToken",
)


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, operations=OPERATIONS)


if __name__ == "__main__":
    sys.exit(main())
