"""Tests for regen_iam_tag_on_create.

Run with: pytest tools/aws -q  (or: make iam-tag-on-create-test)
"""
from __future__ import annotations

import json

import pytest

import regen_iam_tag_on_create as r


def reference(namespace: str, operations: dict[str, list[str]], clients: dict[str, list[str]] | None = None,
              tagging: dict[str, list[str]] | None = None, keys: dict[str, list[str]] | None = None) -> dict:
    """A Service Reference document. Each operation maps to the actions it lists beside its own; `tagging`
    names the namespace's actions that only tag, each with its condition keys, `keys` an operation's own
    action's condition keys, and `clients` an operation's SDK clients, the namespace by default."""
    tagging = {"TagResource": [], "UntagResource": []} if tagging is None else tagging
    actions = [{"Name": name, "ActionConditionKeys": keys,
                "Annotations": {"Properties": {"IsTaggingOnly": True}}} for name, keys in tagging.items()]
    actions += [{"Name": name, "ActionConditionKeys": (keys or {}).get(name, []),
                 "Annotations": {"Properties": {"IsTaggingOnly": False}}}
                for name in operations if name not in tagging]
    entries = []
    for name, beside in operations.items():
        authorized = [{"Service": namespace, "Name": name}]
        authorized += [{"Service": action.split(":")[0], "Name": action.split(":")[1]} for action in beside]
        sdk = [{"Name": c, "Method": name, "Package": "Boto3"} for c in (clients or {}).get(name, [namespace])]
        entries.append({"Name": name, "AuthorizedActions": authorized, "SDK": sdk})
    return {"Name": namespace, "Actions": actions, "Operations": entries}


def model(operations: dict[str, dict], shapes: dict[str, dict], protocol: str = "json") -> dict:
    """A botocore model whose operations take the input shape each names."""
    return {"metadata": {"protocol": protocol},
            "operations": {name: {"input": {"shape": shape}} for name, shape in operations.items()},
            "shapes": {"String": {"type": "string"}, **shapes}}


def structure(**members) -> dict:
    return {"type": "structure", "members": {name: ({"shape": spec} if isinstance(spec, str) else spec)
                                             for name, spec in members.items()}}


TAG_SHAPES = {
    "TagList": {"type": "list", "member": {"shape": "Tag"}},
    "Tag": structure(Key="String", Value="String"),
    "TagMap": {"type": "map", "key": {"shape": "String"}, "value": {"shape": "String"}},
}


@pytest.fixture
def corrections(monkeypatch):
    """No corrections at all, so a test sees the rule alone and sets what it needs."""
    for name in ("BY_HAND", "NOT_READ", "EXCLUDED_MEMBERS", "QUERY_MEMBERS"):
        monkeypatch.setattr(r, name, {})
    monkeypatch.setattr(r, "NAMESPACES", ("svc",))
    return monkeypatch


# --------------------------------------------------------------------------- #
# The Service Reference
# --------------------------------------------------------------------------- #
def test_only_operations_listing_an_action_that_adds_tags_are_kept_with_their_clients():
    found = r.tag_on_create_operations([reference(
        "svc", {"CreateThing": ["svc:TagResource"], "UpdateThing": ["svc:UntagResource"], "GetThing": [],
                "TagResource": []},
        {"CreateThing": ["svc", "svcv2"]})])
    assert found == {"svc:CreateThing": {"tagging": ["svc:TagResource"], "createAction": False,
                                         "requestTags": False, "clients": ["svc", "svcv2"]}}


def test_an_action_the_reference_does_not_mark_as_only_tagging_is_no_tagging_action():
    document = reference("svc", {"CreateThing": ["svc:TagSession"]})
    assert r.tag_on_create_operations([document]) == {}


def test_a_tagging_action_of_another_namespace_counts():
    found = r.tag_on_create_operations([reference("svc", {"CreateThing": ["other:TagResource"]}, tagging={}),
                                        reference("other", {}, tagging={"TagResource": []})])
    assert found["svc:CreateThing"]["tagging"] == ["other:TagResource"]


