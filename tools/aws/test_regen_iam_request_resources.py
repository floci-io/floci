"""Tests for regen_iam_request_resources.

Run with: pytest tools/aws -q  (or: make iam-request-resources-test)
"""
from __future__ import annotations

import json
from pathlib import Path

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


def test_a_member_naming_the_type_before_the_variable_wins_over_a_bare_one():
    formats = {"snapshot": "arn:${Partition}:svc:${Region}:${Account}:snapshot:${ClusterName}/${SnapshotName}",
               "cluster": FORMATS["thing"].replace("thing/${ThingName}", "cluster:${ClusterName}")}
    # The snapshot's cluster is the one its SnapshotClusterIdentifier names; ClusterIdentifier is the cluster's own.
    assert r.resources_for("svc:RestoreFromClusterSnapshot", ["cluster", "snapshot"], formats,
                           members("ClusterIdentifier", "SnapshotIdentifier", "SnapshotClusterIdentifier")) == [
        {"type": "cluster", "fill": {"ClusterName": "ClusterIdentifier"}},
        {"type": "snapshot", "fill": {"ClusterName": "SnapshotClusterIdentifier", "SnapshotName": "SnapshotIdentifier"}}]
    # The tenant's id is its own, not the bare Id of the invalidation the operation reads.
    assert r.resources_for("svc:GetInvalidationForTenant", ["tenant"],
                           {"tenant": "arn:${Partition}:svc::${Account}:tenant/${Id}"}, members("TenantId", "Id")) == [
        {"type": "tenant", "fill": {"Id": "TenantId"}}]


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


def test_a_create_makes_its_own_type_whatever_a_member_holding_the_resources_it_acts_on_names():
    # CreateResourceShare's resourceArns are what the share it makes holds, never the share itself.
    assert r.resources_for("svc:CreatePool", ["pool", "thing"], FORMATS, members("resourceArns[]")) == [
        {"types": ["pool", "thing"], "arn": "resourceArns[]"}, {"type": "pool", "fill": {}}]
    # A member named after the type it makes still holds it, and a non-create makes nothing.
    assert r.resources_for("svc:CreatePool", ["pool"], FORMATS, members("PoolArn")) == [
        {"types": ["pool"], "arn": "PoolArn"}]
    assert r.resources_for("svc:UpdatePool", ["pool"], FORMATS, members("resourceArns[]")) == [
        {"types": ["pool"], "arn": "resourceArns[]"}]


def test_a_creates_bare_name_fills_what_it_makes_only_when_the_type_says_its_a_name_or_an_id():
    # CreateResourceShare's share is `resource-share/${ResourcePath}`, a path RAM mints, which no name fills.
    formats = {"share": "arn:${Partition}:svc:${Region}:${Account}:share/${ResourcePath}", "thing": FORMATS["thing"]}
    assert r.resources_for("svc:CreateShare", ["share", "thing"], formats, members("name", "resourceArns[]")) == [
        {"types": ["share", "thing"], "arn": "resourceArns[]"}, {"type": "share", "fill": {}}]
    # A type whose variable is a name or an id takes the create's bare one.
    named = {"share": "arn:${Partition}:svc:${Region}:${Account}:share/${ShareName}", "thing": FORMATS["thing"]}
    assert r.resources_for("svc:CreateShare", ["share", "thing"], named, members("name", "resourceArns[]")) == [
        {"types": ["share", "thing"], "arn": "resourceArns[]"}, {"type": "share", "fill": {"ShareName": "name"}}]


def test_an_optional_arn_member_of_a_type_without_variables_names_it_when_absent():
    named = [{"types": ["catalog", "thing"], "arn": "CatalogArn"}]
    assert r.with_absent_arn(named, FORMATS, {"CatalogArn"}) == named + [
        {"type": "catalog", "fill": {}, "when": {"CatalogArn": [None]}}]
    # A required member is always there.
    assert r.with_absent_arn(named, FORMATS, set()) == named


def test_an_optional_arn_member_of_a_type_with_variables_names_its_services_default_when_absent():
    # Resource Explorer's Search with no ViewArn searches with the Region's default view, which its service finds.
    named = [{"types": ["thing"], "arn": "ThingArn"}]
    default = {"type": "thing", "fill": {}, "when": {"ThingArn": [None]}, "default": True}
    assert r.with_absent_arn(named, FORMATS, {"ThingArn"}) == named + [default]
    assert r.with_absent_arn(named, FORMATS, set()) == named
    # A member named after another type names no default of this one, and the default takes no account member.
    assert r.with_absent_arn([{"types": ["thing"], "arn": "PoolArn"}], FORMATS, {"PoolArn"}) == [
        {"types": ["thing"], "arn": "PoolArn"}]
    assert r.with_account([default], FORMATS, ["registryId"]) == [default]


VIEWS = {"view": "arn:${Partition}:svc:${Region}:${Account}:view/${ViewName}/${ViewUuid}",
         "managed-view": "arn:${Partition}:svc:${Region}:${Account}:managed-view/${ManagedViewName}/${ManagedViewUuid}",
         "index": "arn:${Partition}:svc:${Region}:${Account}:index/${IndexUuid}",
         "userpool": "arn:${Partition}:svc:${Region}:${Account}:userpool/${UserPoolId}",
         "grouppool": "arn:${Partition}:svc:${Region}:${Account}:grouppool/${GroupPoolId}"}


def test_a_create_whose_action_acts_on_no_type_makes_the_one_named_as_it_is():
    # Its tags are checked on what it makes: the type of its own name before one ending with it, its ids open.
    assert r.makes_for("svc:CreateView", [], VIEWS, members("ViewName", "ClientToken")) == [
        {"type": "view", "fill": {"ViewName": "ViewName"}}]
    assert r.makes_for("svc:CreateIndex", [], VIEWS, members("ClientToken")) == [{"type": "index", "fill": {}}]
    # A create whose action acts on types is checked on them, and an operation creating nothing makes nothing.
    assert r.makes_for("svc:CreateView", ["view"], VIEWS, members("ViewName")) == []
    assert r.makes_for("svc:DescribeView", [], VIEWS, members("ViewName")) == []
    # Nor does a create named after no type, or one named after none exactly and related to two.
    assert r.makes_for("svc:CreateSearch", [], VIEWS, {}) == []
    assert r.makes_for("svc:CreatePool", [], VIEWS, {}) == []


def test_a_built_document_with_what_a_create_makes_a_default_and_a_maps_ids_validates(isolated, tmp_path):
    service = model({"CreateThing": {"ThingName": "String"}, "ListThings": {"ThingArn": "String"}, "ApplySteps": {}})
    service["shapes"]["Step"] = {"type": "structure", "members": {"poolId": {"shape": "String"},
                                                                  "note": {"shape": "String"}}}
    service["shapes"]["Steps"] = {"type": "map", "key": {"shape": "String"}, "value": {"shape": "Step"}}
    service["shapes"]["ApplyStepsIn"]["members"]["steps"] = {"shape": "Steps"}
    write_models(tmp_path, {"svc": service})
    documents = [reference("svc", {"CreateThing": [], "ListThings": ["thing"], "ApplySteps": ["pool"]},
                           {"thing": FORMATS["thing"], "pool": FORMATS["pool"]})]
    document = r.build(documents, "test", tmp_path, "botocore test")
    assert r.validate(document) == [] and r.derive(document, tmp_path) == []
    operations = document["operations"]
    assert operations["svc:CreateThing"] == {"action": "svc:CreateThing", "types": [], "resources": [],
                                             "makes": [{"type": "thing", "fill": {"ThingName": "ThingName"}}]}
    assert operations["svc:ListThings"]["resources"] == [
        {"types": ["thing"], "arn": "ThingArn"}, {"type": "thing", "fill": {}, "when": {"ThingArn": [None]}, "default": True}]
    # A field of each value of a map of structures, named after a type the action acts on, names one of it each.
    assert operations["svc:ApplySteps"]["resources"] == [{"type": "pool", "fill": {"PoolId": "steps{}.poolId"}}]
    assert json.loads(r.render(document)) == document
    # A create acting on types makes nothing this way, and a default is for a request naming none, by no member.
    operations["svc:ListThings"]["makes"] = [{"type": "thing", "fill": {}}]
    operations["svc:CreateThing"]["resources"] = [{"type": "thing", "fill": {"ThingName": "ThingName"},
                                                   "when": {"ThingArn": [None]}, "default": True}]
    assert len(r.validate(document)) == 2


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


def test_a_rest_xml_request_is_read_for_its_path_and_query_members_alone():
    # RequestResources reads a path label or a query parameter whatever the protocol, but not a REST-XML body.
    shapes = {"String": {"type": "string"},
              "In": {"type": "structure", "members": {
                  "Id": {"shape": "String", "location": "uri", "locationName": "Id"},
                  "Marker": {"shape": "String", "location": "querystring", "locationName": "Marker"},
                  "Comment": {"shape": "String"}}}}
    for protocol, read in (("rest-xml", {"Id": "Id", "Marker": "Marker"}),
                           ("rest-json", {"Id": "Id", "Marker": "Marker", "Comment": "Comment"})):
        models = {"svc": {"metadata": {"protocol": protocol}, "operations": {"GetThing": {"input": {"shape": "In"}}},
                          "shapes": shapes}}
        assert r.request_members(models, "GetThing") == read, protocol
        assert set(r.string_patterns(models, "GetThing")) == set(read), protocol
        assert r.located_members(models, "GetThing") == {"Id": "{Id}", "Marker": "?Marker"}, protocol


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
    # A member that may hold the ARN of the parent too names no resource the create makes.
    parent = {"types": ["pipeline", "webhook"], "arn": "PipelineArn"}
    assert r.with_made([parent, webhook], "svc:PutWebhook", formats) == [parent, dict(webhook, made=True)]


def test_a_type_made_in_another_takes_that_ones_id_from_the_arn_member_named_after_it():
    formats = {"instance": "arn:${Partition}:svc:::instance/${InstanceId}",
               "set": "arn:${Partition}:svc:::set/${InstanceId}/${SetId}",
               "pool": FORMATS["pool"]}
    made = {"type": "set", "fill": {}, "made": True}
    instance = {"types": ["instance", "set"], "arn": "InstanceArn"}
    assert r.with_parent_ids([instance, made], formats, ["instance", "set"], members("InstanceArn", "Name")) == [
        instance, {"type": "set", "fill": {"InstanceId": "InstanceArn"}, "arnOf": {"InstanceArn": "instance"},
                   "made": True}]
    # Not from a type the action does not act on, a member in a structure, or with two ARN members to pick from,
    # and not over a variable a member fills already.
    assert r.with_parent_ids([made], formats, ["set"], members("InstanceArn")) == [made]
    assert r.with_parent_ids([made], formats, ["instance", "set"], {"InstanceArn": "Config.InstanceArn"}) == [made]
    assert r.with_parent_ids([made], formats, ["instance", "set"],
                             members("InstanceArn", "SourceInstanceArn")) == [made]
    filled = {"type": "set", "fill": {"InstanceId": "InstanceId"}}
    assert r.with_parent_ids([filled], formats, ["instance", "set"], members("InstanceArn", "InstanceId")) == [filled]


