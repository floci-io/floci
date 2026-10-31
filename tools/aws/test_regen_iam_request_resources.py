"""Tests for regen_iam_request_resources.

Run with: pytest tools/aws -q  (or: make iam-request-resources-test)
"""
from __future__ import annotations

import json

import pytest

import regen_iam_request_resources as r

FORMATS = {
    "thing": "arn:${Partition}:svc:${Region}:${Account}:thing/${ThingName}",
    "group": "arn:${Partition}:svc:${Region}:${Account}:group/${GroupName}/${GroupId}",
    "pool": "arn:${Partition}:svc:${Region}:${Account}:pool/${PoolId}",
    "catalog": "arn:${Partition}:svc:${Region}:${Account}:catalog",
    "lb": "arn:${Partition}:svc:${Region}:${Account}:loadbalancer/${LoadBalancerName}",
    "lb/app/": "arn:${Partition}:svc:${Region}:${Account}:loadbalancer/app/${LoadBalancerName}/${LoadBalancerId}",
}


def members(*names: str) -> dict[str, str]:
    return {name.rstrip("[]"): name for name in names}


def test_a_member_holding_an_arn_names_any_type_the_action_acts_on():
    assert r.resources_for("svc:DescribeThing", ["thing", "pool"], FORMATS, members("ThingArn", "ResourceArns[]")) == [
        {"types": ["thing", "pool"], "arn": "ResourceArns[]"}, {"types": ["thing", "pool"], "arn": "ThingArn"}]


def test_a_member_fills_a_variable_its_stem_relates_to_and_of_its_kind():
    assert r.resources_for("svc:GetThing", ["thing"], FORMATS, members("ThingName")) == [
        {"type": "thing", "fill": {"ThingName": "ThingName"}}]
    # A name fills a name and leaves an id open; an identifier fills either.
    assert r.resources_for("svc:UpdateGroup", ["group"], FORMATS, members("GroupName")) == [
        {"type": "group", "fill": {"GroupName": "GroupName"}}]
    assert r.resources_for("svc:UpdatePool", ["pool"], FORMATS, members("PoolIdentifier")) == [
        {"type": "pool", "fill": {"PoolId": "PoolIdentifier"}}]
    # A longer name ending with the variable's stem fills it.
    assert r.resources_for("svc:GetThing", ["thing"], FORMATS, members("SpecialThingName")) == [
        {"type": "thing", "fill": {"ThingName": "SpecialThingName"}}]


def test_a_bare_name_fills_the_type_the_operation_is_about_and_no_other():
    assert r.resources_for("svc:CreateThing", ["thing"], FORMATS, members("Name")) == [
        {"type": "thing", "fill": {"ThingName": "Name"}}]
    assert r.resources_for("svc:DescribeGroups", ["group"], FORMATS, members("Names[]")) == [
        {"type": "group", "fill": {"GroupName": "Names[]"}}]
    # StartRun's name is the run's, not the thing's.
    assert r.resources_for("svc:StartRun", ["thing"], FORMATS, members("name")) == []
    # A member holding the type's ARN leaves the bare name to something else.
    assert r.resources_for("svc:CreateThingAlias", ["thing"], FORMATS, members("Name")) == []


def test_a_fixed_format_and_a_creates_own_type_are_named_without_members():
    assert r.resources_for("svc:GetCatalog", ["catalog"], FORMATS, {}) == [{"type": "catalog", "fill": {}}]
    assert r.resources_for("svc:CreatePool", ["pool"], FORMATS, members("Description")) == [{"type": "pool", "fill": {}}]
    # Another type the action lists, which the request does not name, is not checked.
    assert r.resources_for("svc:CreatePool", ["pool", "thing"], FORMATS, members("Description")) == [
        {"type": "pool", "fill": {}}]


def test_types_named_only_through_the_same_members_are_alternatives():
    # A classic load balancer's name fills its whole ARN, not an application one's id: the classic one is kept.
    assert r.resources_for("svc:DeleteLoadBalancer", ["lb", "lb/app/"], FORMATS, members("LoadBalancerName")) == [
        {"type": "lb", "fill": {"LoadBalancerName": "LoadBalancerName"}}]


def test_a_type_is_filled_from_one_list_at_most():
    formats = {"pair": "arn:${Partition}:svc:${Region}:${Account}:pair/${LeftName}/${RightName}"}
    assert r.resources_for("svc:GetPair", ["pair"], formats, members("LeftNames[]", "RightNames[]")) == []
    # Only an operation that creates its type names it with every variable open.
    assert r.resources_for("svc:GetPair", ["pair"], formats, {}) == []