def test_a_tagging_action_taking_its_namespace_s_create_action_key_marks_the_operation():
    document = reference("svc", {"RunThing": ["svc:CreateTags"]},
                         tagging={"CreateTags": ["svc:CreateAction", "svc:Region"]})
    assert r.tag_on_create_operations([document])["svc:RunThing"]["createAction"] is True


def test_an_own_action_taking_the_request_s_tags_marks_the_operation():
    document = reference("svc", {"CreateThing": ["svc:TagResource"], "CreateOther": ["svc:TagResource"]},
                         keys={"CreateThing": ["aws:RequestTag/${TagKey}", "aws:TagKeys"]})
    found = r.tag_on_create_operations([document])
    assert found["svc:CreateThing"]["requestTags"] is True
    assert found["svc:CreateOther"]["requestTags"] is False


def test_a_resource_s_keys_count_whether_the_action_names_them_or_its_namespace_does():
    document = reference("svc", {"CreateThing": ["svc:TagResource"], "CreateOther": ["svc:TagResource"]})
    document["Actions"][-2]["Resources"] = [{"Name": "thing", "ConditionKeys": ["aws:TagKeys"]}]
    document["Actions"][-1]["Resources"] = [{"Name": "other"}]
    document["Resources"] = [{"Name": "other", "ConditionKeys": ["aws:RequestTag/${TagKey}"]}]
    found = r.tag_on_create_operations([document])
    assert found["svc:CreateThing"]["requestTags"] is True
    assert found["svc:CreateOther"]["requestTags"] is True


def test_an_operation_with_no_action_of_its_name_goes_by_the_other_actions_of_its_namespace():
    document = reference("svc", {"CreateBudget": ["svc:ModifyBudget", "svc:TagResource"]},
                         keys={"ModifyBudget": ["aws:TagKeys"]})
    document["Actions"] = [action for action in document["Actions"] if action["Name"] != "CreateBudget"]
    document["Actions"].append({"Name": "ModifyBudget", "ActionConditionKeys": ["aws:TagKeys"],
                                "Annotations": {"Properties": {"IsTaggingOnly": False}}})
    assert r.tag_on_create_operations([document])["svc:CreateBudget"]["requestTags"] is True
    # The action of its name is found in any case.
    document = reference("svc", {"CreateACL": ["svc:TagResource"]})
    document["Actions"].append({"Name": "CreateAcl", "ActionConditionKeys": ["aws:RequestTag/${TagKey}"],
                                "Annotations": {"Properties": {"IsTaggingOnly": False}}})
    document["Actions"] = [action for action in document["Actions"] if action["Name"] != "CreateACL"]
    assert r.tag_on_create_operations([document])["svc:CreateACL"]["requestTags"] is True


def test_a_rest_operation_the_reference_does_not_list_needs_the_tagging_of_the_operations_needing_its_action(
        monkeypatch):
    monkeypatch.setattr(r, "REST_MODELS", {"svc": "svc", "svcv2": "svc"})
    document = reference("svc", {"CreateLegacyThing": ["svc:CreateThing", "svc:PutThing", "other:DescribeThing",
                                                       "svc:AddTags"], "PutThing": []}, tagging={"AddTags": []})
    document["Actions"] += [{"Name": name, "ActionConditionKeys": keys,
                             "Annotations": {"Properties": {"IsTaggingOnly": False}}}
                            for name, keys in (("CreateThing", ["aws:RequestTag/${TagKey}"]), ("DescribeThing", []))]
    found = r.with_rest_operations(r.tag_on_create_operations([document]), [document], {
        "svcv2": {"CreateLegacyThing", "CreateThing", "PutThing", "DescribeThing", "AddTags", "Unknown"}})
    # The action of its name needs the legacy create's tagging action; the legacy create gains the client.
    assert found["svc:CreateThing"] == {"tagging": ["svc:AddTags"], "createAction": False, "requestTags": True,
                                        "clients": ["svcv2"]}
    assert found["svc:CreateLegacyThing"]["clients"] == ["svc", "svcv2"]
    # Listed with no tagging action, listed for another namespace only, the tagging action itself, or of no action:
    # left out.
    assert set(found) == {"svc:CreateLegacyThing", "svc:CreateThing"}


