#!/usr/bin/env python3
"""Vendor Account Management request constraints from botocore's Apache-2.0 service model.

AccountRequestShapes checks the operations listed here through RequestConstraintCheck using the
model's types, required members, bounds and patterns.

Run from anywhere in the repo:
    python3 tools/aws/regen_account_shapes.py
    python3 tools/aws/regen_account_shapes.py --check
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes

OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/account-request-shapes.json"
MODEL = Path("account/2021-02-01")

# The alternate contact operations keep the hand-written checks of AccountService.
OPERATIONS = (
    "GetContactInformation",
    "PutContactInformation",
)


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, operations=OPERATIONS)


if __name__ == "__main__":
    sys.exit(main())