def test_stems_drop_the_suffixes_names_carry():
    assert r.stem("TargetGroupArns") == "targetgroup"
    assert r.stem("DBInstanceIdentifier") == "dbinstance"
    # A plural's s goes, from a type's name and a member's alike, so they still meet.
    assert r.related(r.stem("EventBusName"), r.stem("event-bus"))
    assert r.related("autoscalinggroup", "group") and not r.related("ab", "b")
    # A read of a resource's config is a read of the resource, and a type may carry the config in its name.
    assert r.stem("DistributionConfig") == "distribution"
    assert r.related(r.stem("FieldLevelEncryption"), r.stem("field-level-encryption-config"))


def test_a_bare_id_names_the_resource_whose_config_an_operation_reads():
    formats = {"distribution": "arn:${Partition}:cloudfront::${Account}:distribution/${DistributionId}"}
    assert r.resources_for("cloudfront:GetDistributionConfig", ["distribution"], formats, {"Id": "Id"}) == [
        {"type": "distribution", "fill": {"DistributionId": "Id"}}]


def lever_model(request_uri: str, label: str = "id") -> dict:
    """A rest-json model whose UpdateSafetyLeverState takes `id` from the path label given, and a `state`."""
    return {"metadata": {"protocol": "rest-json"},
            "operations": {"UpdateSafetyLeverState": {"http": {"method": "PATCH", "requestUri": request_uri},
                                                      "input": {"shape": "In"}}},
            "shapes": {"In": {"type": "structure", "members": {
                           "id": {"shape": "String", "location": "uri", "locationName": label},
                           "state": {"shape": "String"}}},
                       "String": {"type": "string"}}}


def test_a_bare_path_label_names_the_type_the_segment_before_it_names():
    formats = {"safety-lever": "arn:${Partition}:fis:${Region}:${Account}:safety-lever/${Id}",
               "experiment": "arn:${Partition}:fis:${Region}:${Account}:experiment/${Id}"}
    labels = r.path_labels({"fis": lever_model("/safetyLevers/{id}/state")}, "UpdateSafetyLeverState")
    assert labels == {"id": "safetylever"}
    # The lever's state is the lever's: its path says so where the operation's name goes on past the type.
    assert r.resources_for("fis:UpdateSafetyLeverState", ["safety-lever"], formats, members("id"), labels=labels) == [
        {"type": "safety-lever", "fill": {"Id": "id"}}]
    assert r.resources_for("fis:UpdateSafetyLeverState", ["safety-lever"], formats, members("id")) == []
    # A segment naming another type, a label after another label and a member that is no bare name name nothing.
    assert r.resources_for("fis:UpdateSafetyLeverState", ["experiment"], formats, members("id"), labels=labels) == []
    assert r.path_labels({"fis": lever_model("/{other}/{id}/state")}, "UpdateSafetyLeverState") == {}
    named = lever_model("/safetyLevers/{leverId}", label="leverId")
    named["shapes"]["In"]["members"]["leverId"] = named["shapes"]["In"]["members"].pop("id")
    assert r.path_labels({"fis": named}, "UpdateSafetyLeverState") == {}


def test_a_resource_member_taking_only_an_arn_holds_the_resource_the_action_acts_on():
    model = {
        "metadata": {"protocol": "rest-xml", "endpointPrefix": "svc"},
        "operations": {"TagResource": {"input": {"shape": "Input"}}},
        "shapes": {"Input": {"type": "structure", "members": {
                       "Resource": {"shape": "Value", "location": "querystring", "locationName": "Resource"}}},
                   "Value": {"type": "string", "pattern": "arn:aws(-cn)?:svc::[0-9]+:.*"}},
    }
    formats = {"distribution": "arn:${Partition}:svc::${Account}:distribution/${DistributionId}",
               "function": "arn:${Partition}:svc::${Account}:function/${Name}"}
    entry = r.entry_for("svc:TagResource", {"action": "svc:TagResource", "types": ["distribution", "function"]},
                        formats, {"svc": model})
    assert entry["resources"] == [{"types": ["distribution", "function"], "arn": "Resource"}]
    assert entry["located"] == {"Resource": "?Resource"}


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
def isolated(monkeypatch):
    monkeypatch.setattr(r, "NAMESPACES", ("svc",))
    monkeypatch.setattr(r, "HAND_BUILT", ("hand",))
    monkeypatch.setattr(r, "NOT_COVERED", {"later": "a later batch"})
    monkeypatch.setattr(r, "READ_IN_HAND_BUILT", {})
    monkeypatch.setattr(r, "MEASURED", {})
    monkeypatch.setattr(r, "UNQUALIFIED", {})
    monkeypatch.setattr(r, "MINTED_IDS", {})
    monkeypatch.setattr(r, "FOUND_BY", {})
    monkeypatch.setattr(r, "THROUGH", {})
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


def test_a_built_document_validates_matches_botocore_and_names_the_namespaces_it_does_not_read(isolated, tmp_path):
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}})})
    document = r.build(DOCUMENTS, "test", tmp_path, "botocore test")
    assert r.validate(document) == []
    assert r.derive(document, tmp_path) == []
    assert document["operations"]["svc:GetThing"] == {"action": "svc:GetThing", "types": ["thing"],
                                                      "resources": [{"type": "thing", "fill": {"ThingName": "ThingName"}}]}
    assert document["operations"]["svc:ListThings"]["resources"] == []
    assert document["otherNamespaces"] == ["other"]
    assert json.loads(r.render(document)) == document


def test_a_member_holding_an_account_id_gives_the_account_of_the_types_named_by_variables(isolated, tmp_path):
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


def test_an_account_member_of_a_types_own_structure_gives_that_types_account_alone(tmp_path):
    service = model({"RestoreThing": {"ThingName": "String", "PoolId": "String", "OwnerAccount": "String"}})
    service["shapes"]["Thing"] = {"type": "structure", "members": {"OwnerAccount": {"shape": "String"}}}
    service["shapes"]["Pool"] = {"type": "structure", "members": {"PoolId": {"shape": "String"}}}
    write_models(tmp_path, {"svc": service})
    models = r.load_models(tmp_path, {"svc"})
    assert r.structure_account_members(models, "RestoreThing", ["thing", "pool"]) == {"thing": "OwnerAccount"}
    resources = [{"type": "thing", "fill": {"ThingName": "ThingName"}}, {"type": "pool", "fill": {"PoolId": "PoolId"}},
                 {"types": ["thing"], "arn": "ThingArn"}]
    assert r.with_structure_account(resources, FORMATS, {"thing": "OwnerAccount"}) == [
        {"type": "thing", "fill": {"ThingName": "ThingName"}, "account": "OwnerAccount"},
        {"type": "pool", "fill": {"PoolId": "PoolId"}}, {"types": ["thing"], "arn": "ThingArn"}]
    # An account another rule gave stays, and a format without an account takes none.
    given = [{"type": "thing", "fill": {}, "account": "registryId"}]
    assert r.with_structure_account(given, FORMATS, {"thing": "OwnerAccount"}) == given
    assert r.with_structure_account([{"type": "catalog", "fill": {}}], {"catalog": "arn:${Partition}:svc:::catalog"},
                                    {"catalog": "OwnerAccount"}) == [{"type": "catalog", "fill": {}}]
    # A structure with two such members says nothing about which one is its account.
    service["shapes"]["Thing"]["members"]["SourceAccount"] = {"shape": "String"}
    service["shapes"]["RestoreThingIn"]["members"]["SourceAccount"] = {"shape": "String"}
    write_models(tmp_path / "two", {"svc": service})
    assert r.structure_account_members(r.load_models(tmp_path / "two", {"svc"}), "RestoreThing", ["thing"]) == {}


ACCOUNTS = {"account": "arn:${Partition}:account::${Account}:account",
            "accountInOrganization":
                "arn:${Partition}:account::${ManagementAccountId}:account/o-${OrganizationId}/${MemberAccountId}"}


def test_an_account_member_names_the_account_an_arn_names_before_the_one_it_is_in():
    # AccountId is the member account; the management account, the ARN's own, and the organization stay open.
    assert r.resources_for("account:GetAlternateContact", ["accountInOrganization"], ACCOUNTS,
                           members("AccountId")) == [
        {"type": "accountInOrganization", "fill": {"MemberAccountId": "AccountId"}}]


def test_an_account_alone_applies_to_a_request_leaving_out_the_optional_account_member_naming_another():
    named = r.resources_for("account:GetAlternateContact", ["account", "accountInOrganization"], ACCOUNTS,
                            members("AccountId"))
    assert named == [{"type": "account", "fill": {}},
                     {"type": "accountInOrganization", "fill": {"MemberAccountId": "AccountId"}}]
    absent = r.with_absent_account(named, ACCOUNTS, ["AccountId"], {"AccountId"})
    assert absent == [{"type": "account", "fill": {}, "when": {"AccountId": [None]}}, named[1]]
    # The account member then gives the account of neither: the caller's own, or the one the member fills.
    assert r.with_account(absent, ACCOUNTS, ["AccountId"]) == absent
    # A required member is always there; one naming nothing else gives the account its account.
    assert r.with_absent_account(named, ACCOUNTS, ["AccountId"], set()) == named
    alone = [{"type": "account", "fill": {}}]
    assert r.with_absent_account(alone, ACCOUNTS, ["AccountId"], {"AccountId"}) == alone
    assert r.with_account(alone, ACCOUNTS, ["AccountId"]) == [{"type": "account", "fill": {}, "account": "AccountId"}]


@pytest.mark.parametrize("member", ["ownerAccount", "resourceAccount"])
@pytest.mark.parametrize("pattern", ["[0-9]{12}", r"(\d{12})"])
def test_an_account_member_typed_elsewhere_in_the_service_is_inferred_only_for_non_creates(tmp_path, member, pattern):
    service = model({"GetThing": {member: "String"},
                     "CreateThing": {member: "String"},
                     "ListThings": {member: "AccountId"},
                     "CreateOther": {member: "DirectAccountId"}})
    service["shapes"]["AccountId"]["pattern"] = pattern
    service["shapes"]["DirectAccountId"] = {"type": "string", "pattern": "[0-9]{12}"}
    write_models(tmp_path, {"svc": service})
    models = r.load_models(tmp_path, {"svc"})
    assert r.account_members(models, "GetThing") == [member]
    assert r.account_members(models, "CreateThing") == []
    assert r.account_members(models, "ListThings") == [member]
    assert r.account_members(models, "CreateOther") == [member]


