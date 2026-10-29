#!/usr/bin/env python3
"""Vendor IAM request constraints from botocore's Apache-2.0 service model.

`IamRequestShapes` checks the operations listed here against the vendored file through the shared
`QueryConstraintCheck`: required members, lengths, patterns and enum values, as the model states
them. The list grows an operation at a time, each one taken off the hand-written checks in the
same change, so a wave is always reviewable against the messages it replaces.

Run from anywhere in the repo:
    python3 tools/aws/regen_iam_shapes.py            # rewrite the vendored file in place
    python3 tools/aws/regen_iam_shapes.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from regen_partitions import resolve_botocore_data, strip_source
from request_shapes import REPO_ROOT, build_constraints, load_model

OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-request-shapes.json"
MODEL = Path("iam/2010-05-08")
GENERATOR = "tools/aws/regen_iam_shapes.py"

# One wave per group, each chosen so that no operation in it checks a declared constraint by hand,
# which is what keeps the vendored shapes adding the refusals AWS answers rather than rewording an
# answer already given.
#
# The managed-policy family, every member a scalar. `AttachRolePolicy` and `DetachRolePolicy` are
# the exception and the parity case: `IamService.validateRoleName` already answers `roleName` in
# Smithy's form.
#
# `GetAccountAuthorizationDetails` is the first operation read through the list walk. Its three
# members are declared and none of them is read at all today: `Filter` is a list of an enum,
# `MaxItems` a range and `Marker` a length and a pattern.
#
# The list operations follow it: every operation `aws/iam-paginators.json` pages, so `MaxItems`
# (1 to 1000) and `Marker` (1 to 320 characters) are held to the model here and nowhere else.
OPERATIONS = (
    "AttachGroupPolicy", "AttachRolePolicy", "AttachUserPolicy",
    "DetachGroupPolicy", "DetachRolePolicy", "DetachUserPolicy",
    "GetPolicy", "DeletePolicy",
    "CreatePolicyVersion", "GetPolicyVersion", "DeletePolicyVersion", "SetDefaultPolicyVersion",
    "GetAccountAuthorizationDetails",
    "GetGroup",
    "ListAccessKeys", "ListAccountAliases", "ListAttachedGroupPolicies", "ListAttachedRolePolicies",
    "ListAttachedUserPolicies", "ListDelegationRequests", "ListEntitiesForPolicy",
    "ListGroupPolicies", "ListGroups", "ListGroupsForUser", "ListInstanceProfileTags",
    "ListInstanceProfiles", "ListInstanceProfilesForRole", "ListMFADeviceTags", "ListMFADevices",
    "ListOpenIDConnectProviderTags", "ListPolicies",
    "ListPolicyTags", "ListPolicyVersions", "ListRolePolicies", "ListRoleTags", "ListRoles",
    "ListSAMLProviderTags", "ListSSHPublicKeys", "ListServerCertificateTags",
    "ListServerCertificates", "ListServiceSpecificCredentials", "ListSigningCertificates",
    "ListUserPolicies", "ListUserTags", "ListUsers", "ListVirtualMFADevices",
)

# Paged operations vendored with their paging members alone, so `MaxItems` and `Marker` are still
# the model's while the rest of the request keeps the reading it has today.
PAGING_MEMBERS = ("Marker", "MaxItems")
PAGING_ONLY = (
    # `JobId` is answered by hand as one fixed-length constraint ("length equal to 36"), which the
    # walk would reword as a minimum; `SortKey` is held to its enum by hand too.
    "GetOrganizationsAccessReport", "GetServiceLastAccessedDetails",
    "GetServiceLastAccessedDetailsWithEntities",
    # An empty `ServiceNamespaces` is answered by hand with `InvalidInput`, not the walk's null.
    "ListPoliciesGrantingServiceAccess",
    # `ContextEntries` nests a list inside a list of structures, which the walk refuses.
    "SimulateCustomPolicy", "SimulatePrincipalPolicy",
)


def paging_only(model: dict) -> dict:
    """`model` with each `PAGING_ONLY` request reduced to its paging members."""
    shapes = dict(model["shapes"])
    for operation in PAGING_ONLY:
        name = model["operations"][operation]["input"]["shape"]
        request = shapes[name]
        shapes[name] = {"type": request["type"],
                        "members": {k: v for k, v in request["members"].items() if k in PAGING_MEMBERS}}
    return {**model, "shapes": shapes}


def build(model: dict, provenance: str) -> dict:
    return build_constraints(paging_only(model), OPERATIONS + PAGING_ONLY, provenance, GENERATOR)


def render(doc: dict) -> str:
    return json.dumps(doc, indent=2) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 when the vendored file differs from a fresh generation")
    parser.add_argument("--botocore-data", type=Path, help="botocore data directory")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    data, provenance = resolve_botocore_data(args.botocore_data)
    fresh = render(build(load_model(data, MODEL), provenance))
    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if strip_source(current) != strip_source(fresh):
            print(f"{args.output} is stale (generated from {provenance}).", file=sys.stderr)
            return 1
        return 0
    args.output.write_text(fresh, encoding="utf-8")
    shown = args.output.relative_to(REPO_ROOT) if args.output.is_relative_to(REPO_ROOT) else args.output
    print(f"wrote {shown} from {provenance}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