LISTENERS = {
    "loadbalancer/app/": FORMATS["lb/app/"],
    "listener/app": "arn:${Partition}:svc:${Region}:${Account}:listener/app/${LoadBalancerName}/${LoadBalancerId}/${ListenerId}",
    "rule/app": "arn:${Partition}:svc:${Region}:${Account}:rule/app/${LoadBalancerName}/${LoadBalancerId}/${ListenerId}/${RuleId}",
    "pair": "arn:${Partition}:svc:${Region}:${Account}:pair/app/${LoadBalancerName}/${LoadBalancerId}/${A}/${B}",
}


def test_a_child_format_is_its_parents_with_the_type_word_replaced_and_one_id_appended():
    assert r.child_id(LISTENERS["listener/app"], LISTENERS["loadbalancer/app/"]) == "ListenerId"
    assert r.child_id(LISTENERS["rule/app"], LISTENERS["listener/app"]) == "RuleId"
    # Two ids appended, a grandchild, the parent itself and the same type word are no children.
    assert r.child_id(LISTENERS["pair"], LISTENERS["loadbalancer/app/"]) is None
    assert r.child_id(LISTENERS["rule/app"], LISTENERS["loadbalancer/app/"]) is None
    assert r.child_id(LISTENERS["loadbalancer/app/"], LISTENERS["loadbalancer/app/"]) is None
    assert r.child_id(FORMATS["group"], FORMATS["lb"]) is None


def test_a_create_of_a_child_of_a_type_its_action_acts_on_names_it_from_the_parents_arn():
    assert r.creates_for("svc:CreateListener", ["loadbalancer/app/"], LISTENERS, members("LoadBalancerArn", "RoleArn")) == [
        {"type": "listener/app", "parent": "loadbalancer/app/", "arn": "LoadBalancerArn"}]
    assert r.creates_for("svc:CreateRule", ["listener/app"], LISTENERS, members("ListenerArn")) == [
        {"type": "rule/app", "parent": "listener/app", "arn": "ListenerArn"}]
    # Only a creating verb, a child named after what it creates, and a type its action does not act on.
    assert r.creates_for("svc:DescribeListeners", ["loadbalancer/app/"], LISTENERS, members("LoadBalancerArn")) == []
    assert r.creates_for("svc:CreateRule", ["loadbalancer/app/"], LISTENERS, members("LoadBalancerArn")) == []
    assert r.creates_for("svc:CreateListener", ["loadbalancer/app/", "listener/app"], LISTENERS, members("LoadBalancerArn")) == []
    # A parent's ARN in no member, or in one of another type, names nothing.
    assert r.creates_for("svc:CreateListener", ["loadbalancer/app/"], LISTENERS, members("Name")) == []
    assert r.creates_for("svc:CreateListener", ["loadbalancer/app/"], LISTENERS, members("RoleArn")) == []


def test_an_arn_inside_a_structure_or_a_lists_structures_is_read_by_its_path():
    shapes = {"String": {"type": "string"}, "Strings": {"type": "list", "member": {"shape": "String"}},
              "Priority": {"type": "structure", "members": {"RuleArn": {"shape": "String"}, "Priority": {"shape": "String"},
                                                            "TargetArns": {"shape": "Strings"}}},
              "Priorities": {"type": "list", "member": {"shape": "Priority"}},
              "Config": {"type": "structure", "members": {"RoleArn": {"shape": "String"}, "Name": {"shape": "String"}}},
              "In": {"type": "structure", "members": {"RulePriorities": {"shape": "Priorities"}, "Config": {"shape": "Config"},
                                                      "ListenerArn": {"shape": "String"}}}}
    models = {"svc": {"metadata": {"protocol": "query"}, "operations": {"SetRulePriorities": {"input": {"shape": "In"}}},
                      "shapes": shapes}}
    found = r.request_members(models, "SetRulePriorities")
    assert found == {"RulePriorities[].RuleArn": "RulePriorities[].RuleArn",
                     "RulePriorities[].TargetArns": "RulePriorities[].TargetArns[]", "Config.RoleArn": "Config.RoleArn",
                     "ListenerArn": "ListenerArn"}
    # Each holds the ARN of any type the action acts on, as a top-level one does, and neither holds the type
    # the way a top-level member named for it does, nor names the parent of a create.
    resources = r.resources_for("svc:SetRulePriorities", ["thing"], FORMATS, found)
    assert {"types": ["thing"], "arn": "RulePriorities[].RuleArn"} in resources
    assert r.creates_for("svc:CreateThing", ["thing"], FORMATS, {"Parts[].ThingArn": "Parts[].ThingArn"}) == []