def test_account_member_inference_does_not_cross_service_models(tmp_path):
    write_models(tmp_path, {"svc": model({"GetThing": {"ownerAccount": "String"}}),
                            "other": model({"ListThings": {"ownerAccount": "AccountId"}})})
    models = r.load_models(tmp_path, {"svc", "other"})
    assert r.account_members(models, "GetThing") == []


def test_redshift_serverless_account_inference_preserves_the_generated_resources():
    from regen_partitions import resolve_botocore_data

    document = json.loads(r.OUTPUT.read_text())
    data, _ = resolve_botocore_data(None)
    models = r.load_models(data, set(document["namespaces"]["redshift-serverless"]))
    account_operations = set()
    for operation, entry in document["operations"].items():
        if not operation.startswith("redshift-serverless:"):
            continue
        derived = r.entry_for(operation, {"action": entry["action"], "types": entry["types"]},
                              document["arnFormats"]["redshift-serverless"], models)
        assert derived == entry, operation
        if r.account_members(models, operation.split(":", 1)[1]) == ["ownerAccount"]:
            account_operations.add(operation.split(":", 1)[1])
    assert account_operations == {"GetSnapshot", "ListEndpointAccess", "ListSnapshots", "ListWorkgroups",
                                  "RestoreFromSnapshot"}


def test_a_format_whose_account_is_an_account_id_variable_takes_the_account_every_other_format_does():
    documents = [reference("svc", {}, {"agent": "arn:${Partition}:svc:${Region}:${AccountId}:agent/${AgentId}",
                                       "account": "arn:${Partition}:svc:::account/${AccountId}",
                                       "thing": FORMATS["thing"]})]
    # An account id in the resource, past the account, stays a variable a member fills.
    assert r.arn_formats(documents, {"svc"}) == {"svc": {
        "account": "arn:${Partition}:svc:::account/${AccountId}",
        "agent": "arn:${Partition}:svc:${Region}:${Account}:agent/${AgentId}",
        "thing": FORMATS["thing"]}}


def test_a_file_that_is_not_json_is_reported_and_not_read(isolated, tmp_path, capsys):
    output = tmp_path / "iam-request-resources.json"
    output.write_text("{not json", encoding="utf-8")

    for mode in ("--check", "--verify"):
        assert r.main([mode, "--output", str(output)]) == 1
        assert "could not be read" in capsys.readouterr().err


def test_a_file_that_cannot_be_read_is_reported(isolated, tmp_path, capsys):
    output = tmp_path / "iam-request-resources.json"
    output.write_text("{}", encoding="utf-8")

    def refuse(*args, **kwargs):
        raise PermissionError(13, "Permission denied")

    # The read is refused rather than the file made unreadable, which a run as root could read anyway.
    isolated.setattr(Path, "read_text", refuse)
    assert r.main(["--check", "--output", str(output)]) == 1
    assert "could not be read" in capsys.readouterr().err


def test_a_hand_edited_entry_does_not_match_botocore(isolated, tmp_path):
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}})})
    document = r.build(DOCUMENTS, "test", tmp_path, "botocore test")
    document["operations"]["svc:GetThing"]["resources"] = []
    assert r.validate(document) == []
    assert len(r.derive(document, tmp_path)) == 1


def test_a_type_checked_without_a_qualifier_is_recorded_and_must_be_one_the_file_reads(isolated, tmp_path):
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}})})
    isolated.setattr(r, "UNQUALIFIED", {"svc:thing": "its service checks a qualified ARN without the qualifier"})
    document = r.build(DOCUMENTS, "test", tmp_path, "botocore test")
    assert document["unqualified"] == {"svc": ["thing"]}
    assert r.validate(document) == [] and r.derive(document, tmp_path) == []
    # A hand edit no longer matches, and a type the file has no format for fails the build.
    document["unqualified"] = {}
    assert r.validate(document) == [] and len(r.derive(document, tmp_path)) == 1
    isolated.setattr(r, "UNQUALIFIED", {"svc:gone": "no longer there"})
    with pytest.raises(ValueError, match="svc:gone"):
        r.build(DOCUMENTS, "test", tmp_path, "botocore test")


def test_a_type_whose_service_mints_an_id_after_its_name_is_recorded_and_must_be_one_the_file_reads(isolated, tmp_path):
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}})})
    isolated.setattr(r, "MINTED_IDS", {"svc:thing": "its service follows the name with an id it mints"})
    document = r.build(DOCUMENTS, "test", tmp_path, "botocore test")
    assert document["mintedIds"] == {"svc": ["thing"]}
    assert r.validate(document) == [] and r.derive(document, tmp_path) == []
    # A hand edit no longer matches, and a type the file has no format for fails the build.
    document["mintedIds"] = {}
    assert r.validate(document) == [] and len(r.derive(document, tmp_path)) == 1
    isolated.setattr(r, "MINTED_IDS", {"svc:gone": "no longer there"})
    with pytest.raises(ValueError, match="svc:gone"):
        r.build(DOCUMENTS, "test", tmp_path, "botocore test")


def test_a_type_whose_arn_is_all_an_id_its_service_mints_is_found_by_the_members_naming_it(isolated, tmp_path):
    documents = [reference("svc", {"PutTarget": ["target"], "TagTarget": ["target"], "GetByResource": ["target"],
                                   "RunOn": ["target", "group"]},
                           {"target": "arn:${Partition}:svc:${Region}:${Account}:target/${ResourceId}",
                            "group": "arn:${Partition}:svc:${Region}:${Account}:group/${GroupId}"})]
    write_models(tmp_path, {"svc": model({"PutTarget": {"Namespace": "String", "ResourceId": "String",
                                                        "Dimension": "String"},
                                          "TagTarget": {"ResourceArn": "String"},
                                          "GetByResource": {"ResourceId": "String"},
                                          "RunOn": {"GroupName": "String"}})})
    isolated.setattr(r, "FOUND_BY", {"svc:target": (["Namespace", "ResourceId", "Dimension"], "its service mints the id"),
                                        "svc:group": (["GroupName"], "its service mints the id")})
    document = r.build(documents, "test", tmp_path, "botocore test")
    assert r.validate(document) == [] and r.derive(document, tmp_path) == []
    operations = document["operations"]
    # Found by the three, in order, rather than filled by ResourceId, which is not the id the ARN ends in.
    assert operations["svc:PutTarget"]["resources"] == [
        {"type": "target", "fill": {}, "find": ["Namespace", "ResourceId", "Dimension"]}]
    # Its ARN names it as given, and a request lacking a member names none.
    assert operations["svc:TagTarget"]["resources"] == [{"types": ["target"], "arn": "ResourceArn"}]
    assert operations["svc:GetByResource"]["resources"] == []
    # Found by its name though no member fills its id, so the rule derives nothing for it.
    assert operations["svc:RunOn"]["resources"] == [{"type": "group", "fill": {}, "find": ["GroupName"]}]
    # A hand edit no longer matches, and a type the file has no format for fails the build.
    operations["svc:PutTarget"]["resources"] = [{"type": "target", "fill": {"ResourceId": "ResourceId"}}]
    assert r.validate(document) == [] and len(r.derive(document, tmp_path)) == 1
    isolated.setattr(r, "FOUND_BY", {"svc:gone": (["Namespace"], "no longer there")})
    with pytest.raises(ValueError, match="svc:gone"):
        r.build(documents, "test", tmp_path, "botocore test")


def test_a_member_naming_a_resource_of_no_type_names_the_one_it_runs_on_through_it(isolated, tmp_path):
    documents = [reference("svc", {"Run": ["cluster", "group"], "RunOnGroup": ["group"], "RunOnCluster": ["cluster"],
                                   "ListSessions": []},
                           {"cluster": "arn:${Partition}:svc:${Region}:${Account}:cluster:${ClusterName}",
                            "group": "arn:${Partition}:svc:${Region}:${Account}:group/${GroupId}"})]
    write_models(tmp_path, {"svc": model({"Run": {"SessionId": "String"}, "RunOnGroup": {"SessionId": "String"},
                                          "RunOnCluster": {"Sql": "String"}, "ListSessions": {"SessionId": "String"}})})
    isolated.setattr(r, "THROUGH", {"svc:SessionId": (["cluster", "group"], "a session runs on one")})
    document = r.build(documents, "test", tmp_path, "botocore test")
    assert r.validate(document) == [] and r.derive(document, tmp_path) == []
    operations = document["operations"]
    # Named through the session, as one of the types the action acts on.
    assert operations["svc:Run"]["resources"] == [{"types": ["cluster", "group"], "through": "SessionId"}]
    assert operations["svc:RunOnGroup"]["resources"][-1] == {"types": ["group"], "through": "SessionId"}
    # Not by a request without the member, nor for an action acting on none of the types.
    assert not any("through" in resource for resource in operations["svc:RunOnCluster"]["resources"])
    assert operations["svc:ListSessions"]["resources"] == []
    # A hand edit no longer matches, and a type the file has no format for fails the build.
    operations["svc:Run"]["resources"] = []
    assert r.validate(document) == [] and len(r.derive(document, tmp_path)) == 1
    isolated.setattr(r, "THROUGH", {"svc:SessionId": (["gone"], "no longer there")})
    with pytest.raises(ValueError, match="svc:gone"):
        r.build(documents, "test", tmp_path, "botocore test")


