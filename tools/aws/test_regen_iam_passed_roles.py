"""Tests for regen_iam_passed_roles.

Run with: pytest tools/aws -q  (or: make iam-passed-roles-test)
"""
from __future__ import annotations

import json

import pytest

import regen_iam_passed_roles as r


def reference(namespace: str, operations: dict[str, list[str] | None], clients: dict[str, list[str]] | None = None) -> dict:
    """A Service Reference document. Each operation maps to the services it passes a role to, or to None
    when it lists no iam:PassRole; `clients` gives an operation's SDK clients, the namespace by default."""
    entries = []
    for name, services in operations.items():
        authorized = [{"Service": namespace, "Name": name}]
        if services is not None:
            authorized.append({"Service": "iam", "Name": "PassRole",
                               "Context": {"iam:PassedToService": [s + ".amazonaws.com" for s in services]}})
        sdk = [{"Name": c, "Method": name, "Package": "Boto3"} for c in (clients or {}).get(name, [namespace])]
        entries.append({"Name": name, "AuthorizedActions": authorized, "SDK": sdk})
    return {"Name": namespace, "Operations": entries}


def model(operations: dict[str, dict], shapes: dict[str, dict], protocol: str = "json") -> dict:
    """A botocore model whose operations take the input shape each names."""
    return {"metadata": {"protocol": protocol},
            "operations": {name: {"input": {"shape": shape}} for name, shape in operations.items()},
            "shapes": {"String": {"type": "string"}, **shapes}}


def structure(**members) -> dict:
    return {"type": "structure", "members": {name: ({"shape": spec} if isinstance(spec, str) else spec)
                                             for name, spec in members.items()}}


@pytest.fixture
def corrections(monkeypatch):
    """No corrections at all, so a test sees the rule alone and sets what it needs."""
    for name in ("BY_HAND", "NOT_READ", "EXCLUDED_MEMBERS", "EXTRA_MEMBERS", "MEMBER_SERVICES"):
        monkeypatch.setattr(r, name, {})
    monkeypatch.setattr(r, "NAMESPACES", ("svc",))
    return monkeypatch


# --------------------------------------------------------------------------- #
# The Service Reference
# --------------------------------------------------------------------------- #
def test_only_operations_listing_pass_role_are_kept_with_their_services_and_clients():
    found = r.passed_role_operations([reference("svc", {"CreateThing": ["svc", "other"], "GetThing": None},
                                                {"CreateThing": ["svc", "svcv2"]})])
    assert found == {"svc:CreateThing": {"passedTo": ["other", "svc"], "clients": ["svc", "svcv2"]}}


def test_a_pass_role_with_no_service_passes_to_none():
    document = reference("svc", {"CreateGroup": []})
    assert r.passed_role_operations([document])["svc:CreateGroup"]["passedTo"] == []


def test_a_passed_to_service_that_is_no_principal_is_a_malformed_response():
    document = reference("svc", {"CreateThing": ["svc"]})
    document["Operations"][0]["AuthorizedActions"][1]["Context"]["iam:PassedToService"] = ["svc"]
    with pytest.raises(ValueError, match="service principal"):
        r.passed_role_operations([document])


def test_an_unusable_namespace_is_rejected():
    with pytest.raises(ValueError, match="not usable"):
        r.passed_role_operations([{"Name": "Not A Namespace", "Operations": []}])


# --------------------------------------------------------------------------- #
# botocore: the members that name a role
# --------------------------------------------------------------------------- #
def test_a_member_is_a_role_by_its_name_when_it_holds_a_string():
    m = model({"Op": "In"}, {
        "In": structure(Role="String", ServiceRoleArn="String", IamRoleName="String", IamRoles="Roles",
                        RoleARNUpdate="String", serviceRoleOverride="String", RoleSessionName="String",
                        AmbiguousRoleResolution="String", RoleCount="Integer", Name="String"),
        "Roles": {"type": "list", "member": {"shape": "String"}},
        "Integer": {"type": "integer"},
    })
    assert r.role_members(m, "Op") == {"Role": False, "ServiceRoleArn": False, "IamRoleName": True,
                                       "IamRoles[]": False, "RoleARNUpdate": False, "serviceRoleOverride": False}


