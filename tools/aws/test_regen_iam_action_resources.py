"""Tests for regen_iam_action_resources.

Run with: pytest tools/aws -q  (or: make iam-action-resources-test)
"""
from __future__ import annotations

import pytest

import regen_iam_action_resources as r


def reference(actions: dict[str, list[str]], types: list[str] | None = None) -> dict:
    """A reference payload shaped like AWS's, with an ARN format for every type mentioned."""
    if types is None:
        types = sorted({t for names in actions.values() for t in names})
    return {
        "Name": "iam",
        "Actions": [{"Name": name, "Resources": [{"Name": t} for t in resource_types]}
                    for name, resource_types in actions.items()],
        "Resources": [{"Name": t,
                       "ARNFormats": ["arn:${Partition}:iam::${Account}:" + t + "/${Name}"]}
                      for t in types],
    }


def document(actions: dict[str, list[str]], types: list[str] | None = None) -> dict:
    return r.build(reference(actions, types), "a test")


ANCHORED = {
    "AddUserToGroup": ["group"],
    "RemoveUserFromGroup": ["group"],
    "AddRoleToInstanceProfile": ["instance-profile"],
    "EnableMFADevice": ["user"],
    "DeactivateMFADevice": ["user"],
    "CreateUser": ["user"],
    "ListUsers": [],
}


# --------------------------------------------------------------------------- #
# action_resources_from: what the reference is allowed to contain
# --------------------------------------------------------------------------- #
def test_actions_are_sorted_and_their_types_deduplicated():
    parsed = r.action_resources_from(reference({
        "GetUser": ["user"],
        "AddUserToGroup": ["group", "group"],
    }))
    assert list(parsed) == ["AddUserToGroup", "GetUser"]
    assert parsed["AddUserToGroup"] == ["group"]


def test_an_action_with_no_resource_keeps_an_empty_list():
    # Kept rather than dropped so the Java side can tell "authorized against nothing" from
    # "an action we have not vendored yet". Both read as the wildcard; only one is a gap.
    assert r.action_resources_from(reference({"ListUsers": []}))["ListUsers"] == []


def test_several_resource_types_are_all_kept():
    parsed = r.action_resources_from(reference({"SimulatePrincipalPolicy": ["user", "role", "group"]}))
    assert parsed["SimulatePrincipalPolicy"] == ["group", "role", "user"]


@pytest.mark.parametrize("payload", [
    {"Name": "iam"},
    {"Name": "iam", "Actions": []},
    {"Name": "iam", "Actions": [{"Resources": []}]},
])
def test_malformed_reference_is_rejected(payload):
    with pytest.raises(ValueError):
        r.action_resources_from(payload)


@pytest.mark.parametrize("action", ["getUser", "Get User", "", "Get-User"])
def test_an_unusable_action_name_is_rejected(action):
    with pytest.raises(ValueError):
        r.action_resources_from(reference({action: ["user"]}))


@pytest.mark.parametrize("resource_type", ["User", "my_type", "", "user/name"])
def test_an_unusable_resource_type_is_rejected(resource_type):
    with pytest.raises(ValueError):
        r.action_resources_from(reference({"GetUser": [resource_type]}))


# --------------------------------------------------------------------------- #
# arn_formats_from: the other half, which is what the minting is checked against
# --------------------------------------------------------------------------- #
def test_arn_formats_are_kept_per_type():
    formats = r.arn_formats_from(reference({"GetUser": ["user"]}))
    assert formats["user"] == ["arn:${Partition}:iam::${Account}:user/${Name}"]


def test_the_role_template_format_aws_actually_publishes_is_accepted():
    # Its account segment is a literal `aws` and its resource part carries a colon, so a pattern
    # pinned to ${Account} and a colon-free resource would reject real AWS data.
    payload = {
        "Name": "iam",
        "Actions": [{"Name": "AcquireRole", "Resources": [{"Name": "role-template"}]}],
        "Resources": [{"Name": "role-template", "ARNFormats": [
            "arn:${Partition}:iam::aws:role-template/${AWSServicePrincipal}"
            "/${RoleTemplateName}:${RoleTemplateMajorVersion}"]}],
    }
    assert "role-template" in r.arn_formats_from(payload)


@pytest.mark.parametrize("arn_format", [
    "arn:aws:iam::123456789012:user/bob",
    "arn:${Partition}:s3:::${Account}:bucket",
    "user/${Name}",
])
def test_something_that_is_not_an_iam_arn_format_is_rejected(arn_format):
    payload = {
        "Name": "iam",
        "Actions": [{"Name": "GetUser", "Resources": [{"Name": "user"}]}],
        "Resources": [{"Name": "user", "ARNFormats": [arn_format]}],
    }
    with pytest.raises(ValueError):
        r.arn_formats_from(payload)