def test_a_client_not_read_gives_no_members_and_aws_listing_it_is_no_untrue_claim(isolated, tmp_path):
    isolated.setattr(r, "CLIENTS_NOT_READ", {"svcv2": "a second API Floci does not serve"})
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
    buses = [{"type": "bus", "fill": {"BusName": "Entries[].BusName"}}]
    assert r.with_arn_of(buses, formats, r.name_or_arn_members(models, "PutEvents")) == [
        dict(buses[0], arnOf={"Entries[].BusName": "bus"})]


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
    document["operations"]["svc:B"]["resources"][0]["arnOf"] = {"Bus": "thing"}
    assert r.validate(document) == []
    assert r.validate(with_a_create()) == []
    # A field of each of a list's structures fills a variable, read as given, or as an ARN.
    document = good_document()
    document["operations"]["svc:B"]["resources"][0].update(
        fill={"GroupName": "Groups[].Name"}, arnOf={"Groups[].Name": "thing"})
    assert r.validate(document) == []
    # A field of a structure fills a variable, read as one value, and a variable no member fills may be fixed.
    document = good_document()
    document["operations"]["svc:B"]["resources"][0].update(
        fill={"GroupName": "Group.Name"}, fixed={"GroupId": "g-1"}, made=True)
    document["operations"]["svc:A"]["resources"][0]["made"] = True
    assert r.validate(document) == []
    # A type whose ARN is all an id its service mints, found by the members naming it.
    document = good_document()
    document["operations"]["svc:B"]["resources"][0].update(fill={}, find=["Namespace", "Id"])
    assert r.validate(document) == []
    # A resource named through a member naming another of its service's own, which it runs on, or through each
    # value of a list's field when another member is absent.
    document = good_document()
    document["operations"]["svc:A"]["resources"] = [{"types": ["thing"], "through": "SessionId"},
                                                    {"types": ["thing"], "through": "Parts[].PartId",
                                                     "when": {"ThingName": [None]}}]
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
    (lambda d: d["operations"]["svc:A"]["resources"].__setitem__(0, {"types": ["thing"], "through": "Ids[][]"}),
     "no member"),
    (lambda d: d["operations"]["svc:A"]["resources"].__setitem__(0, {"types": ["thing"], "through": "a.b.c"}),
     "no member"),
    (lambda d: d["operations"]["svc:A"]["resources"].__setitem__(0, {"types": ["thing"], "through": "SessionId",
                                                                     "when": {"Ids[]": ["a"]}}), "no member's values"),
    (lambda d: d["operations"]["svc:A"]["resources"].__setitem__(0, {"types": ["thing"], "through": "SessionId",
                                                                     "unless": {"Id": ["a"]}}), "neither an ARN member"),
    (lambda d: d["operations"]["svc:A"]["resources"].__setitem__(0, {"types": ["pool"], "through": "SessionId"}),
     "not resource types"),
    (lambda d: d["operations"]["svc:A"]["resources"].__setitem__(0, {"types": ["thing"], "through": "SessionId",
                                                                     "before": "#"}), "neither an ARN member"),
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
    # A value read as given for a member left out, or up to a character, is no key of a resource any more.
    (lambda d: d["operations"]["svc:A"]["resources"][0].update(before="#"), "neither an ARN member"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(before=":"), "neither an ARN member"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(absent={"Bus": "default"}), "neither an ARN member"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(account="Ids[]"), "no account of its ARN"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(account="a.b"), "no account of its ARN"),
    (lambda d: d["arnFormats"]["svc"].update(group="arn:${Partition}:svc:::group/${GroupName}")
     or d["operations"]["svc:B"]["resources"][0].update(account="registryId"), "no account of its ARN"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(unless={"Type": []}), "no member's values"),
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
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(find=["Namespace"]), "beside a fill"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(fill={}, find=[]), "beside a fill"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(fill={}, find=["Ids[]"]), "beside a fill"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(fill={}, find="Namespace"), "beside a fill"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(fill={}, find=["Namespace"], fixed={"GroupId": "g"}),
     "beside a fill"),
    (lambda d: d["operations"]["svc:A"]["resources"][0].update(find=["Namespace"]), "neither an ARN member"),
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
    # StartExecution's name is the execution's, so only its state machine ARN is read; Step Functions drops a test
    # case's `#` from it at runtime (StoredResourceArns.canonical).
    assert operations["states:StartExecution"]["resources"] == [{"types": ["statemachine"], "arn": "stateMachineArn"}]
    # An ARN inside a structure or each of a list's structures.
    assert operations["elasticloadbalancing:SetRulePriorities"]["resources"] == [
        {"types": ["listener-rule/app", "listener-rule/net"], "arn": "RulePriorities[].RuleArn"}]
    assert operations["states:CreateStateMachineAlias"]["resources"] == [
        {"types": ["statemachine"], "arn": "routingConfiguration[].stateMachineVersionArn"}]
    # An EventBridge rule is on the bus EventBusName names, by name or ARN; a rule on the default bus, the one a
    # request naming none acts on, which EventBridge gives at runtime, has no bus in its ARN, and a default bus's ARN
    # puts it in that ARN's account.
    assert operations["events:DescribeRule"]["resources"] == [
        {"type": "rule-on-custom-event-bus", "fill": {"EventBusName": "EventBusName", "RuleName": "Name"},
         "arnOf": {"EventBusName": "event-bus"}, "unlessDefault": {"EventBusName": "event-bus"}},
        {"type": "rule-on-default-event-bus", "fill": {"RuleName": "Name"}, "whenDefault": {"EventBusName": "event-bus"},
         "arnOf": {"EventBusName": "event-bus"}}]
    assert operations["events:DescribeEventBus"]["resources"] == [
        {"types": ["event-busv2"], "arn": "EventBusArn", "endpoint": "eventsv2"},
        {"type": "event-bus", "fill": {"EventBusName": "Name"}, "arnOf": {"Name": "event-bus"}, "endpoint": "events"}]
    # Each PutEvents entry is on the bus it names, by name or ARN (the default one, which EventBridge gives, when it
    # names none); an EventBridge V2 request is on the one bus its EventBusArn names.
    assert operations["events:PutEvents"]["resources"] == [
        {"types": ["event-busv2"], "arn": "EventBusArn", "endpoint": "eventsv2"},
        {"type": "event-bus", "fill": {"EventBusName": "Entries[].EventBusName"},
         "arnOf": {"Entries[].EventBusName": "event-bus"}, "endpoint": "events"}]
    # EventBridge V2 is read beside EventBridge, and where both name an operation's resources, which one applies is
    # the endpoint serving the request, never a member a caller could add.
    assert document["namespaces"]["events"] == ["eventbridgev2", "events"]
    assert "eventbridgev2" not in document["clientsNotRead"]
    assert operations["events:DeleteSubscriber"]["resources"] == [{"types": ["subscriber"], "arn": "SubscriberArn"}]
    assert operations["events:DeleteEventBus"]["resources"] == [
        {"types": ["event-busv2"], "arn": "EventBusArn", "endpoint": "eventsv2"},
        {"type": "event-bus", "fill": {"EventBusName": "Name"}, "endpoint": "events"}]
    created = operations["events:CreateEventBus"]["resources"]
    assert {"type": "event-bus", "fill": {"EventBusName": "Name"}, "endpoint": "events", "made": True} in created
    assert {"type": "event-busv2", "fill": {"EventBusName": "Name"}, "endpoint": "eventsv2"} in created
    # A create's tags are checked on what it makes: not a dead-letter target or the bus a subscriber is made on.
    assert [resource for resource in created if "arn" in resource and resource.get("made")] == []
    assert [resource for resource in operations["events:CreateSubscriber"]["resources"] if resource.get("made")] == [
        {"type": "subscriber", "fill": {"SubscriberName": "Name"}, "made": True}]
    assert not any("when" in resource or "unless" in resource
                   for operation, entry in operations.items() if operation.startswith("events:")
                   and "endpoint" in json.dumps(entry) for resource in entry["resources"])
    # A state machine's version or alias is checked on the state machine.
    assert document["unqualified"] == {"ssm": ["parameter"], "states": ["statemachine"]}
    # A listener is made under a load balancer, and a rule under a listener.
    assert operations["elasticloadbalancing:CreateListener"]["creates"] == [
        {"type": f"listener/{kind}", "parent": f"loadbalancer/{kind}/", "arn": "LoadBalancerArn"}
        for kind in ("app", "gwy", "net")]
    assert operations["elasticloadbalancing:CreateRule"]["creates"] == [
        {"type": f"listener-rule/{kind}", "parent": f"listener/{kind}", "arn": "ListenerArn"} for kind in ("app", "net")]
    assert set(document["handBuilt"]) >= {"lambda", "s3", "sqs"}
    # A scalable target is found by the three members naming it, its ARN ending in the id the service mints.
    assert operations["application-autoscaling:DeregisterScalableTarget"]["resources"] == [
        {"type": "ScalableTarget", "fill": {}, "find": ["ServiceNamespace", "ResourceId", "ScalableDimension"]}]
    assert operations["application-autoscaling:TagResource"]["resources"] == [
        {"types": ["ScalableTarget"], "arn": "ResourceARN"}]
    # A Data API request runs on the cluster it names, or on the serverless workgroup its name or ARN finds.
    assert operations["redshift-data:ExecuteStatement"]["resources"][1:] == [
        {"type": "cluster", "fill": {"ClusterName": "ClusterIdentifier"}},
        {"type": "workgroup", "fill": {}, "find": ["WorkgroupName"]},
        {"types": ["cluster", "workgroup"], "through": "SessionId"}]
    # A request naming its session alone runs on the cluster or workgroup the session was opened on.
    assert operations["redshift-data:BatchExecuteStatement"]["resources"][-1] == {
        "types": ["cluster", "workgroup"], "through": "SessionId"}
    assert operations["redshift-data:ListStatements"]["resources"] == []
    assert operations["redshift-data:ListSessions"]["resources"] == []
    # The v2 API's Type picks the load balancer it creates, application when it gives none.
    assert operations["elasticloadbalancing:CreateLoadBalancer"]["resources"][1] == {
        "type": "loadbalancer/app/", "fill": {"LoadBalancerName": "Name"}, "when": {"Type": ["application", None]}}


def test_hand_built_operations_listed_to_read_follow_the_rule(isolated, tmp_path):
    isolated.setattr(r, "READ_IN_HAND_BUILT", {"hand:GetThing": "a family the rule reads"})
    documents = DOCUMENTS + [reference("hand", {"GetThing": ["thing"], "PutThing": ["thing"]},
                                       {"thing": "arn:${Partition}:hand:${Region}:${Account}:thing/${ThingName}"})]
    hand = model({"GetThing": {"ThingName": "String"}, "PutThing": {"ThingName": "String"}})
    hand["metadata"]["endpointPrefix"] = "hand"
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}}), "hand": hand})
    document = r.build(documents, "test", tmp_path, "botocore test")
    assert document["operations"]["hand:GetThing"] == {
        "action": "hand:GetThing", "types": ["thing"], "resources": [{"type": "thing", "fill": {"ThingName": "ThingName"}}]}
    assert "hand:PutThing" not in document["operations"]
    assert r.reads("hand:GetThing") and not r.reads("hand:PutThing")
    assert r.validate(document) == []


def test_operations_listed_to_read_must_exist_and_be_hand_built(isolated, tmp_path):
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}})})
    isolated.setattr(r, "READ_IN_HAND_BUILT", {"hand:Gone": "no longer there"})
    with pytest.raises(ValueError, match="READ_IN_HAND_BUILT names hand:Gone, which the reference does not give"):
        r.build(DOCUMENTS, "test", tmp_path, "botocore test")


MEASURED_FORMATS = {"thing": "arn:${Partition}:hand:${Region}:${Account}:thing/${ThingName}"}
PROBED = "real AWS probes in https://example.com/probes accept an allow and enforce a deny on thing/*"


def measured_documents(tmp_path, listed: list[str]) -> list[dict]:
    hand = model({"ListThings": {"StartName": "String"}})
    hand["metadata"]["endpointPrefix"] = "hand"
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}}), "hand": hand})
    return DOCUMENTS + [reference("hand", {"ListThings": listed}, MEASURED_FORMATS)]


