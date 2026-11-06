"""Matching rule and vendored coverage for the IAM condition keys requests carry."""
import json
import subprocess

import pytest

import regen_iam_condition_keys
from regen_iam_condition_keys import OUTPUT, action_keys, classify, pairs_of, require_by_hand_listed

MODEL = {"operations": {
    "AttachRolePolicy": {"input": {"shape": "AttachInput"}},
    "GetPolicy": {"input": {"shape": "AttachInput"}},
    "GetRole": {"input": {"shape": "GetInput"}},
    "CreateServiceSpecificCredential": {"input": {"shape": "CredentialInput"}},
    "CreateDelegationRequest": {"input": {"shape": "DelegationInput"}},
    "DeleteUserPolicy": {"input": {"shape": "UserInput"}},
    "ListRoles": {"input": {"shape": "ListInput"}},
}, "shapes": {
    "AttachInput": {"type": "structure", "members": {"PolicyArn": {"shape": "Arn"}}},
    "GetInput": {"type": "structure", "members": {"RoleName": {"shape": "Name"}}},
    "UserInput": {"type": "structure", "members": {"UserName": {"shape": "Name"},
                                                   "PolicyName": {"shape": "Name"}}},
    "ListInput": {"type": "structure", "members": {"PathPrefix": {"shape": "Name"}}},
    "CredentialInput": {"type": "structure", "members": {"CredentialAgeDays": {"shape": "Days"},
                                                         "ServiceName": {"shape": "Name"}}},
    "DelegationInput": {"type": "structure", "members": {"Permissions": {"shape": "Permission"},
                                                         "SessionDuration": {"shape": "Days"}}},
    "Permission": {"type": "structure", "members": {"PolicyTemplateArn": {"shape": "Arn"}}},
    "Arn": {"type": "string"}, "Name": {"type": "string"}, "Days": {"type": "integer"},
}}


def test_resource_and_action_keys_are_combined_and_unrelated_global_keys_dropped():
    reference = {"Resources": [{"Name": "role", "ConditionKeys": ["iam:PermissionsBoundary", "aws:ResourceTag/${TagKey}"]}],
                 "ConditionKeys": [{"Name": "iam:PermissionsBoundary", "Types": ["ARN"]},
                                   {"Name": "iam:PolicyARN", "Types": ["ARN"]},
                                   {"Name": "iam:Unused", "Types": ["String"]}],
                 "Actions": [{"Name": "AttachRolePolicy", "ActionConditionKeys": ["iam:PolicyARN", "aws:TagKeys"],
                              "Resources": [{"Name": "role"}]}]}
    assert action_keys(reference) == (
        [("iam:AttachRolePolicy", "aws:ResourceTag/${TagKey}"),
         ("iam:AttachRolePolicy", "iam:PermissionsBoundary"), ("iam:AttachRolePolicy", "iam:PolicyARN")],
        {"iam:PermissionsBoundary": ["ARN"], "iam:PolicyARN": ["ARN"]})


def test_member_is_matched_ignoring_case_only_on_actions_listing_the_key():
    read, from_target, not_read = classify([("iam:AttachRolePolicy", "iam:PolicyARN")],
                                           {"iam:PolicyARN": ["ARN"]}, MODEL)
    assert read == [{"action": "iam:AttachRolePolicy", "key": "iam:PolicyARN", "member": "PolicyArn"}]
    assert from_target == not_read == []


def test_permissions_boundary_without_a_request_member_is_read_from_the_named_role_or_user():
    pairs = [("iam:GetRole", "iam:PermissionsBoundary"), ("iam:DeleteUserPolicy", "iam:PermissionsBoundary")]
    read, from_target, not_read = classify(pairs, {"iam:PermissionsBoundary": ["ARN"]}, MODEL)
    assert read == not_read == []
    assert from_target == [
        {"action": "iam:DeleteUserPolicy", "key": "iam:PermissionsBoundary", "target": "UserName"},
        {"action": "iam:GetRole", "key": "iam:PermissionsBoundary", "target": "RoleName"}]


