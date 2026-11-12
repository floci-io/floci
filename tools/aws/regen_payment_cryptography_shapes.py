#!/usr/bin/env python3
"""Vendor Payment Cryptography request constraints from botocore's Apache-2.0 service model.

`PaymentCryptographyRequestShapes` checks the control plane's actions against the vendored file
through the shared `RequestConstraintCheck`: member types, required members, lengths and counts,
patterns and enum values, as the model states them. The patterns are what settle which key a
request may name: `KeyIdentifier` takes a key ARN, an alias ARN or an alias name, while
`KeyArn` and `ResourceArn` take the ARN of a key alone.

Run from anywhere in the repo:
    python3 tools/aws/regen_payment_cryptography_shapes.py            # rewrite the vendored file in place
    python3 tools/aws/regen_payment_cryptography_shapes.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes

OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/payment-cryptography-request-shapes.json"
MODEL = Path("payment-cryptography/2021-09-14")


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__,
                               constraints=True,
                               prepare=request_shapes.widen_partition_and_region)


if __name__ == "__main__":
    sys.exit(main())