def test_a_measured_operation_takes_the_resources_aws_was_measured_to_check_where_the_reference_lists_none(
        isolated, tmp_path):
    isolated.setattr(r, "MEASURED", {"hand:ListThings": ([{"type": "thing", "fill": {}}], PROBED)})
    document = r.build(measured_documents(tmp_path, []), "test", tmp_path, "botocore test")
    # Read in a namespace otherwise built by hand, keeping the reference's empty types.
    assert document["operations"]["hand:ListThings"] == {
        "action": "hand:ListThings", "types": [], "resources": [{"type": "thing", "fill": {}}]}
    assert r.reads("hand:ListThings")
    assert r.validate(document) == []
    assert r.derive(document, tmp_path) == []


@pytest.mark.parametrize("measured, listed, problem", [
    # No link to the measurement it rests on.
    (([{"type": "thing", "fill": {}}], "probed against AWS, results not published"), [], "no link to the measurement"),
    # A type the reference lists for the action: the rules read it, so a measurement of it belongs in a rule.
    (([{"type": "thing", "fill": {}}], PROBED), ["thing"], "which the Service Reference lists for its action"),
    # A type with no ARN format.
    (([{"type": "gadget", "fill": {}}], PROBED), [], "no ARN format"),
    # What the rules already give: an entry a later rule or reference update makes redundant is taken out.
    (([], PROBED), [], "what the rules already give"),
])
def test_a_measured_entry_is_refused_unless_it_cites_aws_for_what_the_metadata_does_not_say(
        isolated, tmp_path, measured, listed, problem):
    isolated.setattr(r, "MEASURED", {"hand:ListThings": measured})
    with pytest.raises(ValueError, match=problem):
        r.build(measured_documents(tmp_path, listed), "test", tmp_path, "botocore test")


def test_a_measured_entry_must_name_an_operation_the_file_reads(isolated, tmp_path):
    isolated.setattr(r, "MEASURED", {"hand:Gone": ([{"type": "thing", "fill": {}}], PROBED)})
    with pytest.raises(ValueError, match="MEASURED gives hand:Gone, which the file does not read"):
        r.build(measured_documents(tmp_path, []), "test", tmp_path, "botocore test")


def test_a_variable_no_member_fills_takes_the_one_value_of_its_enum_the_operations_name_gives():
    formats = {"actiontype": "arn:${Partition}:svc:${Region}:${Account}:actiontype:${Owner}/${Category}"}
    owners = model({"CreateCustomActionType": {"category": "String"}})
    owners["shapes"]["ActionOwner"] = {"type": "string", "enum": ["AWS", "ThirdParty", "Custom"]}
    named = [{"type": "actiontype", "fill": {"Category": "category"}}]
    assert r.with_enum_named("svc:CreateCustomActionType", named, formats, {"svc": owners}) == [
        dict(named[0], fixed={"Owner": "Custom"})]
    # A name giving none of its values, or a word inside another, fixes nothing.
    assert r.with_enum_named("svc:CreateActionType", named, formats, {"svc": owners}) == named
    assert r.with_enum_named("svc:CreateCustomizedActionType", named, formats, {"svc": owners}) == named


def test_a_type_named_by_a_member_documented_as_holding_the_operations_subject_is_reached_through_it_when_left_out():
    groups = model({"ModifyInstanceGroups": {"ClusterId": "String"}})
    groups["shapes"]["ModifyInstanceGroupsIn"]["members"]["ClusterId"]["documentation"] = \
        "<p>The ID of the cluster to which the instance group belongs.</p>"
    named = [{"type": "cluster", "fill": {"ClusterId": "ClusterId"}}]
    fields = {"InstanceGroups[].InstanceGroupId": "InstanceGroupId"}
    assert r.with_subject_through("svc:ModifyInstanceGroups", named, {"ClusterId": "ClusterId"}, fields,
                                  {"ClusterId"}, {"svc": groups}) == named + [
        {"types": ["cluster"], "through": "InstanceGroups[].InstanceGroupId", "when": {"ClusterId": [None]}}]
    # A member that must be given, or one not documented as holding the subject, is all that names the type.
    assert r.with_subject_through("svc:ModifyInstanceGroups", named, {"ClusterId": "ClusterId"}, fields,
                                  set(), {"svc": groups}) == named
    groups["shapes"]["ModifyInstanceGroupsIn"]["members"]["ClusterId"]["documentation"] = "<p>The cluster.</p>"
    assert r.with_subject_through("svc:ModifyInstanceGroups", named, {"ClusterId": "ClusterId"}, fields,
                                  {"ClusterId"}, {"svc": groups}) == named


# --------------------------------------------------------------------------- #
# REST requests: members in the path and the query, and the REST models' operations
# --------------------------------------------------------------------------- #
def rest_model(operations: dict[str, dict[str, str]], located: dict[str, dict[str, str]]) -> dict:
    """A rest-json model whose operations take the members given, `located` giving a member's `location` and
    `locationName` as `uri:Name` or `querystring:name`."""
    built = model(operations, protocol="rest-json")
    for operation, members in located.items():
        for member, where in members.items():
            location, name = where.split(":", 1)
            built["shapes"][operation + "In"]["members"][member].update(location=location, locationName=name)
    return built


def test_a_rest_request_reads_its_path_and_query_members_and_records_where_the_ones_it_reads_are(isolated, tmp_path):
    svc = rest_model({"GetThing": {"ThingName": "String", "Marker": "String"}, "ListThings": {"Marker": "String"}},
                     {"GetThing": {"ThingName": "uri:Name", "Marker": "querystring:marker"},
                      "ListThings": {"Marker": "querystring:marker"}})
    write_models(tmp_path, {"svc": svc})
    document = r.build(DOCUMENTS, "test", tmp_path, "botocore test")
    assert document["operations"]["svc:GetThing"] == {
        "action": "svc:GetThing", "types": ["thing"], "resources": [{"type": "thing", "fill": {"ThingName": "ThingName"}}],
        "located": {"ThingName": "{Name}"}}
    assert "located" not in document["operations"]["svc:ListThings"]
    assert r.validate(document) == []
    assert r.derive(document, tmp_path) == []


def test_a_member_two_clients_locate_apart_is_an_error():
    one = rest_model({"GetThing": {"ThingName": "String"}}, {"GetThing": {"ThingName": "uri:ThingName"}})
    two = rest_model({"GetThing": {"ThingName": "String"}}, {"GetThing": {"ThingName": "querystring:thing"}})
    with pytest.raises(ValueError, match="GetThing's ThingName is"):
        r.located_members({"one": one, "two": two}, "GetThing")


def test_a_located_member_must_be_a_path_label_or_a_query_parameter(isolated, tmp_path):
    write_models(tmp_path, {"svc": rest_model({"GetThing": {"ThingName": "String"}, "ListThings": {}},
                                              {"GetThing": {"ThingName": "uri:ThingName"}})})
    document = r.build(DOCUMENTS, "test", tmp_path, "botocore test")
    document["operations"]["svc:GetThing"]["located"] = {"ThingName": "header:x"}
    assert any("does not give each member it locates" in problem for problem in r.validate(document))


SPLIT = [reference("svc", {"GetThing": ["thing"], "ListThings": []}, {"thing": FORMATS["thing"]}),
         reference("other", {"Op": ["x"]}, {"x": "arn:${Partition}:other:::x"}),
         reference("later", {"Op": ["y"]}, {"y": "arn:${Partition}:later:::y"})]
# The reference lists svc's operations for its first client alone: DescribeThing is an action with no operation.
SPLIT[0]["Actions"].append({"Name": "DescribeThing", "Resources": [{"Name": "thing"}]})


def test_a_rest_models_operations_the_reference_lists_for_no_client_take_their_own_actions(isolated):
    isolated.setattr(r, "REST_MODELS", {"svc-v2": "svc"})
    found = r.with_rest_operations(r.checked_actions(SPLIT), SPLIT, {"svc-v2": {"GetThing", "DescribeThing", "Gone"}})
    assert found["svc:GetThing"]["clients"] == ["svc", "svc-v2"]
    assert found["svc:DescribeThing"] == {"action": "svc:DescribeThing", "types": ["thing"], "clients": ["svc-v2"]}
    # An operation whose action the reference does not have is left out, not given no types.
    assert "svc:Gone" not in found


def test_a_namespace_reads_the_operations_of_the_rest_models_floci_routes_under_it(isolated, tmp_path):
    isolated.setattr(r, "REST_MODELS", {"svc-v2": "svc"})
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}}),
                            "svc-v2": rest_model({"DescribeThing": {"ThingName": "String"}},
                                                 {"DescribeThing": {"ThingName": "uri:ThingName"}})})
    sources = tmp_path / "reference"
    sources.mkdir()
    for document in SPLIT:
        (sources / f"{document['Name']}.json").write_text(json.dumps(document), encoding="utf-8")
    output = tmp_path / "table.json"
    assert r.main(["--source-dir", str(sources), "--botocore-data", str(tmp_path), "--output", str(output)]) == 0
    document = json.loads(output.read_text())
    assert document["namespaces"]["svc"] == ["svc", "svc-v2"]
    assert document["operations"]["svc:DescribeThing"] == {
        "action": "svc:DescribeThing", "types": ["thing"],
        "resources": [{"type": "thing", "fill": {"ThingName": "ThingName"}}], "located": {"ThingName": "{ThingName}"}}
    assert r.main(["--check", "--botocore-data", str(tmp_path), "--output", str(output)]) == 0
    assert r.main(["--verify", "--source-dir", str(sources), "--output", str(output)]) == 0


def test_verify_reports_a_rest_models_operation_whose_action_aws_no_longer_has(isolated, tmp_path):
    isolated.setattr(r, "REST_MODELS", {"svc-v2": "svc"})
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}}),
                            "svc-v2": rest_model({"DescribeThing": {"ThingName": "String"}},
                                                 {"DescribeThing": {"ThingName": "uri:ThingName"}})})
    sources = tmp_path / "reference"
    sources.mkdir()
    for document in SPLIT:
        (sources / f"{document['Name']}.json").write_text(json.dumps(document), encoding="utf-8")
    output = tmp_path / "table.json"
    assert r.main(["--source-dir", str(sources), "--botocore-data", str(tmp_path), "--output", str(output)]) == 0
    gone = json.loads(json.dumps(SPLIT[0]))
    gone["Actions"] = [action for action in gone["Actions"] if action["Name"] != "DescribeThing"]
    (sources / "svc.json").write_text(json.dumps(gone), encoding="utf-8")
    assert r.main(["--verify", "--source-dir", str(sources), "--output", str(output)]) == 1