def test_a_create_naming_others_marks_what_it_makes():
    formats = {"webhook": "arn:${Partition}:svc:${Region}:${Account}:webhook:${WebhookName}",
               "pipeline": "arn:${Partition}:svc:${Region}:${Account}:${PipelineName}",
               "lb": FORMATS["lb"], "lb/app/": FORMATS["lb/app/"]}
    webhook = {"type": "webhook", "fill": {"WebhookName": "webhook.name"}}
    pipeline = {"type": "pipeline", "fill": {"PipelineName": "webhook.targetPipeline"}}
    assert r.with_made([webhook, pipeline], "svc:PutWebhook", formats) == [dict(webhook, made=True), pipeline]
    # A type made by its ARN format's leading resource word, so every load balancer type is made; with every
    # resource made, or none, or for no create, nothing is marked.
    classic = {"type": "lb", "fill": {"LoadBalancerName": "LoadBalancerName"}}
    app = {"type": "lb/app/", "fill": {"LoadBalancerName": "Name"}}
    assert r.with_made([classic, app], "svc:CreateLoadBalancer", formats) == [classic, app]
    assert r.with_made([pipeline], "svc:PutWebhook", formats) == [pipeline]
    assert r.with_made([webhook, pipeline], "svc:DescribeWebhook", formats) == [webhook, pipeline]


def test_stems_drop_the_suffixes_names_carry():
    assert r.stem("TargetGroupArns") == "targetgroup"
    assert r.stem("DBInstanceIdentifier") == "dbinstance"
    # A plural's s goes, from a type's name and a member's alike, so they still meet.
    assert r.related(r.stem("EventBusName"), r.stem("event-bus"))
    assert r.related("autoscalinggroup", "group") and not r.related("ab", "b")


def reference(namespace: str, operations: dict[str, list[str]], resources: dict[str, str]) -> dict:
    """A Service Reference document: each operation its own action, acting on the types given."""
    return {"Name": namespace,
            "Actions": [{"Name": name, "Resources": [{"Name": kind} for kind in types]} for name, types in operations.items()],
            "Operations": [{"Name": name, "AuthorizedActions": [{"Service": namespace, "Name": name}],
                            "SDK": [{"Name": namespace, "Method": name, "Package": "Boto3"}]} for name in operations],
            "Resources": [{"Name": kind, "ARNFormats": [arn]} for kind, arn in resources.items()]}


def model(operations: dict[str, dict[str, str]], protocol: str = "json") -> dict:
    """A botocore model whose operations take the members given, each a string or a list of strings."""
    shapes = {"String": {"type": "string"}, "Strings": {"type": "list", "member": {"shape": "String"}},
              "AccountId": {"type": "string", "pattern": "[0-9]{12}"}}
    ops = {}
    for name, fields in operations.items():
        shapes[name + "In"] = {"type": "structure", "members": {field: {"shape": shape} for field, shape in fields.items()}}
        ops[name] = {"input": {"shape": name + "In"}}
    return {"metadata": {"protocol": protocol}, "operations": ops, "shapes": shapes}


@pytest.fixture
def corrections(monkeypatch):
    monkeypatch.setattr(r, "NAMESPACES", ("svc",))
    monkeypatch.setattr(r, "HAND_BUILT", ("hand",))
    monkeypatch.setattr(r, "NOT_COVERED", {"later": "a later batch"})
    monkeypatch.setattr(r, "CORRECTIONS", {})
    monkeypatch.setattr(r, "UNQUALIFIED", {})
    monkeypatch.setattr(r, "MINTED_IDS", {})
    monkeypatch.setattr(r, "CLIENTS_NOT_READ", {})
    return monkeypatch


def write_models(root, models: dict[str, dict]):
    for name, value in models.items():
        directory = root / name / "2020-01-01"
        directory.mkdir(parents=True)
        (directory / "service-2.json").write_text(json.dumps(value), encoding="utf-8")


DOCUMENTS = [reference("svc", {"GetThing": ["thing"], "ListThings": []}, {"thing": FORMATS["thing"]}),
             reference("other", {"Op": ["x"]}, {"x": "arn:${Partition}:other:::x"}),
             reference("later", {"Op": ["y"]}, {"y": "arn:${Partition}:later:::y"})]