def test_an_enum_names_no_role():
    m = model({"Op": "In"}, {"In": structure(role="RoleType"), "RoleType": {"type": "string", "enum": ["ADMIN"]}})
    assert r.role_members(m, "Op") == {}


def test_nested_lists_maps_and_named_list_members_are_written_as_steps():
    m = model({"Op": "In"}, {
        "In": structure(Rule="Rule", Roles="RoleMap", Associated="Associated"),
        "Rule": structure(Actions="Actions"),
        "Actions": {"type": "list", "member": {"shape": "Action"}},
        "Action": structure(S3Action="S3Action"),
        "S3Action": structure(IamRoleArn="String"),
        "RoleMap": {"type": "map", "key": {"shape": "String"}, "value": {"shape": "String"}},
        "Associated": {"type": "list", "member": {"shape": "Assoc", "locationName": "AssociatedRole"}},
        "Assoc": structure(RoleArn="String"),
    }, protocol="query")
    assert r.role_members(m, "Op") == {"Rule.Actions[].S3Action.IamRoleArn": False, "Roles{}": False,
                                       "Associated[AssociatedRole].RoleArn": False}


def test_a_member_serialized_under_another_name_is_written_under_it():
    m = model({"Op": "In"}, {"In": structure(RoleArn={"shape": "String", "locationName": "roleArn"})})
    assert r.role_members(m, "Op") == {"roleArn": False}


def test_a_member_bound_to_the_uri_a_header_or_the_query_string_is_not_read():
    m = model({"Op": "In"}, {"In": structure(RoleArn={"shape": "String", "location": "uri"},
                                             BodyRoleArn="String")}, protocol="rest-json")
    assert r.role_members(m, "Op") == {"BodyRoleArn": False}


def test_a_payload_member_is_the_body():
    m = model({"Op": "In"}, {"In": {"type": "structure", "payload": "Config",
                                    "members": {"Config": {"shape": "Config"}}},
                             "Config": structure(RoleArn="String")}, protocol="rest-json")
    assert r.role_members(m, "Op") == {"RoleArn": False}


@pytest.mark.parametrize("documentation", [
    "The name or Amazon Resource Name (ARN) of the IAM role associated with this job.",
    "The short name or full Amazon Resource Name (ARN) of the IAM role.",
    "The IAM role or Amazon Resource Name (ARN) of an IAM role used by the new crawler.",
    "The name of a service role for this build.",
])
def test_documentation_saying_a_member_takes_a_name_marks_it_by_name(documentation):
    m = model({"Op": "In"}, {"In": structure(Role={"shape": "String", "documentation": documentation})})
    assert r.role_members(m, "Op") == {"Role": True}


def test_an_amazon_resource_name_alone_takes_no_name():
    m = model({"Op": "In"}, {"In": structure(RoleArn={"shape": "String", "documentation":
                                                      "The Amazon Resource Name of the IAM role assumed."})})
    assert r.role_members(m, "Op") == {"RoleArn": False}


def test_a_pattern_that_only_admits_an_arn_takes_no_name():
    m = model({"Op": "In"}, {"In": structure(ExecutionRoleArn={"shape": "RoleArn", "documentation":
                                                               "The name of the IAM execution role."}),
                             "RoleArn": {"type": "string", "pattern": "arn:.+:iam::[0-9]{12}:role/.+"}})
    assert r.role_members(m, "Op") == {"ExecutionRoleArn": False}


@pytest.mark.parametrize("protocol", ["ec2", "rest-xml"])
def test_a_model_whose_requests_are_not_read_has_no_members(protocol):
    m = model({"Op": "In"}, {"In": structure(RoleArn="String")}, protocol=protocol)
    assert r.role_members(m, "Op") == {}


def test_a_member_path_with_a_map_key_exists_only_for_a_key_the_enum_lists():
    m = model({"Op": "In"}, {"In": structure(Definition="Definition"),
                             "Definition": {"type": "map", "key": {"shape": "Key"}, "value": {"shape": "String"}},
                             "Key": {"type": "string", "enum": ["Name", "AssumeRole"]}})
    assert r.member_exists(m, "Op", "Definition{AssumeRole}")
    assert not r.member_exists(m, "Op", "Definition{Missing}")
    assert not r.member_exists(m, "Op", "Definition[]")
    assert not r.member_exists(m, "Op", "Other")


