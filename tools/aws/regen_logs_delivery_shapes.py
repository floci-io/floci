#!/usr/bin/env python3
"""Vendor CloudWatch Logs delivery and saved-query constraints from botocore's Apache-2.0 service model."""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes
from request_shapes import REPO_ROOT

OUTPUT = REPO_ROOT / "src/main/resources/aws/logs-delivery-request-shapes.json"
MODEL = Path("logs/2014-03-28")
OPERATIONS = ("PutDeliverySource", "GetDeliverySource", "DeleteDeliverySource", "DescribeDeliverySources",
              "PutDeliveryDestination", "GetDeliveryDestination", "DeleteDeliveryDestination",
              "DescribeDeliveryDestinations", "CreateDelivery", "GetDelivery", "UpdateDeliveryConfiguration",
              "DeleteDelivery", "DescribeDeliveries", "PutQueryDefinition", "DescribeQueryDefinitions",
              "DeleteQueryDefinition")


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, operations=OPERATIONS)


if __name__ == "__main__":
    sys.exit(main())