def test_a_built_document_validates_matches_botocore_and_names_the_namespaces_it_does_not_read(corrections, tmp_path):
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}})})
    document = r.build(DOCUMENTS, "test", tmp_path, "botocore test")
    assert r.validate(document) == []
    assert r.derive(document, tmp_path) == []
    assert document["operations"]["svc:GetThing"] == {"action": "svc:GetThing", "types": ["thing"],
                                                      "resources": [{"type": "thing", "fill": {"ThingName": "ThingName"}}]}
    assert document["operations"]["svc:ListThings"]["resources"] == []
    assert document["otherNamespaces"] == ["other"]
    assert json.loads(r.render(document)) == document


def test_a_member_holding_an_account_id_gives_the_account_of_the_types_named_by_variables(corrections, tmp_path):
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String", "registryId": "AccountId"},
                                          "ListThings": {"registryId": "AccountId"}})})
    document = r.build(DOCUMENTS, "test", tmp_path, "botocore test")
    assert r.validate(document) == [] and r.derive(document, tmp_path) == []
    assert document["operations"]["svc:GetThing"]["resources"] == [
        {"type": "thing", "fill": {"ThingName": "ThingName"}, "account": "registryId"}]
    # An ARN carries its own account, and a format without one takes none.
    assert r.with_account([{"types": ["thing"], "arn": "ThingArn"}], FORMATS, ["registryId"]) == [
        {"types": ["thing"], "arn": "ThingArn"}]
    assert r.with_account([{"type": "catalog", "fill": {}}], {"catalog": "arn:${Partition}:svc:::catalog"},
                          ["registryId"]) == [{"type": "catalog", "fill": {}}]
    # Two account members, either of which it could be, give none.
    assert r.with_account([{"type": "thing", "fill": {}}], FORMATS, ["a", "b"]) == [{"type": "thing", "fill": {}}]


def test_a_hand_edited_entry_does_not_match_botocore(corrections, tmp_path):
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}})})
    document = r.build(DOCUMENTS, "test", tmp_path, "botocore test")
    document["operations"]["svc:GetThing"]["resources"] = []
    assert r.validate(document) == []
    assert len(r.derive(document, tmp_path)) == 1


def test_a_type_checked_without_a_qualifier_is_recorded_and_must_be_one_the_file_reads(corrections, tmp_path):
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}})})
    corrections.setattr(r, "UNQUALIFIED", {"svc:thing": "its service checks a qualified ARN without the qualifier"})
    document = r.build(DOCUMENTS, "test", tmp_path, "botocore test")
    assert document["unqualified"] == {"svc": ["thing"]}
    assert r.validate(document) == [] and r.derive(document, tmp_path) == []
    # A hand edit no longer matches, and a type the file has no format for fails the build.
    document["unqualified"] = {}
    assert r.validate(document) == [] and len(r.derive(document, tmp_path)) == 1
    corrections.setattr(r, "UNQUALIFIED", {"svc:gone": "no longer there"})
    with pytest.raises(ValueError, match="svc:gone"):
        r.build(DOCUMENTS, "test", tmp_path, "botocore test")


def test_a_type_whose_service_mints_an_id_after_its_name_is_recorded_and_must_be_one_the_file_reads(corrections, tmp_path):
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}})})
    corrections.setattr(r, "MINTED_IDS", {"svc:thing": "its service follows the name with an id it mints"})
    document = r.build(DOCUMENTS, "test", tmp_path, "botocore test")
    assert document["mintedIds"] == {"svc": ["thing"]}
    assert r.validate(document) == [] and r.derive(document, tmp_path) == []
    # A hand edit no longer matches, and a type the file has no format for fails the build.
    document["mintedIds"] = {}
    assert r.validate(document) == [] and len(r.derive(document, tmp_path)) == 1
    corrections.setattr(r, "MINTED_IDS", {"svc:gone": "no longer there"})
    with pytest.raises(ValueError, match="svc:gone"):
        r.build(DOCUMENTS, "test", tmp_path, "botocore test")


def test_a_client_not_read_gives_no_members_and_aws_listing_it_is_no_untrue_claim(corrections, tmp_path):
    corrections.setattr(r, "CLIENTS_NOT_READ", {"svcv2": "a second API Floci does not serve"})
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}}),
                            "svcv2": model({"GetThing": {"ThingArn": "String"}})})
    documents = [dict(DOCUMENTS[0], Operations=[dict(op, SDK=op["SDK"] + [{"Name": "svcv2", "Package": "Boto3"}])
                                                for op in DOCUMENTS[0]["Operations"]])] + DOCUMENTS[1:]
    document = r.build(documents, "test", tmp_path, "botocore test")
    assert document["namespaces"] == {"svc": ["svc"]} and document["clientsNotRead"] == {"svcv2": "a second API Floci does not serve"}
    assert document["operations"]["svc:GetThing"]["resources"] == [{"type": "thing", "fill": {"ThingName": "ThingName"}}]
    assert r.validate(document) == [] and r.derive(document, tmp_path) == []
    upstream = r.checked_actions(documents)
    assert r.verify_against(document, upstream, {"svc": {"thing": FORMATS["thing"]}}) == ([], [])
    document["clientsNotRead"] = {}
    assert len(r.derive(document, tmp_path)) == 1