def test_an_unusable_namespace_is_rejected():
    with pytest.raises(ValueError, match="not usable"):
        r.tag_on_create_operations([{"Name": "Not A Namespace", "Operations": []}])


# --------------------------------------------------------------------------- #
# botocore: the members that carry the tags
# --------------------------------------------------------------------------- #
def test_a_tag_list_and_a_tag_map_are_read_with_their_key_and_value_names():
    m = model({"Op": "In"}, {
        "In": structure(Tags="TagList", ResourceTags="KmsTags", UserPoolTags="TagMap", Name="String"),
        "KmsTags": {"type": "list", "member": {"shape": "KmsTag"}},
        "KmsTag": structure(TagKey="String", TagValue="String"),
        **TAG_SHAPES,
    })
    assert r.tag_members(m, "Op")[0] == [
        {"member": "Tags[]", "key": "Key", "value": "Value"},
        {"member": "ResourceTags[]", "key": "TagKey", "value": "TagValue"},
        {"member": "UserPoolTags{}"},
    ]


def test_tag_specifications_are_read_through_their_list():
    m = model({"Op": "In"}, {
        "In": structure(TagSpecifications={"shape": "Specs", "locationName": "TagSpecification"}),
        "Specs": {"type": "list", "member": {"shape": "Spec", "locationName": "item"}},
        "Spec": structure(ResourceType="String", Tags={"shape": "Ec2Tags", "locationName": "Tag"}),
        "Ec2Tags": {"type": "list", "member": {"shape": "Ec2Tag", "locationName": "item"}},
        "Ec2Tag": structure(Key={"shape": "String", "locationName": "key"},
                            Value={"shape": "String", "locationName": "value"}),
    }, protocol="ec2")
    assert r.tag_members(m, "Op")[0] == [
        {"member": "TagSpecification[item].Tag[item]", "key": "Key", "value": "Value", "resourceType": "ResourceType"}]


def test_a_specification_naming_no_resource_type_gives_none():
    m = model({"Op": "In"}, {"In": structure(TagSpecifications="Specs"), "Specs": {"type": "list", "member": {"shape": "Spec"}},
                             "Spec": structure(Tags="TagList"), **TAG_SHAPES})
    assert r.tag_members(m, "Op")[0] == [{"member": "TagSpecifications[].Tags[]", "key": "Key", "value": "Value"}]


def test_each_resource_type_s_arn_keeps_the_partition_region_and_account_and_leaves_the_rest_open():
    document = {"Name": "svc", "Resources": [
        {"Name": "thing", "ARNFormats": ["arn:${Partition}:svc:${Region}:${Account}:thing/${ThingId}"]},
        {"Name": "image", "ARNFormats": ["arn:${Partition}:svc:${Region}::image/${ImageId}"]},
        {"Name": "pair", "ARNFormats": ["arn:${Partition}:svc:${Region}:${Account}:a/${A}/b/${B}", "arn:other"]},
        {"Name": "nameless"}]}
    assert r.resource_arns([document, {"Name": "other", "Resources": []}], {"svc"}) == {"svc": {
        "image": "arn:${Partition}:svc:${Region}::image/*",
        "pair": "arn:${Partition}:svc:${Region}:${Account}:a/*/b/*",
        "thing": "arn:${Partition}:svc:${Region}:${Account}:thing/*"}}


def test_an_ec2_query_name_wins_over_its_location_name():
    m = model({"Op": "In"}, {"In": structure(Tags={"shape": "TagList", "locationName": "tags", "queryName": "Tag"}),
                             **TAG_SHAPES}, protocol="ec2")
    assert r.tag_members(m, "Op")[0] == [{"member": "Tag[]", "key": "Key", "value": "Value"}]


