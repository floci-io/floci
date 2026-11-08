#!/usr/bin/env python3
"""Vendor the Security Hub request constraints used by findings import and retrieval."""
from __future__ import annotations

from pathlib import Path

import request_shapes

MODEL = Path("securityhub/2018-10-26")
OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/securityhub-request-shapes.json"
OPERATIONS = ("BatchImportFindings", "GetFindings")

# Keep the model fields this service validates. Other ASFF fields have separate checks or are
# preserved without interpretation; walking the entire model would reject unrelated content.
MEMBERS = {
    "AwsSecurityFinding": ("SchemaVersion", "Id", "ProductArn", "ProductName", "CompanyName",
                           "GeneratorId", "AwsAccountId", "Types", "CreatedAt", "UpdatedAt",
                           "Title", "Description", "Resources", "VerificationState", "RecordState",
                           "Compliance", "Severity", "FindingProviderFields", "ProductFields",
                           "RelatedFindings"),
    "Resource": ("Type", "Id"),
    "Compliance": ("Status",),
    "Severity": ("Label",),
    "FindingProviderFields": ("Confidence", "Criticality", "Severity", "RelatedFindings", "Types"),
    "FindingProviderSeverity": ("Label",),
    "GetFindingsRequest": ("Filters", "MaxResults"),
    "AwsSecurityFindingFilters": ("ComplianceAssociatedStandardsId", "RecordState",
                                  "WorkflowStatus", "ComplianceStatus", "ComplianceSecurityControlId",
                                  "ProductArn", "AwsAccountId", "Region"),
    "StringFilter": ("Value", "Comparison"),
}


def prepare(model: dict) -> dict:
    for name, included in MEMBERS.items():
        shape = model["shapes"][name]
        members = shape["members"]
        shape["members"] = {field: members[field] for field in included}
    return model


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, operations=OPERATIONS, prepare=prepare)


if __name__ == "__main__":
    raise SystemExit(main())
