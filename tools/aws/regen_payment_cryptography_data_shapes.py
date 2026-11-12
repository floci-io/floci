#!/usr/bin/env python3
"""Vendor Payment Cryptography data plane request constraints from botocore's Apache-2.0 service model.

`PaymentCryptographyDataRequestShapes` checks the data plane's actions against the vendored file
through the shared `RequestConstraintCheck`: member types, required members, lengths and counts,
patterns and enum values, as the model states them. The patterns are what settle which key a
request may name: `KeyIdentifier` takes the ARN of a key, the ARN of an alias or the name of an
alias, and the identifier of a key on its own names none of them.

The data plane is a service of its own, so its constraints are vendored in a file of their own,
as the control plane's are.

Run from anywhere in the repo:
    python3 tools/aws/regen_payment_cryptography_data_shapes.py            # rewrite the vendored file in place
    python3 tools/aws/regen_payment_cryptography_data_shapes.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes

OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/payment-cryptography-data-request-shapes.json"
MODEL = Path("payment-cryptography-data/2022-02-03")


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__,
                               constraints=True,
                               prepare=request_shapes.widen_partition_and_region)


if __name__ == "__main__":
    sys.exit(main())