def test_id_targets_follow_the_model_and_resource_types_not_operation_names():
    ec2 = model({"AnnotateResources": {"Resources": "Strings"}}, "ec2")
    member = ec2["shapes"]["AnnotateResourcesIn"]["members"]["Resources"]
    member["locationName"] = "resourceId"
    formats = {"instance": "arn:${Partition}:ec2:${Region}:${Account}:instance/${InstanceId}"}
    upstream = {"action": "ec2:AnnotateResources", "types": ["instance", "volume"]}
    entry = r.entry_for("ec2:AnnotateResources", upstream, formats, {"ec2": ec2})
    assert entry["targets"] == {"member": "Resources[]", "query": "ResourceId", "prefixes": {"instance": "i-"}}
    member["queryName"] = "ExactWireName"
    assert r.entry_for("ec2:AnnotateResources", upstream, formats, {"ec2": ec2})["targets"]["query"] == "ExactWireName"
    # Neither a resource type the action does not authorize nor a scalar masquerading as a list is read.
    assert "targets" not in r.entry_for("ec2:AnnotateResources", {**upstream, "types": ["volume"]}, formats, {"ec2": ec2})
    member["shape"] = "String"
    assert "targets" not in r.entry_for("ec2:AnnotateResources", upstream, formats, {"ec2": ec2})


@pytest.mark.parametrize("operation,target", [(operation, entry["targets"])
    for operation, entry in json.loads(r.OUTPUT.read_text())["operations"].items() if "targets" in entry])
def test_generated_targets_match_botocores_actual_serialization(operation, target):
    import botocore.session
    from botocore.serialize import create_serializer
    from botocore.utils import ArgumentGenerator

    document = json.loads(r.OUTPUT.read_text())
    namespace, name = operation.split(":", 1)
    first = next(iter(target["prefixes"].values())) + "first"
    ids = [first, "unmodelled-second", first]
    checked = 0
    for client in document["namespaces"][namespace]:
        model = botocore.session.get_session().get_service_model(client)
        if name not in model.operation_names:
            continue
        operation_model = model.operation_model(name)
        parameters = ArgumentGenerator().generate_skeleton(operation_model.input_shape)
        parameters[target["member"].removesuffix("[]")] = ids
        request = create_serializer(model.metadata["protocol"]).serialize_to_request(parameters, operation_model)
        assert [request["body"][target["query"] + "." + str(i)] for i in range(1, 4)] == ids
        checked += 1
    assert checked > 0


def test_instance_target_member_is_derived_from_the_arn_variable_not_the_action_name():
    names = ("StartInstances", "StopInstances", "RebootInstances", "TerminateInstances", "InventedAction")
    ec2 = model({name: {"InstanceIds": "Strings", "Resources": "Strings"} for name in names}, "ec2")
    formats = {"instance": "arn:${Partition}:ec2:${Region}:${Account}:instance/${InstanceId}"}
    for name in names:
        member = ec2["shapes"][name + "In"]["members"]["InstanceIds"]
        member["locationName"] = "instanceId"
        upstream = {"action": "ec2:" + name, "types": ["instance"]}
        entry = r.entry_for("ec2:" + name, upstream, formats, {"ec2": ec2})
        assert entry["targets"] == {"member": "InstanceIds[]", "query": "InstanceId", "prefixes": {"instance": "i-"}}
        member["queryName"] = "ExactWireName"
        assert r.entry_for("ec2:" + name, upstream, formats, {"ec2": ec2})["targets"]["query"] == "ExactWireName"
    # Unrelated lists cannot become instance targets, even with a familiar action name.
    del ec2["shapes"]["StartInstancesIn"]["members"]["InstanceIds"]
    del ec2["shapes"]["StartInstancesIn"]["members"]["Resources"]
    ec2["shapes"]["StartInstancesIn"]["members"]["DecoyInstanceIds"] = {"shape": "Strings"}
    assert "targets" not in r.entry_for("ec2:StartInstances",
            {"action": "ec2:StartInstances", "types": ["instance"]}, formats, {"ec2": ec2})
    renamed = model({"InventedAction": {"MachineIds": "Strings"}}, "ec2")
    renamed_formats = {"instance": "arn:${Partition}:ec2:${Region}:${Account}:instance/${MachineId}"}
    assert r.entry_for("ec2:InventedAction", {"action": "ec2:InventedAction", "types": ["instance"]},
                       renamed_formats, {"ec2": renamed})["targets"]["member"] == "MachineIds[]"


def test_resource_tag_prefixes_come_only_from_supported_reference_resources():
    documents = [{"Name": "ec2", "Resources": [
        {"Name": "instance", "ConditionKeys": ["vendor:ResourceTag/${TagKey}", "aws:RequestTag/${TagKey}",
                                               "aws:ResourceTag/${TagKey}", "ec2:Region"]},
        {"Name": "volume", "ConditionKeys": ["volume:ResourceTag/${TagKey}"]}]}]
    assert r.resource_tag_keys(documents) == {"ec2": {"instance": ["aws:ResourceTag/", "vendor:ResourceTag/"]}}
    assert r.resource_tag_keys([]) == {}


def test_id_targets_reject_unknown_types_ambiguous_prefixes_and_malformed_members():
    import copy

    document = json.loads(r.OUTPUT.read_text())
    for field, value in (("member", "Resources"), ("query", "ResourceId.member"),
                         ("prefixes", {"not-a-type": "i-"}), ("prefixes", {"instance": ""}),
                         ("prefixes", {"instance": "i-", "volume": "i-"})):
        invalid = copy.deepcopy(document)
        invalid["operations"]["ec2:CreateTags"]["targets"][field] = value
        assert r.validate(invalid), (field, value)


@pytest.mark.parametrize("operation", [
    "GetLandingZone", "UpdateLandingZone", "DeleteLandingZone", "ResetLandingZone",
    "GetBaseline", "GetEnabledBaseline", "UpdateEnabledBaseline", "ResetEnabledBaseline", "DisableBaseline",
    "GetEnabledControl", "UpdateEnabledControl", "ResetEnabledControl", "DisableControl", "EnableControl",
])
def test_controltower_entries_are_derived_without_operation_corrections(operation):
    from regen_partitions import resolve_botocore_data

    key = "controltower:" + operation
    document = json.loads(r.OUTPUT.read_text())
    data, _ = resolve_botocore_data(None)
    model = r.load_models(data, {"controltower"})
    expected = document["operations"][key]
    assert r.entry_for(key, {"action": expected["action"], "types": expected["types"]},
                       document["arnFormats"]["controltower"], model) == expected


@pytest.mark.parametrize("pattern, expected", [
    (None, False), ("", False), ("arn:aws:service:.*", True),
    (r"^arn:(aws|aws-cn):service:[a-z0-9-]+:.*$", True),
    (r"arn:aws:service:[a|b]+", True), (r"arn:aws:service:foo\|bar", True),
    (r"^arn:aws:service:.*$|plain-name", False),
    (r"(arn:aws:service:.*)?plain-name", False),
])
def test_arn_only_patterns_distinguish_nested_and_top_level_alternatives(pattern, expected):
    assert r.only_arn(pattern) is expected


def test_arn_only_identifier_never_fills_a_different_resource_id():
    formats = {"EnabledControl": "arn:${Partition}:svc:${Region}:${Account}:enabledcontrol/${EnabledControlId}"}
    result = r.resources_for("svc:EnableControl", ["EnabledControl"], formats,
                             {"controlIdentifier": "controlIdentifier"}, {"controlIdentifier"})
    assert result == [{"types": ["EnabledControl"], "arn": "controlIdentifier"},
                      {"type": "EnabledControl", "fill": {}}]


def test_explicit_arn_member_set_is_respected_by_resource_mapping():
    formats = {"pool": "arn:${Partition}:svc:${Region}:${Account}:pool/${PoolId}"}
    assert r.resources_for("svc:ReadPool", ["pool"], formats,
                           {"opaqueReference": "opaqueReference"}, {"opaqueReference"}) == [
        {"types": ["pool"], "arn": "opaqueReference"}]



@pytest.mark.parametrize("member", ["opaqueReference", "targetApplicationArn", "applicationArn"])
def test_arn_pattern_does_not_turn_unrelated_members_into_identifiers(member):
    model = {
        "metadata": {"protocol": "json", "endpointPrefix": "svc"},
        "operations": {"ReadPool": {"input": {"shape": "Input"}}},
        "shapes": {"Input": {"type": "structure", "members": {member: {"shape": "Value"}}},
                   "Value": {"type": "string", "pattern": "arn:.*"}},
    }
    formats = {"pool": "arn:${Partition}:svc:${Region}:${Account}:pool/${PoolId}"}
    entry = r.entry_for("svc:ReadPool", {"action": "svc:ReadPool", "types": ["pool"]}, formats, {"svc": model})
    assert entry["resources"] == r.resources_for("svc:ReadPool", ["pool"], formats, {member: member})


def test_no_inferred_identifier_metadata_or_withdrawn_redshift_rows():
    document = json.loads(r.OUTPUT.read_text())
    resource = document["operations"]["redshift:DescribeClusters"]["resources"][0]
    assert "arnOf" not in resource


def test_sso_access_scope_does_not_authorize_target_applications():
    from regen_partitions import resolve_botocore_data

    document = json.loads(r.OUTPUT.read_text())
    data, _ = resolve_botocore_data(None)
    operation = "sso:PutApplicationAccessScope"
    entry = document["operations"][operation]
    models = r.load_models(data, set(document["namespaces"]["sso"]))
    derived = r.entry_for(operation, {"action": entry["action"], "types": entry["types"]},
                          document["arnFormats"]["sso"], models)
    assert derived["resources"] == [{"types": ["Application"], "arn": "ApplicationArn"}]


def test_a_path_segment_in_ies_names_the_type_in_y():
    # lever_model's operation stands in for Route 53's ListTrafficPolicyVersions, /trafficpolicies/{Id}/versions.
    labels = r.path_labels({"route53": lever_model("/2013-04-01/trafficpolicies/{id}/versions")},
                           "UpdateSafetyLeverState")
    assert labels == {"id": "trafficpolicy"}
    formats = {"trafficpolicy": "arn:${Partition}:route53:::trafficpolicy/${Id}"}
    assert r.resources_for("route53:ListTrafficPolicyVersions", ["trafficpolicy"], formats, members("id"),
                           labels=labels) == [{"type": "trafficpolicy", "fill": {"Id": "id"}}]
    assert r.resources_for("route53:ListTrafficPolicyVersions", ["trafficpolicy"], formats, members("id")) == []


def test_a_member_whose_enum_lists_two_of_the_actions_types_picks_the_type():
    formats = {"healthcheck": "arn:${Partition}:route53:::healthcheck/${Id}",
               "hostedzone": "arn:${Partition}:route53:::hostedzone/${Id}"}
    members = {"ResourceType": "ResourceType", "ResourceId": "ResourceId"}
    enums = {"ResourceType": {"healthcheck", "hostedzone"}}
    assert r.picked_by_enum("route53:TagResource", ["healthcheck", "hostedzone"], formats, members, enums) == [
        {"type": "healthcheck", "fill": {"Id": "ResourceId"}, "when": {"ResourceType": ["healthcheck"]}},
        {"type": "hostedzone", "fill": {"Id": "ResourceId"}, "when": {"ResourceType": ["hostedzone"]}}]
    # An enum listing one of the action's types, or none of them, picks nothing.
    assert r.picked_by_enum("route53:TagResource", ["hostedzone"], formats, members, enums) is None
    assert r.picked_by_enum("route53:TagResource", ["healthcheck", "hostedzone"], formats, members, {"ResourceType": {"other"}}) is None
    # Without a member naming the resource, the request names none of them.
    assert r.picked_by_enum("route53:TagResource", ["healthcheck", "hostedzone"], formats, {"ResourceType": "ResourceType"}, enums) is None