# --------------------------------------------------------------------------- #
# validate: what makes the vendored file unusable rather than merely behind
# --------------------------------------------------------------------------- #
def test_a_freshly_built_document_validates():
    assert r.validate(document(ANCHORED)) == []


def test_an_anchor_authorized_against_the_wrong_resource_is_rejected():
    # The case the whole file exists for: AddUserToGroup is authorized against the group, not the
    # user its UserName names. A map that said otherwise would let a deny on the group pass.
    wrong = dict(ANCHORED)
    wrong["AddUserToGroup"] = ["user"]
    problems = r.validate(document(wrong))
    assert any("AddUserToGroup" in problem for problem in problems)


def test_a_missing_anchor_is_rejected():
    missing = {name: types for name, types in ANCHORED.items() if name != "EnableMFADevice"}
    assert any("EnableMFADevice" in problem for problem in r.validate(document(missing)))


def test_an_unsorted_map_is_rejected():
    doc = document(ANCHORED)
    doc["actionResources"] = {"ListUsers": [], "AddUserToGroup": ["group"]}
    assert any("not sorted" in problem for problem in r.validate(doc))


def test_a_resource_type_with_no_arn_format_is_rejected():
    doc = document(ANCHORED)
    doc["actionResources"]["GetRole"] = ["role"]
    assert any("no ARN format" in problem for problem in r.validate(doc))


def test_an_empty_map_is_rejected():
    assert r.validate({"actionResources": {}, "resourceArnFormats": {}}) == [
        "actionResources is missing or empty"]


# --------------------------------------------------------------------------- #
# verify_against: the online gate's judgement
# --------------------------------------------------------------------------- #
def test_a_claim_aws_does_not_make_fails():
    # A swapped type reads as both at once: the vendored `user` is untrue, and the `group` AWS
    # gives is missing. The untrue half is what fails the gate.
    untrue, added = r.verify_against({"AddUserToGroup": ["user"]}, {"AddUserToGroup": ["group"]})
    assert untrue == ["'AddUserToGroup' is not authorized against 'user' by AWS"]
    assert added == ["AddUserToGroup -> group"]


def test_an_action_aws_no_longer_publishes_fails():
    untrue, _ = r.verify_against({"RetiredAction": ["user"]}, {"GetUser": ["user"]})
    assert any("RetiredAction" in claim for claim in untrue)


def test_a_new_action_upstream_is_reported_without_failing():
    untrue, added = r.verify_against({"GetUser": ["user"]},
                                     {"GetUser": ["user"], "NewAction": ["role"]})
    assert not untrue
    assert added == ["NewAction (new action)"]


def test_a_type_aws_has_added_to_a_known_action_is_reported_without_failing():
    # Being behind leaves that action on the wildcard, which is where it started. Wrong is worse
    # than incomplete, so only the first direction fails.
    untrue, added = r.verify_against({"GetUser": ["user"]}, {"GetUser": ["user", "role"]})
    assert not untrue
    assert added == ["GetUser -> role"]


def test_an_identical_map_verifies_clean():
    assert r.verify_against({"GetUser": ["user"]}, {"GetUser": ["user"]}) == ([], [])

def test_a_drifted_arn_format_fails():
    # The gap the shape check cannot see: a changed format is still a well-formed one, so only a
    # comparison with AWS catches it, and the minting places the resource type from this string.
    untrue, added = r.verify_formats_against(
        {"user": ["arn:${Partition}:iam::${Account}:user/${UserName}"]},
        {"user": ["arn:${Partition}:iam::${Account}:user/${UserNameWithPath}"]})
    assert untrue == ["AWS does not spell a user ARN as "
                      "'arn:${Partition}:iam::${Account}:user/${UserName}'"]
    assert added == ["user -> arn:${Partition}:iam::${Account}:user/${UserNameWithPath}"]


def test_a_resource_type_aws_does_not_publish_fails():
    untrue, added = r.verify_formats_against(
        {"make-believe": ["arn:${Partition}:iam::${Account}:make-believe/${Name}"]},
        {"user": ["arn:${Partition}:iam::${Account}:user/${UserNameWithPath}"]})
    assert untrue == ["'make-believe' is not a resource type AWS publishes"]
    assert added == ["user (new resource type)"]


def test_an_arn_format_aws_added_is_reported_without_failing():
    untrue, added = r.verify_formats_against(
        {"user": ["arn:${Partition}:iam::${Account}:user/${UserNameWithPath}"]},
        {"user": ["arn:${Partition}:iam::${Account}:user/${UserNameWithPath}"],
         "role": ["arn:${Partition}:iam::${Account}:role/${RoleNameWithPath}"]})
    assert not untrue
    assert added == ["role (new resource type)"]


def test_matching_formats_verify_clean():
    formats = {"user": ["arn:${Partition}:iam::${Account}:user/${UserNameWithPath}"]}
    assert r.verify_formats_against(formats, dict(formats)) == ([], [])