def test_a_member_taking_an_arn_before_a_name_is_read_as_the_variable_of_that_types_arn():
    shapes = {"String": {"type": "string"},
              "BusNameOrArn": {"type": "string", "pattern": "(arn:aws[\\w-]*:svc:[a-z]+-[a-z]+-[\\w-]+:[0-9]{12}:bus\\/)?[/\\.\\-_A-Za-z0-9]+"},
              "In": {"type": "structure", "members": {"Name": {"shape": "String"}, "BusName": {"shape": "BusNameOrArn"}}}}
    models = {"svc": {"metadata": {"protocol": "json"}, "operations": {"PutRule": {"input": {"shape": "In"}}}, "shapes": shapes}}
    assert r.name_or_arn_members(models, "PutRule") == {"BusName": "bus"}
    formats = {"bus": "arn:${Partition}:svc:${Region}:${Account}:bus/${BusName}",
               "rule": "arn:${Partition}:svc:${Region}:${Account}:rule/${BusName}/${RuleName}"}
    assert r.named_by("bus", formats) == "bus" and r.named_by("rule", formats) is None
    rules = [{"type": "rule", "fill": {"BusName": "BusName", "RuleName": "Name"}},
             {"type": "rule", "fill": {"RuleName": "Name"}, "when": {"BusName": ["default"]}},
             {"types": ["rule"], "arn": "RuleArn"}]
    assert r.with_arn_of(rules, formats, {"BusName": "bus"}) == [
        dict(rules[0], arnOf={"BusName": "bus"}), dict(rules[1], arnOf={"BusName": "bus"}), rules[2]]


def test_a_field_of_each_of_a_lists_structures_taking_an_arn_before_a_name_is_read_by_its_path():
    shapes = {"String": {"type": "string"},
              "BusNameOrArn": {"type": "string", "pattern": "(arn:aws[\\w-]*:svc:[a-z]+-[a-z]+-[\\w-]+:[0-9]{12}:bus\\/)?[\\.\\-_A-Za-z0-9]+"},
              "Entry": {"type": "structure", "members": {"Source": {"shape": "String"}, "BusName": {"shape": "BusNameOrArn"}}},
              "Entries": {"type": "list", "member": {"shape": "Entry"}},
              "Config": {"type": "structure", "members": {"BusName": {"shape": "BusNameOrArn"}}},
              "In": {"type": "structure", "members": {"Entries": {"shape": "Entries"}, "Config": {"shape": "Config"}}}}
    models = {"svc": {"metadata": {"protocol": "json"}, "operations": {"PutEvents": {"input": {"shape": "In"}}}, "shapes": shapes}}
    # Only a list's structures give one value for each, so a lone structure's field is not read.
    assert r.name_or_arn_members(models, "PutEvents") == {"Entries[].BusName": "bus"}
    formats = {"bus": "arn:${Partition}:svc:${Region}:${Account}:bus/${BusName}"}
    buses = [{"type": "bus", "fill": {"BusName": "Entries[].BusName"}, "absent": {"Entries[].BusName": "default"}}]
    assert r.with_arn_of(buses, formats, r.name_or_arn_members(models, "PutEvents")) == [
        dict(buses[0], arnOf={"Entries[].BusName": "bus"})]


def test_a_correction_for_an_operation_the_file_does_not_read_fails(corrections, tmp_path):
    corrections.setattr(r, "CORRECTIONS", {"svc:Gone": ([], "no longer there")})
    with pytest.raises(ValueError, match="svc:Gone"):
        r.build(DOCUMENTS, "test", tmp_path, "botocore test")


def good_document() -> dict:
    return {"namespaces": {"svc": ["svc"]}, "clientsNotRead": {"svcv2": "not served"}, "handBuilt": ["hand"],
            "notCovered": {"later": "a later batch"},
            "otherNamespaces": ["other"], "arnFormats": {"svc": {"thing": FORMATS["thing"], "group": FORMATS["group"]}},
            "unqualified": {"svc": ["thing"]}, "mintedIds": {"svc": ["thing"]},
            "operations": {
                "svc:A": {"action": "svc:A", "types": ["thing"], "resources": [{"types": ["thing"], "arn": "ThingArn"}]},
                "svc:B": {"action": "svc:B", "types": ["group"], "resources": [{"type": "group", "fill": {"GroupName": "Names[]"}}]},
                "svc:C": {"action": "svc:C", "types": [], "resources": []}}}