def test_members_named_otherwise_nested_deeper_or_holding_no_tags_are_not_read():
    m = model({"Op": "In"}, {
        "In": structure(TagFilters="TagList", Config="Config", Tag="TagList", Tags="String",
                        TagKeys="Keys"),
        "Config": structure(Tags="TagList"),
        "Keys": {"type": "list", "member": {"shape": "String"}},
        **TAG_SHAPES,
    })
    assert r.tag_members(m, "Op") == ([], r.NO_TAGS)


def test_a_member_bound_to_the_uri_a_header_or_the_query_string_is_not_read():
    m = model({"Op": "In"}, {"In": structure(Tags={"shape": "TagMap", "location": "header"},
                                             tags="TagMap"), **TAG_SHAPES}, protocol="rest-json")
    assert r.tag_members(m, "Op")[0] == [{"member": "tags{}"}]


@pytest.mark.parametrize("protocol", ["rest-xml"])
def test_a_model_whose_requests_are_not_read_has_no_members(protocol):
    m = model({"Op": "In"}, {"In": structure(Tags="TagList"), **TAG_SHAPES}, protocol=protocol)
    assert r.tag_members(m, "Op") == ([], r.UNREADABLE_PROTOCOL)


# --------------------------------------------------------------------------- #
# One operation's entry
# --------------------------------------------------------------------------- #
SIMPLE = {"svc": model({"CreateThing": "In"}, {"In": structure(Tags="TagList"), **TAG_SHAPES})}


def upstream(*tagging: str, create_action: bool = False, request_tags: bool = False) -> dict:
    return {"tagging": list(tagging), "createAction": create_action, "requestTags": request_tags, "clients": ["svc"]}


def test_an_operation_reads_its_members_for_its_one_tagging_action(corrections):
    assert r.entry_for("svc:CreateThing", upstream("svc:TagResource", create_action=True, request_tags=True),
                       SIMPLE) == {"tagging": ["svc:TagResource"], "createAction": True, "requestTags": True,
                                   "tags": [{"member": "Tags[]", "key": "Key", "value": "Value"}]}


def test_a_modeled_operation_without_tag_members_derives_no_tags(corrections):
    models = {"svc": model({"CreateThing": "In"}, {"In": structure(Name="String")})}
    assert r.entry_for("svc:CreateThing", upstream("svc:TagResource"), models) == {
        "tagging": ["svc:TagResource"], "notRead": r.NO_TAGS}


def test_an_unreadable_client_does_not_derive_no_tags_from_another_client(corrections):
    models = {
        "svc": model({"CreateThing": "In"}, {"In": structure(Name="String")}),
        "svc-xml": model({"CreateThing": "In"}, {"In": structure(Tags="TagList"), **TAG_SHAPES},
                         protocol="rest-xml"),
    }
    assert r.entry_for("svc:CreateThing", upstream("svc:TagResource"), models) == {
        "tagging": ["svc:TagResource"], "notRead": r.UNREADABLE_PROTOCOL}


def test_an_unreadable_client_does_not_hide_another_client_s_tag_members(corrections):
    models = {
        "svc": model({"CreateThing": "In"}, {"In": structure(Tags="TagList"), **TAG_SHAPES}),
        "svc-xml": model({"CreateThing": "In"}, {"In": structure(Tags="TagList"), **TAG_SHAPES},
                         protocol="rest-xml"),
    }
    assert r.entry_for("svc:CreateThing", upstream("svc:TagResource"), models) == {
        "tagging": ["svc:TagResource"], "tags": [{"member": "Tags[]", "key": "Key", "value": "Value"}]}


def test_an_opaque_client_does_not_hide_another_client_s_tag_members(corrections):
    opaque = model({"CreateThing": "In"}, {"In": structure(Tags="TagList"), **TAG_SHAPES})
    opaque["shapes"]["In"]["payload"] = "Tags"
    models = {"svc": opaque, "svcv2": SIMPLE["svc"]}
    assert r.entry_for("svc:CreateThing", upstream("svc:TagResource"), models) == {
        "tagging": ["svc:TagResource"], "tags": [{"member": "Tags[]", "key": "Key", "value": "Value"}]}