def test_permissions_boundary_on_an_operation_naming_no_role_or_user_fails():
    with pytest.raises(ValueError, match="iam:ListRoles: no member names the role or user"):
        classify([("iam:ListRoles", "iam:PermissionsBoundary")], {"iam:PermissionsBoundary": ["ARN"]}, MODEL)


def test_unread_pairs_name_their_reason():
    pairs = [("iam:GetRole", "iam:RoleTemplateARN"), ("iam:PassRole", "iam:PassedToService"),
             ("iam:CreateServiceSpecificCredential", "iam:CredentialAgeDays")]
    types = {"iam:RoleTemplateARN": ["ARN"],
             "iam:PassedToService": ["String"], "iam:CredentialAgeDays": ["Numeric"]}
    read, from_target, not_read = classify(pairs, types, MODEL)
    assert read == from_target == []
    assert {(e["action"], e["key"]): e["reason"] for e in not_read} == {
        ("iam:CreateServiceSpecificCredential", "iam:CredentialAgeDays"): "the key's type is Numeric",
        ("iam:GetRole", "iam:RoleTemplateARN"): "the value is the role template a service acquires the role from",
        ("iam:PassRole", "iam:PassedToService"): "no SDK operation of this name"}


BY_HAND_TYPES = {"iam:DelegationDuration": ["String"], "iam:TemplateArn": ["ARN"],
                 "iam:ServiceSpecificCredentialAgeDays": ["Numeric"],
                 "iam:ServiceSpecificCredentialServiceName": ["String"]}


def test_by_hand_pairs_read_their_member_including_a_nested_one():
    read, from_target, not_read = classify(sorted(regen_iam_condition_keys.BY_HAND), BY_HAND_TYPES, MODEL)
    assert from_target == not_read == []
    assert {(e["action"], e["key"]): e["member"] for e in read} == regen_iam_condition_keys.BY_HAND


def test_by_hand_member_missing_from_botocore_fails():
    model = json.loads(json.dumps(MODEL))
    del model["shapes"]["Permission"]["members"]["PolicyTemplateArn"]
    with pytest.raises(ValueError, match="Permissions.PolicyTemplateArn for iam:TemplateArn is not in botocore"):
        classify(sorted(regen_iam_condition_keys.BY_HAND), BY_HAND_TYPES, model)


def test_by_hand_member_whose_type_does_not_fit_the_key_fails():
    types = dict(BY_HAND_TYPES, **{"iam:ServiceSpecificCredentialAgeDays": ["String"],
                                   "iam:ServiceSpecificCredentialServiceName": ["Numeric"]})
    with pytest.raises(ValueError, match="ServiceName is string, which does not fit"):
        classify(sorted(regen_iam_condition_keys.BY_HAND), types, MODEL)


def test_by_hand_pair_the_reference_no_longer_lists_fails():
    pairs = sorted(regen_iam_condition_keys.BY_HAND)[1:]
    with pytest.raises(ValueError, match="BY_HAND names pairs the reference does not list"):
        require_by_hand_listed(pairs)