def test_a_well_formed_document_validates():
    assert r.validate(good_document()) == []
    document = good_document()
    document["operations"]["svc:B"]["resources"][0]["when"] = {"Type": ["application", None]}
    document["operations"]["svc:B"]["resources"][0]["account"] = "registryId"
    document["operations"]["svc:B"]["resources"][0]["unless"] = {"Bus": ["default"]}
    document["operations"]["svc:B"]["resources"][0]["absent"] = {"Bus": "default"}
    document["operations"]["svc:B"]["resources"][0]["arnOf"] = {"Bus": "thing"}
    document["operations"]["svc:A"]["resources"][0]["before"] = "#"
    # A member filling a variable read up to a character, as a CodeBuild build id names its project before ':'.
    document["operations"]["svc:B"]["resources"][0]["before"] = ":"
    assert r.validate(document) == []
    assert r.validate(with_a_create()) == []
    # A field of each of a list's structures fills a variable, read as given when absent, or as an ARN.
    document = good_document()
    document["operations"]["svc:B"]["resources"][0].update(
        fill={"GroupName": "Groups[].Name"}, absent={"Groups[].Name": "default"}, arnOf={"Groups[].Name": "thing"})
    assert r.validate(document) == []
    # A field of a structure fills a variable, read as one value, and a variable no member fills may be fixed.
    document = good_document()
    document["operations"]["svc:B"]["resources"][0].update(
        fill={"GroupName": "Group.Name"}, absent={"Group.Name": "default"}, fixed={"GroupId": "g-1"}, made=True)
    document["operations"]["svc:A"]["resources"][0]["made"] = True
    assert r.validate(document) == []


def with_a_create() -> dict:
    document = good_document()
    document["arnFormats"]["svc"]["part"] = "arn:${Partition}:svc:${Region}:${Account}:part/${ThingName}/${PartId}"
    document["operations"]["svc:A"]["creates"] = [{"type": "part", "parent": "thing", "arn": "ThingArn"}]
    return document


@pytest.mark.parametrize("change, problem", [
    (lambda d: d["operations"]["svc:A"]["resources"][0].update(types=["pool"]), "not resource types"),
    (lambda d: d["operations"]["svc:A"]["resources"][0].update(arn="a.b.c"), "no member"),
    (lambda d: d["operations"]["svc:A"]["resources"][0].update(arn="a[][]"), "no member"),
    (lambda d: d["operations"]["svc:B"]["resources"][0]["fill"].update(GroupName="Group.Names[]"), "no variables"),
    (lambda d: d["operations"]["svc:B"]["resources"][0]["fill"].update(GroupName="A.B.Name"), "no variables"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(fixed={"Other": "x"}), "fixes"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(fixed={"GroupName": "x"}), "fixes"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(fixed={"GroupId": ""}), "fixes"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(fixed={"GroupId": 1}), "fixes"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(fixed=["GroupId"]), "fixes"),
    (lambda d: d["operations"]["svc:A"]["resources"][0].update(fixed={"GroupId": "x"}), "neither an ARN member"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(made=False), "not true"),
    (lambda d: d["operations"]["svc:A"]["resources"][0].update(made="true"), "not true"),
    (lambda d: d["operations"]["svc:B"]["resources"][0]["fill"].update(GroupName="Groups[].Names[]"), "no variables"),
    (lambda d: d["operations"]["svc:B"]["resources"][0]["fill"].update(GroupId="Groups[].Id"), "more than one list"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(absent={"Groups[].Name": "default"}), "absent members"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(arnOf={"Groups[].Name": "thing"}), "types of one variable"),
    (lambda d: d["operations"]["svc:B"]["resources"][0]["fill"].update(Other="Name"), "no variables"),
    (lambda d: d["operations"]["svc:B"]["resources"][0]["fill"].update(GroupId="Ids[]"), "more than one list"),
    (lambda d: d["operations"]["svc:A"].update(action="other:A"), "no action of its namespace"),
    (lambda d: d["operations"]["svc:A"].pop("types"), "an action, its types"),
    (lambda d: d["arnFormats"]["svc"].update(thing="*"), "arnFormats"),
    (lambda d: d["operations"].update({"other:X": {"action": "other:X", "types": [], "resources": []}}),
     "namespace the file reads"),
    (lambda d: d["notCovered"].update(later=""), "reason"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(when={"Type": []}), "no member's values"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(when={"Types[]": ["a"]}), "no member's values"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(when={"Type": [1]}), "no member's values"),
    (lambda d: d["operations"]["svc:A"]["resources"][0].update(before="##"), "no one character"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(before="##"), "no one character"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(before=":", fill={"GroupName": "Names[]"}) or
     d["operations"]["svc:B"]["resources"][0].update(before=1), "no one character"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(account="Ids[]"), "no account of its ARN"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(account="a.b"), "no account of its ARN"),
    (lambda d: d["arnFormats"]["svc"].update(group="arn:${Partition}:svc:::group/${GroupName}")
     or d["operations"]["svc:B"]["resources"][0].update(account="registryId"), "no account of its ARN"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(unless={"Type": []}), "no member's values"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(absent={"Bus": ""}), "absent members"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(absent={"Bus[]": "default"}), "absent members"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(arnOf={"Bus": "pool"}), "types of one variable"),
    (lambda d: d["arnFormats"]["svc"].update(group="arn:${Partition}:svc:${Region}:${Account}:group/${GroupName}/${Id}")
     or d["operations"]["svc:B"]["resources"][0].update(arnOf={"Bus": "group"}), "types of one variable"),
    (lambda d: d["operations"]["svc:A"]["resources"][0].update(unless={"Type": ["a"]}), "neither an ARN member"),
    (lambda d: d["clientsNotRead"].update(svcv2=""), "clientsNotRead"),
    (lambda d: d.update(unqualified={"svc": ["pool"]}), "unqualified"),
    (lambda d: d.update(unqualified={"other": ["thing"]}), "unqualified"),
    (lambda d: d.update(unqualified={"svc": []}), "unqualified"),
    (lambda d: d.pop("unqualified"), "unqualified"),
    (lambda d: d.update(mintedIds={"svc": ["group"]}), "mintedIds"),
    (lambda d: d.update(mintedIds={"other": ["thing"]}), "mintedIds"),
    (lambda d: d.update(mintedIds={"svc": []}), "mintedIds"),
    (lambda d: d.pop("mintedIds"), "mintedIds"),
])
def test_a_malformed_document_is_rejected(change, problem):
    document = good_document()
    change(document)
    assert any(problem in p for p in r.validate(document)), r.validate(document)