def test_an_enum_value_picks_a_type_ignoring_case_and_hyphens_and_the_resource_id_fills_it():
    formats = {"document": "arn:${Partition}:ssm:${Region}:${Account}:document/${DocumentName}",
               "managed-instance": "arn:${Partition}:ssm:${Region}:${Account}:managed-instance/${InstanceId}",
               "parameter": "arn:${Partition}:ssm:${Region}:${Account}:parameter/${ParameterNameWithoutLeadingSlash}"}
    members = {"ResourceType": "ResourceType", "ResourceId": "ResourceId"}
    enums = {"ResourceType": {"Document", "ManagedInstance", "Parameter", "Automation"}}
    assert r.picked_by_enum("ssm:AddTagsToResource", ["document", "managed-instance", "parameter"], formats, members,
                            enums) == [
        {"type": "document", "fill": {"DocumentName": "ResourceId"}, "when": {"ResourceType": ["Document"]}},
        {"type": "managed-instance", "fill": {"InstanceId": "ResourceId"},
         "when": {"ResourceType": ["ManagedInstance"]}},
        {"type": "parameter", "fill": {"ParameterNameWithoutLeadingSlash": "ResourceId"},
         "when": {"ResourceType": ["Parameter"]}}]


def test_a_member_whose_shape_is_named_after_the_type_names_it():
    assert r.resources_for("svc:GetThingsByPath", ["thing"], FORMATS, members("Path"),
                           shapes={"Path": "ThingName"}) == [{"type": "thing", "fill": {"ThingName": "Path"}}]
    assert r.resources_for("svc:GetThingsByPath", ["thing"], FORMATS, members("Path")) == []
    # A shape named for something else, a shape of another kind, or a list names nothing.
    assert r.resources_for("svc:GetThingsByPath", ["thing"], FORMATS, members("Path"), shapes={"Path": "PathString"}) == []
    assert r.resources_for("svc:GetPoolsByPath", ["pool"], FORMATS, members("Path"), shapes={"Path": "PoolName"}) == []
    assert r.resources_for("svc:GetThingsByPath", ["thing"], FORMATS, members("Paths[]"),
                           shapes={"Paths": "ThingName"}) == []


# --------------------------------------------------------------------------- #
# What botocore's documentation and shapes say a member names
# --------------------------------------------------------------------------- #
def documented(operation: str, fields: dict[str, tuple[str, str]], output: dict[str, str] | None = None,
               protocol: str = "json") -> dict:
    """A model whose operation takes the members given, each a shape name and its documentation."""
    shapes = {"String": {"type": "string"}, "Strings": {"type": "list", "member": {"shape": "String"}},
              "AccountId": {"type": "string", "pattern": "\\d{12}"},
              "RoleArn": {"type": "string", "pattern": "arn:aws(-[\\w]+)*:iam::[0-9]{12}:role/.*"}}
    shapes["In"] = {"type": "structure", "members": {name: {"shape": shape, "documentation": f"<p>{doc}</p>"}
                                                     for name, (shape, doc) in fields.items()}}
    spec = {"input": {"shape": "In"}}
    if output:
        shapes["Out"] = {"type": "structure", "members": {name: {"shape": shape} for name, shape in output.items()}}
        spec["output"] = {"shape": "Out"}
    return {"metadata": {"protocol": protocol, "endpointPrefix": "svc"}, "operations": {operation: spec}, "shapes": shapes}


DOCUMENTED = {"pipeline": "arn:${Partition}:svc:${Region}:${Account}:pipeline/${PipelineName}",
              "processor": "arn:${Partition}:svc:${Region}:${Account}:processor/${ProcessorId}",
              "trail": "arn:${Partition}:svc:${Region}:${Account}:trail/${TrailName}",
              "dashboard": "arn:${Partition}:svc:${Region}:${Account}:dashboard/${DashboardName}"}


def test_a_member_whose_documentation_names_the_type_fills_it_or_is_found_by_its_service():
    starts = {"svc": documented("StartPipelineExecution", {"name": ("String", "The name of the pipeline to start.")})}
    assert r.with_documented("svc:StartPipelineExecution", ["pipeline"], DOCUMENTED, starts, []) == [
        {"type": "pipeline", "fill": {"PipelineName": "name"}}]
    # A name for an id variable: the type's service finds the resource by it, its id open when it finds none.
    stops = {"svc": documented("StopProcessor", {"Name": ("String", "The name of the processor to stop.")})}
    assert r.with_documented("svc:StopProcessor", ["processor"], DOCUMENTED, stops, []) == [
        {"type": "processor", "fill": {}, "find": ["Name"]}]
    # A type named only past the word the documentation stops at names nothing: a pipeline execution is no pipeline.
    runs = {"svc": documented("GetRun", {"id": ("String", "The ID of the pipeline execution.")})}
    assert r.with_documented("svc:GetRun", ["pipeline"], DOCUMENTED, runs, []) == []
    # A type an ARN member of the request already holds takes no other member: an update's Name is the new name.
    held = [{"types": ["pipeline"], "arn": "PipelineArn"}]
    updates = {"svc": documented("UpdatePipeline", {"Name": ("String", "The name of the pipeline."),
                                                    "PipelineArn": ("String", "The ARN of the pipeline.")})}
    assert r.with_documented("svc:UpdatePipeline", ["pipeline"], DOCUMENTED, updates, held) == held


def test_a_member_whose_documentation_says_it_is_an_arn_holds_one():
    tags = {"svc": documented("AddTags", {"ResourceId": ("String", "Specifies the ARN of the trail or dashboard to "
                                                                  "which one or more tags will be added.")})}
    assert r.with_documented("svc:AddTags", ["dashboard", "trail"], DOCUMENTED, tags, []) == [
        {"types": ["dashboard", "trail"], "arn": "ResourceId"}]
    # A name or an ARN is a name, and an ARN of something else is no ARN of the action's types.
    named = {"svc": documented("GetTrail", {"Name": ("String", "Specifies the name or the ARN of the trail.")})}
    assert r.with_documented("svc:GetTrail", ["trail"], DOCUMENTED, named, []) == [
        {"type": "trail", "fill": {"TrailName": "Name"}}]
    keys = {"svc": documented("UntagResource", {"ResourceTagKeys": ("Strings", "The tag keys associated with the "
                                                                              "resource ARN.")})}
    assert r.with_documented("svc:UntagResource", ["trail"], DOCUMENTED, keys, []) == []
    assert r.doc_head("the amazon resource name (arn) for which you want to describe the tags") == (
        "the amazon resource name (arn)", None)


def test_an_arn_member_whose_pattern_takes_another_services_arns_names_none_of_the_actions_resources():
    models = {"svc": documented("CreatePipeline", {"roleArn": ("RoleArn", "The role it assumes.")})}
    assert r.with_documented("svc:CreatePipeline", ["pipeline"], DOCUMENTED, models,
                             [{"types": ["pipeline"], "arn": "roleArn"}]) == []
    # A pattern whose ARN is optional, in a group of its own: CUR's billing view ARN.
    views = documented("PutPipeline", {"viewArn": ("ViewArn", "The billing view.")})
    views["shapes"]["ViewArn"] = {"type": "string",
                                  "pattern": "(arn:aws(-cn)?:billing::[0-9]{12}:billingview/)?[a-zA-Z0-9_\\+=\\.\\-@].{1,30}"}
    assert r.with_documented("svc:PutPipeline", ["pipeline"], DOCUMENTED, {"svc": views},
                             [{"types": ["pipeline"], "arn": "viewArn"}]) == []


def test_an_arn_member_documented_as_another_types_names_none_of_the_actions_resources():
    # CreateExport's `Export.ExportArn`, "the ARN for this export", is the export's, of no type the action acts on.
    model_ = documented("CreateTrailExport", {})
    model_["shapes"]["Export"] = {"type": "structure", "members": {
        "ExportArn": {"shape": "String", "documentation": "<p>The Amazon Resource Name (ARN) for this dashboard.</p>"}}}
    model_["shapes"]["In"]["members"]["Export"] = {"shape": "Export"}
    assert r.with_documented("svc:CreateTrailExport", ["trail"], DOCUMENTED, {"svc": model_},
                             [{"types": ["trail"], "arn": "Export.ExportArn"}]) == []
    # One documented as an acted-on type's is kept, and so is one named after it, whatever its documentation says.
    model_["shapes"]["Export"]["members"]["ExportArn"]["documentation"] = "<p>The ARN of the trail.</p>"
    assert r.with_documented("svc:CreateTrailExport", ["trail"], DOCUMENTED, {"svc": model_},
                             [{"types": ["trail"], "arn": "Export.ExportArn"}]) == [{"types": ["trail"], "arn": "Export.ExportArn"}]
    named = documented("PutTrailSetting", {"trailArn": ("String", "The Amazon Resource Name (ARN) of the dashboard.")})
    assert r.with_documented("svc:PutTrailSetting", ["trail"], DOCUMENTED, {"svc": named},
                             [{"types": ["trail"], "arn": "trailArn"}]) == [{"types": ["trail"], "arn": "trailArn"}]


def test_a_structures_field_and_each_lists_element_name_the_type_they_are_named_after():
    model_ = documented("CreateBudget", {})
    model_["shapes"]["Budget"] = {"type": "structure", "members": {"BudgetName": {"shape": "String"},
                                                                   "BillingViewArn": {"shape": "String"}}}
    model_["shapes"]["In"]["members"]["Budget"] = {"shape": "Budget"}
    formats = {"budget": "arn:${Partition}:svc::${Account}:budget/${BudgetName}"}
    assert r.with_documented("svc:CreateBudget", ["budget"], formats, {"svc": model_},
                             [{"type": "budget", "fill": {}}]) == [{"type": "budget", "fill": {"BudgetName": "Budget.BudgetName"}}]
    # A field of each element of a list of structures, as of each value of a map.
    entries = documented("PutEvents", {})
    entries["shapes"]["Entry"] = {"type": "structure", "members": {"BusName": {"shape": "String"}}}
    entries["shapes"]["Entries"] = {"type": "list", "member": {"shape": "Entry"}}
    entries["shapes"]["In"]["members"]["Entries"] = {"shape": "Entries"}
    assert r.map_fields({"svc": entries}, "PutEvents") == {"Entries[].BusName": "BusName"}


