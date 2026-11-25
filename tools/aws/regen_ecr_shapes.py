#!/usr/bin/env python3
"""Vendor ECR request constraints from botocore's Apache-2.0 service model.

`EcrRequestShapes` checks the registry setting, repository creation template and image listing actions against
the vendored file through the shared `RequestConstraintCheck`: member types, required members, lengths and counts, patterns and
enum values, as the model states them.

Run from anywhere in the repo:
    python3 tools/aws/regen_ecr_shapes.py            # rewrite the vendored file in place
    python3 tools/aws/regen_ecr_shapes.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import sys
from pathlib import Path

import request_shapes
from request_shapes import REPO_ROOT

OUTPUT = REPO_ROOT / "src/main/resources/aws/ecr-request-shapes.json"
MODEL = Path("ecr/2015-09-21")
OPERATIONS = (
    "GetAccountSetting", "PutAccountSetting",
    "RegisterPullTimeUpdateExclusion", "DeregisterPullTimeUpdateExclusion", "ListPullTimeUpdateExclusions",
    "PutRegistryPolicy", "GetRegistryPolicy", "DeleteRegistryPolicy",
    "PutReplicationConfiguration", "DescribeRegistry",
    "PutRegistryScanningConfiguration", "GetRegistryScanningConfiguration",
    "BatchGetRepositoryScanningConfiguration",
    "CreateRepositoryCreationTemplate", "UpdateRepositoryCreationTemplate",
    "DescribeRepositoryCreationTemplates", "DeleteRepositoryCreationTemplate",
    "ListImages", "DescribeImages",
)


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, operations=OPERATIONS)


if __name__ == "__main__":
    sys.exit(main())