@pytest.mark.parametrize("change, problem", [
    (lambda d: d["operations"]["svc:A"]["creates"][0].update(parent="group"), "no parent of it"),
    (lambda d: d["operations"]["svc:A"]["creates"][0].update(type="group"), "no parent of it"),
    (lambda d: d["operations"]["svc:A"]["creates"][0].update(arn="a.b"), "no type made under"),
    (lambda d: d["operations"]["svc:A"]["creates"][0].pop("parent"), "no type made under"),
    (lambda d: d["arnFormats"]["svc"].update(part="arn:${Partition}:svc:${Region}:${Account}:part/${PartId}"),
     "no parent of it"),
])
def test_a_malformed_create_is_rejected(change, problem):
    document = with_a_create()
    change(document)
    assert any(problem in p for p in r.validate(document)), r.validate(document)


def test_verify_fails_on_an_action_type_or_format_aws_does_not_give_and_reports_an_added_operation():
    document = good_document()
    upstream = {"svc:A": {"action": "svc:A", "types": ["thing"], "clients": ["svc"]},
                "svc:B": {"action": "svc:Other", "types": ["group"], "clients": ["svc"]},
                "svc:C": {"action": "svc:C", "types": ["thing"], "clients": ["svc"]},
                "svc:D": {"action": "svc:D", "types": [], "clients": ["svc"]}}
    untrue, added = r.verify_against(document, upstream, {"svc": {"thing": FORMATS["thing"], "group": "arn:${Partition}:svc:::g"}})
    assert any("svc:B" in u for u in untrue) and any("svc:C" in u for u in untrue)
    assert any("group" in u and "ARN format" in u for u in untrue)
    assert added == ["svc:D"]


def test_every_correction_is_the_vendored_entry_of_an_operation_the_file_reads():
    operations = json.loads(r.OUTPUT.read_text(encoding="utf-8"))["operations"]
    assert r.CORRECTIONS
    for operation, (resources, reason) in r.CORRECTIONS.items():
        assert operation in operations and reason, operation
        # A resource's account and the ARNs its members may give are botocore's, and whether a create makes it the
        # rule's, on top of the correction.
        assert [{key: value for key, value in resource.items() if key not in ("account", "arnOf", "made")}
                for resource in operations[operation]["resources"]] == resources, operation


