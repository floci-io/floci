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

import argparse
import json
import sys
from pathlib import Path

from regen_partitions import resolve_botocore_data, strip_source
from request_shapes import REPO_ROOT, build_constraints, load_model

OUTPUT = REPO_ROOT / "src/main/resources/aws/payment-cryptography-request-shapes.json"
MODEL = Path("payment-cryptography/2021-09-14")
OPERATIONS = (
    "CreateKey", "GetKey", "ListKeys", "DeleteKey",
    "CreateAlias", "GetAlias", "ListAliases", "UpdateAlias", "DeleteAlias",
    "TagResource", "UntagResource", "ListTagsForResource",
)

# The partition the model writes in its ARN patterns, and the one the service writes in
# every partition it serves.
COMMERCIAL = "arn:aws:"
EVERY_PARTITION = "arn:aws[a-z0-9-]*:"


def widen_partition(doc: dict) -> dict:
    """The model's patterns, with the partition they name widened to every partition.

    The model writes the commercial partition, as the service's own model need not name the
    others, but the service mints and takes the ARN of the partition a key is in, so the check
    must not refuse the ARN of a key outside the commercial one.
    """
    for shape in doc["shapes"].values():
        pattern = shape.get("pattern")
        if pattern and COMMERCIAL in pattern:
            shape["pattern"] = pattern.replace(COMMERCIAL, EVERY_PARTITION)
    return doc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 when the vendored file differs from a fresh generation")
    parser.add_argument("--botocore-data", type=Path, help="botocore data directory")
    args = parser.parse_args(argv)
    data, provenance = resolve_botocore_data(args.botocore_data)
    doc = widen_partition(build_constraints(load_model(data, MODEL), OPERATIONS, provenance,
                                           "tools/aws/regen_payment_cryptography_shapes.py"))
    fresh = json.dumps(doc, indent=2) + "\n"
    if args.check:
        if not OUTPUT.exists() or strip_source(OUTPUT.read_text(encoding="utf-8")) != strip_source(fresh):
            print(f"{OUTPUT} is stale (generated from {provenance}).", file=sys.stderr)
            return 1
        return 0
    OUTPUT.write_text(fresh, encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)} from {provenance}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
