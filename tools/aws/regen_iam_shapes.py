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
# A service-linked role's custom suffix answers `InvalidInput` rather than a validation error at
# all, "Custom suffix is not allowed for <service>", which is a recorded refusal across roughly
# eighty principals. `ServiceNamespaces` on `ListPoliciesGrantingServiceAccess` answers
# `InvalidInput` too when the list arrives empty, and `Encoding` on `GetSSHPublicKey` is answered
# with the modelled `UnrecognizedPublicKeyEncoding` rather than as a constraint at all. The
# simulators answer an empty `ActionNames`, `PolicyInputList` or `PolicySourceArn` with a sentence
# of their own, "At least one ActionNames member is required.", which no capture here moves. And
# `TagKeys` on the untag operations is answered against the list as a whole, "Value at 'tagKeys'
# failed to satisfy constraint: Member must satisfy constraint: [...]", where the walk names the
# member that broke it under its own index: both forms are readings, and this is the one those
# operations merged.
#
# Two entries came off this list on 2026-10-31, when captures from a real account answered them on
# issue #6042. `AWSServiceName` was held back because the operation answered it as `InvalidInput`
# with a sentence about its own bounds; AWS answers `ValidationError` naming the declared pattern,
# so the walk reads it. `Status` on the four operations that share `statusType` was held back
# because a 2025 recording carried neither the offending value nor the permitted set; AWS names the
# set, and answers an omitted `Status` as a required member rather than as a value outside it, so
# all four are read here with their `required` lists intact.
MEMBERS_BY_HAND = {
    "CreateServiceLinkedRole": ("CustomSuffix",),
    "ListPoliciesGrantingServiceAccess": ("ServiceNamespaces",),
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


# The permitted set AWS lists inside a validation message, where this model declares the same values
# in another order. `statusType`'s message was captured from a real account on 2026-10-31, on issue
# #6042, as "Member must satisfy enum value set: [Expired, Active, Inactive]", which is the order
# AWS's own model declares rather than the order botocore lists. The values are the model's either
# way and the check below holds them to it: only the order is read from the capture.
ENUM_ORDER = {
    "statusType": ("Expired", "Active", "Inactive"),
}


def enum_order(model: dict, orders: dict[str, tuple[str, ...]] | None = None) -> dict:
    """`model` with each named shape's enum in the order AWS lists it in, `ENUM_ORDER` by default."""
    orders = ENUM_ORDER if orders is None else orders
    shapes = dict(model["shapes"])
    for name, order in orders.items():
        declared = shapes[name]["enum"]
        if sorted(declared) != sorted(order):
            raise SystemExit(f"{name} declares {declared}, which is not a reordering of "
                             f"{list(order)}: the capture is stale, read the model again")
        shapes[name] = {**shapes[name], "enum": list(order)}
    return {**model, "shapes": shapes}


# The pattern AWS quotes inside a validation message, where this model declares another one for the
# same member. Each entry names the shape the member resolves to and the pattern that shape
# declares, so a model that changes either stops the run rather than quoting a text the capture no
# longer answers for. Only the quoted text is read from the capture: what a value is held to stays
# the model's.
#
# `CreateServiceLinkedRole`'s `AWSServiceName` resolves to `groupNameType`, the shape botocore gives
# a group name, 1 to 128 characters of `[\w+=,.@-]+`. The capture on issue #6042, from a real
# account on 2026-10-31, quotes `[\w+=.@-]*` for it, without the comma this model admits and with a
# `*` where the model sets a minimum of 1. Only AWS's message is in hand rather than its shape, so
# the member carries a shape of its own here with AWS's text beside the model's: the message is the
# captured one and a comma is still admitted, which nothing in hand settles either way.
QUOTED_PATTERNS = {
    ("CreateServiceLinkedRole", "AWSServiceName"): ("groupNameType", r"[\w+=,.@-]+", r"[\w+=.@-]*"),
}


def quoted_patterns(model: dict, quoted: dict | None = None) -> dict:
    """`model` with each named member on a shape of its own, carrying the pattern AWS quotes beside
    the one the model declares, `QUOTED_PATTERNS` by default.

    The shape is named after the member as the wire names it, `AWSServiceName` reading
    `aWSServiceName` in a message and so taking `aWSServiceNameType`, since the shape it is copied
    from is one several members share and only this one's message differs."""
    quoted = QUOTED_PATTERNS if quoted is None else quoted
    shapes = dict(model["shapes"])
    for (operation, member), (shape_name, declared, text) in quoted.items():
        request = model["operations"][operation]["input"]["shape"]
        members = shapes[request]["members"]
        if member not in members:
            raise SystemExit(f"{operation} vendors no {member} to quote a pattern for: it is "
                             f"answered by hand again, or the model dropped it")
        if members[member]["shape"] != shape_name:
            raise SystemExit(f"{operation}.{member} resolves to {members[member]['shape']}, not "
                             f"{shape_name}: the capture is stale, read the model again")
        if shapes[shape_name].get("pattern") != declared:
            raise SystemExit(f"{shape_name} declares {shapes[shape_name].get('pattern')}, not "
                             f"{declared}: the capture is stale, read the model again")
        own = member[0].lower() + member[1:] + "Type"
        shapes[own] = {**shapes[shape_name], "quotedPattern": text}
        shapes[request] = {**shapes[request], "members": {**members, member: {"shape": own}}}
    return {**model, "shapes": shapes}


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
    """The four readings this generator makes, in the order they apply: the required members an
    operation answers for itself, the members it answers in a wording of its own, the enum whose set
    AWS lists in an order of its own, and the member whose pattern AWS quotes in a text of its
    own."""
    return quoted_patterns(enum_order(members_by_hand(required_by_hand(model))))


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, operations=OPERATIONS, prepare=prepare)


if __name__ == "__main__":
    sys.exit(main())