def test_vendored_table_reads_nine_keys_on_fourteen_actions_and_the_boundary_of_fifteen_targets():
    document = json.loads(OUTPUT.read_text())
    assert {(e["action"], e["key"]) for e in document["read"]} == {
        *(("iam:" + verb + kind + "Policy", "iam:PolicyARN")
          for verb in ("Attach", "Detach") for kind in ("Group", "Role", "User")),
        ("iam:CreateRole", "iam:PermissionsBoundary"), ("iam:CreateUser", "iam:PermissionsBoundary"),
        ("iam:PutRolePermissionsBoundary", "iam:PermissionsBoundary"),
        ("iam:PutUserPermissionsBoundary", "iam:PermissionsBoundary"),
        ("iam:CreateServiceLinkedRole", "iam:AWSServiceName"),
        ("iam:CreateDelegationRequest", "iam:NotificationChannel"),
        ("iam:CreateDelegationRequest", "iam:DelegationDuration"),
        ("iam:CreateDelegationRequest", "iam:TemplateArn"),
        ("iam:CreateServiceSpecificCredential", "iam:ServiceSpecificCredentialAgeDays"),
        ("iam:CreateServiceSpecificCredential", "iam:ServiceSpecificCredentialServiceName"),
        ("iam:GenerateOrganizationsAccessReport", "iam:OrganizationsPolicyId")}
    assert not [e for e in document["notRead"] if e["key"] == "iam:PermissionsBoundary"]
    targets = {e["action"]: e["target"] for e in document["fromTarget"] if e["key"] == "iam:PermissionsBoundary"}
    assert len(targets) == 15
    assert targets["iam:GetRole"] == "RoleName" and targets["iam:DeleteUserPermissionsBoundary"] == "UserName"


def test_check_fails_on_a_hand_edited_target(tmp_path, monkeypatch):
    pytest.importorskip("botocore")
    document = json.loads(OUTPUT.read_text())
    document["fromTarget"][0]["target"] = "UserName"
    edited = tmp_path / "iam-condition-keys.json"
    edited.write_text(json.dumps(document))
    monkeypatch.setattr(regen_iam_condition_keys, "OUTPUT", edited)
    with pytest.raises(ValueError, match="differ from pinned botocore"):
        regen_iam_condition_keys.main(["--check"])


def test_check_fails_on_a_hand_edited_member(tmp_path, monkeypatch):
    pytest.importorskip("botocore")
    document = json.loads(OUTPUT.read_text())
    document["read"][0]["member"] = "PolicyName"
    edited = tmp_path / "iam-condition-keys.json"
    edited.write_text(json.dumps(document))
    monkeypatch.setattr(regen_iam_condition_keys, "OUTPUT", edited)
    with pytest.raises(ValueError, match="differ from pinned botocore"):
        regen_iam_condition_keys.main(["--check"])


def reference_from_document(document):
    actions = {}
    for entry in document["read"] + document["fromTarget"] + document["notRead"]:
        name = entry["action"].removeprefix("iam:")
        action = actions.setdefault(name, {"Name": name, "ActionConditionKeys": [], "Resources": []})
        if entry.get("resources"):
            for resource in entry["resources"]:
                action["Resources"].append({"Name": resource, "ConditionKeys": [entry["key"]]})
        else:
            action["ActionConditionKeys"].append(entry["key"])
    for entry in document["requestTags"]:
        name = entry["action"].removeprefix("iam:")
        action = actions.setdefault(name, {"Name": name, "ActionConditionKeys": [], "Resources": []})
        action["Annotations"] = {"Properties": {"IsTaggingOnly": True}}
        action["ActionConditionKeys"].append(entry["key"])
    return {"Resources": [], "Actions": list(actions.values()),
            "ConditionKeys": [{"Name": key, "Types": types} for key, types in document["types"].items()]}


def test_verify_fails_on_a_pair_the_table_does_not_name(tmp_path):
    document = json.loads(OUTPUT.read_text())
    reference = reference_from_document(document)
    source = tmp_path / "iam.json"
    source.write_text(json.dumps(reference))
    assert regen_iam_condition_keys.main(["--verify", "--source", str(source)]) == 0
    reference["Actions"].append({"Name": "GetRole", "ActionConditionKeys": ["iam:PolicyARN"]})
    source.write_text(json.dumps(reference))
    with pytest.raises(ValueError, match="differ from AWS Service Reference"):
        regen_iam_condition_keys.main(["--verify", "--source", str(source)])


def test_verify_fails_when_resource_type_changes_but_condition_keys_do_not(tmp_path):
    reference = reference_from_document(json.loads(OUTPUT.read_text()))
    action = next(a for a in reference["Actions"] if a["Name"] == "GetUser")
    for resource in action["Resources"]:
        resource["Name"] = "role"
    source = tmp_path / "iam.json"
    source.write_text(json.dumps(reference))
    with pytest.raises(ValueError, match="targets differ from AWS Service Reference"):
        regen_iam_condition_keys.main(["--verify", "--source", str(source)])