def test_an_unreadable_client_derives_its_reason_without_a_hand_entry(corrections):
    models = {"svc-xml": model({"CreateThing": "In"}, {"In": structure(Tags="TagList"), **TAG_SHAPES},
                               protocol="rest-xml")}
    assert r.entry_for("svc:CreateThing", upstream("svc:TagResource"), models) == {
        "tagging": ["svc:TagResource"], "notRead": r.UNREADABLE_PROTOCOL}


def test_an_opaque_payload_does_not_derive_no_tags(corrections):
    opaque = model({"CreateThing": "In"}, {"In": structure(Tags="TagList"), **TAG_SHAPES})
    opaque["shapes"]["In"]["payload"] = "Tags"
    assert r.entry_for("svc:CreateThing", upstream("svc:TagResource"), {"svc": opaque}) == {
        "tagging": ["svc:TagResource"], "notRead": r.OPAQUE_PAYLOAD}
    tagless = model({"CreateThing": "In"}, {"In": structure(Name="String")})
    assert r.entry_for("svc:CreateThing", upstream("svc:TagResource"), {"svc": tagless, "svcv2": opaque}) == {
        "tagging": ["svc:TagResource"], "notRead": r.OPAQUE_PAYLOAD}


def test_an_operation_absent_from_the_pinned_models_derives_not_in_botocore(corrections):
    models = {"svc": model({"CreateOther": "In"}, {"In": structure(Tags="TagList"), **TAG_SHAPES})}
    assert r.entry_for("svc:CreateThing", upstream("svc:TagResource"), models) == {
        "tagging": ["svc:TagResource"], "notRead": r.NOT_IN_BOTOCORE}


def test_a_modeled_operation_without_input_derives_no_tags(corrections):
    no_input = model({}, {})
    no_input["operations"]["CreateThing"] = {}
    assert r.tag_members(no_input, "CreateThing") == ([], r.NO_TAGS)
    assert r.entry_for("svc:CreateThing", upstream("svc:TagResource"), {"svc": no_input}) == {
        "tagging": ["svc:TagResource"], "notRead": r.NO_TAGS}


def test_a_malformed_modeled_operation_is_not_reported_as_absent(corrections):
    malformed = model({}, {})
    malformed["operations"]["CreateThing"] = None
    with pytest.raises(ValueError, match="botocore operation is not an object"):
        r.entry_for("svc:CreateThing", upstream("svc:TagResource"), {"svc": malformed})


@pytest.mark.parametrize("operation, client, member", [
    ("ecs:CreateGateway", "ecs", {"member": "tags[]", "key": "key", "value": "value"}),
    ("rds:CreateDBCapacityReservation", "rds", {"member": "Tags[Tag]", "key": "Key", "value": "Value"}),
])
def test_a_stale_no_tags_verdict_cannot_hide_modeled_request_tags(operation, client, member):
    data, _ = r.resolve_botocore_data(None)
    models = r.load_models(data, {client})
    assert r.entry_for(operation, upstream(f"{client}:TagResource"), models) == {
        "tagging": [f"{client}:TagResource"], "tags": [member]}


def test_several_tagging_actions_must_be_checked_by_hand(corrections):
    with pytest.raises(ValueError, match="BY_HAND"):
        r.entry_for("svc:CreateThing", upstream("svc:TagResource", "svc:TagThing"), SIMPLE)
    corrections.setattr(r, "BY_HAND", {"svc:CreateThing": "either action"})
    assert r.entry_for("svc:CreateThing", upstream("svc:TagResource", "svc:TagThing"), SIMPLE) == {
        "tagging": ["svc:TagResource", "svc:TagThing"], "byHand": "either action"}


def test_an_operation_absent_from_botocore_is_read_after_a_model_bump(corrections):
    before = {"svc": model({"CreateOther": "In"}, {"In": structure(Name="String")})}
    assert r.entry_for("svc:CreateThing", upstream("svc:TagResource"), before) == {
        "tagging": ["svc:TagResource"], "notRead": r.NOT_IN_BOTOCORE}
    bumped = {"svc": model({"CreateThing": "In"}, {"In": structure(Tags="TagList"), **TAG_SHAPES})}
    assert r.entry_for("svc:CreateThing", upstream("svc:TagResource"), bumped) == {
        "tagging": ["svc:TagResource"], "tags": [{"member": "Tags[]", "key": "Key", "value": "Value"}]}


