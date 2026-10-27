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

import sys
from pathlib import Path

import request_shapes
from request_shapes import REPO_ROOT

OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-request-shapes.json"
MODEL = Path("iam/2010-05-08")

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


def paging_only(model: dict, operations: tuple[str, ...] = PAGING_ONLY) -> dict:
    """`model` with each of `operations` reduced to its paging members, `PAGING_ONLY` by default."""
    shapes = dict(model["shapes"])
    for operation in operations:
        name = model["operations"][operation]["input"]["shape"]
        request = shapes[name]
        shapes[name] = {"type": request["type"],
                        "members": {k: v for k, v in request["members"].items() if k in PAGING_MEMBERS}}
    return {**model, "shapes": shapes}


# What the vendored file carries: the operations read whole, then the paged ones read in part.
VENDORED = OPERATIONS + PAGING_ONLY


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, operations=VENDORED, prepare=paging_only)


if __name__ == "__main__":
    sys.exit(main())