def test_the_id_a_create_answers_beside_another_types_arn_is_that_types():
    model_ = documented("TerminateJobFlows", {"JobFlowIds": ("Strings", "A list of job flows to be shut down.")})
    model_["operations"]["RunJobFlow"] = {"input": {"shape": "In"}, "output": {"shape": "RunOut"}}
    model_["shapes"]["RunOut"] = {"type": "structure", "members": {"JobFlowId": {"shape": "String"},
                                                                   "ClusterArn": {"shape": "String"}}}
    assert r.created_aliases({"svc": model_}) == {"jobflow": "cluster"}
    formats = {"cluster": "arn:${Partition}:svc:${Region}:${Account}:cluster/${ClusterId}"}
    assert r.with_documented("svc:TerminateJobFlows", ["cluster"], formats, {"svc": model_}, []) == [
        {"type": "cluster", "fill": {"ClusterId": "JobFlowIds[]"}}]


def test_an_account_id_member_fills_an_account_id_variable():
    models = {"svc": documented("CreateAssignment", {"TargetId": ("AccountId", "TargetID is an account identifier.")})}
    formats = {"Account": "arn:${Partition}:svc:::account/${AccountId}"}
    assert r.with_documented("svc:CreateAssignment", ["Account"], formats, models, []) == [
        {"type": "Account", "fill": {"AccountId": "TargetId"}}]


def test_a_non_create_about_a_type_its_action_does_not_act_on_reaches_the_acted_types_through_its_service():
    formats = {"build": "arn:${Partition}:svc:${Region}:${Account}:build/${BuildId}",
               "project": "arn:${Partition}:svc:${Region}:${Account}:project/${ProjectName}"}
    gets = {"svc": documented("BatchGetBuilds", {"ids": ("Strings", "The IDs of the builds.")})}
    assert r.with_documented("svc:BatchGetBuilds", ["project"], formats, gets, []) == [
        {"types": ["project"], "through": "ids[]"}]
    # A create's bare name is the new resource's.
    creates = {"svc": documented("CreateBuild", {"name": ("String", "The name of the build.")})}
    assert r.with_documented("svc:CreateBuild", ["project"], formats, creates, []) == []


def test_an_enum_value_the_word_setting_two_formats_apart_abbreviates_picks_the_type():
    formats = {"loadbalancer": "arn:${Partition}:svc:${Region}:${Account}:loadbalancer/${LoadBalancerName}",
               "loadbalancer/app/": "arn:${Partition}:svc:${Region}:${Account}:loadbalancer/app/${LoadBalancerName}/${LoadBalancerId}",
               "loadbalancer/gwy/": "arn:${Partition}:svc:${Region}:${Account}:loadbalancer/gwy/${LoadBalancerName}/${LoadBalancerId}"}
    models = {"svc": documented("CreateLoadBalancer", {"Name": ("String", "The name of the load balancer."),
                                                       "Type": ("String", "The type of load balancer. The default is application.")})}
    picked = r.picked_by_enum("svc:CreateLoadBalancer", ["loadbalancer/app/", "loadbalancer/gwy/"], formats,
                              {"Name": "Name", "Type": "Type"}, {"Type": {"application", "gateway", "network"}}, models)
    assert picked == [
        {"type": "loadbalancer/app/", "fill": {"LoadBalancerName": "Name"}, "when": {"Type": ["application", None]}},
        {"type": "loadbalancer/gwy/", "fill": {"LoadBalancerName": "Name"}, "when": {"Type": ["gateway"]}}]


def test_two_types_differing_by_one_variable_are_marked_for_the_default_its_service_gives():
    formats = {"rule-on-default-bus": "arn:${Partition}:svc:${Region}:${Account}:rule/${RuleName}",
               "rule-on-custom-bus": "arn:${Partition}:svc:${Region}:${Account}:rule/${BusName}/${RuleName}",
               "bus": "arn:${Partition}:svc:${Region}:${Account}:bus/${BusName}"}
    resources = [{"type": "rule-on-custom-bus", "fill": {"BusName": "BusName", "RuleName": "Name"}},
                 {"type": "rule-on-default-bus", "fill": {"RuleName": "Name"}}]
    assert r.with_default_pairs(resources, formats, {"BusName"}) == [
        dict(resources[0], unlessDefault={"BusName": "bus"}), dict(resources[1], whenDefault={"BusName": "bus"})]
    # A required member always names its bus.
    assert r.with_default_pairs(resources, formats, set()) == resources


def test_a_resource_one_optional_member_qualifies_is_named_only_when_the_request_gives_it():
    resources = [{"type": "dbname", "fill": {"ClusterName": "ClusterIdentifier", "DbName": "DbName"}},
                 {"type": "dbuser", "fill": {"ClusterName": "ClusterIdentifier", "DbUser": "DbUser"}}]
    assert r.with_optional_qualifier(resources, {"ClusterIdentifier", "DbName"}) == [
        dict(resources[0], unless={"DbName": [None]}), resources[1]]
    assert r.with_optional_qualifier(resources, {"ClusterIdentifier"}) == resources


def test_an_arn_patterns_type_words_and_a_clients_versioned_twin():
    assert r.pattern_words("arn:aws:svc:[a-z0-9-]+:([0-9]{12}):(bus-v2|subscriber|source-v2\\/(a|b))\\/[A-Za-z]+") == {
        "bus-v2", "subscriber", "source-v2"}
    assert r.pattern_words("(arn:aws[\\w-]*:svc:[a-z]+:[0-9]{12}:bus\\/)?[A-Za-z]+") == {"bus"}
    formats = {"bus": "arn:${Partition}:svc:${Region}:${Account}:bus/${BusName}",
               "busv2": "arn:${Partition}:svc:${Region}:${Account}:busv2/${BusName}/${OpaqueId}"}
    model_ = documented("CreateBus", {"Name": ("String", "The name of the bus.")})
    # A client whose patterns name only the versioned type reads the other as it.
    assert r.narrowed([{"type": "bus", "fill": {"BusName": "Name"}}], {"busv2"}, model_, "CreateBus", formats) == [
        {"type": "busv2", "fill": {"BusName": "Name"}}]
    assert r.narrowed([{"type": "bus", "fill": {"BusName": "Name"}}], {"bus", "busv2"}, model_, "CreateBus", formats) == [
        {"type": "bus", "fill": {"BusName": "Name"}}]


def test_a_lone_resource_id_in_a_rest_request_is_found_by_its_path_labels_and_the_member_named_after_it():
    model_ = documented("DescribeUser", {"AwsAccountId": ("String", "The account."), "Namespace": ("String", "The namespace."),
                                         "UserName": ("String", "The name of the user that you want to describe.")},
                        protocol="rest-json")
    model_["operations"]["DescribeUser"]["http"] = {
        "method": "GET", "requestUri": "/accounts/{AwsAccountId}/namespaces/{Namespace}/users/{UserName}"}
    for name in ("AwsAccountId", "Namespace", "UserName"):
        model_["shapes"]["In"]["members"][name].update(location="uri", locationName=name)
    formats = {"user": "arn:${Partition}:svc:${Region}:${Account}:user/${ResourceId}"}
    assert r.with_documented("svc:DescribeUser", ["user"], formats, {"svc": model_}, []) == [
        {"type": "user", "fill": {}, "find": ["AwsAccountId", "Namespace", "UserName"]}]
    # A member holding the user's ARN names it by that ARN, and its service has nothing to find.
    embeds = documented("GenerateEmbedUrlForRegisteredUser", {"AwsAccountId": ("String", "The account."),
                                                              "UserArn": ("String", "The ARN of the user.")},
                        protocol="rest-json")
    embeds["operations"]["GenerateEmbedUrlForRegisteredUser"]["http"] = {
        "method": "POST", "requestUri": "/accounts/{AwsAccountId}/embed-url/registered-user"}
    embeds["shapes"]["In"]["members"]["AwsAccountId"].update(location="uri", locationName="AwsAccountId")
    held = [{"types": ["user"], "arn": "UserArn"}]
    assert r.with_documented("svc:GenerateEmbedUrlForRegisteredUser", ["user"], formats, {"svc": embeds}, held) == held
    # A label after the member's own names something inside it: a membership's member is not its group's.
    members_ = documented("DescribeGroupMembership", {name: ("String", "A label.") for name in
                                                      ("AwsAccountId", "Namespace", "GroupName", "MemberName")},
                          protocol="rest-json")
    members_["operations"]["DescribeGroupMembership"]["http"] = {
        "method": "GET",
        "requestUri": "/accounts/{AwsAccountId}/namespaces/{Namespace}/groups/{GroupName}/members/{MemberName}"}
    for name in ("AwsAccountId", "Namespace", "GroupName", "MemberName"):
        members_["shapes"]["In"]["members"][name].update(location="uri", locationName=name)
    groups = {"group": "arn:${Partition}:svc:${Region}:${Account}:group/${ResourceId}"}
    found = [{"type": "group", "fill": {}, "find": ["GroupName"]}]
    assert r.with_documented("svc:DescribeGroupMembership", ["group"], groups, {"svc": members_}, found) == [
        {"type": "group", "fill": {}, "find": ["AwsAccountId", "Namespace", "GroupName"]}]


def test_a_resource_id_naming_whatever_the_action_acts_on_reaches_it_through_its_service():
    formats = {"cluster": "arn:${Partition}:svc:${Region}:${Account}:cluster/${ClusterId}",
               "studio": "arn:${Partition}:svc:${Region}:${Account}:studio/${StudioId}"}
    models = {"svc": documented("AddTags", {"ResourceId": ("String", "The resource identifier to tag."),
                                            "ClusterId": ("String", "The cluster that scopes the call.")})}
    # Another member then scopes the call rather than names a resource.
    assert r.with_documented("svc:AddTags", ["cluster", "studio"], formats, models,
                             [{"type": "cluster", "fill": {"ClusterId": "ClusterId"}}]) == [
        {"types": ["cluster", "studio"], "through": "ResourceId"}]


def test_a_resource_id_variable_takes_a_member_named_so_and_a_bare_id_the_member_named_after_the_type():
    # CreateOpsMetadata names `opsmetadata/${ResourceId}` by its `ResourceId`.
    assert r._filling("ResourceId", "opsmetadata", 1, {"ResourceId": "ResourceId", "Tags": "Tags[]"}, set()) == \
        "ResourceId"
    # A QuickSight ingestion's `ResourceId` is its `IngestionId` when no member is named so.
    assert r._filling("ResourceId", "ingestion", 1, {"IngestionId": "IngestionId"}, set()) == "IngestionId"
    # A bare `Id` is whatever the operation is about: the tenant's is its `DistributionTenantId`.
    assert r._filling("Id", "distributiontenant", 1, {"Id": "Id", "DistributionTenantId": "DistributionTenantId"},
                      {"Id"}) == "DistributionTenantId"