# --------------------------------------------------------------------------- #
# One operation's entry
# --------------------------------------------------------------------------- #
SIMPLE = {"svc": model({"CreateThing": "In"}, {"In": structure(RoleArn="String", Roles="RoleList"),
                                               "RoleList": {"type": "list", "member": {"shape": "String"}}})}


def test_each_member_goes_to_the_one_service_the_reference_names(corrections):
    assert r.entry_for("svc:CreateThing", ["svc"], SIMPLE) == {
        "passedTo": ["svc"],
        "roles": [{"member": "RoleArn", "passedTo": "svc"}, {"member": "Roles[]", "passedTo": "svc"}]}


def test_a_role_passed_to_no_named_service_carries_none(corrections):
    assert r.entry_for("svc:CreateThing", [], SIMPLE)["roles"][0] == {"member": "RoleArn"}


def test_several_services_need_a_member_service(corrections):
    with pytest.raises(ValueError, match="MEMBER_SERVICES"):
        r.entry_for("svc:CreateThing", ["svc", "other"], SIMPLE)
    corrections.setattr(r, "MEMBER_SERVICES", {"svc:*": {"*": "other"}})
    assert {role["passedTo"] for role in r.entry_for("svc:CreateThing", ["svc", "other"], SIMPLE)["roles"]} == {"other"}


def test_hand_and_unread_operations_carry_their_reason_and_no_members(corrections):
    corrections.setattr(r, "BY_HAND", {"svc:CreateThing": "an instance profile's roles"})
    assert r.entry_for("svc:CreateThing", ["svc"], SIMPLE) == {"passedTo": ["svc"],
                                                               "byHand": "an instance profile's roles"}
    corrections.setattr(r, "BY_HAND", {})
    corrections.setattr(r, "NOT_READ", {"svc:CreateThing": "no member of the request names a role"})
    assert r.entry_for("svc:CreateThing", ["svc"], SIMPLE)["notRead"] == "no member of the request names a role"


def test_an_exclusion_drops_a_member_and_an_extra_one_adds_it(corrections):
    corrections.setattr(r, "EXCLUDED_MEMBERS", {"svc:*": {"Roles[]": "roles a policy is attached to"}})
    corrections.setattr(r, "EXTRA_MEMBERS", {"svc:CreateThing": {"RoleArn": (True, "takes a name")}})
    assert r.entry_for("svc:CreateThing", ["svc"], SIMPLE)["roles"] == [
        {"member": "RoleArn", "passedTo": "svc", "byName": True}]


def test_an_extra_member_the_request_does_not_have_fails(corrections):
    corrections.setattr(r, "EXTRA_MEMBERS", {"svc:CreateThing": {"Missing.RoleArn": (False, "a typo")}})
    with pytest.raises(ValueError, match="does not have"):
        r.entry_for("svc:CreateThing", ["svc"], SIMPLE)


def test_an_operation_naming_no_role_must_say_why(corrections):
    empty = {"svc": model({"CreateThing": "In"}, {"In": structure(Name="String")})}
    with pytest.raises(ValueError, match="NOT_READ"):
        r.entry_for("svc:CreateThing", ["svc"], empty)


# --------------------------------------------------------------------------- #
# The whole document
# --------------------------------------------------------------------------- #
def write_models(root, models: dict[str, dict]):
    for name, value in models.items():
        directory = root / name / "2020-01-01"
        directory.mkdir(parents=True)
        (directory / "service-2.json").write_text(json.dumps(value), encoding="utf-8")


def test_a_built_document_validates_and_matches_botocore(corrections, tmp_path):
    write_models(tmp_path, SIMPLE)
    document = r.build([reference("svc", {"CreateThing": ["svc"]}), reference("elsewhere", {"Op": ["x"]})],
                       "test", tmp_path, "botocore test")
    assert r.validate(document) == []
    assert r.derive(document, tmp_path) == []
    assert document["namespaces"] == {"svc": ["svc"]}
    assert document["otherNamespaces"] == ["elsewhere"]
    assert json.loads(r.render(document)) == document


