#!/usr/bin/env python3
"""Vendor IAM request constraints from botocore's Apache-2.0 service model.

`IamRequestShapes` checks the operations listed here against the vendored file through the shared
`QueryConstraintCheck`: required members, lengths, patterns and enum values, as the model states
them. The list is every operation the model constrains, bar the two the comment beside it names,
nothing being left out for the shape of its request any more.

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

# Every operation IAM models with a constraint of its own: a required member, a length, a pattern,
# an enum or a range. Nothing is left out for the shape of its request any more, the walk reading a
# map and anything one nests, so the maps on `AcquireRole` and `PutAccountProperties`, the
# structure holding a list on `CreateDelegationRequest` and the simulators' context entries are
# read here with the rest.
#
# `CreateAccountAlias` and `DeleteAccountAlias` carry one member each, and AWS answers it in its
# own wording, "The specified value for accountAlias is invalid. It must be a minimum length of 3
# characters ...", so there is nothing left for the walk to read on them.
OPERATIONS = (
    "AcceptDelegationRequest", "AcquireRole", "AddClientIDToOpenIDConnectProvider",
    "AddRoleToInstanceProfile", "AddUserToGroup", "AssociateDelegationRequest",
    "AttachGroupPolicy", "AttachRolePolicy", "AttachUserPolicy", "ChangePassword",
    "CreateAccessKey", "CreateDelegationRequest", "CreateGroup", "CreateInstanceProfile",
    "CreateLoginProfile", "CreateOpenIDConnectProvider", "CreatePolicy", "CreatePolicyVersion",
    "CreateRole", "CreateSAMLProvider", "CreateServiceLinkedRole",
    "CreateServiceSpecificCredential", "CreateUser", "CreateVirtualMFADevice",
    "DeactivateMFADevice", "DeleteAccessKey", "DeleteGroup", "DeleteGroupPolicy",
    "DeleteInstanceProfile", "DeleteLoginProfile", "DeleteOpenIDConnectProvider", "DeletePolicy",
    "DeletePolicyVersion", "DeleteRole", "DeleteRolePermissionsBoundary", "DeleteRolePolicy",
    "DeleteSAMLProvider", "DeleteSSHPublicKey", "DeleteServerCertificate",
    "DeleteServiceLinkedRole", "DeleteServiceSpecificCredential", "DeleteSigningCertificate",
    "DeleteUser", "DeleteUserPermissionsBoundary", "DeleteUserPolicy", "DeleteVirtualMFADevice",
    "DetachGroupPolicy", "DetachRolePolicy", "DetachUserPolicy", "EnableMFADevice",
    "GenerateOrganizationsAccessReport", "GenerateServiceLastAccessedDetails",
    "GetAccessKeyLastUsed", "GetAccountAuthorizationDetails", "GetContextKeysForCustomPolicy",
    "GetContextKeysForPrincipalPolicy", "GetDelegationRequest", "GetGroup", "GetGroupPolicy",
    "GetHumanReadableSummary", "GetInstanceProfile", "GetLoginProfile", "GetMFADevice",
    "GetOpenIDConnectProvider", "GetOrganizationsAccessReport", "GetPolicy", "GetPolicyVersion",
    "GetRole", "GetRolePolicy", "GetRoleTemplateVersion", "GetSAMLProvider", "GetSSHPublicKey",
    "GetServerCertificate", "GetServiceLastAccessedDetails",
    "GetServiceLastAccessedDetailsWithEntities", "GetServiceLinkedRoleDeletionStatus", "GetUser",
    "GetUserPolicy", "ListAccessKeys", "ListAccountAliases", "ListAttachedGroupPolicies",
    "ListAttachedRolePolicies", "ListAttachedUserPolicies", "ListDelegationRequests",
    "ListEntitiesForPolicy", "ListGroupPolicies", "ListGroups", "ListGroupsForUser",
    "ListInstanceProfileTags", "ListInstanceProfiles", "ListInstanceProfilesForRole",
    "ListMFADeviceTags", "ListMFADevices", "ListOpenIDConnectProviderTags", "ListPolicies",
    "ListPoliciesGrantingServiceAccess", "ListPolicyTags", "ListPolicyVersions",
    "ListRolePolicies", "ListRoleTags", "ListRoles", "ListSAMLProviderTags", "ListSSHPublicKeys",
    "ListServerCertificateTags", "ListServerCertificates", "ListServiceSpecificCredentials",
    "ListSigningCertificates", "ListUserPolicies", "ListUserTags", "ListUsers",
    "ListVirtualMFADevices", "PutAccountProperties", "PutGroupPolicy",
    "PutRolePermissionsBoundary", "PutRolePolicy", "PutUserPermissionsBoundary", "PutUserPolicy",
    "RejectDelegationRequest", "RemoveClientIDFromOpenIDConnectProvider",
    "RemoveRoleFromInstanceProfile", "RemoveUserFromGroup", "ResetServiceSpecificCredential",
    "ResyncMFADevice", "SendDelegationToken", "SetDefaultPolicyVersion",
    "SetSecurityTokenServicePreferences", "SimulateCustomPolicy", "SimulatePrincipalPolicy",
    "TagInstanceProfile", "TagMFADevice", "TagOpenIDConnectProvider", "TagPolicy", "TagRole",
    "TagSAMLProvider", "TagServerCertificate", "TagUser", "UntagInstanceProfile",
    "UntagMFADevice", "UntagOpenIDConnectProvider", "UntagPolicy", "UntagRole",
    "UntagSAMLProvider", "UntagServerCertificate", "UntagUser", "UpdateAccessKey",
    "UpdateAccountPasswordPolicy", "UpdateAssumeRolePolicy", "UpdateDelegationRequest",
    "UpdateGroup", "UpdateLoginProfile", "UpdateOpenIDConnectProviderThumbprint", "UpdateRole",
    "UpdateRoleDescription", "UpdateSAMLProvider", "UpdateSSHPublicKey",
    "UpdateServerCertificate", "UpdateServiceSpecificCredential", "UpdateSigningCertificate",
    "UpdateUser", "UploadSSHPublicKey", "UploadServerCertificate", "UploadSigningCertificate",
)

# Operations that answer a member the request leaves out with AWS's own Query wording, "The request
# must contain the parameter CertificateBody.", rather than with the validation layer's null
# violation. Their shapes are vendored without the model's `required` list, so the lengths and the
# patterns are read here while the operation keeps the refusal it answers today.
REQUIRED_BY_HAND = (
    "CreateLoginProfile", "UpdateLoginProfile",
    "UploadServerCertificate", "UploadSigningCertificate", "UploadSSHPublicKey",
    "CreateOpenIDConnectProvider", "RemoveClientIDFromOpenIDConnectProvider",
    "UpdateOpenIDConnectProviderThumbprint",
)


# Members an operation answers in a wording the walk would move, each with the reason it keeps.
#
# A service-linked role's principal and suffix answer `InvalidInput` rather than a validation error
# at all. `ServiceNamespaces` on `ListPoliciesGrantingServiceAccess` answers `InvalidInput` too
# when the list arrives empty. `statusType` is answered without the offending value or the
# permitted set, on all four operations that take it, which is what the recorded refusal carries,
# and `Encoding` on `GetSSHPublicKey` is answered with the modelled `UnrecognizedPublicKeyEncoding`
# rather than as a constraint at all. The simulators answer an empty `ActionNames`,
# `PolicyInputList` or `PolicySourceArn` with a sentence of their own, "At least one ActionNames
# member is required.", which no capture here moves. And `TagKeys` on the untag
# operations is answered against the list as a whole, "Value at 'tagKeys' failed to satisfy
# constraint: Member must satisfy constraint: [...]", where the walk names the member that broke
# it under its own index: both forms are readings, and this is the one those operations merged.
MEMBERS_BY_HAND = {
    "CreateServiceLinkedRole": ("AWSServiceName", "CustomSuffix"),
    "ListPoliciesGrantingServiceAccess": ("ServiceNamespaces",),
    "UpdateAccessKey": ("Status",),
    "UpdateSSHPublicKey": ("Status",),
    "UpdateServiceSpecificCredential": ("Status",),
    "UpdateSigningCertificate": ("Status",),
    "GetSSHPublicKey": ("Encoding",),
    "SimulateCustomPolicy": ("ActionNames", "PolicyInputList"),
    "SimulatePrincipalPolicy": ("ActionNames", "PolicyInputList", "PolicySourceArn"),
    "UntagInstanceProfile": ("TagKeys",),
    "UntagMFADevice": ("TagKeys",),
    "UntagOpenIDConnectProvider": ("TagKeys",),
    "UntagPolicy": ("TagKeys",),
    "UntagRole": ("TagKeys",),
    "UntagSAMLProvider": ("TagKeys",),
    "UntagServerCertificate": ("TagKeys",),
    "UntagUser": ("TagKeys",),
}


def members_by_hand(model: dict, members: dict[str, tuple[str, ...]] | None = None) -> dict:
    """`model` with each operation's named members dropped, `MEMBERS_BY_HAND` by default."""
    members = MEMBERS_BY_HAND if members is None else members
    shapes = dict(model["shapes"])
    for operation, names in members.items():
        shape = model["operations"][operation]["input"]["shape"]
        request = shapes[shape]
        kept = {k: v for k, v in request["members"].items() if k not in names}
        required = [r for r in request.get("required", ()) if r not in names]
        shapes[shape] = {**request, "members": kept}
        if required:
            shapes[shape]["required"] = required
        else:
            shapes[shape].pop("required", None)
    return {**model, "shapes": shapes}


def required_by_hand(model: dict, operations: tuple[str, ...] = REQUIRED_BY_HAND) -> dict:
    """`model` with each of `operations` carrying no required member, `REQUIRED_BY_HAND` by
    default."""
    shapes = dict(model["shapes"])
    for operation in operations:
        name = model["operations"][operation]["input"]["shape"]
        shapes[name] = {k: v for k, v in shapes[name].items() if k != "required"}
    return {**model, "shapes": shapes}


def prepare(model: dict) -> dict:
    """The two reductions this generator makes, in the order they read: the required members an
    operation answers for itself, and the members it answers in a wording of its own."""
    return members_by_hand(required_by_hand(model))


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, operations=OPERATIONS, prepare=prepare)


if __name__ == "__main__":
    sys.exit(main())