def test_an_explicit_exception_keeps_its_reason(corrections):
    corrections.setattr(r, "NOT_READ", {"svc:CreateThing": "tags nested deeper than this file reads"})
    models = {"svc": model({"CreateThing": "In"}, {"In": structure(Name="String")})}
    assert r.entry_for("svc:CreateThing", upstream("svc:TagResource"), models) == {
        "tagging": ["svc:TagResource"], "notRead": "tags nested deeper than this file reads"}


def test_an_exclusion_drops_a_member_and_a_query_name_is_added(corrections):
    models = {"svc": model({"CreateThing": "In"}, {"In": structure(Tags="TagList", tags="TagMap"), **TAG_SHAPES})}
    corrections.setattr(r, "EXCLUDED_MEMBERS", {"svc:CreateThing": {"Tags[]": "the tags of something else"}})
    corrections.setattr(r, "QUERY_MEMBERS", {"svc:CreateThing": {"tags{}": ("Tag{}", "the Query example")}})
    assert r.entry_for("svc:CreateThing", upstream("svc:TagResource"), models)["tags"] == [
        {"member": "tags{}", "query": "Tag{}"}]


def test_a_query_name_for_a_member_the_request_does_not_have_fails(corrections):
    corrections.setattr(r, "QUERY_MEMBERS", {"svc:CreateThing": {"tags{}": ("Tag{}", "the Query example")}})
    with pytest.raises(ValueError, match="QUERY_MEMBERS"):
        r.entry_for("svc:CreateThing", upstream("svc:TagResource"), SIMPLE)


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
    document = r.build([reference("svc", {"CreateThing": ["svc:TagResource"]}),
                        reference("elsewhere", {"Op": ["elsewhere:TagResource"]})], "test", tmp_path, "botocore test")
    assert r.validate(document) == []
    assert r.derive(document, tmp_path) == []
    assert document["namespaces"] == {"svc": ["svc"]}
    assert document["otherNamespaces"] == ["elsewhere"]
    assert json.loads(r.render(document)) == document


def test_a_hand_edited_member_does_not_match_botocore(corrections, tmp_path):
    write_models(tmp_path, SIMPLE)
    document = r.build([reference("svc", {"CreateThing": ["svc:TagResource"]})], "test", tmp_path, "botocore test")
    document["operations"]["svc:CreateThing"]["tags"].append({"member": "Other{}"})
    assert r.validate(document) == []
    assert len(r.derive(document, tmp_path)) == 1


def test_a_stale_not_read_verdict_does_not_match_botocore(corrections, tmp_path):
    write_models(tmp_path, SIMPLE)
    document = r.build([reference("svc", {"CreateThing": ["svc:TagResource"]})], "test", tmp_path, "botocore test")
    document["operations"]["svc:CreateThing"] = {
        "tagging": ["svc:TagResource"], "notRead": r.NO_TAGS}
    assert r.validate(document) == []
    assert len(r.derive(document, tmp_path)) == 1


def test_a_correction_naming_an_operation_the_reference_does_not_list_fails(corrections, tmp_path):
    write_models(tmp_path, SIMPLE)
    corrections.setattr(r, "NOT_READ", {"svc:Gone": "no member of the request carries tags"})
    with pytest.raises(ValueError, match="svc:Gone"):
        r.build([reference("svc", {"CreateThing": ["svc:TagResource"]})], "test", tmp_path, "botocore test")


def test_an_exclusion_its_operation_does_not_have_fails(corrections, tmp_path):
    write_models(tmp_path, SIMPLE)
    corrections.setattr(r, "EXCLUDED_MEMBERS", {"svc:CreateThing": {"Missing[]": "no longer there"}})
    with pytest.raises(ValueError, match="does not have"):
        r.build([reference("svc", {"CreateThing": ["svc:TagResource"]})], "test", tmp_path, "botocore test")