def test_a_correction_gives_the_operation_its_resources(corrections):
    corrections.setattr(r, "CORRECTIONS", {"svc:GetThing": ([{"type": "thing", "fill": {}}], "a reason")})
    entry = r.entry_for("svc:GetThing", {"action": "svc:GetThing", "types": ["thing"]}, FORMATS, {})
    assert entry["resources"] == [{"type": "thing", "fill": {}}]


def test_the_vendored_file_is_well_formed():
    document = json.loads(r.OUTPUT.read_text(encoding="utf-8"))
    assert r.validate(document) == []
    operations = document["operations"]
    assert operations["elasticloadbalancing:CreateTargetGroup"]["resources"] == [
        {"type": "targetgroup", "fill": {"TargetGroupName": "Name"}}]
    assert operations["states:DescribeStateMachine"]["resources"] == [{"types": ["statemachine"], "arn": "stateMachineArn"}]
    assert operations["ecr:DescribeRepositories"]["resources"] == [
        {"type": "repository", "fill": {"RepositoryName": "repositoryNames[]"}, "account": "registryId"}]
    # Its request has no registryId, so the repositories are the caller's.
    assert operations["ecr:BatchGetRepositoryScanningConfiguration"]["resources"] == [
        {"type": "repository", "fill": {"RepositoryName": "repositoryNames[]"}}]
    assert operations["cognito-idp:DescribeUserPool"]["resources"] == [{"type": "userpool", "fill": {"UserPoolId": "UserPoolId"}}]
    # StartExecution's name is the execution's, so only its state machine ARN is read, up to a test case's #.
    assert operations["states:StartExecution"]["resources"] == [
        {"types": ["statemachine"], "arn": "stateMachineArn", "before": "#"}]
    # An ARN inside a structure or each of a list's structures.
    assert operations["elasticloadbalancing:SetRulePriorities"]["resources"] == [
        {"types": ["listener-rule/app", "listener-rule/net"], "arn": "RulePriorities[].RuleArn"}]
    assert operations["states:CreateStateMachineAlias"]["resources"] == [
        {"types": ["statemachine"], "arn": "routingConfiguration[].stateMachineVersionArn"}]
    # An EventBridge rule is on the bus EventBusName names, by name or ARN, or the default one when it names none.
    assert operations["events:DescribeRule"]["resources"] == [
        {"type": "rule-on-default-event-bus", "fill": {"RuleName": "Name"}, "when": {"EventBusName": ["default"]},
         "absent": {"EventBusName": "default"}, "arnOf": {"EventBusName": "event-bus"}},
        {"type": "rule-on-custom-event-bus", "fill": {"EventBusName": "EventBusName", "RuleName": "Name"},
         "unless": {"EventBusName": ["default"]}, "absent": {"EventBusName": "default"}, "arnOf": {"EventBusName": "event-bus"}}]
    assert operations["events:DescribeEventBus"]["resources"] == [
        {"type": "event-bus", "fill": {"EventBusName": "Name"}, "absent": {"Name": "default"}, "arnOf": {"Name": "event-bus"}}]
    # Each PutEvents entry is on the bus it names, by name or ARN, or the default one when it names none.
    assert operations["events:PutEvents"]["resources"] == [
        {"type": "event-bus", "fill": {"EventBusName": "Entries[].EventBusName"},
         "absent": {"Entries[].EventBusName": "default"}, "arnOf": {"Entries[].EventBusName": "event-bus"}}]
    assert document["namespaces"]["events"] == ["events"] and "eventbridgev2" in document["clientsNotRead"]
    # A state machine's version or alias is checked on the state machine.
    assert document["unqualified"] == {"states": ["statemachine"]}
    # A listener is made under a load balancer, and a rule under a listener.
    assert operations["elasticloadbalancing:CreateListener"]["creates"] == [
        {"type": f"listener/{kind}", "parent": f"loadbalancer/{kind}/", "arn": "LoadBalancerArn"}
        for kind in ("app", "gwy", "net")]
    assert operations["elasticloadbalancing:CreateRule"]["creates"] == [
        {"type": f"listener-rule/{kind}", "parent": f"listener/{kind}", "arn": "ListenerArn"} for kind in ("app", "net")]
    assert set(document["handBuilt"]) >= {"lambda", "s3", "sqs"}
    # The v2 API's Type picks the load balancer it creates, application when it gives none.
    assert operations["elasticloadbalancing:CreateLoadBalancer"]["resources"][1] == {
        "type": "loadbalancer/app/", "fill": {"LoadBalancerName": "Name"}, "when": {"Type": ["application", None]}}