@pytest.mark.parametrize("changed", [
    "src/main/resources/aws/iam-condition-keys.json",
    "tools/aws/regen_iam_condition_keys.py",
    "tools/aws/test_regen_iam_condition_keys.py",
    "tools/aws/regen_partitions.py",
    "tools/aws/regen_query_services.py",
    "",
])
def test_aggregate_gate_checks_condition_data_and_imports(tmp_path, changed):
    result = run_aggregate_gate(tmp_path, changed, fail=True)
    assert "checked:iam-condition-keys-verify" in result.stdout
    assert result.returncode != 0
    assert "the IAM condition key table" in result.stdout


@pytest.mark.parametrize("changed", [
    "docs/services/iam.md",
    "src/main/resources/aws/dynamodb-condition-keys.json",
    "src/main/resources/aws/iam-condition-keys.json.bak",
])
def test_aggregate_gate_does_not_check_unrelated_condition_data(tmp_path, changed):
    result = run_aggregate_gate(tmp_path, changed, fail=True)
    assert "checked:iam-condition-keys-verify" not in result.stdout
    assert result.returncode == 0


def test_aggregate_gate_accepts_successful_condition_check(tmp_path):
    result = run_aggregate_gate(tmp_path, "src/main/resources/aws/iam-condition-keys.json", fail=False)
    assert "checked:iam-condition-keys-verify" in result.stdout
    assert result.returncode == 0


def run_aggregate_gate(tmp_path, changed, fail):
    recursive_make = tmp_path / "check-gate"
    recursive_make.write_text(
        '#!/bin/sh\nprintf "checked:%s\\n" "$2"\n'
        + ('[ "$2" != iam-condition-keys-verify ]\n' if fail else 'exit 0\n'))
    recursive_make.chmod(0o700)
    return subprocess.run(
        ["make", "--no-print-directory", "iam-data-verify", "CHANGED=" + changed,
         "MAKE=" + str(recursive_make)],
        cwd=OUTPUT.parents[4], capture_output=True, text=True, check=False)


def test_resource_tags_use_the_same_target_route_and_keep_reasoned_exceptions():
    model = json.loads(json.dumps(MODEL))
    for name in ("CreateRole", "PassRole", "AddRoleToInstanceProfile"):
        model["operations"][name] = {"input": {"shape": "GetInput"}}
    for name in ("CreateUser", "CreateAccessKey", "GetUser"):
        model["operations"][name] = {"input": {"shape": "UserInput"}}
    reference = {"ConditionKeys": [], "Resources": [
        {"Name": name, "ConditionKeys": sorted(regen_iam_condition_keys.RESOURCE_TAG_KEYS)}
        for name in ("role", "user", "instance-profile")], "Actions": [
            {"Name": name, "Resources": [{"Name": resource}]} for name, resource in (
                ("GetRole", "role"), ("CreateRole", "role"), ("PassRole", "role"),
                ("GetUser", "user"), ("CreateUser", "user"), ("CreateAccessKey", "user"),
                ("AddRoleToInstanceProfile", "instance-profile"))]}
    pairs, types = action_keys(reference)
    read, targets, not_read = classify(pairs, types, model, regen_iam_condition_keys.resource_tag_types(reference))
    assert read == []
    assert {(e["action"], e["target"]) for e in targets} == {
        ("iam:GetRole", "RoleName"), ("iam:GetUser", "UserName"), ("iam:CreateAccessKey", "UserName")}
    assert {e["action"] for e in not_read} == {
        "iam:PassRole", "iam:CreateRole", "iam:CreateUser", "iam:AddRoleToInstanceProfile"}
    assert all("reliable results" in e["reason"] for e in not_read if e["action"] == "iam:PassRole")
    reference["Actions"][0]["Resources"][0]["ConditionKeys"] = []
    assert not [pair for pair in action_keys(reference)[0] if pair[0] == "iam:GetRole"]