def test_a_hand_edited_member_does_not_match_botocore(corrections, tmp_path):
    write_models(tmp_path, SIMPLE)
    document = r.build([reference("svc", {"CreateThing": ["svc"]})], "test", tmp_path, "botocore test")
    document["operations"]["svc:CreateThing"]["roles"].append({"member": "Other", "passedTo": "svc"})
    assert r.validate(document) == []
    assert len(r.derive(document, tmp_path)) == 1


def test_a_correction_naming_an_operation_the_reference_does_not_list_fails(corrections, tmp_path):
    write_models(tmp_path, SIMPLE)
    corrections.setattr(r, "NOT_READ", {"svc:Gone": "no member of the request names a role"})
    with pytest.raises(ValueError, match="svc:Gone"):
        r.build([reference("svc", {"CreateThing": ["svc"]})], "test", tmp_path, "botocore test")


def test_an_exclusion_no_operation_has_fails(corrections, tmp_path):
    write_models(tmp_path, SIMPLE)
    corrections.setattr(r, "EXCLUDED_MEMBERS", {"svc:*": {"Missing": "no longer there"}})
    with pytest.raises(ValueError, match="no operation there has"):
        r.build([reference("svc", {"CreateThing": ["svc"]})], "test", tmp_path, "botocore test")


def good_document() -> dict:
    return {"namespaces": {"svc": ["svc"]}, "operations": {
        "svc:A": {"passedTo": ["svc"], "roles": [{"member": "Rule.Actions[].RoleArn", "passedTo": "svc"}]},
        "svc:B": {"passedTo": ["svc"], "byHand": "an instance profile's roles"},
        "svc:C": {"passedTo": [], "notRead": "no member of the request names a role"}}}


def test_a_well_formed_document_validates():
    assert r.validate(good_document()) == []


@pytest.mark.parametrize("change, problem", [
    (lambda d: d["operations"]["svc:A"].update(byHand="x"), "exactly one"),
    (lambda d: d["operations"]["svc:A"]["roles"][0].update(member="Rule..RoleArn"), "no member path"),
    (lambda d: d["operations"]["svc:A"]["roles"][0].update(passedTo="other"), "does not name"),
    (lambda d: d["operations"]["svc:A"]["roles"][0].pop("passedTo"), "goes to no service"),
    (lambda d: d["operations"]["svc:A"]["roles"][0].update(byName=False), "unknown field"),
    (lambda d: d["operations"].update({"other:D": {"passedTo": [], "notRead": "x"}}), "namespace the file reads"),
])
def test_a_malformed_document_is_rejected(change, problem):
    document = good_document()
    change(document)
    assert any(problem in p for p in r.validate(document)), r.validate(document)


def test_verify_fails_on_a_claim_aws_does_not_make_and_reports_an_added_operation():
    vendored = good_document()
    upstream = {"svc:A": {"passedTo": ["svc"], "clients": ["svc"]},
                "svc:B": {"passedTo": ["other"], "clients": ["svc"]},
                "svc:D": {"passedTo": ["svc"], "clients": ["svc"]},
                "elsewhere:E": {"passedTo": ["x"], "clients": ["elsewhere"]}}
    untrue, added = r.verify_against(vendored, upstream)
    assert len(untrue) == 2 and any("svc:B" in u for u in untrue) and any("svc:C" in u for u in untrue)
    assert added == ["svc:D"]


def test_verify_fails_when_aws_lists_an_operation_for_a_client_the_file_does_not_read():
    untrue, _ = r.verify_against(good_document(), {
        "svc:A": {"passedTo": ["svc"], "clients": ["svc", "svcv2"]},
        "svc:B": {"passedTo": ["svc"], "clients": ["svc"]},
        "svc:C": {"passedTo": [], "clients": ["svc"]}})
    assert untrue == ["AWS lists svc:A for ['svc', 'svcv2'], which the file does not read"]


def test_the_vendored_file_is_well_formed():
    document = json.loads(r.OUTPUT.read_text(encoding="utf-8"))
    assert r.validate(document) == []
    assert document["operations"]["athena:CreateWorkGroup"]["roles"] == [
        {"member": "Configuration.ExecutionRole", "passedTo": "athena"}]
    assert document["operations"]["budgets:CreateBudgetAction"]["roles"] == [
        {"member": "ExecutionRoleArn", "passedTo": "budgets"}]
    assert document["operations"]["ec2:RunInstances"].get("byHand")