def good_document() -> dict:
    return {"namespaces": {"svc": ["svc"]}, "operations": {
        "svc:A": {"tagging": ["svc:CreateTags"], "createAction": True, "requestTags": True,
                  "tags": [{"member": "TagSpecification[item].Tag[item]", "key": "Key", "value": "Value"}]},
        "svc:B": {"tagging": ["svc:TagResource", "svc:TagThing"], "byHand": "either action"},
        "svc:C": {"tagging": ["svc:TagResource"], "notRead": "no member of the request carries tags"},
        "svc:D": {"tagging": ["svc:TagResource"], "tags": [{"member": "tags{}", "query": "Tag{}"}]}}}


def test_a_well_formed_document_validates():
    assert r.validate(good_document()) == []
    document = good_document()
    document["resourceArns"] = {"svc": {"thing": "arn:${Partition}:svc:${Region}:${Account}:thing/*"}}
    document["operations"]["svc:A"]["tags"][0]["resourceType"] = "ResourceType"
    assert r.validate(document) == []


def typed(document: dict) -> dict:
    document["operations"]["svc:A"]["tags"][0]["resourceType"] = "ResourceType"
    document["resourceArns"] = {"svc": {"thing": "arn:${Partition}:svc:${Region}:${Account}:thing/*"}}
    return document


@pytest.mark.parametrize("change, problem", [
    (lambda d: d["operations"]["svc:A"].update(byHand="x"), "exactly one"),
    (lambda d: d["operations"]["svc:A"]["tags"][0].update(member="TagSpecification..Tag[]"), "no path"),
    (lambda d: d["operations"]["svc:A"]["tags"][0].update(member="TagSpecification[item].Key"), "no path"),
    (lambda d: d["operations"]["svc:A"]["tags"][0].pop("value"), "names no key and value"),
    (lambda d: d["operations"]["svc:D"]["tags"][0].update(key="Key", value="Value"), "names a key and value"),
    (lambda d: d["operations"]["svc:D"]["tags"][0].update(query="Tag[]"), "Query protocol"),
    (lambda d: d["operations"]["svc:A"].update(createAction=False), "unknown field"),
    (lambda d: d["operations"]["svc:A"].update(requestTags=False), "unknown field"),
    (lambda d: d["operations"]["svc:A"].update(tagging=["svc:CreateTags", "svc:TagResource"]), "not one tagging"),
    (lambda d: d["operations"]["svc:C"].update(tagging=[]), "no usable tagging"),
    (lambda d: d["operations"].update({"other:E": {"tagging": ["other:TagResource"], "notRead": "x"}}),
     "namespace the file reads"),
    (lambda d: d["operations"]["svc:D"]["tags"][0].update(resourceType="ResourceType"), "no list of tag specifications"),
    (lambda d: typed(d).pop("resourceArns"), "resourceArns has no resource types of svc"),
    (lambda d: typed(d)["resourceArns"]["svc"].update(thing="*"), "resourceArns is not an ARN"),
    (lambda d: typed(d)["resourceArns"].update(other={"thing": "arn:${Partition}:other:::thing/*"}),
     "resourceArns is not an ARN"),
])
def test_a_malformed_document_is_rejected(change, problem):
    document = good_document()
    change(document)
    assert any(problem in p for p in r.validate(document)), r.validate(document)


def test_verify_fails_on_a_claim_aws_does_not_make_and_reports_an_added_operation():
    def found(tagging, create_action=False, request_tags=False, clients=("svc",)):
        return {"tagging": tagging, "createAction": create_action, "requestTags": request_tags,
                "clients": list(clients)}

    upstream_operations = {
        "svc:A": found(["svc:CreateTags"], create_action=True),
        "svc:B": found(["svc:TagResource"]),
        "svc:D": found(["svc:TagResource"], create_action=True),
        "svc:F": found(["svc:TagResource"]),
        "elsewhere:E": found(["elsewhere:TagResource"], clients=("elsewhere",))}
    untrue, added = r.verify_against(good_document(), upstream_operations)
    assert len(untrue) == 4 and any("svc:B" in u for u in untrue) and any("svc:C" in u for u in untrue)
    assert any("svc:D" in u and "CreateAction" in u for u in untrue)
    assert any("svc:A" in u and "request's tags" in u for u in untrue)
    assert added == ["svc:F"]