def tag_model():
    return {"operations": {"ApplyLabels": {"input": {"shape": "Input"}},
                           "RemoveLabels": {"input": {"shape": "RemoveInput"}}}, "shapes": {
        "Input": {"members": {"Labels": {"shape": "List", "locationName": "WireTags"}}},
        "RemoveInput": {"members": {"Names": {"shape": "Keys"}}},
        "List": {"type": "list", "member": {"shape": "Tag"}},
        "Keys": {"type": "list", "member": {"shape": "String"}},
        "Tag": {"type": "structure", "members": {"TagKey": {"shape": "String", "locationName": "Name"},
                                                  "TagValue": {"shape": "String", "locationName": "Text"}}},
        "String": {"type": "string"}}}


def test_tag_request_actions_and_members_are_inferred_without_operation_or_member_names():
    model = tag_model()
    reference = {"Actions": [
        {"Name": "ApplyLabels", "Annotations": {"Properties": {"IsTaggingOnly": True}},
         "ActionConditionKeys": sorted(regen_iam_condition_keys.REQUEST_TAG_KEYS)},
        {"Name": "RemoveLabels", "Annotations": {"Properties": {"IsTaggingOnly": True}},
         "ActionConditionKeys": ["aws:TagKeys"]},
        {"Name": "CreateRole", "ActionConditionKeys": sorted(regen_iam_condition_keys.REQUEST_TAG_KEYS)}]}
    entries = regen_iam_condition_keys.request_tag_context(reference, model)
    assert entries == [
        {"action": "iam:ApplyLabels", "key": "aws:RequestTag/${TagKey}", "member": "WireTags",
         "keyMember": "Name", "valueMember": "Text"},
        {"action": "iam:ApplyLabels", "key": "aws:TagKeys", "member": "WireTags",
         "keyMember": "Name", "valueMember": "Text"},
        {"action": "iam:RemoveLabels", "key": "aws:TagKeys", "member": "Names"}]
    regen_iam_condition_keys.validate_request_tags(entries, model)
    entries[0]["valueMember"] = "Wrong"
    with pytest.raises(ValueError, match="differ from pinned botocore"):
        regen_iam_condition_keys.validate_request_tags(entries, model)


@pytest.mark.parametrize("mutation", ["missing", "ambiguous", "wrong-type"])
def test_tag_request_shape_drift_fails_instead_of_silently_dropping_context(mutation):
    model = tag_model()
    if mutation == "missing":
        model["shapes"]["Input"]["members"].clear()
    elif mutation == "ambiguous":
        model["shapes"]["Input"]["members"]["OtherTags"] = {"shape": "List"}
    else:
        model["shapes"]["String"]["type"] = "integer"
    with pytest.raises(ValueError, match="expected one request tag list"):
        regen_iam_condition_keys.request_tag_entry("iam:ApplyLabels", "aws:RequestTag/${TagKey}", model)


def test_vendored_request_tags_cover_all_sixteen_tagging_actions():
    document = json.loads(OUTPUT.read_text())
    assert {e["action"] for e in document["requestTags"]} == {
        "iam:" + verb + resource for verb in ("Tag", "Untag") for resource in (
            "InstanceProfile", "MFADevice", "OpenIDConnectProvider", "Policy", "Role",
            "SAMLProvider", "ServerCertificate", "User")}


def test_check_rejects_hand_edited_tag_fields(tmp_path, monkeypatch):
    document = json.loads(OUTPUT.read_text())
    document["requestTags"][0]["keyMember"] = "Wrong"
    edited = tmp_path / "iam-condition-keys.json"
    edited.write_text(json.dumps(document))
    monkeypatch.setattr(regen_iam_condition_keys, "OUTPUT", edited)
    with pytest.raises(ValueError, match="request tag members differ"):
        regen_iam_condition_keys.main(["--check"])