def test_verify_fails_when_aws_gives_a_resource_type_another_arn():
    document = typed(good_document())
    upstream_operations = {operation: {"tagging": entry["tagging"], "createAction": entry.get("createAction", False),
                                       "requestTags": entry.get("requestTags", False), "clients": ["svc"]}
                           for operation, entry in document["operations"].items()}
    assert r.verify_against(document, upstream_operations, {"svc": dict(document["resourceArns"]["svc"])}) == ([], [])
    untrue, _ = r.verify_against(document, upstream_operations,
                                 {"svc": {"thing": "arn:${Partition}:svc:${Region}:${Account}:thing:*"}})
    assert untrue == ["AWS no longer gives svc's thing the ARN the file does"]


def test_verify_fails_when_aws_lists_an_operation_for_a_client_the_file_does_not_read():
    document = good_document()
    untrue, _ = r.verify_against(document, {
        "svc:A": {"tagging": ["svc:CreateTags"], "createAction": True, "requestTags": True, "clients": ["svc", "svcv2"]},
        "svc:B": {"tagging": ["svc:TagResource", "svc:TagThing"], "createAction": False, "requestTags": False,
                  "clients": ["svc"]},
        "svc:C": {"tagging": ["svc:TagResource"], "createAction": False, "requestTags": False, "clients": ["svc"]},
        "svc:D": {"tagging": ["svc:TagResource"], "createAction": False, "requestTags": False, "clients": ["svc"]}})
    assert untrue == ["AWS lists svc:A for ['svc', 'svcv2'], which the file does not read"]


def test_the_vendored_file_is_well_formed():
    document = json.loads(r.OUTPUT.read_text(encoding="utf-8"))
    assert r.validate(document) == []
    operations = document["operations"]
    assert operations["sqs:CreateQueue"] == {"tagging": ["sqs:TagQueue"], "requestTags": True,
                                             "tags": [{"member": "tags{}", "query": "Tag{}"}]}
    assert operations["kms:CreateKey"]["tags"] == [{"member": "Tags[]", "key": "TagKey", "value": "TagValue"}]
    assert operations["ec2:RunInstances"] == {"tagging": ["ec2:CreateTags"], "createAction": True, "requestTags": True,
                                              "tags": [{"member": "TagSpecification[item].Tag[item]", "key": "Key",
                                                        "value": "Value", "resourceType": "ResourceType"}]}
    assert document["resourceArns"]["ec2"]["instance"] == "arn:${Partition}:ec2:${Region}:${Account}:instance/*"
    assert document["resourceArns"]["ec2"]["image"] == "arn:${Partition}:ec2:${Region}::image/*"
    assert operations["budgets:CreateBudget"]["requestTags"] is True
    assert operations["ecs:CreateGateway"]["tags"] == [
        {"member": "tags[]", "key": "key", "value": "value"}]
    assert operations["rds:CreateDBCapacityReservation"]["tags"] == [
        {"member": "Tags[Tag]", "key": "Key", "value": "Value"}]
    assert operations["network-firewall:CreateFirewallPolicyV2"]["notRead"] == r.NOT_IN_BOTOCORE
    assert operations["ec2:RequestSpotFleet"]["notRead"] == r.NOT_READ["ec2:RequestSpotFleet"]
    # The opensearch client's CreateDomain, which the reference does not list, as es's CreateElasticsearchDomain.
    assert document["namespaces"]["es"] == ["es", "opensearch"]
    for operation in ("es:CreateDomain", "es:CreateElasticsearchDomain"):
        assert operations[operation] == {"tagging": ["es:AddTags"], "requestTags": True,
                                         "tags": [{"member": "TagList[]", "key": "Key", "value": "Value"}]}
    assert operations["logs:CreateLogGroup"].get("byHand")
