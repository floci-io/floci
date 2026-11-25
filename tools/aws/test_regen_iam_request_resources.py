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
    # A format ending in a wildcard (DMS's `cert:*`) names every resource of its type, so a request leaving the member
    # out names none of them.
    wildcard = {"cert": "arn:${Partition}:svc:${Region}:${Account}:cert:*"}
    assert r.with_absent_arn([{"types": ["cert"], "arn": "CertificateArn"}], wildcard, {"CertificateArn"}) == [
        {"types": ["cert"], "arn": "CertificateArn"}]


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


def test_a_create_joining_a_related_type_by_a_member_of_its_own_makes_the_type_named_as_it_is(tmp_path):
    # MemoryDB's CreateCluster names the multi-Region cluster it may join and makes a cluster, whose tags it carries.
    write_models(tmp_path, {"svc": model({
        "CreateCluster": {"ClusterName": "String", "MultiRegionClusterName": "String", "Tags": "Strings"},
        "CreatePermission": {"name": "String", "Tags": "Strings"}})})
    models = r.load_models(tmp_path, {"svc"})
    clusters = {"cluster": "arn:${Partition}:svc:${Region}:${Account}:cluster/${ClusterName}",
                "multiregioncluster": "arn:${Partition}:svc::${Account}:multiregioncluster/${ClusterName}"}
    assert r.makes_for("svc:CreateCluster", ["multiregioncluster"], clusters,
                       members("ClusterName", "MultiRegionClusterName"), models=models) == [
        {"type": "cluster", "fill": {"ClusterName": "ClusterName"}}]
    # RAM's CreatePermission names no customer managed permission: that is the one it makes, not the AWS managed one.
    permissions = {"permission": "arn:${Partition}:svc::${Account}:permission/${ResourcePath}",
                   "customer-managed-permission":
                       "arn:${Partition}:svc:${Region}:${Account}:permission/${ResourcePath}"}
    assert r.makes_for("svc:CreatePermission", ["customer-managed-permission"], permissions, members("name"),
                       models=models) == []


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
        assert r.located_members(models, "GetThing") == ({"Id": "{Id}", "Marker": "?Marker"}, {}), protocol


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
    # Where a type is named as the create is, a type whose name merely ends in that word is one it joins, not makes:
    # ElastiCache's CreateReplicationGroup makes a replication group, not the global one it may join.
    groups = {"replicationgroup": "arn:${Partition}:svc:${Region}:${Account}:replicationgroup:${ReplicationGroupId}",
              "globalreplicationgroup": "arn:${Partition}:svc::${Account}:globalreplicationgroup:${GlobalId}"}
    made = {"type": "replicationgroup", "fill": {"ReplicationGroupId": "ReplicationGroupId"}}
    joined = {"type": "globalreplicationgroup", "fill": {"GlobalId": "GlobalReplicationGroupId"}}
    assert r.with_made([made, joined], "svc:CreateReplicationGroup", groups) == [dict(made, made=True), joined]
    # A type's whole name counts before its stem, which drops a trailing word: SageMaker's CreateEndpoint makes an
    # endpoint, not the endpoint-config whose stem is endpoint too.
    endpoints = {"endpoint": "arn:${Partition}:svc:${Region}:${Account}:endpoint/${EndpointName}",
                 "endpoint-config": "arn:${Partition}:svc:${Region}:${Account}:endpoint-config/${EndpointConfigName}"}
    endpoint = {"type": "endpoint", "fill": {"EndpointName": "EndpointName"}}
    config = {"type": "endpoint-config", "fill": {"EndpointConfigName": "EndpointConfigName"}}
    assert r.with_made([endpoint, config], "svc:CreateEndpoint", endpoints) == [dict(endpoint, made=True), config]


def test_a_type_made_in_another_takes_that_ones_id_from_the_arn_member_named_after_it():
    formats = {"instance": "arn:${Partition}:svc:::instance/${InstanceId}",
               "set": "arn:${Partition}:svc:::set/${InstanceId}/${SetId}",
               "pool": FORMATS["pool"]}
    made = {"type": "set", "fill": {}, "made": True}
    instance = {"types": ["instance", "set"], "arn": "InstanceArn"}
    assert r.with_parent_ids([instance, made], formats, ["instance", "set"], members("InstanceArn", "Name")) == [
        instance, {"type": "set", "fill": {"InstanceId": "InstanceArn"}, "arnOf": {"InstanceArn": "instance"},
                   "made": True}]
    # Not from a type the action does not act on, a member in a structure or a list's elements (keyed by its path, as
    # RAM's permissionTemplateParameters[].permissionArn is), or with two ARN members to pick from, and not over a
    # variable a member fills already.
    assert r.with_parent_ids([made], formats, ["set"], members("InstanceArn")) == [made]
    assert r.with_parent_ids([made], formats, ["instance", "set"], {"InstanceArn": "Config.InstanceArn"}) == [made]
    for path in ("Config.InstanceArn", "Configs[].InstanceArn"):
        assert r.with_parent_ids([made], formats, ["instance", "set"], {path: path}) == [made]
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
    monkeypatch.setattr(r, "NOT_COVERED", {"later": "a later batch"})
    monkeypatch.setattr(r, "MEASURED", {})
    monkeypatch.setattr(r, "STATEMENT_SOURCES", {})
    monkeypatch.setattr(r, "PATH_SOURCES", {})
    monkeypatch.setattr(r, "PERMISSION_SOURCES", {})
    return monkeypatch


def write_models(root, models: dict[str, dict]):
    for name, value in models.items():
        directory = root / name / "2020-01-01"
        directory.mkdir(parents=True)
        (directory / "service-2.json").write_text(json.dumps(value), encoding="utf-8")


def tables_model() -> dict:
    """A rest-json model naming a table by its table bucket's ARN, its namespace and its name, as S3 Tables does: in its
    path, documented as the table's name or holding a `TableName`, and in `GetTable`'s query string beside an optional
    `tableArn`."""
    def table_members(location: str, name_doc: str) -> dict:
        return {"tableBucketARN": {"shape": "TableBucketARN", "location": location, "locationName": "tableBucketARN",
                                   "documentation": "<p>The Amazon Resource Name (ARN) of the table bucket that "
                                                    "contains the table.</p>"},
                "namespace": {"shape": "NamespaceName", "location": location, "locationName": "namespace",
                              "documentation": "<p>The namespace associated with the table.</p>"},
                "name": {"shape": "TableName", "location": location, "locationName": "name",
                         "documentation": name_doc}}
    get = table_members("querystring", "<p>The name of the table.</p>")
    get["tableArn"] = {"shape": "TableARN", "location": "querystring", "locationName": "tableArn",
                       "documentation": "<p>The Amazon Resource Name (ARN) of the table.</p>"}
    return {"metadata": {"protocol": "rest-json"},
            "operations": {
                "DeleteTable": {"http": {"method": "DELETE",
                                         "requestUri": "/tables/{tableBucketARN}/{namespace}/{name}"},
                                "input": {"shape": "DeleteIn"}},
                "GetTablePolicy": {"http": {"method": "GET",
                                            "requestUri": "/tables/{tableBucketARN}/{namespace}/{name}/policy"},
                                   "input": {"shape": "PolicyIn"}},
                "GetTable": {"http": {"method": "GET", "requestUri": "/get-table"}, "input": {"shape": "GetIn"}},
                "GetTableBucket": {"http": {"method": "GET", "requestUri": "/buckets/{tableBucketARN}"},
                                   "input": {"shape": "BucketIn"}}},
            "shapes": {
                "DeleteIn": {"type": "structure", "required": ["tableBucketARN", "namespace", "name"],
                             "members": table_members("uri", "<p>The name of the table.</p>")},
                "PolicyIn": {"type": "structure", "required": ["tableBucketARN", "namespace", "name"],
                             "members": table_members("uri", "<p>The table name.</p>")},
                "GetIn": {"type": "structure", "members": get},
                "BucketIn": {"type": "structure", "required": ["tableBucketARN"],
                             "members": {"tableBucketARN": table_members("uri", "")["tableBucketARN"]}},
                "TableBucketARN": {"type": "string", "pattern": "(arn:aws[-a-z0-9]*:[a-z0-9]+:[-a-z0-9]*:[0-9]{12}"
                                                                ":bucket/[a-z0-9_-]{3,63})"},
                "TableARN": {"type": "string", "pattern": "(arn:aws[-a-z0-9]*:[a-z0-9]+:[-a-z0-9]*:[0-9]{12}"
                                                          ":bucket/[a-z0-9_-]{3,63}/table/[0-9a-f_-]{36})"},
                "NamespaceName": {"type": "string"},
                "TableName": {"type": "string"}}}


def test_a_type_ending_in_a_minted_id_is_found_by_the_path_members_naming_it():
    formats = {"Table": "arn:${Partition}:s3tables:${Region}:${Account}:bucket/${TableBucketName}/table/${TableID}",
               "TableBucket": "arn:${Partition}:s3tables:${Region}:${Account}:bucket/${TableBucketName}"}
    models = {"s3tables": tables_model()}
    found = {"type": "Table", "fill": {}, "find": ["tableBucketARN", "namespace", "name"]}

    def resources(operation: str, types: list[str]) -> list[dict]:
        return r.entry_for(f"s3tables:{operation}", {"action": f"s3tables:{operation}", "types": types},
                           formats, models)["resources"]

    # Its table bucket's ARN scopes the table rather than holding the table's ARN, and the table's name fills no
    # variable of the bucket: the table is found by all three, documented as its name or holding a `TableName`.
    assert resources("DeleteTable", ["Table"]) == [found]
    assert resources("GetTablePolicy", ["Table"]) == [found]
    # The same from a query string, unless the request gives the table's own ARN.
    assert resources("GetTable", ["Table"]) == [{"types": ["Table"], "arn": "tableArn"},
                                                dict(found, when={"tableArn": [None]})]
    # A type its ARN member holds, of one variable, is read as before.
    assert resources("GetTableBucket", ["TableBucket"]) == [{"types": ["TableBucket"], "arn": "tableBucketARN"}]


def test_a_create_making_a_type_nested_under_its_parent_names_it_from_the_parents_arn():
    formats = {"Table": "arn:${Partition}:s3tables:${Region}:${Account}:bucket/${TableBucketName}/table/${TableID}",
               "TableBucket": "arn:${Partition}:s3tables:${Region}:${Account}:bucket/${TableBucketName}"}
    # The table's format is the bucket's whole with a word of its own and the table's id, so a create acting on the
    # bucket makes a table under it, whose tags are checked there.
    assert r.child_id(formats["Table"], formats["TableBucket"]) == "TableID"
    assert r.creates_for("s3tables:CreateTable", ["TableBucket"], formats,
                         members("tableBucketARN", "namespace", "name")) == [
        {"type": "Table", "parent": "TableBucket", "arn": "tableBucketARN"}]
    # A namespace is no type of its own here, so creating one makes nothing.
    assert r.creates_for("s3tables:CreateNamespace", ["TableBucket"], formats,
                         members("tableBucketARN", "namespace")) == []
    # The same word with no word of the child's own between is no child.
    assert r.child_id("arn:${Partition}:svc:${Region}:${Account}:thing/${ThingName}/${PartId}",
                      "arn:${Partition}:svc:${Region}:${Account}:thing/${ThingName}") is None


def test_rest_arn_alternatives_and_integer_shapes_fill_versioned_resources(isolated, tmp_path):
    formats = {
        "product": "arn:${Partition}:svc:${Region}:${Account}:product:${ProductName}",
        "productVersion": "arn:${Partition}:svc:${Region}:${Account}:product:${ProductName}:${ProductVersion}",
    }
    api = model({"GetProductVersion": {"ProductName": "ProductName", "VersionNumber": "ProductVersionNumber"},
                 "GetProductVersionByArn": {"Arn": "String"}}, "rest-json")
    api["shapes"]["ProductName"] = {
        "type": "string", "pattern": "(arn:(aws[a-z-]*)?:svc:[a-z0-9-]+:[0-9]{12}:product:[a-z]+)|[a-z]+"}
    api["shapes"]["ProductVersionNumber"] = {"type": "long"}
    api["shapes"]["GetProductVersionIn"]["required"] = ["ProductName", "VersionNumber"]
    api["operations"]["GetProductVersion"]["http"] = {
        "method": "GET", "requestUri": "/products/{ProductName}/versions/{VersionNumber}"}
    for name, member in api["shapes"]["GetProductVersionIn"]["members"].items():
        member.update(location="uri", locationName=name)
    api["operations"]["GetProductVersionByArn"]["http"] = {"method": "GET", "requestUri": "/products"}
    api["shapes"]["GetProductVersionByArnIn"]["members"]["Arn"].update(location="querystring", locationName="Arn")
    write_models(tmp_path, {"svc": api})
    documents = [reference("svc", {"GetProductVersion": ["productVersion"],
                                   "GetProductVersionByArn": ["productVersion"]}, formats)]
    document = r.build(documents, "test", tmp_path, "botocore test")
    entry = document["operations"]["svc:GetProductVersion"]
    assert entry["resources"] == [{
        "type": "productVersion", "fill": {"ProductName": "ProductName", "ProductVersion": "VersionNumber"},
        "arnOf": {"ProductName": "product"}}]
    assert entry["located"] == {"ProductName": "{ProductName}", "VersionNumber": "{VersionNumber}"}
    assert document["operations"]["svc:GetProductVersionByArn"]["resources"] == [
        {"types": ["productVersion"], "arn": "Arn"}]
    assert document["integerVariables"] == {"svc": {"productVersion": ["ProductVersion"]}}
    assert r.validate(document) == []
    assert r.derive(document, tmp_path) == []
    document["integerVariables"] = {}
    assert any("integerVariables differ from botocore" in problem for problem in r.derive(document, tmp_path))
    document["integerVariables"] = {"svc": {"productVersion": ["Account"]}}
    assert r.validate(document) == ["integerVariables names no ARN variables for svc:productVersion"]


def test_numeric_shapes_do_not_guess_between_members_or_accept_floats():
    api = model({"UpdateThing": {"MinCapacity": "Capacity", "MaxCapacity": "Capacity",
                                 "Weight": "WeightNumber"}})
    api["shapes"]["Capacity"] = {"type": "integer"}
    api["shapes"]["WeightNumber"] = {"type": "double"}
    assert r.integer_members({"svc": api}, "UpdateThing") == {}


def test_an_arn_alternative_requires_the_same_name_pattern():
    shape = {"type": "string", "pattern": "(arn:aws:svc:us-east-1:123456789012:thing:[a-z]+)|[0-9]+"}
    assert r._name_or_arn(shape) is None


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


def test_an_account_and_a_region_a_format_names_beside_its_own_take_the_requests_members(tmp_path):
    # Config's aggregation authorization: arn:...:<caller>:aggregation-authorization/<authorized account>/<Region>.
    formats = {"grant": "arn:${Partition}:svc:${Region}:${Account}:grant/${GranteeAccount}/${GranteeRegion}"}
    write_models(tmp_path, {"svc": model({"PutGrant": {"AuthorizedAccountId": "AccountId",
                                                       "AuthorizedAwsRegion": "String"}})})
    models = r.load_models(tmp_path, {"svc"})
    assert r.region_members(models, "PutGrant") == ["AuthorizedAwsRegion"]
    filled = r.with_account_variables([{"type": "grant", "fill": {}}], formats,
                                      r.account_members(models, "PutGrant"), r.region_members(models, "PutGrant"))
    assert filled == [{"type": "grant", "fill": {"GranteeAccount": "AuthorizedAccountId",
                                                 "GranteeRegion": "AuthorizedAwsRegion"}}]
    # The member that fills the format's own account variable is not also the ARN's account, which stays the caller's.
    assert r.with_account(filled, formats, ["AuthorizedAccountId"]) == filled
    # A format naming no other account or Region is left as it is.
    assert r.with_account_variables([{"type": "thing", "fill": {}}], FORMATS, ["registryId"], ["Region"]) == [
        {"type": "thing", "fill": {}}]


def test_lists_a_request_adds_or_removes_name_a_type_no_resource_names_yet():
    # ElastiCache's ModifyUserGroup changes the users of its UserIdsToAdd and UserIdsToRemove.
    formats = {"group": "arn:${Partition}:svc:${Region}:${Account}:group:${GroupId}",
               "user": "arn:${Partition}:svc:${Region}:${Account}:user:${UserId}"}
    members = {"GroupId": "GroupId", "UserIdsToAdd": "UserIdsToAdd[]", "UserIdsToRemove": "UserIdsToRemove[]"}
    resources = [{"type": "group", "fill": {"GroupId": "GroupId"}}]
    assert r.with_change_lists(resources, ["group", "user"], formats, members) == resources + [
        {"type": "user", "fill": {"UserId": "UserIdsToAdd[]"}}, {"type": "user", "fill": {"UserId": "UserIdsToRemove[]"}}]
    # A type another resource names already, or a list named after something else, adds nothing.
    named = resources + [{"type": "user", "fill": {"UserId": "UserIds[]"}}]
    assert r.with_change_lists(named, ["group", "user"], formats, members) == named
    assert r.with_change_lists(resources, ["group", "user"], formats, {"TagsToAdd": "TagsToAdd[]"}) == resources


def membership_model() -> dict:
    """A model of Identity Store's memberships: a record of the membership's group and member, a call naming it by its
    id, and lookups returning the id of a membership, a group or a user its request names otherwise."""
    service = model({"DeleteMembership": {"StoreId": "String", "MembershipId": "String"},
                     "GetMembershipId": {"StoreId": "String", "GroupId": "String", "MemberId": "MemberId"},
                     "GetGroupId": {"StoreId": "String", "AlternateIdentifier": "AlternateIdentifier"},
                     "ListMembers": {"StoreId": "String", "GroupIds": "Strings"}})
    service["shapes"].update({
        "GroupMembership": {"type": "structure", "members": {"StoreId": {"shape": "String"},
                                                             "MembershipId": {"shape": "String"},
                                                             "GroupId": {"shape": "String"},
                                                             "MemberId": {"shape": "MemberId"}}},
        "MemberId": {"type": "structure", "union": True, "members": {"UserId": {"shape": "String"}}},
        "AlternateIdentifier": {"type": "structure", "union": True,
                                "members": {"ExternalId": {"shape": "ExternalId"},
                                            "UniqueAttribute": {"shape": "UniqueAttribute"}}},
        "ExternalId": {"type": "structure", "members": {"Issuer": {"shape": "String"}, "Id": {"shape": "String"}}},
        "UniqueAttribute": {"type": "structure", "members": {"AttributePath": {"shape": "String"},
                                                             "AttributeValue": {"shape": "Document"}}},
        "Document": {"type": "structure", "members": {}, "document": True},
        "GetMembershipIdOut": {"type": "structure", "members": {"StoreId": {"shape": "String"},
                                                                "MembershipId": {"shape": "String"}}},
        "GetGroupIdOut": {"type": "structure", "members": {"StoreId": {"shape": "String"},
                                                           "GroupId": {"shape": "String"}}},
        "ListMembersOut": {"type": "structure", "members": {"MembershipId": {"shape": "String"}}}})
    for name in ("GetMembershipId", "GetGroupId", "ListMembers"):
        service["operations"][name]["output"] = {"shape": name + "Out"}
    return service


MEMBERSHIP_FORMATS = {"Group": "arn:${Partition}:svc:::group/${GroupId}",
                      "GroupMembership": "arn:${Partition}:svc:::membership/${MembershipId}",
                      "User": "arn:${Partition}:svc:::user/${UserId}"}


def test_a_record_names_the_types_it_links_beside_the_id_a_request_names_it_by(tmp_path):
    # Identity Store's DeleteGroupMembership names the membership, whose record holds its group and its member.
    write_models(tmp_path, {"svc": membership_model()})
    models = r.load_models(tmp_path, {"svc"})
    types = ["Group", "GroupMembership", "User"]
    reached = r.record_reached(models, "DeleteMembership", types)
    assert reached == {"MembershipId": {"GroupMembership": ["Group", "User"]}}
    named = [{"type": "GroupMembership", "fill": {"MembershipId": "MembershipId"}}]
    assert r.with_record_through(named, MEMBERSHIP_FORMATS, reached, {"Group", "GroupMembership", "User"}) == named + [
        {"types": ["Group", "User"], "through": "MembershipId"}]
    # Only a type the action requires is named so, and only through the member that names the record's own type.
    assert r.with_record_through(named, MEMBERSHIP_FORMATS, reached, {"GroupMembership", "User"}) == named + [
        {"types": ["User"], "through": "MembershipId"}]
    assert r.with_record_through(named, MEMBERSHIP_FORMATS, reached, set()) == named
    assert r.with_record_through([], MEMBERSHIP_FORMATS, reached, {"Group", "User"}) == []


def test_a_lookup_finds_the_type_whose_id_it_returns_by_what_its_request_names(tmp_path):
    # GetGroupMembershipId returns the id of the membership of its GroupId and MemberId.UserId.
    write_models(tmp_path, {"svc": membership_model()})
    models = r.load_models(tmp_path, {"svc"})
    keys = r.lookup_keys(models, "GetMembershipId")
    assert keys == {"MembershipId": [["StoreId", "GroupId", "MemberId.UserId"]]}
    named = [{"type": "Group", "fill": {"GroupId": "GroupId"}}]
    types = ["Group", "GroupMembership", "User"]
    assert r.with_lookup_find(named, types, MEMBERSHIP_FORMATS, keys, {"Group", "GroupMembership"}) == named + [
        {"type": "GroupMembership", "fill": {}, "find": ["StoreId", "GroupId", "MemberId.UserId"]}]
    # A union is given one way at a time, so each way finds the type apart; a document is one value.
    assert r.lookup_keys(models, "GetGroupId") == {"GroupId": [
        ["StoreId", "AlternateIdentifier.ExternalId.Issuer", "AlternateIdentifier.ExternalId.Id"],
        ["StoreId", "AlternateIdentifier.UniqueAttribute.AttributePath",
         "AlternateIdentifier.UniqueAttribute.AttributeValue"]]}
    # A request holding a list looks nothing up, and a type the action does not require is not found.
    assert r.lookup_keys(models, "ListMembers") == {}
    assert r.with_lookup_find(named, types, MEMBERSHIP_FORMATS, keys, {"Group"}) == named


def test_the_vendored_reference_gives_the_types_an_action_requires():
    assert r.required_types("identitystore:DeleteGroupMembership", ["Group", "GroupMembership", "Identitystore",
                                                                    "User"]) == {"Group", "GroupMembership",
                                                                                 "Identitystore", "User"}
    # A namespace the source is not granted for requires nothing it says.
    assert r.required_types("elasticache:CreateCacheCluster", ["cluster", "parametergroup"]) == set()
    # A required type the Service Reference does not give the action is refused.
    with pytest.raises(ValueError, match="does not give it"):
        r.required_types("identitystore:DeleteGroupMembership", ["Group", "GroupMembership", "Identitystore"])


def test_a_member_holding_another_services_arn_names_nothing_of_the_namespace(tmp_path):
    # Route 53 Resolver's CreateOutpostResolver names the Outpost it runs on in its OutpostArn, an outposts ARN, whose
    # name begins its own outpost resolver's.
    service = model({"CreateOutpostResolver": {"Name": "String", "OutpostArn": "OutpostArn",
                                               "ResolverArn": "ResolverArn"}})
    service["shapes"]["OutpostArn"] = {"type": "string",
                                       "pattern": "^arn:aws([a-z-]+)?:outposts:[a-z\\d-]+:\\d{12}:outpost/.+"}
    service["shapes"]["ResolverArn"] = {"type": "string", "pattern": "^arn:aws([a-z-]+)?:svc:[a-z\\d-]+:\\d{12}:.+"}
    write_models(tmp_path, {"svc": service})
    models = r.load_models(tmp_path, {"svc"})
    formats = {"outpost-resolver": "arn:${Partition}:svc:${Region}:${Account}:outpost-resolver/${ResourceId}"}
    assert r.foreign_arn_members(models, "CreateOutpostResolver", formats) == {"OutpostArn"}
    members = {"Name": "Name", "OutpostArn": "OutpostArn"}
    assert r.resources_for("svc:CreateOutpostResolver", ["outpost-resolver"], formats, members) == [
        {"types": ["outpost-resolver"], "arn": "OutpostArn"}]
    assert r.resources_for("svc:CreateOutpostResolver", ["outpost-resolver"], formats, members,
                           foreign={"OutpostArn"}) == [{"type": "outpost-resolver", "fill": {}}]


def test_a_member_named_after_a_type_and_its_variable_fills_that_type():
    # MemoryDB's cluster and multi-Region cluster both name their variable ClusterName.
    formats = {"cluster": "arn:${Partition}:svc:${Region}:${Account}:cluster/${ClusterName}",
               "multiregioncluster": "arn:${Partition}:svc::${Account}:multiregioncluster/${ClusterName}",
               "snapshot": "arn:${Partition}:svc:${Region}:${Account}:snapshot/${SnapshotName}"}
    members = {"ClusterName": "ClusterName", "MultiRegionClusterName": "MultiRegionClusterName",
               "FinalSnapshotName": "FinalSnapshotName"}
    # DeleteCluster: nothing read its cluster, which ClusterName names.
    read = [{"type": "multiregioncluster", "fill": {"ClusterName": "MultiRegionClusterName"}}]
    assert r.with_type_named_members(read, ["cluster", "multiregioncluster", "snapshot"], formats, members) == read + [
        {"type": "cluster", "fill": {"ClusterName": "ClusterName"}}]
    # CreateCluster: the multi-Region cluster took the new regional cluster's ClusterName.
    created = [{"type": "multiregioncluster", "fill": {"ClusterName": "ClusterName"}, "made": True}]
    assert r.with_type_named_members(created, ["multiregioncluster"], formats, members) == [
        {"type": "multiregioncluster", "fill": {"ClusterName": "MultiRegionClusterName"}, "made": True}]
    # A member that does not end in the variable's name names nothing: RDS's DBName is a database, not
    # `${DbInstanceName}`.
    db = {"db": "arn:${Partition}:svc:${Region}:${Account}:db:${DbInstanceName}"}
    assert r.with_type_named_members([], ["db"], db, {"DBName": "DBName"}) == []
    # A member two types may take names neither alone: SSM's InstanceId, an instance or a managed instance.
    shared = {"instance": "arn:${Partition}:ec2:${Region}:${Account}:instance/${InstanceId}",
              "managed-instance": "arn:${Partition}:svc:${Region}:${Account}:managed-instance/${InstanceId}"}
    assert r.with_type_named_members([], ["instance", "managed-instance"], shared, {"InstanceId": "InstanceId"}) == []
    # For the type an operation is about, a member the variable's name ends in names it: SageMaker's JobName, of an
    # inference recommendations job, read where nothing names it and where a create leaves its id open.
    jobs = {"inference-recommendations-job": "arn:${Partition}:svc:${Region}:${Account}:"
            "inference-recommendations-job/${InferenceRecommendationsJobName}"}
    named_job = {"type": "inference-recommendations-job", "fill": {"InferenceRecommendationsJobName": "JobName"}}
    assert r.with_type_named_members([], ["inference-recommendations-job"], jobs, {"JobName": "JobName"},
                                     "inferencerecommendationsjob") == [named_job]
    assert r.with_type_named_members([{"type": "inference-recommendations-job", "fill": {}}],
                                     ["inference-recommendations-job"], jobs, {"JobName": "JobName"},
                                     "inferencerecommendationsjob") == [named_job]
    # Not for an operation about something else, nor from a bare Name.
    assert r.with_type_named_members([], ["inference-recommendations-job"], jobs, {"JobName": "JobName"},
                                     "model") == []
    assert r.with_type_named_members([], ["inference-recommendations-job"], jobs, {"Name": "Name"},
                                     "inferencerecommendationsjob") == []


def test_a_structure_named_after_the_type_gives_its_name_to_a_type_named_by_nothing_else(tmp_path):
    # Config's PutConfigurationRecorder names its recorder in ConfigurationRecorder.name.
    service = model({"PutRecorder": {"Recorder": "Recorder"}})
    service["shapes"]["Recorder"] = {"type": "structure", "members": {"name": {"shape": "String"},
                                                                      "roleARN": {"shape": "String"}}}
    write_models(tmp_path, {"svc": service})
    models = r.load_models(tmp_path, {"svc"})
    assert r.structure_name_fields(models, "PutRecorder", ["recorder"]) == {"recorder": "Recorder.name"}
    formats = {"recorder": "arn:${Partition}:svc:${Region}:${Account}:recorder/${RecorderName}/${RecorderId}"}
    assert r.with_structure_names([{"type": "recorder", "fill": {}}], formats, {"recorder": "Recorder.name"}) == [
        {"type": "recorder", "fill": {"RecorderName": "Recorder.name"}}]
    # A row another rule filled keeps its fill, and a format of two names says nothing about which one it is.
    given = [{"type": "recorder", "fill": {"RecorderId": "Id"}}]
    assert r.with_structure_names(given, formats, {"recorder": "Recorder.name"}) == given
    two = {"recorder": "arn:${Partition}:svc:${Region}:${Account}:recorder/${GroupName}/${RecorderName}"}
    assert r.with_structure_names([{"type": "recorder", "fill": {}}], two, {"recorder": "Recorder.name"}) == [
        {"type": "recorder", "fill": {}}]


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


def test_an_account_member_naming_another_records_account_gives_none(tmp_path):
    # CloudFormation's StackInstanceAccount is the account of a StackInstance, not of the stack set it belongs to.
    service = model({"DescribeStackInstance": {"StackInstanceAccount": "AccountId", "OwnerAccount": "AccountId"}})
    service["shapes"]["AccountId"]["pattern"] = "^[0-9]{12}$"
    service["shapes"]["StackInstance"] = {"type": "structure", "members": {}}
    write_models(tmp_path, {"svc": service})
    models = r.load_models(tmp_path, {"svc"})
    assert r.account_members(models, "DescribeStackInstance") == ["OwnerAccount"]


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
                              document["arnFormats"]["redshift-serverless"], models, None,
                              document["forwardedActions"])
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


THINGS = {"thing": "arn:${Partition}:svc:${Region}:${Account}:thing/${ThingName}",
          "thingversion": "arn:${Partition}:svc:${Region}:${Account}:thing/${ThingName}:${ThingVersion}"}


def test_a_type_another_qualifies_and_no_action_acts_on_is_checked_without_the_qualifier(isolated, tmp_path):
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}})})
    documents = [reference("svc", {"GetThing": ["thing"], "ListThings": []}, THINGS)]
    document = r.build(documents, "test", tmp_path, "botocore test")
    # `thingversion` is a thing's format and one more `:${...}`, and no action acts on it.
    assert document["unqualified"] == {"svc": ["thing"]}
    assert r.validate(document) == [] and r.derive(document, tmp_path) == []
    # A hand edit no longer matches.
    document["unqualified"] = {}
    assert r.validate(document) == [] and len(r.derive(document, tmp_path)) == 1
    # An action acting on the qualified type checks it on its own ARN.
    documents = [reference("svc", {"GetThing": ["thing"], "ListThings": ["thingversion"]}, THINGS)]
    assert r.build(documents, "test", tmp_path, "botocore test")["unqualified"] == {}


def test_a_member_documented_to_take_a_selector_after_the_name_checks_its_type_without_it(isolated, tmp_path):
    documented_ = model({"GetThing": {"ThingName": "String"}, "ListThings": {}})
    documented_["shapes"]["GetThingIn"]["members"]["ThingName"]["documentation"] = (
        '<p>The thing. To read a version, use "ThingName": "name:version".</p>')
    write_models(tmp_path, {"svc": documented_})
    document = r.build([reference("svc", {"GetThing": ["thing"], "ListThings": []}, {"thing": THINGS["thing"]})],
                       "test", tmp_path, "botocore test")
    assert document["unqualified"] == {"svc": ["thing"]}
    assert r.derive(document, tmp_path) == []
    # Without that form documented, the thing is checked as given.
    write_models(tmp_path / "plain", {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}})})
    assert r.build([reference("svc", {"GetThing": ["thing"], "ListThings": []}, {"thing": THINGS["thing"]})],
                   "test", tmp_path / "plain", "botocore test")["unqualified"] == {}


def test_a_type_whose_arn_shape_takes_a_segment_past_its_name_has_an_id_its_service_mints(isolated, tmp_path):
    minted = model({"GetThing": {"ThingName": "String"}, "ListThings": {}})
    minted["shapes"]["ThingArn"] = {"type": "string",
                                    "pattern": "^arn:aws:svc:[a-z0-9-]+:[0-9]{12}:thing\\/[\\.\\-_A-Za-z0-9]+\\/[\\-A-Za-z0-9]+$"}
    write_models(tmp_path, {"svc": minted})
    document = r.build(DOCUMENTS, "test", tmp_path, "botocore test")
    assert document["mintedIds"] == {"svc": ["thing"]}
    assert r.validate(document) == [] and r.derive(document, tmp_path) == []
    document["mintedIds"] = {}
    assert len(r.derive(document, tmp_path)) == 1
    # One segment that may hold slashes is the name alone.
    minted["shapes"]["ThingArn"]["pattern"] = "^arn:aws:svc:[a-z0-9-]+:[0-9]{12}:thing\\/([a-zA-Z0-9-_\\.\\/]*)"
    write_models(tmp_path / "plain", {"svc": minted})
    assert r.build(DOCUMENTS, "test", tmp_path / "plain", "botocore test")["mintedIds"] == {}


def test_a_create_documenting_characters_its_service_mints_after_a_name_gives_them_open(isolated, tmp_path):
    # Secrets Manager: "automatically adds a hyphen and six random characters after the secret name at the end of
    # the ARN".
    service = model({"CreateThing": {"Name": "String"}, "GetThing": {"ThingName": "String"}, "ListThings": {}})
    service["shapes"]["CreateThingIn"]["members"]["Name"]["documentation"] = (
        "<p>The name of the new thing.</p> <p>Svc automatically adds a hyphen and six random characters after the "
        "thing name at the end of the ARN.</p>")
    write_models(tmp_path, {"svc": service})
    documents = [reference("svc", {"CreateThing": ["thing"], "GetThing": ["thing"], "ListThings": []},
                           {"thing": FORMATS["thing"]})]
    document = r.build(documents, "test", tmp_path, "botocore test")
    assert document["mintedSuffixes"] == {"svc": {"thing": "-??????"}}
    assert document["operations"]["svc:CreateThing"]["resources"] == [{"type": "thing", "fill": {"ThingName": "Name"}}]
    assert r.validate(document) == [] and r.derive(document, tmp_path) == []
    document["mintedSuffixes"] = {}
    assert any("minted suffix" in problem for problem in r.derive(document, tmp_path))
    for bad in ({"svc": {"thing": "??????"}}, {"svc": {"group": "-??"}}, {"svc": {"thing": "-ab"}}, {"svc": {}}):
        document["mintedSuffixes"] = bad
        assert any("mintedSuffixes" in problem for problem in r.validate(document)), bad
    # Undocumented, the thing has no suffix.
    write_models(tmp_path / "plain", {"svc": model({"CreateThing": {"Name": "String"},
                                                    "GetThing": {"ThingName": "String"}, "ListThings": {}})})
    assert r.build(documents, "test", tmp_path / "plain", "botocore test")["mintedSuffixes"] == {}


def test_a_type_whose_arn_is_all_an_id_its_service_mints_is_found_by_its_documented_key_or_its_name(isolated, tmp_path):
    documents = [reference("svc", {"PutTarget": ["target"], "TagTarget": ["target"], "GetByResource": ["target"],
                                   "RunOn": ["target", "group"], "RenameGroup": ["group"]},
                           {"target": "arn:${Partition}:svc:${Region}:${Account}:target/${ResourceId}",
                            "group": "arn:${Partition}:svc:${Region}:${Account}:group/${GroupId}"})]
    found = model({"PutTarget": {"Namespace": "String", "ResourceId": "String", "Dimension": "String"},
                   "TagTarget": {"ResourceArn": "String"},
                   "GetByResource": {"ResourceId": "String"},
                   "RunOn": {"GroupName": "String"}, "RenameGroup": {"GroupName": "String"}})
    found["operations"]["PutTarget"]["documentation"] = (
        "<p>Targets are uniquely identified by the combination of namespace, resource ID, and dimension.</p>")
    write_models(tmp_path, {"svc": found})
    document = r.build(documents, "test", tmp_path, "botocore test")
    assert r.validate(document) == [] and r.derive(document, tmp_path) == []
    operations = document["operations"]
    # Found by its documented key, in the request's order, rather than filled by ResourceId, not the id its ARN ends in.
    assert operations["svc:PutTarget"]["resources"] == [
        {"type": "target", "fill": {}, "find": ["Namespace", "ResourceId", "Dimension"]}]
    # Its ARN names it as given, and a request lacking a member of its key names none.
    assert operations["svc:TagTarget"]["resources"] == [{"types": ["target"], "arn": "ResourceArn"}]
    assert operations["svc:GetByResource"]["resources"] == []
    # Found by its name, its id being one no request gives.
    assert operations["svc:RunOn"]["resources"] == [{"type": "group", "fill": {}, "find": ["GroupName"]}]
    # Where a request gives the id, a name beside it is a new name, not the group's.
    found["shapes"]["RenameGroupIn"]["members"]["GroupId"] = {"shape": "String"}
    write_models(tmp_path / "given", {"svc": found})
    renamed = r.build(documents, "test", tmp_path / "given", "botocore test")["operations"]
    assert renamed["svc:RenameGroup"]["resources"] == [{"type": "group", "fill": {"GroupId": "GroupId"}}]
    assert not any("find" in resource for resource in renamed["svc:RunOn"]["resources"])
    # A hand edit no longer matches.
    operations["svc:PutTarget"]["resources"] = [{"type": "target", "fill": {"ResourceId": "ResourceId"}}]
    assert len(r.derive(document, tmp_path)) == 1


def test_a_member_naming_a_resource_of_no_type_names_what_its_record_says_it_runs_on_through_it(isolated, tmp_path):
    documents = [reference("svc", {"Run": ["cluster", "group"], "RunOnGroup": ["group"], "RunOnCluster": ["cluster"],
                                   "ListSessions": []},
                           {"cluster": "arn:${Partition}:svc:${Region}:${Account}:cluster:${ClusterName}",
                            "group": "arn:${Partition}:svc:${Region}:${Account}:group/${GroupId}"})]
    sessions = model({"Run": {"SessionId": "String"}, "RunOnGroup": {"SessionId": "String"},
                      "RunOnCluster": {"Sql": "String"}, "ListSessions": {"SessionId": "String"}})
    # A session's record holds its id beside the cluster or group it runs on.
    sessions["shapes"]["SessionData"] = {"type": "structure", "members": {
        "SessionId": {"shape": "String"}, "ClusterIdentifier": {"shape": "String"}, "GroupId": {"shape": "String"},
        "Status": {"shape": "String"}}}
    write_models(tmp_path, {"svc": sessions})
    document = r.build(documents, "test", tmp_path, "botocore test")
    assert r.validate(document) == [] and r.derive(document, tmp_path) == []
    operations = document["operations"]
    assert operations["svc:Run"]["resources"] == [{"types": ["cluster", "group"], "through": "SessionId"}]
    assert operations["svc:RunOnGroup"]["resources"][-1] == {"types": ["group"], "through": "SessionId"}
    # Not by a request without the member, nor for an action acting on none of the types.
    assert not any("through" in resource for resource in operations["svc:RunOnCluster"]["resources"])
    assert operations["svc:ListSessions"]["resources"] == []
    # Without the record, the session reaches nothing.
    del sessions["shapes"]["SessionData"]
    write_models(tmp_path / "plain", {"svc": sessions})
    assert r.build(documents, "test", tmp_path / "plain", "botocore test")["operations"]["svc:Run"]["resources"] == []


def test_a_member_whose_shape_takes_an_alias_names_its_type_with_a_word_before_it():
    formats = {"key": "arn:${Partition}:svc:${Region}:${Account}:key/${KeyArn}"}
    keys = model({"ReEncrypt": {"IncomingKeyIdentifier": "KeyArnOrKeyAliasType",
                                "OutgoingKeyIdentifier": "KeyArnOrKeyAliasType"}})
    keys["shapes"]["KeyArnOrKeyAliasType"] = {
        "type": "string", "pattern": "arn:aws:svc:[a-z0-9-]+:[0-9]{12}:(key/[0-9a-zA-Z]{16,64}|alias/[a-zA-Z0-9/_-]+)$|^alias/[a-zA-Z0-9/_-]+"}
    assert r.alias_members({"svc": keys}, "ReEncrypt") == {"IncomingKeyIdentifier", "OutgoingKeyIdentifier"}
    keys["shapes"]["KeyArnOrKeyAliasType"]["pattern"] = "arn:aws:svc:[a-z0-9-]+:[0-9]{12}:key/[0-9a-zA-Z]{16,64}"
    assert r.alias_members({"svc": keys}, "ReEncrypt") == set()


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
    return {"namespaces": {"svc": ["svc"]},
            "notCovered": {"later": "a later batch"},
            "otherNamespaces": ["other"], "arnFormats": {"svc": {"thing": FORMATS["thing"], "group": FORMATS["group"]}},
            "pathFormats": {},
            "unqualified": {"svc": ["thing"]}, "mintedIds": {"svc": ["thing"]}, "mintedSuffixes": {},
            "forwardedActions": {},
            "operations": {
                "svc:A": {"action": "svc:A", "types": ["thing"], "resources": [{"types": ["thing"], "arn": "ThingArn"}]},
                "svc:B": {"action": "svc:B", "types": ["group"], "resources": [{"type": "group", "fill": {"GroupName": "Names[]"}}]},
                "svc:C": {"action": "svc:C", "types": [], "resources": []}}}


GUIDE = {
    "action": {"sentence": "The API-managing Action expression has the format svc:{action}, where {action} is one of "
                           "the following actions: GET, POST, PUT, DELETE, PATCH, or *.",
               "actions": ["GET", "POST", "PUT", "DELETE", "PATCH", "*"]},
    "resource": {"sentence": "API Gateway resources have the following ARN format:",
                 "format": "arn:aws:svc:{region}::{resource-path-specifier}"},
    "tables": [
        {"heading": "REST API resources",
         "rows": [{"resource": "Stage", "arn": "arn:{partition}:svc:{region}::/restapis/{api-id}/stages/{stage-name}"},
                  {"resource": "Associations", "arn": "arn:{partition}:svc:{region}:{account-id}:/associations"}]},
        {"heading": "execute-api",
         "rows": [{"resource": "endpoint",
                   "arn": "arn:{partition}:execute-api:{region}:{account-id}:{api-id}/{stage}"}]}],
}
NO_ROUTE_ACTIONS = {"byMethod": {}, "byModel": {}}


def routed_model(routes: dict[str, tuple[str, str, dict[str, str]]]) -> dict:
    """A rest-json model: each operation its method, its path, and the member each label of its path carries."""
    shapes = {"String": {"type": "string"}}
    operations = {}
    for name, (method, uri, labels) in routes.items():
        shapes[name + "In"] = {"type": "structure", "members": {
            member: {"shape": "String", "location": "uri", "locationName": label} for label, member in labels.items()}}
        operations[name] = {"http": {"method": method, "requestUri": uri}, "input": {"shape": name + "In"}}
    return {"metadata": {"protocol": "rest-json"}, "operations": operations, "shapes": shapes}


def test_a_route_authorized_by_its_method_names_the_reference_type_of_its_own_path():
    formats = {"Api": "arn:${Partition}:svc:${Region}::/apis/${ApiId}", "Apis": "arn:${Partition}:svc:${Region}::/apis"}
    models = {"svc": routed_model({"GetApi": ("GET", "/apis/{api_id}", {"api_id": "restApiId"})}),
              "svcv2": routed_model({"GetApi": ("GET", "/v2/apis/{apiId}", {"apiId": "ApiId"}),
                                   "CreateApi": ("POST", "/v2/apis", {})})}
    operations = {"svc:GetApi": {"action": "svc:GET", "types": ["Api"]},
                  "svc:CreateApi": {"action": "svc:CreateApi", "types": []}}
    # CreateApi is authorized as the method of its route, which the reference gives no single action.
    resources, needed = r.routed_resources("svc", operations, formats, GUIDE, models,
                                           {"byMethod": {"svc": {"CreateApi"}}, "byModel": {}})
    assert resources == {
        "svc:CreateApi": [{"type": "Apis", "fill": {}}],
        "svc:GetApi": [{"type": "Api", "fill": {"ApiId": "restApiId"}}, {"type": "Api", "fill": {"ApiId": "ApiId"}}],
    }
    assert needed == {}


def test_a_path_the_reference_does_not_give_takes_the_guides_row_and_else_the_guides_format():
    models = {"svc": routed_model({
        "GetStage": ("GET", "/restapis/{restapi_id}/stages/{stage_name}",
                     {"restapi_id": "restApiId", "stage_name": "stageName"}),
        "GetAssociations": ("GET", "/associations", {}),
        "GetSdkType": ("GET", "/sdktypes/{sdktype_id}", {"sdktype_id": "id"})})}
    operations = {f"svc:{name}": {"action": "svc:GET", "types": []}
                  for name in ("GetStage", "GetAssociations", "GetSdkType")}
    resources, needed = r.routed_resources("svc", operations, {}, GUIDE, models, NO_ROUTE_ACTIONS)
    assert resources == {
        "svc:GetAssociations": [{"type": "Associations (REST API resources)", "fill": {}}],
        "svc:GetSdkType": [{"type": "/sdktypes/{sdktype_id} (resource-path-specifier)", "fill": {"SdktypeId": "id"}}],
        "svc:GetStage": [{"type": "Stage (REST API resources)",
                          "fill": {"ApiId": "restApiId", "StageName": "stageName"}}],
    }
    # The guide's row keeps the account it gives; its ARN format gives none.
    assert needed == {
        "/sdktypes/{sdktype_id} (resource-path-specifier)": "arn:${Partition}:svc:${Region}::/sdktypes/${SdktypeId}",
        "Associations (REST API resources)": "arn:${Partition}:svc:${Region}:${Account}:/associations",
        "Stage (REST API resources)": "arn:${Partition}:svc:${Region}::/restapis/${ApiId}/stages/${StageName}",
    }


def test_a_route_whose_label_holds_an_arn_or_checked_as_a_named_action_is_left_to_the_rules():
    models = {"svc": routed_model({
        "DeleteAssociation": ("DELETE", "/associations/{association_arn}", {"association_arn": "associationArn"}),
        "DeletePortal": ("DELETE", "/portals/{portal_id}", {"portal_id": "PortalId"})})}
    operations = {"svc:DeleteAssociation": {"action": "svc:DELETE", "types": []},
                  "svc:DeletePortal": {"action": "svc:DeletePortal", "types": []}}
    assert r.routed_resources("svc", operations, {}, GUIDE, models, NO_ROUTE_ACTIONS) == ({}, {})


def test_published_paths_leaving_out_different_leading_segments_of_one_clients_routes_are_refused():
    formats = {"Apis": "arn:${Partition}:svc:${Region}::/apis", "Things": "arn:${Partition}:svc:${Region}::/things"}
    models = {"svcv2": routed_model({"GetApis": ("GET", "/v2/apis", {}), "GetThings": ("GET", "/v3/things", {})})}
    operations = {f"svc:{name}": {"action": "svc:GET", "types": []} for name in ("GetApis", "GetThings")}
    with pytest.raises(ValueError, match="different leading segments"):
        r.routed_resources("svc", operations, formats, GUIDE, models, NO_ROUTE_ACTIONS)


def test_route_types_are_read_only_for_a_namespace_with_a_path_source_and_never_named_as_a_reference_type(monkeypatch):
    document = good_document()
    document["pathFormats"] = {
        "svc": {"/sdktypes (resource-path-specifier)": "arn:${Partition}:svc:${Region}::/sdktypes"}}
    assert any("pathFormats" in problem for problem in r.validate(document))
    monkeypatch.setattr(r, "PATH_SOURCES", {"svc": Path("svc.json")})
    assert r.validate(document) == []
    document["pathFormats"] = {"svc": {"thing": "arn:${Partition}:svc:${Region}::/things"}}
    assert any("pathFormats" in problem for problem in r.validate(document))


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
    # A type whose ARN is all an id its service mints, found by the members naming it, or each value of one list.
    document = good_document()
    document["operations"]["svc:B"]["resources"][0].update(fill={}, find=["Namespace", "Id"])
    assert r.validate(document) == []
    document["operations"]["svc:B"]["resources"][0].update(find=["Ids[]"])
    assert r.validate(document) == []
    # A member filling the one variable of another type's format names that type's default when left out.
    document = good_document()
    document["arnFormats"]["svc"]["service"] = "arn:${Partition}:svc:${Region}:${Account}:service/${ThingName}/${Name}"
    document["operations"]["svc:A"]["types"] = ["service"]
    document["operations"]["svc:A"]["resources"] = [{"type": "service", "fill": {"ThingName": "thing", "Name": "Names[]"},
                                                     "defaults": {"thing": "thing"}}]
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
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(fill={}, find=["Ids[]", "Namespace"]), "beside a fill"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(defaults={"Names[]": "thing"}), "defaults of types"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(defaults={"Other": "thing"}), "defaults of types"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(defaults={}), "defaults of types"),
    (lambda d: d["operations"]["svc:B"]["resources"][0].update(defaults=["thing"]), "defaults of types"),
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
    # A state machine's version or alias is checked on the state machine; a Lambda function's is checked qualified.
    assert document["unqualified"] == {"ssm": ["parameter"], "states": ["statemachine"]}
    assert document["integerVariables"] == {"lambda": {"layerVersion": ["LayerVersion"]}}
    # Lambda is read whole: a partial ARN names a function, a provisioned concurrency config acts on the version or
    # alias its Qualifier names, the action the reference gives it, and AddPermission's SourceAccount is its source's.
    assert "lambda" in document["namespaces"]
    assert operations["lambda:Invoke"]["resources"] == [
        {"type": "function", "fill": {"FunctionName": "FunctionName"}, "partialArn": True}]
    assert operations["lambda:PublishVersion"]["resources"] == [
        {"type": "function", "fill": {"FunctionName": "FunctionName"}, "partialArn": True}]
    assert operations["lambda:GetProvisionedConcurrencyConfig"]["resources"] == [
        {"type": kind, "fill": {"FunctionName": "FunctionName", variable: "Qualifier"},
         "arnOf": {"FunctionName": "function"}, "partialArn": True}
        for kind, variable in (("function alias", "Alias"), ("function version", "Version"))]
    assert not any("account" in resource for resource in operations["lambda:AddPermission"]["resources"])
    assert operations["lambda:GetMicrovm"]["resources"] == [{"types": ["microvmImage"], "through": "microvmIdentifier"}]
    assert operations["lambda:SendDurableExecutionCallbackSuccess"]["resources"] == [
        {"types": ["durable execution"], "through": "CallbackId"}]
    assert operations["lambda:GetWebFunctionEndpoint"]["resources"][0]["arnOf"] == {"functionName": "webFunction"}
    # A listener is made under a load balancer, and a rule under a listener.
    assert operations["elasticloadbalancing:CreateListener"]["creates"] == [
        {"type": f"listener/{kind}", "parent": f"loadbalancer/{kind}/", "arn": "LoadBalancerArn"}
        for kind in ("app", "gwy", "net")]
    assert operations["elasticloadbalancing:CreateRule"]["creates"] == [
        {"type": f"listener-rule/{kind}", "parent": f"listener/{kind}", "arn": "ListenerArn"} for kind in ("app", "net")]
    # S3 is read from the table: an object by its bucket and key, a bucket per client, and DeleteObjects, whose keys
    # its XML body names, left to S3.
    assert "s3" in document["namespaces"]
    assert operations["s3:PutObject"]["resources"] == [
        {"type": "object", "fill": {"BucketName": "Bucket", "ObjectName": "Key"}}]
    assert operations["s3:CreateBucket"]["locatedApart"] == {"s3": {"Bucket": "{Bucket}"}, "s3control": {"Bucket": "{name}"}}
    assert operations["s3:DeleteObjects"]["unread"] == ["Delete.Objects[].Key"]
    # An SQS request names its queue by URL, which SQS finds as its handlers do; a create names it by name.
    assert operations["sqs:SendMessage"]["resources"] == [{"type": "queue", "fill": {}, "find": ["QueueUrl"]}]
    assert operations["sqs:CreateQueue"]["resources"] == [{"type": "queue", "fill": {"QueueName": "QueueName"}}]
    # CloudWatch is read whole: each alarm a list names, and a dashboard by its name, its ARN with no Region.
    assert "cloudwatch" in document["namespaces"] and "cloudwatch" not in document["notCovered"]
    assert operations["cloudwatch:DeleteAlarms"]["resources"] == [
        {"type": "alarm", "fill": {"AlarmName": "AlarmNames[]"}}]
    assert operations["cloudwatch:DeleteDashboards"]["resources"] == [
        {"type": "dashboard", "fill": {"DashboardName": "DashboardNames[]"}}]
    assert document["arnFormats"]["cloudwatch"]["dashboard"] == (
        "arn:${Partition}:cloudwatch::${Account}:dashboard/${DashboardName}")
    # Comprehend is read whole; its detection actions act on no resource type, and a classifier's are read by ARN.
    assert "comprehend" in document["namespaces"] and "comprehend" not in document["notCovered"]
    assert operations["comprehend:DetectSentiment"] == {
        "action": "comprehend:DetectSentiment", "types": [], "resources": []}
    assert operations["comprehend:DescribeDocumentClassifier"]["resources"] == [
        {"types": ["document-classifier"], "arn": "DocumentClassifierArn"}]
    # Config is read whole: a rule is found by its name, its ARN ending in the id Config mints; a conformance pack is
    # its name, the id it mints after it found the same way when Config holds the pack.
    assert "config" in document["namespaces"] and "config" not in document["notCovered"]
    assert operations["config:DeleteConfigRule"]["resources"] == [
        {"type": "ConfigRule", "fill": {}, "find": ["ConfigRuleName"]}]
    assert operations["config:DeleteConformancePack"]["resources"] == [
        {"type": "ConformancePack", "fill": {"ConformancePackName": "ConformancePackName"}}]
    # An aggregation authorization is the caller's, its path the account and Region it authorizes; a recorder put
    # names its recorder in the ConfigurationRecorder structure.
    assert operations["config:PutAggregationAuthorization"]["resources"] == [
        {"type": "AggregationAuthorization",
         "fill": {"AggregatorAccount": "AuthorizedAccountId", "AggregatorRegion": "AuthorizedAwsRegion"}}]
    assert {"type": "ConfigurationRecorder", "fill": {"RecorderName": "ConfigurationRecorder.name"}} in operations[
        "config:PutConfigurationRecorder"]["resources"]
    # DMS is read whole: an existing resource by the ARN its request names it by, each format ending in a literal `*`.
    assert document["arnFormats"]["dms"]["Endpoint"] == "arn:${Partition}:dms:${Region}:${Account}:endpoint:*"
    assert "dms" in document["namespaces"] and "dms" not in document["notCovered"]
    assert operations["dms:DeleteEndpoint"]["resources"] == [{"types": ["Endpoint"], "arn": "EndpointArn"}]
    # An endpoint written with no CertificateArn names no certificate.
    assert not any(resource.get("type") == "Certificate" for resource in operations["dms:CreateEndpoint"]["resources"])
    # ElastiCache is read whole: a cluster, group or user by the identifier its request names.
    assert "elasticache" in document["namespaces"] and "elasticache" not in document["notCovered"]
    assert operations["elasticache:DeleteCacheCluster"]["resources"][0] == {
        "type": "cluster", "fill": {"CacheClusterId": "CacheClusterId"}}
    # MemoryDB's DeleteCluster is checked on the cluster its ClusterName names, CreateCluster on the multi-Region
    # cluster its MultiRegionClusterName names.
    assert {"type": "cluster", "fill": {"ClusterName": "ClusterName"}} in \
        operations["memorydb:DeleteCluster"]["resources"]
    assert {"type": "multiregioncluster", "fill": {"ClusterName": "MultiRegionClusterName"}} in \
        operations["memorydb:CreateCluster"]["resources"]
    # CreateCluster makes a cluster, whose tags are checked on it, not on the multi-Region cluster it may join.
    assert operations["memorydb:CreateCluster"]["makes"] == [
        {"type": "cluster", "fill": {"ClusterName": "ClusterName"}}]
    assert operations["elasticache:ModifyUserGroup"]["resources"] == [
        {"type": "usergroup", "fill": {"UserGroupId": "UserGroupId"}},
        {"type": "user", "fill": {"UserId": "UserIdsToAdd[]"}}, {"type": "user", "fill": {"UserId": "UserIdsToRemove[]"}}]
    # Identity Store is read whole: the identity store and the user or group a request names by id.
    assert "identitystore" in document["namespaces"] and "identitystore" not in document["notCovered"]
    assert {"type": "User", "fill": {"UserId": "UserId"}} in operations["identitystore:DescribeUser"]["resources"]
    # A membership's group and user are read through its id, and a lookup's group, user or membership found by what
    # its request names, as the vendored reference requires those types.
    assert {"types": ["Group", "User"], "through": "MembershipId"} in operations[
        "identitystore:DeleteGroupMembership"]["resources"]
    assert {"type": "GroupMembership", "fill": {}, "find": ["IdentityStoreId", "GroupId", "MemberId.UserId"]} in \
        operations["identitystore:GetGroupMembershipId"]["resources"]
    assert {"type": "User", "fill": {}, "find": ["IdentityStoreId", "AlternateIdentifier.ExternalId.Issuer",
                                                 "AlternateIdentifier.ExternalId.Id"]} in \
        operations["identitystore:GetUserId"]["resources"]
    # MemoryDB is read whole: a cluster, ACL or user by its name.
    assert "memorydb" in document["namespaces"] and "memorydb" not in document["notCovered"]
    assert operations["memorydb:DeleteACL"]["resources"] == [{"type": "acl", "fill": {"AclName": "ACLName"}}]
    # Route 53 Resolver is read whole: a rule, an endpoint or a domain list by the id its request names.
    assert "route53resolver" in document["namespaces"] and "route53resolver" not in document["notCovered"]
    assert {"type": "inference-recommendations-job", "fill": {"InferenceRecommendationsJobName": "JobName"}} in \
        operations["sagemaker:DescribeInferenceRecommendationsJob"]["resources"]
    assert operations["route53resolver:GetResolverRule"]["resources"] == [
        {"type": "resolver-rule", "fill": {"ResourceId": "ResolverRuleId"}}]
    # CreateOutpostResolver's OutpostArn is an Outpost's ARN, so it holds no resolver: it makes its own, id open.
    assert operations["route53resolver:CreateOutpostResolver"]["resources"] == [
        {"type": "outpost-resolver", "fill": {}}]
    # SageMaker is read whole: a model, an endpoint or a training job by its name.
    assert "sagemaker" in document["namespaces"] and "sagemaker" not in document["notCovered"]
    assert operations["sagemaker:DescribeModel"]["resources"] == [{"type": "model", "fill": {"ModelName": "ModelName"}}]
    # Transcribe is read whole: a transcription job or a vocabulary by its name.
    assert "transcribe" in document["namespaces"] and "transcribe" not in document["notCovered"]
    assert operations["transcribe:GetTranscriptionJob"]["resources"] == [
        {"type": "transcriptionjob", "fill": {"JobName": "TranscriptionJobName"}}]
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


MEASURED_FORMATS = {"thing": "arn:${Partition}:probed:${Region}:${Account}:thing/${ThingName}"}
PROBED = "real AWS probes in https://example.com/probes accept an allow and enforce a deny on thing/*"


def measured_documents(tmp_path, listed: list[str]) -> list[dict]:
    probed = model({"ListThings": {"StartName": "String"}})
    probed["metadata"]["endpointPrefix"] = "probed"
    write_models(tmp_path, {"svc": model({"GetThing": {"ThingName": "String"}, "ListThings": {}}), "probed": probed})
    return DOCUMENTS + [reference("probed", {"ListThings": listed}, MEASURED_FORMATS)]


def test_a_measured_operation_takes_the_resources_aws_was_measured_to_check_where_the_reference_lists_none(
        isolated, tmp_path):
    isolated.setattr(r, "NAMESPACES", ("svc", "probed"))
    isolated.setattr(r, "MEASURED", {"probed:ListThings": ([{"type": "thing", "fill": {}}], PROBED)})
    document = r.build(measured_documents(tmp_path, []), "test", tmp_path, "botocore test")
    # The measured resources replace what the rules read, keeping the reference's empty types.
    assert document["operations"]["probed:ListThings"] == {
        "action": "probed:ListThings", "types": [], "resources": [{"type": "thing", "fill": {}}]}
    assert r.reads("probed:ListThings")
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
    isolated.setattr(r, "NAMESPACES", ("svc", "probed"))
    isolated.setattr(r, "MEASURED", {"probed:ListThings": measured})
    with pytest.raises(ValueError, match=problem):
        r.build(measured_documents(tmp_path, listed), "test", tmp_path, "botocore test")


def test_a_measured_entry_must_name_an_operation_the_file_reads(isolated, tmp_path):
    isolated.setattr(r, "NAMESPACES", ("svc", "probed"))
    isolated.setattr(r, "MEASURED", {"probed:Gone": ([{"type": "thing", "fill": {}}], PROBED)})
    with pytest.raises(ValueError, match="MEASURED gives probed:Gone, which the file does not read"):
        r.build(measured_documents(tmp_path, []), "test", tmp_path, "botocore test")


GUIDE_SAYS = "the guide (https://example.com/guide) says 'restoring a thing needs keys:Decrypt on its key'"
KEY_FORMAT = "arn:${Partition}:keys:${Region}:${Account}:key/${KeyId}"


def keys_reference(resources: list[str] | None = None, condition_keys: list[str] | None = None) -> dict:
    """A Service Reference document of a key service whose `Decrypt` acts on keys and takes its `ViaService` key."""
    document = reference("keys", {"Decrypt": ["key"] if resources is None else resources}, {"key": KEY_FORMAT})
    document["Actions"][0]["ActionConditionKeys"] = ["keys:ViaService"] if condition_keys is None else condition_keys
    return document


def forwarding_documents(tmp_path, record_member: str = "KeysKeyId", listed: bool = False,
                         keys: dict | None = None) -> list[dict]:
    """`svc`, whose `GetThing` restores a thing its record says a key of `keys` encrypts, and that key service."""
    svc = model({"GetThing": {"ThingName": "String"}, "ListThings": {}})
    svc["metadata"]["endpointPrefix"] = "svc"
    svc["shapes"]["Thing"] = {"type": "structure", "members": {"ThingName": {"shape": "String"},
                                                               record_member: {"shape": "String"}}}
    write_models(tmp_path, {"svc": svc})
    documents = [dict(DOCUMENTS[0]), *DOCUMENTS[1:], keys or keys_reference()]
    if listed:
        documents[0] = json.loads(json.dumps(DOCUMENTS[0]))
        documents[0]["Operations"][0]["AuthorizedActions"].append({"Service": "keys", "Name": "Decrypt"})
    return documents


def test_a_forwarded_operation_is_also_checked_as_the_action_on_what_its_record_holds(isolated, tmp_path):
    isolated.setattr(r, "MEASURED", {"svc:GetThing": ([{"action": "keys:Decrypt", "of": "thing"}], GUIDE_SAYS)})
    document = r.build(forwarding_documents(tmp_path), "test", tmp_path, "botocore test")
    # The member is the record's, named after the action's namespace and its type's variable, whatever its case.
    assert document["operations"]["svc:GetThing"]["forwarded"] == [
        {"action": "keys:Decrypt", "of": "thing", "member": "KeysKeyId", "endpoint": "svc"}]
    assert document["forwardedActions"] == {
        "keys:Decrypt": {"type": "key", "format": KEY_FORMAT, "viaService": "keys:ViaService"}}
    assert "forwarded" not in document["operations"]["svc:ListThings"]
    assert r.validate(document) == []
    assert r.derive(document, tmp_path) == []
    assert json.loads(r.render(document)) == document
    # An action the reference gives no ViaService key carries none.
    documents = forwarding_documents(tmp_path / "plain", keys=keys_reference(condition_keys=[]))
    assert r.build(documents, "test", tmp_path / "plain", "botocore test")["forwardedActions"] == {
        "keys:Decrypt": {"type": "key", "format": KEY_FORMAT}}


@pytest.mark.parametrize("forwarded, change, problem", [
    # No link to where AWS says it.
    ((["keys:Decrypt", "thing"], "the guide says so"), {}, "no link to the evidence"),
    # The reference lists the action for the operation: a rule reads it.
    ((["keys:Decrypt", "thing"], GUIDE_SAYS), {"listed": True}, "which the Service Reference lists for it"),
    # An action the reference does not list, or one acting on no one type.
    ((["keys:Encrypt", "thing"], GUIDE_SAYS), {}, "which the Service Reference does not list"),
    ((["keys:Decrypt", "thing"], GUIDE_SAYS), {"keys": keys_reference(resources=[])}, "not one resource type"),
    # What a type the operation's action does not act on holds.
    ((["keys:Decrypt", "group"], GUIDE_SAYS), {}, "no type its action acts on"),
    # A record holding no member named after the action's namespace and its type's variable.
    ((["keys:Decrypt", "thing"], GUIDE_SAYS), {"record_member": "EncryptionKey"}, "has no keysKeyId member"),
])
def test_a_forwarded_entry_is_refused_unless_it_cites_aws_for_what_the_metadata_does_not_say_and_is_read(
        isolated, tmp_path, forwarded, change, problem):
    (action, of), evidence = forwarded
    isolated.setattr(r, "MEASURED", {"svc:GetThing": ([{"action": action, "of": of}], evidence)})
    with pytest.raises(ValueError, match=problem):
        r.build(forwarding_documents(tmp_path, **change), "test", tmp_path, "botocore test")


def test_a_forwarded_entry_must_name_an_operation_the_file_reads(isolated, tmp_path):
    isolated.setattr(r, "MEASURED", {"svc:Gone": ([{"action": "keys:Decrypt", "of": "thing"}], GUIDE_SAYS)})
    with pytest.raises(ValueError, match="MEASURED gives svc:Gone, which the file does not read"):
        r.build(forwarding_documents(tmp_path), "test", tmp_path, "botocore test")


def forwarding_document() -> dict:
    document = good_document()
    document["forwardedActions"] = {"keys:Decrypt": {"type": "key", "format": KEY_FORMAT, "viaService": "keys:ViaService"}}
    document["operations"]["svc:A"]["forwarded"] = [
        {"action": "keys:Decrypt", "of": "thing", "member": "KeysKeyId", "endpoint": "svc"}]
    return document


@pytest.mark.parametrize("change, problem", [
    (lambda d: d["operations"]["svc:A"]["forwarded"][0].update(action="keys:Encrypt"), "forwards no recorded action"),
    (lambda d: d["operations"]["svc:A"]["forwarded"][0].update(of="group"), "forwards no recorded action"),
    (lambda d: d["operations"]["svc:A"]["forwarded"][0].update(member="Keys[]"), "forwards no recorded action"),
    (lambda d: d["operations"]["svc:A"]["forwarded"][0].pop("endpoint"), "forwards no recorded action"),
    (lambda d: d["operations"]["svc:A"].update(forwarded=[]), "forwards no recorded action"),
    (lambda d: d["forwardedActions"]["keys:Decrypt"].update(viaService="other:ViaService"), "forwardedActions is not"),
    (lambda d: d["forwardedActions"]["keys:Decrypt"].update(format="arn:${Partition}:other:::key/${KeyId}"),
     "forwardedActions is not"),
    (lambda d: d.pop("forwardedActions"), "forwardedActions is not"),
])
def test_a_malformed_forwarded_entry_is_rejected(change, problem):
    assert r.validate(forwarding_document()) == []
    document = forwarding_document()
    change(document)
    assert any(problem in found for found in r.validate(document))


def test_verify_fails_when_aws_changes_what_it_gives_a_forwarded_action_or_lists_it_for_the_operation(isolated):
    isolated.setattr(r, "MEASURED", {"svc:A": ([{"action": "keys:Decrypt", "of": "thing"}], GUIDE_SAYS)})
    document = forwarding_document()
    documents = [reference("svc", {"A": ["thing"]}, {"thing": FORMATS["thing"]}), keys_reference()]
    assert r.verify_forwarded(document, documents) == []
    assert r.verify_forwarded(document, [documents[0], keys_reference(condition_keys=[])]) == [
        'AWS gives keys:Decrypt {"type": "key", "format": "' + KEY_FORMAT + '"}, not '
        '{"type": "key", "format": "' + KEY_FORMAT + '", "viaService": "keys:ViaService"}']
    documents[0]["Operations"][0]["AuthorizedActions"].append({"Service": "keys", "Name": "Decrypt"})
    assert r.verify_forwarded(document, documents) == [
        "AWS now lists ['keys:Decrypt'] for svc:A: a rule reads those, so MEASURED should not give them"]


def test_check_fails_when_the_file_records_other_forwarded_actions_than_forwarded_names(isolated, tmp_path):
    isolated.setattr(r, "MEASURED", {"svc:GetThing": ([{"action": "keys:Decrypt", "of": "thing"}], GUIDE_SAYS)})
    document = r.build(forwarding_documents(tmp_path), "test", tmp_path, "botocore test")
    isolated.setattr(r, "MEASURED", {})
    document["operations"]["svc:GetThing"].pop("forwarded")
    assert r.derive(document, tmp_path) == [
        "the file records ['keys:Decrypt'] as forwarded actions, MEASURED names []"]


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


def test_a_member_two_clients_locate_apart_is_read_under_each_client(monkeypatch):
    # S3's CreateBucket carries its Bucket as {Bucket}, S3 Control's as {name}: a request's route tells which it is.
    one = rest_model({"GetThing": {"ThingName": "String", "Marker": "String"}},
                     {"GetThing": {"ThingName": "uri:ThingName", "Marker": "querystring:marker"}})
    two = rest_model({"GetThing": {"ThingName": "String", "Marker": "String"}},
                     {"GetThing": {"ThingName": "uri:name", "Marker": "querystring:marker"}})
    monkeypatch.setattr(r, "REST_MODELS", {"one": "svc", "two": "svc"})
    assert r.located_members({"one": one, "two": two}, "GetThing") == (
        {"Marker": "?marker"}, {"one": {"ThingName": "{ThingName}"}, "two": {"ThingName": "{name}"}})


def test_a_member_one_client_locates_and_another_carries_in_a_body_it_reads_is_located_for_the_first_alone(monkeypatch):
    # A request of the client carrying it in its body is read from its body, not from a path it has no label in.
    one = rest_model({"GetThing": {"ThingName": "String"}}, {"GetThing": {"ThingName": "uri:ThingName"}})
    two = model({"GetThing": {"ThingName": "String"}})
    monkeypatch.setattr(r, "REST_MODELS", {"one": "svc"})
    assert r.located_members({"one": one, "two": two}, "GetThing") == ({}, {"one": {"ThingName": "{ThingName}"}})
    # A REST-XML body is not read, so a client carrying the member there leaves it shared.
    xml = {**two, "metadata": {**two.get("metadata", {}), "protocol": "rest-xml"}}
    assert r.located_members({"one": one, "two": xml}, "GetThing") == ({"ThingName": "{ThingName}"}, {})
    monkeypatch.setattr(r, "REST_MODELS", {})
    with pytest.raises(ValueError, match="one has no vendored routes"):
        r.located_members({"one": one, "two": two}, "GetThing")


def test_a_member_located_apart_by_a_client_whose_routes_are_not_vendored_is_an_error(monkeypatch):
    one = rest_model({"GetThing": {"ThingName": "String"}}, {"GetThing": {"ThingName": "uri:ThingName"}})
    two = rest_model({"GetThing": {"ThingName": "String"}}, {"GetThing": {"ThingName": "querystring:thing"}})
    monkeypatch.setattr(r, "REST_MODELS", {"one": "svc"})
    with pytest.raises(ValueError, match="GetThing's ThingName is located apart by one, two, and two has no vendored"):
        r.located_members({"one": one, "two": two}, "GetThing")


# S3's object types: an access point's object and a bucket's.
OBJECTS = {"accesspointobject": "arn:${Partition}:s3:${Region}:${Account}:accesspoint/${AccessPointName}/object/"
                                "${ObjectName}",
           "object": "arn:${Partition}:s3:::${BucketName}/${ObjectName}"}
OBJECT_MEMBERS = {"Bucket": "Bucket", "Key": "Key", "ContentType": "ContentType"}
OBJECT_SHAPES = {"Bucket": "BucketName", "Key": "ObjectKey", "ContentType": "ContentType"}


def test_a_member_whose_shape_is_named_as_the_type_and_key_fills_the_types_name():
    assert r._filling("ObjectName", "object", 2, {"Key": "Key", "Bucket": "Bucket"}, bare=set(),
                      shapes={"Key": "ObjectKey", "Bucket": "BucketName"}) == "Key"
    assert r.resources_for("s3:GetObject", ["accesspointobject", "object"], OBJECTS, OBJECT_MEMBERS,
                           shapes=OBJECT_SHAPES) == [
        {"type": "object", "fill": {"BucketName": "Bucket", "ObjectName": "Key"}}]
    # A shape named as another type and Key names that one.
    assert r._filling("ObjectName", "object", 2, {"Key": "Key"}, bare=set(), shapes={"Key": "BucketKey"}) is None


def test_a_type_named_exactly_as_what_the_operation_makes_rules_out_one_only_ending_with_it():
    # PutObject makes an `object`, not an `accesspointobject`, whose access point the request does not name.
    assert r.resources_for("s3:PutObject", ["accesspointobject", "object"], OBJECTS, OBJECT_MEMBERS,
                           shapes=OBJECT_SHAPES) == [
        {"type": "object", "fill": {"BucketName": "Bucket", "ObjectName": "Key"}}]
    # With no type named exactly so, one ending with the name is still what it makes.
    assert r.resources_for("s3:PutObject", ["accesspointobject"], OBJECTS, OBJECT_MEMBERS, shapes=OBJECT_SHAPES) == [
        {"type": "accesspointobject", "fill": {}}]


def test_a_type_a_body_the_reader_does_not_read_names_by_a_key_shape_is_left_unread():
    # S3's DeleteObjects names each object it deletes in Delete.Objects[].Key, an ObjectKey, in a REST-XML body.
    shapes = {"String": {"type": "string"}, "ObjectKey": {"type": "string"},
              "Identifier": {"type": "structure", "members": {"Key": {"shape": "ObjectKey"}}},
              "Identifiers": {"type": "list", "member": {"shape": "Identifier"}},
              "Delete": {"type": "structure", "members": {"Objects": {"shape": "Identifiers"}}},
              "In": {"type": "structure", "members": {
                  "Bucket": {"shape": "String", "location": "uri", "locationName": "Bucket"},
                  "Delete": {"shape": "Delete"}}}}
    models = {"s3": {"metadata": {"protocol": "rest-xml"}, "operations": {"DeleteObjects": {"input": {"shape": "In"}}},
                     "shapes": shapes}}
    bucket_only = [{"type": "object", "fill": {"BucketName": "Bucket"}}]
    assert r.body_only_identifiers(["object"], OBJECTS, bucket_only, models, "DeleteObjects") == ["Delete.Objects[].Key"]
    # The type's own variable filled by what the reader reads, nothing is left.
    assert r.body_only_identifiers(["object"], OBJECTS, [{"type": "object", "fill": {
        "BucketName": "Bucket", "ObjectName": "Key"}}], models, "DeleteObjects") == []
    # A body the reader reads leaves nothing unread.
    rest_json = {"s3": {**models["s3"], "metadata": {"protocol": "rest-json"}}}
    assert r.body_only_identifiers(["object"], OBJECTS, bucket_only, rest_json, "DeleteObjects") == []


def test_a_located_member_must_be_a_path_label_or_a_query_parameter(isolated, tmp_path):
    write_models(tmp_path, {"svc": rest_model({"GetThing": {"ThingName": "String"}, "ListThings": {}},
                                              {"GetThing": {"ThingName": "uri:ThingName"}})})
    document = r.build(DOCUMENTS, "test", tmp_path, "botocore test")
    document["operations"]["svc:GetThing"]["located"] = {"ThingName": "header:x"}
    assert any("does not give each member it locates" in problem for problem in r.validate(document))


def test_a_member_located_apart_names_a_client_whose_routes_are_vendored_and_an_unread_one_a_body_path(
        isolated, tmp_path, monkeypatch):
    write_models(tmp_path, {"svc": rest_model({"GetThing": {"ThingName": "String"}, "ListThings": {}},
                                              {"GetThing": {"ThingName": "uri:ThingName"}})})
    document = r.build(DOCUMENTS, "test", tmp_path, "botocore test")
    monkeypatch.setattr(r, "REST_MODELS", {"one": "svc"})
    entry = document["operations"]["svc:GetThing"]
    entry.update(locatedApart={"one": {"ThingName": "{name}"}}, unread=["Delete.Objects[].Key"])
    assert r.validate(document) == []
    for broken in ({"locatedApart": {"two": {"ThingName": "{name}"}}}, {"locatedApart": {"one": {"ThingName": "x"}}},
                   {"locatedApart": {}}):
        assert any("locates apart" in problem for problem in r.validate(
            {**document, "operations": {**document["operations"], "svc:GetThing": {**entry, **broken}}}))
    for broken in (["Delete..Key"], [], "Delete.Objects[].Key"):
        assert any("leaves unread" in problem for problem in r.validate(
            {**document, "operations": {**document["operations"], "svc:GetThing": {**entry, "unread": broken}}}))


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


def with_instance_example(model_: dict) -> dict:
    """An EC2 model whose documentation gives an instance id's example, as botocore's does."""
    model_["shapes"]["Filter"] = {"type": "structure", "members": {"Value": {
        "shape": "String", "documentation": "<p>For associations, this is the instance ID (for example, "
                                            "i-0123456789abcdef0).</p>"}}}
    return model_


def test_create_vpc_reads_the_new_vpc_and_each_optional_pool():
    ec2 = model({"CreateVpc": {"CidrBlock": "String", "Ipv4IpamPoolId": "IpamPoolId",
                               "Ipv6IpamPoolId": "IpamPoolId", "Ipv6Pool": "Ipv6PoolEc2Id"}}, "ec2")
    ec2["shapes"]["IpamPoolId"] = {"type": "string"}
    ec2["shapes"]["Ipv6PoolEc2Id"] = {"type": "string"}
    formats = {"vpc": "arn:${Partition}:ec2:${Region}:${Account}:vpc/${VpcId}",
               "ipam-pool": "arn:${Partition}:ec2::${Account}:ipam-pool/${IpamPoolId}",
               "ipv6pool-ec2": "arn:${Partition}:ec2:${Region}:${Account}:ipv6pool-ec2/${Ipv6PoolEc2Id}"}
    upstream = {"action": "ec2:CreateVpc", "types": ["ipam-pool", "ipv6pool-ec2", "vpc"]}
    entry = r.entry_for("ec2:CreateVpc", upstream, formats, {"ec2": ec2})
    assert entry["resources"] == [
        {"type": "ipam-pool", "fill": {"IpamPoolId": "Ipv4IpamPoolId"}},
        {"type": "ipv6pool-ec2", "fill": {"Ipv6PoolEc2Id": "Ipv6Pool"}},
        {"type": "vpc", "fill": {}, "made": True},
        {"type": "ipam-pool", "fill": {"IpamPoolId": "Ipv6IpamPoolId"}}]
    assert "targets" not in entry


def test_id_targets_follow_the_model_and_resource_types_not_operation_names():
    ec2 = with_instance_example(model({"AnnotateResources": {"Resources": "Strings"}}, "ec2"))
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
    # With no example of an instance id in botocore's documentation, nothing tells an instance id apart.
    member["shape"] = "Strings"
    del ec2["shapes"]["Filter"]
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
        # The skeleton's other members are placeholders (an empty tag key), so only the serialization is checked.
        request = create_serializer(model.metadata["protocol"], include_validation=False).serialize_to_request(
            parameters, operation_model)
        assert [request["body"][target["query"] + "." + str(i)] for i in range(1, 4)] == ids
        checked += 1
    assert checked > 0


def test_instance_target_member_is_derived_from_the_arn_variable_not_the_action_name():
    names = ("StartInstances", "StopInstances", "RebootInstances", "TerminateInstances", "InventedAction")
    ec2 = with_instance_example(model({name: {"InstanceIds": "Strings", "Resources": "Strings"} for name in names}, "ec2"))
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
    renamed = with_instance_example(model({"InventedAction": {"MachineIds": "Strings"}}, "ec2"))
    renamed_formats = {"instance": "arn:${Partition}:ec2:${Region}:${Account}:instance/${MachineId}"}
    assert r.entry_for("ec2:InventedAction", {"action": "ec2:InventedAction", "types": ["instance"]},
                       renamed_formats, {"ec2": renamed})["targets"]["member"] == "MachineIds[]"


def test_resource_tag_prefixes_cover_every_type_with_a_format_of_the_namespaces_read_from_the_reference():
    documents = [{"Name": "ec2", "Resources": [
        {"Name": "instance", "ConditionKeys": ["vendor:ResourceTag/${TagKey}", "aws:RequestTag/${TagKey}",
                                               "aws:ResourceTag/${TagKey}", "ec2:Region"]},
        {"Name": "volume", "ConditionKeys": ["volume:ResourceTag/${TagKey}"]},
        {"Name": "unformatted", "ConditionKeys": ["aws:ResourceTag/${TagKey}"]},
        {"Name": "untaggable", "ConditionKeys": ["ec2:Region"]}]},
        {"Name": "secretsmanager", "Resources": [{"Name": "Secret", "ConditionKeys": [
            "aws:ResourceTag/${TagKey}", "secretsmanager:ResourceTag/tag-key", "secretsmanager:SecretPrimaryRegion"]}]},
        {"Name": "ssm", "Resources": [{"Name": "parameter", "ConditionKeys": [
            "aws:ResourceTag/${TagKey}", "ssm:resourceTag/${TagKey}", "ssm:resourceTag/tag-key",
            "ssm:resourceTag/aws:ssmmessages:session-id"]}]},
        {"Name": "other", "Resources": [{"Name": "thing", "ConditionKeys": ["aws:ResourceTag/${TagKey}"]}]}]
    formats = {"ec2": {kind: f"arn:${{Partition}}:ec2:${{Region}}:${{Account}}:{kind}/${{Id}}"
                       for kind in ("instance", "volume", "untaggable")},
               "secretsmanager": {"Secret": "arn:${Partition}:secretsmanager:${Region}:${Account}:secret:${SecretId}"},
               "ssm": {"parameter": "arn:${Partition}:ssm:${Region}:${Account}:parameter/${ParameterNameWithoutLeadingSlash}"}}
    # A key naming one tag key of its own (`aws:ssmmessages:session-id`) is not a prefix; a namespace not read, or a
    # type without an ARN format, has none.
    assert r.resource_tag_keys(documents, formats) == {
        "ec2": {"instance": ["aws:ResourceTag/", "vendor:ResourceTag/"], "volume": ["volume:ResourceTag/"]},
        "secretsmanager": {"Secret": ["aws:ResourceTag/", "secretsmanager:ResourceTag/"]},
        "ssm": {"parameter": ["aws:ResourceTag/", "ssm:resourceTag/"]}}
    assert r.resource_tag_keys([], formats) == {}


def test_resource_keys_are_the_reference_keys_a_member_of_the_types_shape_names(monkeypatch):
    model = {"shapes": {
        "NetworkInterface": {"type": "structure", "members": {
            "VpcId": {"shape": "String"}, "SubnetId": {"shape": "String"},
            "NetworkInterfaceId": {"shape": "String"}, "Groups": {"shape": "GroupList"}}},
        "String": {"type": "string"}, "GroupList": {"type": "list", "member": {"shape": "String"}}}}
    monkeypatch.setattr(r, "load_models", lambda data, clients: {"ec2": model})
    document = {"Name": "ec2", "ConditionKeys": [
        {"Name": "ec2:Vpc", "Types": ["ARN"]}, {"Name": "ec2:Subnet", "Types": ["ARN"]},
        {"Name": "ec2:NetworkInterfaceID", "Types": ["String"]}, {"Name": "ec2:Region", "Types": ["String"]},
        {"Name": "ec2:Groups", "Types": ["String"]}],
        "Resources": [
            {"Name": "network-interface", "ARNFormats": ["arn:${Partition}:ec2:${Region}:${Account}:network-interface/x"],
             "ConditionKeys": ["aws:ResourceTag/${TagKey}", "ec2:Vpc", "ec2:Subnet", "ec2:NetworkInterfaceID",
                               "ec2:Region", "ec2:Groups", "ec2:Attribute/${AttributeName}"]},
            {"Name": "vpc", "ARNFormats": ["arn:${Partition}:ec2:${Region}:${Account}:vpc/${VpcId}"],
             "ConditionKeys": []}]}
    # A key named as the member or as it without its Id; a list member, a key no member names, an ARN key with no
    # type named as it and a key with a variable are left out.
    assert r.resource_keys([document], Path("."), {"ec2": ["ec2"]}) == {"ec2": {"network-interface": {
        "ec2:NetworkInterfaceID": {"member": "NetworkInterfaceId"}, "ec2:Vpc": {"member": "VpcId", "arn": "vpc"}}}}
    assert r.resource_keys([document], Path("."), {}) == {}


def test_id_prefixes_read_botocores_examples_by_the_shape_of_each_member():
    ec2 = model({"DescribeGroups": {"GroupIds": "GroupIdList", "Label": "String"}}, "ec2")
    ec2["shapes"]["SecurityGroupId"] = {"type": "string"}
    ec2["shapes"]["GroupIdList"] = {"type": "list", "member": {"shape": "SecurityGroupId"}}
    ec2["operations"]["DescribeGroups"]["output"] = {"shape": "DescribeGroupsOut"}
    ec2["shapes"]["DescribeGroupsOut"] = {"type": "structure", "members": {"GroupId": {"shape": "SecurityGroupId"}}}
    examples = {"ec2": {"examples": {"DescribeGroups": [
        {"input": {"GroupIds": ["sg-903004f8", "sg-1a2b3c4d"], "Label": "lb-0123456789abcdef"},
         "output": {"GroupId": "sg-0a1b2c3d4e5f60718"}},
        {"input": {"GroupIds": ["xx-12345678"]}}]}}}
    assert r.id_prefixes({"ec2": ec2}, ("security-group",), examples) == {"security-group": "sg-"}
    # A String member's id-like value names no type, and without examples nothing is known.
    assert r.id_prefixes({"ec2": ec2}, ("lb",), examples) == {}
    assert r.id_prefixes({"ec2": ec2}, ("security-group",)) == {}



def test_vpc_endpoint_prefix_remains_unknown_when_botocore_has_none():
    ec2 = model({"DescribeVpcEndpoints": {"VpcEndpointIds": "VpcEndpointIdList"}}, "ec2")
    ec2["shapes"]["VpcEndpointId"] = {"type": "string"}
    ec2["shapes"]["VpcEndpointIdList"] = {"type": "list", "member": {"shape": "VpcEndpointId"}}
    assert r.id_prefixes({"ec2": ec2}, ("vpc-endpoint",)) == {}
    assert r.namespace_id_prefixes("ec2", {"ec2": ec2}) == {}
    assert r.namespace_id_prefixes("other", {"ec2": ec2}) is None


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


CLUSTERS = {"cluster": "arn:${Partition}:svc:${Region}:${Account}:cluster/${ClusterName}",
            "service": "arn:${Partition}:svc:${Region}:${Account}:service/${ClusterName}/${ServiceName}",
            "task-definition": "arn:${Partition}:svc:${Region}:${Account}:task-definition/"
                               "${TaskDefinitionFamilyName}:${TaskDefinitionRevisionNumber}"}


def cluster_model(operation: str, docs: dict[str, str], required: tuple[str, ...] = ()) -> dict[str, dict]:
    """A JSON model whose operation takes `docs`' members, `Names` lists for those ending in `s`, others strings."""
    shapes = {"String": {"type": "string"}, "Names": {"type": "list", "member": {"shape": "String"}},
              "In": {"type": "structure", "required": list(required), "members": {
                  name: {"shape": "Names" if name.endswith("s") else "String", "documentation": doc}
                  for name, doc in docs.items()}}}
    return {"svc": {"metadata": {"protocol": "json"}, "operations": {operation: {"input": {"shape": "In"}}},
                    "shapes": shapes}}


CLUSTER_DOC = ("<p>The short name or full Amazon Resource Name (ARN) of the cluster that hosts the service to describe. "
               "If you do not specify a cluster, the default cluster is assumed.</p>")


def test_a_member_whose_service_uses_its_default_when_left_out_names_that_default():
    # ECS: "If you do not specify a cluster, the default cluster is assumed", or "default is used".
    assert r.defaulted_members(cluster_model("DescribeServices", {"cluster": CLUSTER_DOC}), "DescribeServices") == {
        "cluster": "cluster"}
    assert r.defaulted_members(cluster_model("ListThings", {"cluster": "<p>If you don't specify a cluster, default is "
                                                                       "used.</p>"}), "ListThings") == {"cluster": "cluster"}
    assert r.defaulted_members(cluster_model("DescribeClusters", {"clusters": "<p>A list of up to 100 cluster names. If "
                                                                              "you do not specify a cluster, the default "
                                                                              "cluster is assumed.</p>"}),
                               "DescribeClusters") == {"clusters[]": "cluster"}
    # A required member is never left out, and one documenting no default names none.
    assert r.defaulted_members(cluster_model("DeleteCluster", {"cluster": CLUSTER_DOC}, ("cluster",)),
                               "DeleteCluster") == {}
    assert r.defaulted_members(cluster_model("DescribeThing", {"cluster": "<p>The name of the cluster.</p>"}),
                               "DescribeThing") == {}
    service = {"type": "service", "fill": {"ClusterName": "cluster", "ServiceName": "services[]"}}
    defaulted = dict(service, defaults={"cluster": "cluster"})
    assert r.with_documented_defaults([service], CLUSTERS, {"cluster": "cluster"}) == [defaulted]
    # Only the default of the type whose format is that variable alone, named by the documentation's word.
    assert r.with_documented_defaults([service], CLUSTERS, {"cluster": "pool"}) == [service]
    assert r.with_documented_defaults([service], CLUSTERS, {"serviceName": "service"}) == [service]
    # Named then, not only when the request gives it.
    assert r.with_optional_qualifier([defaulted], {"cluster"}) == [defaulted]
    assert r.with_optional_qualifier([service], {"cluster"}) == [dict(service, unless={"cluster": [None]})]


GROUPS = {"schedule": "arn:${Partition}:scheduler:${Region}:${Account}:schedule/${GroupName}/${ScheduleName}",
          "schedule-group": "arn:${Partition}:scheduler:${Region}:${Account}:schedule-group/${GroupName}"}


def test_a_member_whose_service_assumes_its_default_when_omitted_names_that_default():
    # EventBridge Scheduler names the type after "default", in each of the three ways botocore puts it.
    for doc in ("<p>The name of the schedule group to associate with this schedule. If you omit this, the default "
                "schedule group is used.</p>",
                "<p>The name of the schedule group associated with this schedule. If you omit this, EventBridge "
                "Scheduler assumes that the schedule is associated with the default group.</p>",
                "<p>The name of the schedule group with which the schedule is associated. You must provide this value "
                "in order for EventBridge Scheduler to find the schedule you want to update. If you omit this value, "
                "EventBridge Scheduler assumes the group is associated to the default group.</p>"):
        defaulted = r.defaulted_members(cluster_model("GetSchedule", {"GroupName": doc}), "GetSchedule")
        assert set(defaulted) == {"GroupName"}
        schedule = {"type": "schedule", "fill": {"GroupName": "GroupName", "ScheduleName": "Name"}}
        assert r.with_documented_defaults([schedule], GROUPS, defaulted) == [
            dict(schedule, defaults={"GroupName": "schedule-group"})]
    # A filter naming no default names none.
    listing = cluster_model("ListSchedules", {"GroupName": "<p>If specified, only lists the schedules whose associated "
                                                           "schedule group matches the given filter.</p>"})
    assert r.defaulted_members(listing, "ListSchedules") == {}


def test_a_resource_of_a_default_pair_takes_no_default_of_its_own():
    # EventBridge documents EventBusName "If you omit this, the default event bus is used", and a rule on the
    # default bus is the pair's other resource, which applies when the request leaves the bus out.
    formats = {"event-bus": "arn:${Partition}:events:${Region}:${Account}:event-bus/${EventBusName}",
               "rule-on-custom-event-bus": "arn:${Partition}:events:${Region}:${Account}:rule/${EventBusName}/"
                                           "${RuleName}"}
    custom = {"type": "rule-on-custom-event-bus", "fill": {"EventBusName": "EventBusName", "RuleName": "Name"},
              "unlessDefault": {"EventBusName": "event-bus"}}
    assert r.with_documented_defaults([custom], formats, {"EventBusName": "event-bus"}) == [custom]
    unpaired = {key: value for key, value in custom.items() if key != "unlessDefault"}
    assert r.with_documented_defaults([unpaired], formats, {"EventBusName": "event-bus"}) == [
        dict(unpaired, defaults={"EventBusName": "event-bus"})]


def test_a_member_documented_as_a_name_or_an_arn_of_another_type_may_give_that_types_arn():
    for doc in (CLUSTER_DOC, "<p>The short name or full ARN of the cluster that hosts the task.</p>",
                # AWS's own typo, the "of" left out.
                "<p>The short name or full Amazon Resource Name (ARN)the cluster that hosts the service to describe.</p>",
                # The two the other way round, and the type before them past the first sentence.
                "<p>The Amazon Resource Name (ARN) or short name of the cluster the task is running in.</p>",
                "<p>The cluster that hosts the service. This can either be the cluster name or ARN.</p>"):
        assert r.documented_name_or_arn_members(cluster_model("DescribeServices", {"cluster": doc}),
                                                "DescribeServices") == {"cluster": "cluster"}
    assert r.documented_name_or_arn_members(cluster_model("DescribeServices", {"cluster": "<p>The name of the cluster."
                                                                                          "</p>"}), "DescribeServices") == {}
    service = {"type": "service", "fill": {"ClusterName": "cluster", "ServiceName": "service"}}
    cluster = {"type": "cluster", "fill": {"ClusterName": "cluster"}}
    assert r.with_arn_of([service, cluster], CLUSTERS, {}, {"cluster": "cluster"}) == [
        dict(service, arnOf={"cluster": "cluster"}), cluster]
    # A pattern taking the ARN says so of the resource's own type too.
    assert r.with_arn_of([cluster], CLUSTERS, {"cluster": "cluster"}) == [dict(cluster, arnOf={"cluster": "cluster"})]


def test_each_elements_id_whose_target_type_is_one_type_fills_that_types_last_variable():
    shapes = {"String": {"type": "string"}, "TargetType": {"type": "string", "enum": ["container-instance"]},
              "Attribute": {"type": "structure", "members": {"name": {"shape": "String"},
                                                             "targetType": {"shape": "TargetType"},
                                                             "targetId": {"shape": "String"}}},
              "Attributes": {"type": "list", "member": {"shape": "Attribute"}},
              "In": {"type": "structure", "members": {"cluster": {"shape": "String"},
                                                      "attributes": {"shape": "Attributes"}}}}
    models = {"svc": {"metadata": {"protocol": "json"}, "operations": {"PutAttributes": {"input": {"shape": "In"}}},
                      "shapes": shapes}}
    typed = r.typed_target_fields(models, "PutAttributes")
    assert typed == {"attributes[].targetId": ["container-instance"]}
    formats = {"container-instance": "arn:${Partition}:svc:${Region}:${Account}:container-instance/${ClusterName}/"
                                     "${ContainerInstanceId}"}
    instance = {"type": "container-instance", "fill": {"ClusterName": "cluster"}}
    assert r.with_typed_targets([instance], formats, typed) == [
        {"type": "container-instance", "fill": {"ClusterName": "cluster",
                                                "ContainerInstanceId": "attributes[].targetId"}}]
    # Not a type the enum does not name alone, nor a variable a member fills already.
    assert r.with_typed_targets([instance], formats, {"attributes[].targetId": ["container-instance", "task"]}) == [
        instance]
    filled = {"type": "container-instance", "fill": {"ClusterName": "cluster", "ContainerInstanceId": "instance"}}
    assert r.with_typed_targets([filled], formats, typed) == [filled]


def test_a_type_of_two_variables_its_documentation_spells_is_found_by_its_service():
    assert r.spelled("task-definition", CLUSTERS["task-definition"]) == "(family:revision)"
    assert r.spelled("service", CLUSTERS["service"]) is None
    assert r.spelled("cluster", CLUSTERS["cluster"]) is None
    run = cluster_model("RunTask", {"taskDefinition": "<p>The <code>family</code> and <code>revision</code> (<code>"
                                                      "family:revision</code>) or full ARN of the task definition to "
                                                      "run.</p>"})
    # Read neither as an ARN alone nor left unnamed: its service resolves a family to its latest revision.
    assert r.with_documented("svc:RunTask", ["task-definition"], CLUSTERS, run, []) == [
        {"type": "task-definition", "fill": {}, "find": ["taskDefinition"]}]
    delete = cluster_model("DeleteTaskDefinitions", {"taskDefinitions": "<p>The family and revision (family:revision)"
                                                                        " or full Amazon Resource Name (ARN) of the "
                                                                        "task definition to delete.</p>"})
    assert r.with_documented("svc:DeleteTaskDefinitions", ["task-definition"], CLUSTERS, delete, []) == [
        {"type": "task-definition", "fill": {}, "find": ["taskDefinitions[]"]}]
    # A member holding the ARN alone still names it as one.
    by_arn = cluster_model("RunTask", {"definition": "<p>The full ARN of the task definition to run.</p>"})
    assert r.with_documented("svc:RunTask", ["task-definition"], CLUSTERS, by_arn, []) == [
        {"types": ["task-definition"], "arn": "definition"}]


def test_an_arn_patterns_type_words_and_a_clients_versioned_twin():
    assert r.pattern_words("arn:aws:svc:[a-z0-9-]+:([0-9]{12}):(bus-v2|subscriber|source-v2\\/(a|b))\\/[A-Za-z]+") == {
        "bus-v2", "subscriber", "source-v2"}
    assert r.pattern_words("(arn:aws[\\w-]*:svc:[a-z]+:[0-9]{12}:bus\\/)?[A-Za-z]+") == {"bus"}
    # An optional account gives each type word in an alternative of its own (Bedrock's taggable ARNs).
    assert r.pattern_words("(^arn:aws:svc:[a-z0-9-]{1,20}:[0-9]{12}:model/(imported)/[a-z0-9]{12}$)|"
                           "(^arn:aws:svc:[a-z0-9-]{1,20}:([0-9]{12}|)((:(job|model)/[a-z0-9]+$)|(:guardrail/[a-z0-9]+$)|"
                           "(:(profile|app-profile)/[a-z0-9]+$)))") == {"model", "job", "guardrail", "profile", "app-profile"}
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


def test_vpc_operations_read_vpc_ids_and_stored_tag_keys():
    ec2 = model({"ModifyVpcAttribute": {"VpcId": "String"}}, "ec2")
    formats = {"vpc": "arn:${Partition}:ec2:${Region}:${Account}:vpc/${VpcId}"}
    assert r.entry_for("ec2:ModifyVpcAttribute", {"action": "ec2:ModifyVpcAttribute", "types": ["vpc"]},
                       formats, {"ec2": ec2})["resources"] == [{"type": "vpc", "fill": {"VpcId": "VpcId"}}]
    assert r.resource_tag_keys([{"Name": "ec2", "Resources": [{"Name": "vpc", "ConditionKeys": [
        "aws:ResourceTag/${TagKey}", "ec2:ResourceTag/${TagKey}", "ec2:Region"]}]}], {"ec2": formats}) == {
            "ec2": {"vpc": ["aws:ResourceTag/", "ec2:ResourceTag/"]}}


@pytest.mark.parametrize("operation,kind,variable", [
    ("CreateSubnet", "subnet", "SubnetId"),
    ("CreateRouteTable", "route-table", "RouteTableId"),
    ("CreateSecurityGroup", "security-group", "SecurityGroupId"),
    ("CreateInternetGateway", "internet-gateway", "InternetGatewayId"),
])
def test_network_creates_authorize_new_resource_and_existing_vpc(operation, kind, variable):
    has_parent = operation != "CreateInternetGateway"
    members = {"VpcId": "String"} if has_parent else {}
    ec2 = model({operation: members}, "ec2")
    formats = {kind: f"arn:${{Partition}}:ec2:${{Region}}:${{Account}}:{kind}/${{{variable}}}",
               "vpc": "arn:${Partition}:ec2:${Region}:${Account}:vpc/${VpcId}"}
    types = [kind, "vpc"] if has_parent else [kind]
    entry = r.entry_for(f"ec2:{operation}", {"action": f"ec2:{operation}", "types": types},
                        formats, {"ec2": ec2})
    expected = [{"type": kind, "fill": {}}]
    if has_parent:
        expected[0]["made"] = True
        expected.append({"type": "vpc", "fill": {"VpcId": "VpcId"}})
    assert entry["resources"] == expected


@pytest.mark.parametrize("documentation,output_id,input_id,expected", [
    ("<p>Allocates an Elastic IP address to your account.</p>", True, False, True),
    ("Allocates a pool for an Elastic IP address.", True, False, False),
    ("Describes an Elastic IP address.", True, False, False),
    ("Allocates an Elastic IP address.", False, False, False),
    ("Allocates an Elastic IP address.", True, True, False),
])
def test_documented_creation_requires_direct_object_and_new_response_id(documentation, output_id, input_id, expected):
    ec2 = model({"AllocateAddress": {"AllocationId": "String"} if input_id else {}}, "ec2")
    ec2["operations"]["AllocateAddress"].update(documentation=documentation, output={"shape": "Out"})
    ec2["shapes"]["Out"] = {"type": "structure", "members": {
        "AllocationId" if output_id else "PublicIp": {"shape": "String"}}}
    formats = {"elastic-ip": "arn:${Partition}:ec2:${Region}:${Account}:elastic-ip/${AllocationId}"}
    assert r.with_documented_creation([], "ec2:AllocateAddress", ["elastic-ip"], formats, {"ec2": ec2}) == (
        [{"type": "elastic-ip", "fill": {}, "made": True}] if expected else [])


def test_ec2_query_names_follow_model_serialization_and_only_read_resource_members():
    ec2 = model({"CreateNetworkInterface": {"Groups": "Strings", "SubnetId": "String", "Ignored": "Strings"}}, "ec2")
    group = ec2["shapes"]["CreateNetworkInterfaceIn"]["members"]["Groups"]
    group["locationName"] = "securityGroupId"
    assert r.ec2_query_members("ec2:CreateNetworkInterface", {"ec2": ec2}, {"Groups", "SubnetId"}) == {
        "Groups[]": "SecurityGroupId"}
    group["queryName"] = "ExactGroupWireName"
    assert r.ec2_query_members("ec2:CreateNetworkInterface", {"ec2": ec2}, {"Groups"}) == {
        "Groups[]": "ExactGroupWireName"}
    ec2["metadata"]["protocol"] = "query"
    assert r.ec2_query_members("ec2:CreateNetworkInterface", {"ec2": ec2}, {"Groups"}) == {}


def test_a_typed_list_is_the_targets_only_when_the_request_names_nothing_else_the_action_acts_on():
    ec2 = model({"Launch": {"SecurityGroupIds": "Strings", "ImageId": "String"},
                 "ApplyGroups": {"SecurityGroupIds": "Strings"},
                 "AnnotateResources": {"Resources": "Strings"}}, "ec2")
    formats = {"security-group": "arn:${Partition}:ec2:${Region}:${Account}:security-group/${SecurityGroupId}",
               "image": "arn:${Partition}:ec2:${Region}::image/${ImageId}"}
    known = {"security-group": "sg-"}
    # A launch's groups sit beside its image: the ordinary rules read both, and the groups are not its targets.
    launch = r.entry_for("ec2:Launch", {"action": "ec2:Launch", "types": ["image", "security-group"]},
                         formats, {"ec2": ec2}, known)
    assert "targets" not in launch
    assert {"type": "image", "fill": {"ImageId": "ImageId"}} in launch["resources"]
    applied = r.entry_for("ec2:ApplyGroups", {"action": "ec2:ApplyGroups", "types": ["security-group"]},
                          formats, {"ec2": ec2}, known)
    assert applied["targets"] == {"member": "SecurityGroupIds[]", "query": "SecurityGroupIds",
                                  "prefixes": {"security-group": "sg-"}}
    tags = r.entry_for("ec2:AnnotateResources", {"action": "ec2:AnnotateResources", "types": ["security-group"]},
                       formats, {"ec2": ec2}, known)
    assert tags["targets"]["prefixes"] == {"security-group": "sg-"}


def test_a_list_inside_a_structure_is_no_member_the_rules_read():
    ec2 = model({"CreateSnapshots": {"InstanceSpecification": "Specification"}}, "ec2")
    ec2["shapes"]["Specification"] = {"type": "structure", "members": {
        "InstanceId": {"shape": "String"}, "ExcludeDataVolumeIds": {"shape": "Strings"}}}
    found = r.documented_members({"ec2": ec2}, "CreateSnapshots")
    assert "InstanceSpecification.InstanceId" in found
    assert "InstanceSpecification.ExcludeDataVolumeIds" not in found


def test_lists_of_several_types_are_read_by_the_ordinary_rules():
    ec2 = model({"CreateEndpoint": {"RouteTableIds": "Strings", "SecurityGroupIds": "Strings"}}, "ec2")
    formats = {"route-table": "arn:${Partition}:ec2:${Region}:${Account}:route-table/${RouteTableId}",
               "security-group": "arn:${Partition}:ec2:${Region}:${Account}:security-group/${SecurityGroupId}"}
    entry = r.entry_for("ec2:CreateEndpoint", {"action": "ec2:CreateEndpoint", "types": ["route-table", "security-group"]},
                        formats, {"ec2": ec2}, {"route-table": "rtb-", "security-group": "sg-"})
    assert "targets" not in entry
    assert {kind for resource in entry["resources"] for kind in r._kinds(resource)} == {"route-table", "security-group"}


@pytest.mark.parametrize("operation,members,kinds", [
    ("AssociateAddress", {"AllocationId": "String", "InstanceId": "String", "NetworkInterfaceId": "String"},
     ["elastic-ip", "instance", "network-interface"]),
    ("ReleaseAddress", {"AllocationId": "String"}, ["elastic-ip"]),
])
def test_eip_operations_read_scalar_targets_from_models(operation, members, kinds):
    variables = {"elastic-ip": "AllocationId", "instance": "InstanceId", "network-interface": "NetworkInterfaceId"}
    formats = {kind: f"arn:${{Partition}}:ec2:${{Region}}:${{Account}}:{kind}/${{{variables[kind]}}}" for kind in kinds}
    ec2 = model({operation: members}, "ec2")
    action = "ec2:" + operation
    entry = r.entry_for(action, {"action": action, "types": kinds}, formats, {"ec2": ec2})
    assert entry["resources"] == [{"type": kind, "fill": {variables[kind]: variables[kind]}} for kind in kinds]


@pytest.mark.parametrize("documentation,expected", [
    ("<p>The resource to tag. This value is an Amazon Resource Name (ARN).</p>", True),
    ("<p>This value is an Amazon Resource Name (ARN).</p>", True),
    ("<p>The resource to tag. This value is an Amazon Resource Name (ARN) or a name.</p>", False),
    ("<p>The resource to tag. This value can be an Amazon Resource Name (ARN).</p>", False),
    ("<p>The resource to tag. Its parent is an Amazon Resource Name (ARN).</p>", False),
])
def test_an_explicit_arn_value_sentence_identifies_the_request_resource(documentation, expected):
    modeled = model({"AddTagsToResource": {"ResourceName": "String"}}, protocol="query")
    modeled["shapes"]["AddTagsToResourceIn"]["members"]["ResourceName"]["documentation"] = documentation
    result = r.with_documented("svc:AddTagsToResource", ["thing", "pool"], FORMATS, {"svc": modeled}, [])
    assert result == ([{"types": ["thing", "pool"], "arn": "ResourceName"}] if expected else [])


def test_a_creates_subject_can_be_its_sole_arn_variable_for_an_abbreviated_type():
    formats = {"db": "arn:${Partition}:svc:${Region}:${Account}:db:${DbInstanceName}",
               "pg": "arn:${Partition}:svc:${Region}:${Account}:pg:${ParameterGroupName}"}
    database = {"type": "db", "fill": {"DbInstanceName": "DBInstanceIdentifier"}}
    group = {"type": "pg", "fill": {"ParameterGroupName": "DBParameterGroupName"}}
    assert r.with_made([database, group], "svc:CreateDBInstance", formats) == [dict(database, made=True), group]
    assert r.with_made([database, group], "svc:ModifyDBInstance", formats) == [database, group]


def test_resource_type_tag_prefixes_require_the_documented_namespace_and_type():
    formats = {"rds": {"pg": "arn:${Partition}:rds:${Region}:${Account}:pg:${PgName}"}}
    documents = [{"Name": "rds", "Resources": [{"Name": "pg", "ConditionKeys": [
        "aws:ResourceTag/${TagKey}", "rds:pg-tag/${TagKey}", "rds:pg-tag/tag-key",
        "rds:req-tag/${TagKey}", "rds:db-tag/${TagKey}", "other:pg-tag/${TagKey}", "rds:pg-tag/Owner"]}]}]
    assert r.resource_tag_keys(documents, formats) == {"rds": {"pg": ["aws:ResourceTag/", "rds:pg-tag/"]}}


def test_distinct_qualified_parameter_groups_do_not_collapse_to_one_ambiguous_member():
    formats = {"cluster-pg": "arn:${Partition}:rds:${Region}:${Account}:cluster-pg:${ClusterParameterGroupName}",
               "pg": "arn:${Partition}:rds:${Region}:${Account}:pg:${ParameterGroupName}"}
    assert r.resources_for("rds:ModifyDBCluster", ["cluster-pg", "pg"], formats,
            members("DBClusterParameterGroupName", "DBInstanceParameterGroupName")) == [
        {"type": "cluster-pg", "fill": {"ClusterParameterGroupName": "DBClusterParameterGroupName"}},
        {"type": "pg", "fill": {"ParameterGroupName": "DBInstanceParameterGroupName"}}]
    assert r.resources_for("rds:ModifyDBCluster", ["cluster-pg", "pg"], formats,
            members("DBClusterParameterGroupName")) == [
        {"type": "cluster-pg", "fill": {"ClusterParameterGroupName": "DBClusterParameterGroupName"}}]


def test_a_shorter_member_is_not_reserved_for_a_more_qualified_variable():
    formats = {"subnet": "arn:${Partition}:ec2:${Region}:${Account}:subnet/${SubnetId}",
               "secondary-subnet": "arn:${Partition}:ec2:${Region}:${Account}:secondary-subnet/${SecondarySubnetId}"}
    assert r.resources_for("ec2:RunInstances", ["subnet", "secondary-subnet"], formats,
            members("SubnetId", "SecondarySubnetId")) == [
        {"type": "subnet", "fill": {"SubnetId": "SubnetId"}},
        {"type": "secondary-subnet", "fill": {"SecondarySubnetId": "SecondarySubnetId"}}]


QUALIFIED_FORMATS = {"function": "arn:${Partition}:svc:${Region}:${Account}:function:${FunctionName}",
                     "function alias": "arn:${Partition}:svc:${Region}:${Account}:function:${FunctionName}:${Alias}",
                     "function version": "arn:${Partition}:svc:${Region}:${Account}:function:${FunctionName}:${Version}"}


def _qualified_model() -> dict:
    functions = model({"Invoke": {"FunctionName": "FunctionName", "Qualifier": "String"},
                       "GetConfig": {"FunctionName": "FunctionName", "Qualifier": "String"},
                       "CreateAlias": {"FunctionName": "FunctionName", "Name": "String", "FunctionVersion": "String"},
                       "ListAliases": {"FunctionName": "FunctionName", "FunctionVersion": "String"},
                       "Publish": {"FunctionName": "FunctionName"}})
    functions["shapes"]["FunctionName"] = {"type": "string", "pattern": (
        r"(arn:(aws[a-zA-Z-]*)?:svc:)?([a-z]{2}(-gov)?-[a-z]+-\d{1}:)?(\d{12}:)?(function:)?([a-zA-Z0-9-_\.]+)"
        r"(:(\$LATEST|[a-zA-Z0-9-_]+))?")}
    docs = {("Invoke", "Qualifier"): "<p>Specify a version or alias to invoke a published version of the function.</p>",
            ("GetConfig", "Qualifier"): "<p>The version number or alias name.</p>",
            ("CreateAlias", "FunctionVersion"): "<p>The function version that the alias invokes.</p>",
            ("ListAliases", "FunctionVersion"): "<p>Specify a function version to only list aliases that invoke that "
                                                "version.</p>"}
    for (operation, member), text in docs.items():
        functions["shapes"][operation + "In"]["members"][member]["documentation"] = text
    return functions


def test_a_member_documented_as_a_version_or_alias_names_the_qualified_types_an_action_acts_on():
    functions = {"svc": _qualified_model()}
    assert r.qualified_kinds(QUALIFIED_FORMATS) == {
        "function": {"alias": "function alias", "version": "function version"}}
    assert r.qualifier_members(functions, "Invoke", {"alias", "version"}) == ["Qualifier"]
    assert r.qualifier_members(functions, "GetConfig", {"alias", "version"}) == ["Qualifier"]
    # What an alias points to, or a filter naming aliases, is no qualifier of the request's function.
    assert r.qualifier_members(functions, "CreateAlias", {"alias", "version"}) == []
    assert r.qualifier_members(functions, "ListAliases", {"alias", "version"}) == []
    named = [{"type": "function", "fill": {"FunctionName": "FunctionName"}}]
    members = {"FunctionName": "FunctionName", "Qualifier": "Qualifier"}
    # An action acting on the type alone is checked on it as its members name it.
    assert r.with_qualifier(named, ["function"], QUALIFIED_FORMATS, members, functions, "Invoke") == named
    # An action acting on the qualified types names each by the function and the qualifier.
    assert r.with_qualifier([], ["function alias", "function version"], QUALIFIED_FORMATS, members, functions,
                            "GetConfig") == [
        {"type": "function alias", "fill": {"FunctionName": "FunctionName", "Alias": "Qualifier"},
         "arnOf": {"FunctionName": "function"}},
        {"type": "function version", "fill": {"FunctionName": "FunctionName", "Version": "Qualifier"},
         "arnOf": {"FunctionName": "function"}}]
    # A request with no qualifier member is as it was.
    assert r.with_qualifier(named, ["function"], QUALIFIED_FORMATS, {"FunctionName": "FunctionName"}, functions,
                            "Publish") == named


def test_a_member_whose_pattern_takes_an_arn_without_its_leading_parts_names_its_type_by_a_partial_arn():
    functions = {"svc": _qualified_model()}
    assert r.partial_arn_members(functions, "Publish", QUALIFIED_FORMATS) == {
        "FunctionName": {"function", "function alias", "function version"}}
    # A pattern that does not spell the type's resource word takes no partial ARN of it.
    functions["svc"]["shapes"]["FunctionName"]["pattern"] = "[a-zA-Z0-9-_:]+"
    assert r.partial_arn_members(functions, "Publish", QUALIFIED_FORMATS) == {}
    marked = r.with_partial_arns([{"type": "function", "fill": {"FunctionName": "FunctionName"}},
                                  {"types": ["function"], "arn": "Other"}], {"FunctionName": {"function"}})
    assert marked == [{"type": "function", "fill": {"FunctionName": "FunctionName"}, "partialArn": True},
                      {"types": ["function"], "arn": "Other"}]


def test_an_account_id_beside_an_arn_of_the_same_name_is_that_arns_account(tmp_path):
    write_models(tmp_path, {"svc": model({"AddPermission": {"FunctionName": "String", "SourceArn": "String",
                                                            "SourceAccount": "AccountId"},
                                          "GetThing": {"ThingName": "String", "OwnerAccount": "AccountId"}})})
    models = r.load_models(tmp_path, {"svc"})
    assert r.account_members(models, "AddPermission") == []
    assert r.account_members(models, "GetThing") == ["OwnerAccount"]


def test_a_record_field_named_after_the_end_of_a_types_name_reaches_that_type_and_a_request_names_it_by_its_identifier():
    formats = {"microvmImage": "arn:${Partition}:svc:${Region}:${Account}:microvm-image:${MicrovmImageName}"}
    vms = model({"GetMicrovm": {"microvmIdentifier": "String"}})
    vms["shapes"]["MicrovmItem"] = {"type": "structure", "members": {
        "microvmId": {"shape": "String"}, "imageArn": {"shape": "String"}, "state": {"shape": "String"}}}
    models = {"svc": vms}
    assert r.through_members(models, formats) == {"microvmId": ["microvmImage"]}
    assert r.with_through([], ["microvmImage"], {"microvmIdentifier": "microvmIdentifier"},
                          {"microvmId": ["microvmImage"]}) == [{"types": ["microvmImage"], "through": "microvmIdentifier"}]


def test_an_operation_documented_as_acting_on_a_thing_in_a_resource_reaches_that_resource_through_the_things_id():
    formats = {"durable execution": "arn:${Partition}:svc:${Region}:${Account}:function:${FunctionName}:${Version}"
                                    "/durable-execution/${ExecutionName}/${ExecutionId}"}
    callbacks = model({"SendCallbackSuccess": {"CallbackId": "String"}, "Other": {"CallbackId": "String"}})
    callbacks["operations"]["SendCallbackSuccess"]["documentation"] = (
        "<p>Sends a successful completion response for a callback operation in a durable execution.</p>")
    assert r.documented_through({"svc": callbacks}, formats) == {"CallbackId": {"durable execution"}}
    callbacks["operations"]["SendCallbackSuccess"]["documentation"] = "<p>Sends a response for a callback.</p>"
    assert r.documented_through({"svc": callbacks}, formats) == {}


def test_a_path_member_documented_as_either_a_name_or_an_arn_takes_the_arn_of_the_thing_it_leads_with():
    assert r._documented_name_or_arn(
        "<p>The web function to retrieve. You can specify either of the following:</p> <ul> <li> <p>The function "
        "name, for example <code>my-function</code>.</p> </li> <li> <p>The function ARN.</p> </li> </ul>",
        located=True) == "web-function"
    # A body member keeps the phrasings it had.
    assert r._documented_name_or_arn("<p>The web function. You can specify either of the following: The function "
                                     "name. The function ARN.</p>") is None


def test_clients_sharing_an_endpoint_keep_every_type_an_arn_member_may_name_and_others_narrow_by_the_arns_word():
    formats = {"function": "arn:${Partition}:svc:${Region}:${Account}:function:${FunctionName}",
               "eventSourceMapping": "arn:${Partition}:svc:${Region}:${Account}:event-source-mapping:${UUID}",
               "image": "arn:${Partition}:svc:${Region}:${Account}:image:${ImageName}"}
    tags = model({"TagResource": {"Resource": "TaggableArn"}})
    tags["shapes"]["TaggableArn"] = {"type": "string",
                                     "pattern": "arn:aws:svc:[a-z0-9-]+:[0-9]{12}:(function:[a-z]+|event-source-mapping:[a-f0-9-]+)"}
    resources = [{"types": ["eventSourceMapping", "function", "image"], "arn": "Resource"}]
    assert r.narrowed(resources, set(), tags, "TagResource", formats) == [
        {"types": ["eventSourceMapping", "function"], "arn": "Resource"}]
    assert r.narrowed(resources, set(), tags, "TagResource", formats, arn_types=False) == resources


def test_input_and_list_suffixes_stem_away_like_the_others():
    # Glue names what a create makes in a `<Type>Input` structure, and a batch's names in a `<Type>NameList`.
    assert r.stem("DatabaseInput") == "database"
    assert r.stem("ConnectionNameList") == "connection"
    assert r.resources_for("svc:BatchDeleteThing", ["thing"], FORMATS, members("ThingNameList[]")) == [
        {"type": "thing", "fill": {"ThingName": "ThingNameList[]"}}]


def test_a_list_whose_botocore_shape_is_named_after_the_type_fills_it():
    # Glue's BatchDeleteTable names its tables by TablesToDelete, a BatchDeleteTableNameList.
    assert r.resources_for("svc:BatchDeleteThing", ["thing"], FORMATS, members("ThingsToDelete[]"),
                           shapes={"ThingsToDelete": "BatchDeleteThingNameList"}) == [
        {"type": "thing", "fill": {"ThingName": "ThingsToDelete[]"}}]
    # A list whose shape is named after nothing the action acts on names nothing.
    assert r.resources_for("svc:BatchDeleteThing", ["thing"], FORMATS, members("ThingsToDelete[]"),
                           shapes={"ThingsToDelete": "BatchDeleteNameList"}) == []


def test_a_type_of_no_variables_stays_beside_a_creates_own_unnamed_type():
    # Glue's CreateDatabase names its database only in a field the documented rule reads; the account's catalog,
    # of no variables, is checked all the same rather than dropped as its alternative.
    assert r.resources_for("svc:CreatePool", ["catalog", "pool"], FORMATS, members("Description")) == [
        {"type": "catalog", "fill": {}}]


def test_being_ends_a_types_name_in_documentation():
    # "Name of the workflow being run", Glue's GetWorkflowRun's Name.
    formats = {"workflow": "arn:${Partition}:svc:${Region}:${Account}:workflow/${WorkflowName}"}
    assert r.doc_names("name of the workflow being run", "workflow", formats)
    assert not r.doc_names("name of the workflowrun being run", "workflow", formats)


def test_a_url_of_the_type_is_found_by_its_service():
    # "The URL of the Amazon SQS queue to which a message is sent", SQS's SendMessage's QueueUrl: a URL names the type,
    # but is neither its name nor its id.
    formats = {"queue": "arn:${Partition}:sqs:${Region}:${Account}:${QueueName}"}
    assert r.doc_names("the url of the queue to which a message is sent", "queue", formats)
    assert r.doc_kind("the url of the queue to which a message is sent") == "url"
    assert r.kind_of("QueueUrl") == "url"
    assert not r.compatible("QueueUrl", "QueueName")
    # "The URL of a dead-letter queue", ListDeadLetterSourceQueues' QueueUrl: a hyphenated word qualifies the type.
    assert r.doc_names("the url of a dead-letter queue", "queue", formats)


def test_a_lead_drops_its_own_service_name():
    model = {"metadata": {"serviceFullName": "Amazon Simple Queue Service", "serviceAbbreviation": "Amazon SQS"}}
    assert r._without_service_name("the url of the amazon sqs queue to delete", model) == "the url of the queue to delete"
    assert r._without_service_name("the url of the amazon simple queue service queue", model) == "the url of the queue"
    # Another service's name, or this one's at the end, stays.
    assert r._without_service_name("the arn of the amazon sns topic", model) == "the arn of the amazon sns topic"
    assert r._without_service_name("a queue of amazon sqs", model) == "a queue of amazon sqs"


def widget_model(tags: bool) -> dict:
    """A create whose action acts on the widget's kind alone, naming the widget it makes in `WidgetInput.Name`, and
    carrying tags when asked, as Glue's CreateConnection does."""
    service = model({"CreateWidget": {"KindName": "String"}})
    service["shapes"]["WidgetInput"] = {"type": "structure", "members": {
        "Name": {"shape": "String", "documentation": "<p>The name of the widget.</p>"},
        "Description": {"shape": "String", "documentation": "<p>The description of the widget.</p>"}}}
    service["shapes"]["Tags"] = {"type": "map", "key": {"shape": "String"}, "value": {"shape": "String"}}
    service["shapes"]["CreateWidgetIn"]["members"]["WidgetInput"] = {"shape": "WidgetInput"}
    if tags:
        service["shapes"]["CreateWidgetIn"]["members"]["Tags"] = {"shape": "Tags"}
    return {"svc": service}


def test_a_create_acting_on_other_types_alone_tags_what_it_makes_when_it_names_it_in_full():
    formats = {"widget": "arn:${Partition}:svc:${Region}:${Account}:widget/${WidgetName}",
               "kind": "arn:${Partition}:svc:${Region}:${Account}:kind/${KindName}"}
    made = r.makes_for("svc:CreateWidget", ["kind"], formats, members("KindName"), models=widget_model(True))
    assert made == [{"type": "widget", "fill": {"WidgetName": "WidgetInput.Name"}}]
    # Without tags, what it makes is not needed; and a create acting on the type it makes is checked on it.
    assert r.makes_for("svc:CreateWidget", ["kind"], formats, members("KindName"), models=widget_model(False)) == []
    assert r.makes_for("svc:CreateWidget", ["widget"], formats, members("KindName"), models=widget_model(True)) == []
    # One whose request does not name every variable of what it makes leaves it to the action's own resources.
    formats["widget"] = "arn:${Partition}:svc:${Region}:${Account}:widget/${WidgetName}/${WidgetId}"
    assert r.makes_for("svc:CreateWidget", ["kind"], formats, members("KindName"), models=widget_model(True)) == []


def test_an_arn_identifying_a_type_is_not_read_as_its_name():
    modeled = model({"RemoveFromPool": {"ThingIdentifier": "String"}}, protocol="query")
    modeled["shapes"]["RemoveFromPoolIn"]["members"]["ThingIdentifier"]["documentation"] = (
        "<p>The Amazon Resource Name (ARN) identifying the thing that was detached from the pool.</p>")
    result = r.with_documented("svc:RemoveFromPool", ["thing"], FORMATS, {"svc": modeled}, [])
    assert result == [{"types": ["thing"], "arn": "ThingIdentifier"}]


def test_a_name_documented_as_an_identifier_finds_a_generated_id():
    formats = {"proxy": "arn:${Partition}:svc:${Region}:${Account}:proxy:${ProxyId}"}
    modeled = model({"ModifyProxy": {"ProxyName": "String"}}, protocol="query")
    modeled["shapes"]["ModifyProxyIn"]["members"]["ProxyName"]["documentation"] = (
        "<p>The identifier for the proxy to modify.</p>")
    assert r.with_documented("svc:ModifyProxy", ["proxy"], formats, {"svc": modeled}, []) == [
        {"type": "proxy", "fill": {}, "find": ["ProxyName"]}]


def test_an_arn_identifying_a_type_replaces_the_identifier_name_mapping():
    modeled = model({"RemoveFromPool": {"ThingIdentifier": "String"}}, protocol="query")
    modeled["shapes"]["RemoveFromPoolIn"]["members"]["ThingIdentifier"]["documentation"] = (
        "<p>The Amazon Resource Name (ARN) identifying the thing that was detached from the pool.</p>")
    modeled["shapes"]["RemoveFromPoolIn"]["required"] = ["ThingIdentifier"]
    assert r.read_resources("svc:RemoveFromPool", ["thing"], FORMATS, {"svc": modeled}) == [
        {"types": ["thing"], "arn": "ThingIdentifier"}]


def test_a_found_child_name_is_scoped_by_its_records_associated_parent():
    formats = {"group": "arn:${Partition}:svc:${Region}:${Account}:group:${GroupId}"}
    modeled = model({"ModifyGroup": {"ProxyName": "String", "GroupName": "String"}}, protocol="query")
    modeled["shapes"]["ProxyGroup"] = {"type": "structure", "members": {
        "GroupName": {"shape": "String"}, "ProxyName": {"shape": "String", "documentation":
            "<p>The identifier for the proxy associated with this group.</p>"}}}
    modeled["shapes"]["ModifyGroupIn"]["required"] = ["GroupName", "ProxyName"]
    resources = [{"type": "group", "fill": {}, "find": ["GroupName"]}]
    assert r.with_find_scope(resources, {"svc": modeled}, "ModifyGroup", formats) == [
        {"type": "group", "fill": {}, "find": ["ProxyName", "GroupName"]}]
    modeled["shapes"]["ModifyGroupIn"]["required"] = ["ProxyName"]
    assert r.with_find_scope(resources, {"svc": modeled}, "ModifyGroup", formats) == [
        {"type": "group", "fill": {}, "find": ["ProxyName", "GroupName"], "unless": {"GroupName": [None]}},
        {"type": "group", "fill": {}, "find": ["ProxyName"], "when": {"GroupName": [None]}}]
    # A fully named record, such as a proxy endpoint, already has its independent key.
    modeled["shapes"]["ProxyGroup"]["members"]["ProxyGroupName"] = {"shape": "String"}
    independently_named = [{"type": "group", "fill": {}, "find": ["ProxyGroupName"]}]
    assert r.with_find_scope(independently_named, {"svc": modeled}, "ModifyGroup", formats) == independently_named
    modeled["shapes"]["OtherGroup"] = modeled["shapes"].pop("ProxyGroup")
    assert r.with_find_scope(resources, {"svc": modeled}, "ModifyGroup", formats) == resources


def test_a_task_identifier_reaches_the_source_arn_its_record_documents():
    modeled = model({"DescribeExports": {"ExportTaskIdentifier": "String"}}, protocol="query")
    modeled["shapes"]["ExportTask"] = {"type": "structure", "members": {
        "ExportTaskIdentifier": {"shape": "String"}, "SourceArn": {"shape": "String", "documentation":
            "<p>The Amazon Resource Name (ARN) of the thing that was exported to storage.</p>"}}}
    assert r.read_resources("svc:DescribeExports", ["thing"], FORMATS, {"svc": modeled}) == [
        {"types": ["thing"], "through": "ExportTaskIdentifier"}]


GLUE_FORMATS = {
    "catalog": "arn:${Partition}:glue:${Region}:${Account}:catalog/${CatalogName}",
    "rootcatalog": "arn:${Partition}:glue:${Region}:${Account}:catalog",
    "database": "arn:${Partition}:glue:${Region}:${Account}:database/${DatabaseName}",
    "table": "arn:${Partition}:glue:${Region}:${Account}:table/${DatabaseName}/${TableName}",
}
EXAMPLE = "arn:aws:glue:us-east-1:123456789012:"


def statement_source(**statements) -> dict:
    """A vendored statement source as the fetch script writes it: the page's example names and each statement's
    sentence and example policy."""
    return {"_source": {"page": "https://example.com/page", "fetcher": "fetch.py"},
            "examples": {"example_db": "database", "test": "table"},
            "statements": {name.replace("_", " "): {"text": text, "policy": policy}
                           for name, (text, policy) in statements.items()}}


def allow(actions: list[str], resources: list[str]) -> dict:
    return {"Effect": "Allow", "Action": actions, "Resource": [EXAMPLE + resource for resource in resources]}


GLUE_ACTIONS = {"glue:GetDatabase": ["catalog", "database", "rootcatalog"],
                "glue:GetTable": ["catalog", "database", "rootcatalog", "table"],
                "glue:DeleteTable": ["catalog", "database", "rootcatalog", "table"],
                "glue:GetPartitions": ["catalog", "database", "rootcatalog", "table"]}


def test_a_statement_makes_each_action_of_its_policy_on_each_resource_of_its_statement_the_reference_has_it_act_on():
    source = statement_source(
        DROP_TABLE=("Allows you to drop a partitioned table named test in the example_db database. If your table does "
                    "not have partitions, do not include partition actions.",
                    [allow(["glue:GetDatabase"], ["catalog", "database/example_db"]),
                     allow(["glue:GetTable", "glue:DeleteTable", "glue:GetPartitions"],
                           ["catalog", "database/example_db", "table/example_db/test"])]),
        SHOW_TABLES=("Allows you to list all tables in the example_db database.",
                     [allow(["glue:GetDatabase", "glue:GetTable"],
                            ["catalog", "database/example_db", "table/example_db/*"])]))
    root = {"type": "rootcatalog", "fill": {}}
    database = {"type": "database", "fill": {"DatabaseName": "database"}}
    table = {"type": "table", "fill": {"DatabaseName": "database", "TableName": "table"}}
    assert r.statement_requests(source, GLUE_ACTIONS, {"glue": GLUE_FORMATS}) == {
        # Each action on the resources of its own policy statement, the catalog by the bare format it matches.
        "DROP TABLE": [{"action": "glue:GetDatabase", "resources": [root, database]},
                       {"action": "glue:GetTable", "resources": [root, database, table]},
                       {"action": "glue:DeleteTable", "resources": [root, database, table]},
                       # The sentence says a table without partitions needs no partition action.
                       {"action": "glue:GetPartitions", "resources": [root, database, table], "partitioned": True}],
        # A wildcard leaves its variable open, and an action is not checked on a type it does not act on.
        "SHOW TABLES": [{"action": "glue:GetDatabase", "resources": [root, database]},
                        {"action": "glue:GetTable", "resources": [
                            root, database, {"type": "table", "fill": {"DatabaseName": "database"}}]}],
    }


@pytest.mark.parametrize("policy, problem", [
    # An ARN of no type's format, or of a name the page does not say stands for anything.
    ([allow(["glue:GetTable"], ["function/f"])], "is no resource type's ARN format"),
    ([allow(["glue:GetTable"], ["database/default"])], "whose default the page does not say stands for anything"),
    # An action granted on no resource of a type the reference gives it.
    ([allow(["glue:GetDatabase"], ["table/example_db/test"])], "grants glue:GetDatabase on no resource"),
])
def test_a_statement_policy_the_reference_cannot_read_is_refused(policy, problem):
    with pytest.raises(ValueError, match=problem):
        r.statement_requests(statement_source(DROP_TABLE=("Drops it.", policy)), GLUE_ACTIONS,
                             {"glue": GLUE_FORMATS})


def test_the_statement_actions_are_the_types_the_reference_gives_each_and_an_unlisted_one_is_refused():
    glue = reference("glue", {"GetDatabase": ["catalog", "database", "rootcatalog"]}, GLUE_FORMATS)
    source = statement_source(CREATE_DATABASE=("Creates it.", [allow(["glue:GetDatabase"], ["catalog"])]))
    assert r.statement_actions([glue], {"svc:A": source}) == {"glue:GetDatabase": ["catalog", "database", "rootcatalog"]}
    unlisted = statement_source(CREATE_DATABASE=("Creates it.", [allow(["glue:CreateDatabase"], ["catalog"])]))
    with pytest.raises(ValueError, match="names glue:CreateDatabase, which the Service Reference does not list"):
        r.statement_actions([glue], {"svc:A": unlisted})


def test_the_statements_are_built_checked_offline_and_verified_against_aws_and_the_page(isolated, tmp_path, monkeypatch):
    isolated.setattr(r, "NAMESPACES", ("svc", "glue"))
    source = statement_source(CREATE_DATABASE=("Creates the example_db database.",
                                               [allow(["glue:GetDatabase"], ["catalog", "database/example_db"])]))
    path = tmp_path / "source.json"
    path.write_text(json.dumps(source), encoding="utf-8")
    isolated.setattr(r, "STATEMENT_SOURCES", {"svc:GetThing": path})
    glue = reference("glue", {"GetDatabase": ["catalog", "database", "rootcatalog"]}, GLUE_FORMATS)
    glue["Operations"] = []
    documents = [*forwarding_documents(tmp_path), glue]
    document = r.build(documents, "test", tmp_path, "botocore test")
    assert document["statementActions"] == {"glue:GetDatabase": ["catalog", "database", "rootcatalog"]}
    assert document["statements"] == {"svc:GetThing": {"CREATE DATABASE": [{"action": "glue:GetDatabase", "resources": [
        {"type": "rootcatalog", "fill": {}}, {"type": "database", "fill": {"DatabaseName": "database"}}]}]}}
    assert r.validate(document) == [] and r.derive(document, tmp_path) == []
    assert json.loads(r.render(document)) == document

    # A statement edited by hand, or a source changed without a sync, fails the offline check.
    edited = json.loads(json.dumps(document))
    edited["statements"]["svc:GetThing"]["CREATE DATABASE"][0]["resources"].pop()
    assert any("statements differ from what its sources give" in problem for problem in r.derive(edited, tmp_path))
    edited["statements"]["svc:GetThing"]["CREATE DATABASE"][0]["action"] = "glue:GetTable"
    assert any("something other than requests of statementActions" in problem for problem in r.validate(edited))

    # Online, the types AWS gives each action and the page itself are checked.
    page = {"https://example.com/page": "the page"}
    monkeypatch.setattr(r.fetch_athena_glue_ddl_permissions, "fetch_url", lambda url: page[url])
    monkeypatch.setattr(r.fetch_athena_glue_ddl_permissions, "stale", lambda vendored, text: text != "the page")
    assert r.verify_statements(document, documents) == []
    narrower = reference("glue", {"GetDatabase": ["database"]}, GLUE_FORMATS)
    assert r.verify_statements(document, [*documents[:-1], narrower]) == [
        "AWS gives glue:GetDatabase the resource types ['database'], not ['catalog', 'database', 'rootcatalog']"]
    page["https://example.com/page"] = "a changed page"
    assert r.verify_statements(document, documents) == [
        "https://example.com/page no longer gives what source.json holds for svc:GetThing: run fetch.py"]


@pytest.mark.parametrize("operation", ["DeleteWidget", "CreateWidgetVersion"])
def test_documented_alternate_name_resolves_an_optional_id(operation):
    formats = {"widget": "arn:${Partition}:svc:${Region}:${Account}:widget/${WidgetId}"}
    docs = {"WidgetId": "<p>The ID of the widget.</p>",
            "WidgetName": "<p>The name of the widget.</p> <p>Specify either the widget ID or the widget name, "
                          "but not both.</p>"}
    resource = {"type": "widget", "fill": {"WidgetId": "WidgetId"}}
    models = cluster_model(operation, docs)
    assert r.with_documented("svc:" + operation, ["widget"], formats, models, [resource]) == [
        resource, {"type": "widget", "fill": {}, "find": ["WidgetName"], "when": {"WidgetId": [None]}}]
    # A required id, an ordinary rename, and a create's new name are not alternate selectors.
    assert r.with_documented("svc:" + operation, ["widget"], formats,
                             cluster_model(operation, docs, ("WidgetId",)), [resource]) == [resource]
    docs["WidgetName"] = "<p>The name of the widget.</p>"
    assert r.with_documented("svc:" + operation, ["widget"], formats,
                             cluster_model(operation, docs), [resource]) == [resource]


def test_create_name_does_not_look_up_an_existing_id():
    formats = {"widget": "arn:${Partition}:svc:${Region}:${Account}:widget/${WidgetId}"}
    docs = {"WidgetId": "<p>The ID of the widget.</p>",
            "WidgetName": "<p>The name of the widget.</p> <p>Specify either the widget ID or the widget name, "
                          "but not both.</p>"}
    resource = {"type": "widget", "fill": {"WidgetId": "WidgetId"}}
    assert r.with_documented("svc:CreateWidget", ["widget"], formats,
                             cluster_model("CreateWidget", docs), [resource]) == [resource]


def test_a_name_for_a_new_resource_does_not_select_an_existing_id():
    formats = {"widget": "arn:${Partition}:svc:${Region}:${Account}:widget/${WidgetId}"}
    models = cluster_model("CreateWidget", {"WidgetName": "<p>A name for the widget.</p>"})
    resource = {"type": "widget", "fill": {}}
    assert r.with_documented("svc:CreateWidget", ["widget"], formats, models, [resource]) == [resource]


def test_paired_added_and_removed_id_lists_both_name_acted_on_resources():
    formats = {"security-group": "arn:${Partition}:ec2:${Region}:${Account}:security-group/${SecurityGroupId}"}
    named = members("AddSecurityGroupIds[]", "RemoveSecurityGroupIds[]")
    shapes = {name: "VpcEndpointSecurityGroupIdList" for name in named}
    resources = r.resources_for("ec2:ModifyVpcEndpoint", ["security-group"], formats, named, shapes=shapes)
    assert resources == [
        {"type": "security-group", "fill": {"SecurityGroupId": "AddSecurityGroupIds[]"}},
        {"type": "security-group", "fill": {"SecurityGroupId": "RemoveSecurityGroupIds[]"}}]
    shapes["RemoveSecurityGroupIds"] = "UnrelatedList"
    assert len(r.resources_for("ec2:ModifyVpcEndpoint", ["security-group"], formats, named, shapes=shapes)) == 1


def test_nested_record_id_finds_only_containing_and_adjacent_resource_types():
    formats = {kind: "arn:${Partition}:svc:${Region}:${Account}:" + kind + "/${Id}"
               for kind in ("interface", "instance", "subnet")}
    shapes = {
        "String": {"type": "string"},
        "Interface": {"type": "structure", "members": {
            "InterfaceId": {"shape": "String"}, "SubnetId": {"shape": "String"},
            "Attachment": {"shape": "InterfaceAttachment"}}},
        "InterfaceAttachment": {"type": "structure", "members": {
            "AttachmentId": {"shape": "String"}, "InstanceId": {"shape": "String"}}}}
    shapes["Request"] = {"type": "structure", "members": {"AttachmentId": {"shape": "String"}}}
    models = {"svc": {"shapes": shapes, "metadata": {"protocol": "json"},
                      "operations": {"DetachInterface": {"input": {"shape": "Request"}}}}}
    expected = [{"type": kind, "fill": {}, "find": ["AttachmentId"]} for kind in ("instance", "interface")]
    assert r.read_resources("svc:DetachInterface", list(formats), formats, models) == expected
    assert r.with_nested_record_find([], list(formats), formats, members("UnrelatedId"), models) == []
    existing = [{"type": "interface", "fill": {"Id": "InterfaceId"}}]
    assert r.with_nested_record_find(existing, list(formats), formats, members("AttachmentId"), models) == [
        *existing, expected[0]]
    instance = shapes["InterfaceAttachment"]["members"].pop("InstanceId")
    assert r.with_nested_record_find([], list(formats), formats, members("AttachmentId"), models) == []
    shapes["InterfaceAttachment"]["members"]["InstanceId"] = instance
    shapes["Interface"]["members"]["OtherAttachment"] = {"shape": "OtherAttachment"}
    shapes["OtherAttachment"] = {"type": "structure", "members": {
        "OtherAttachmentId": {"shape": "String"}, "InstanceId": {"shape": "String"}}}
    assert r.with_nested_record_find([], list(formats), formats,
                                     members("AttachmentId", "OtherAttachmentId"), models) == []
    shapes["DescribeInterfaceResponse"] = shapes.pop("Interface")
    assert r.with_nested_record_find([], list(formats), formats, members("AttachmentId"), models) == []


# --------------------------------------------------------------------------- #
# PERMISSION_SOURCES: the vendored KMS permissions
# --------------------------------------------------------------------------- #
KEYS_FORMATS = {"alias": "arn:${Partition}:kms:${Region}:${Account}:alias/${Alias}",
                "key": "arn:${Partition}:kms:${Region}:${Account}:key/${KeyId}"}
KEYS_ACTIONS = {"kms:ReEncryptFrom": ["key"], "kms:ReEncryptTo": ["key"], "kms:UpdateAlias": ["alias", "key"],
                "kms:DeleteAlias": ["alias", "key"], "kms:ReplicateKey": ["key"], "kms:CreateKey": []}


def keys_model() -> dict:
    """A model shaped as KMS's: ReEncrypt naming its keys by `SourceKeyId` and `DestinationKeyId`, documented as the
    keys it decrypts and reencrypts with, the alias calls naming an alias whose record holds its `TargetKeyId`."""
    def request(members: dict, required: list[str]) -> dict:
        return {"type": "structure", "members": members, "required": required}
    shapes = {
        "KeyIdType": {"type": "string"}, "AliasNameType": {"type": "string"}, "CiphertextType": {"type": "blob"},
        "RegionType": {"type": "string"},
        "ReEncryptRequest": request({
            "CiphertextBlob": {"shape": "CiphertextType"},
            "SourceKeyId": {"shape": "KeyIdType", "documentation": "<p>Specifies the KMS key that KMS will use to "
                                                                   "decrypt the ciphertext before it is re-encrypted."
                                                                   "</p>"},
            "DestinationKeyId": {"shape": "KeyIdType", "documentation": "<p>A unique identifier for the KMS key that "
                                                                        "is used to reencrypt the data.</p>"}},
            ["DestinationKeyId"]),
        "UpdateAliasRequest": request({"AliasName": {"shape": "AliasNameType"},
                                       "TargetKeyId": {"shape": "KeyIdType"}}, ["AliasName", "TargetKeyId"]),
        "DeleteAliasRequest": request({"AliasName": {"shape": "AliasNameType"}}, ["AliasName"]),
        "ReplicateKeyRequest": request({"KeyId": {"shape": "KeyIdType"}, "ReplicaRegion": {"shape": "RegionType"}},
                                       ["KeyId", "ReplicaRegion"]),
        "KeyMetadata": {"type": "structure", "members": {"KeyId": {"shape": "KeyIdType"}}},
        "AliasListEntry": {"type": "structure", "members": {"AliasName": {"shape": "AliasNameType"},
                                                            "TargetKeyId": {"shape": "KeyIdType"}}},
    }
    operations = {name: {"name": name, "input": {"shape": f"{name}Request"}}
                  for name in ("ReEncrypt", "UpdateAlias", "DeleteAlias", "ReplicateKey")}
    return {"metadata": {"protocol": "json", "endpointPrefix": "kms"}, "operations": operations, "shapes": shapes}


def keys_row(operation: str, actions: list[str], items: list[str]) -> dict:
    return {"operation": operation, "apiReference": f"https://example.com/API_{operation}.html", "actions": actions,
            "description": [], "items": items, "policies": [{"policyType": "Key policy", "resources": "KMS key"}]}


def test_vendored_permissions_check_each_resource_they_list_as_the_action_they_name():
    models = {"kms": keys_model()}
    row = keys_row("ReEncrypt", ["kms:ReEncryptFrom", "kms:ReEncryptTo"],
                   ["kms:ReEncryptFrom on the KMS key used to decrypt",
                    "kms:ReEncryptTo on the KMS key used to encrypt"])
    resources, actions = r.permission_resources("kms:ReEncrypt", "kms:ReEncrypt", [], row, KEYS_ACTIONS,
                                                KEYS_FORMATS, models)
    assert actions == {"kms:ReEncryptFrom": ["key"], "kms:ReEncryptTo": ["key"]}
    assert resources == [{"type": "key", "fill": {"KeyId": "SourceKeyId"}, "action": "kms:ReEncryptFrom"},
                         {"type": "key", "fill": {"KeyId": "DestinationKeyId"}, "action": "kms:ReEncryptTo"}]
    sentence = {"operation": "ReEncrypt", "parameter": "SourceKeyId",
                "sentence": "If you used a symmetric encryption KMS key, AWS KMS can get the KMS key from metadata "
                            "that it adds to the symmetric ciphertext blob."}
    assert r.with_from_metadata(resources, "kms:ReEncrypt", [sentence], models)[2:] == [
        {"types": ["key"], "through": "CiphertextBlob", "when": {"SourceKeyId": [None]}, "action": "kms:ReEncryptFrom"}]


def test_a_listed_resource_no_member_names_is_reached_through_the_record_of_another_it_lists():
    models = {"kms": keys_model()}
    alias = {"type": "alias", "fill": {"Alias": "AliasName"}}
    row = keys_row("UpdateAlias", ["kms:UpdateAlias"],
                   ["The alias", "The currently associated KMS key", "The newly associated KMS key"])
    assert r.permission_resources("kms:UpdateAlias", "kms:UpdateAlias", [alias], row, KEYS_ACTIONS, KEYS_FORMATS,
                                  models) == (
        [alias, {"type": "key", "fill": {"KeyId": "TargetKeyId"}}, {"types": ["key"], "through": "AliasName"}], None)
    row = keys_row("DeleteAlias", ["kms:DeleteAlias"],
                   ["The alias (in an IAM policy)", "The KMS key (in a key policy)"])
    assert r.permission_resources("kms:DeleteAlias", "kms:DeleteAlias", [alias], row, KEYS_ACTIONS, KEYS_FORMATS,
                                  models) == ([alias, {"types": ["key"], "through": "AliasName"}], None)


def test_a_listed_action_on_no_resource_is_one_the_operation_is_checked_as():
    row = keys_row("ReplicateKey", ["kms:ReplicateKey"],
                   ["kms:ReplicateKey on the multi-Region primary key", "kms:CreateKey in an IAM policy in the replica "
                                                                        "Region"])
    assert r.permission_resources("kms:ReplicateKey", "kms:ReplicateKey", [], row, KEYS_ACTIONS, KEYS_FORMATS,
                                  {"kms": keys_model()}) == (
        [{"type": "key", "fill": {"KeyId": "KeyId"}, "action": "kms:ReplicateKey"}],
        {"kms:ReplicateKey": ["key"], "kms:CreateKey": []})


def test_vendored_permissions_the_metadata_does_not_bear_out_are_refused():
    models = {"kms": keys_model()}
    unknown = keys_row("ReEncrypt", [], ["kms:ReEncryptAll on the KMS key used to decrypt"])
    with pytest.raises(ValueError, match="does not have"):
        r.permission_resources("kms:ReEncrypt", "kms:ReEncrypt", [], unknown, KEYS_ACTIONS, KEYS_FORMATS, models)
    undocumented = keys_row("ReEncrypt", ["kms:ReEncryptFrom"], ["kms:ReEncryptFrom on the KMS key used to sign"])
    with pytest.raises(ValueError, match="documented as used for"):
        r.permission_resources("kms:ReEncrypt", "kms:ReEncrypt", [], undocumented, KEYS_ACTIONS, KEYS_FORMATS, models)
    sentence = {"operation": "ReEncrypt", "parameter": "SourceKeyId",
                "sentence": "AWS KMS can get the KMS key from metadata that it adds to the envelope."}
    with pytest.raises(ValueError, match="names no member"):
        r.with_from_metadata([], "kms:ReEncrypt", [sentence], models)
    assert r.permission_resources("kms:Encrypt", "kms:Encrypt", [{"type": "key", "fill": {"KeyId": "KeyId"}}],
                                  keys_row("Encrypt", ["kms:Encrypt"], []), KEYS_ACTIONS, KEYS_FORMATS, models) == (
        [{"type": "key", "fill": {"KeyId": "KeyId"}}], None)


def test_an_identifier_given_as_another_arns_resource_names_its_type_by_that_resource():
    identifiers = [{"identifier": "Key ARN", "sentence": "", "format": "arn:{partition}:kms:{region}:{account-id}:key/"
                                                                        "{key-id}"},
                   {"identifier": "Alias ARN", "sentence": "",
                    "format": "arn:{partition}:kms:{region}:{account-id}:alias/{alias-name}"},
                   {"identifier": "Alias name", "sentence": "", "format": "alias/{alias-name}"}]
    assert r.named_resources(identifiers, KEYS_FORMATS) == {"alias"}
    assert r.named_resources(identifiers[:2], KEYS_FORMATS) == set()


def test_an_operation_checked_as_several_actions_checks_each_resource_as_one_of_them(isolated):
    isolated.setattr(r, "PERMISSION_SOURCES", {"svc": Path("svc.json")})
    document = good_document()
    document["arnFormats"]["svc"]["key"] = "arn:${Partition}:svc:${Region}:${Account}:key/${KeyId}"
    entry = {"action": "svc:ReEncrypt", "types": [],
             "actions": {"svc:ReEncryptFrom": ["key"], "svc:ReEncryptTo": ["key"]},
             "resources": [{"type": "key", "fill": {"KeyId": "SourceKeyId"}, "action": "svc:ReEncryptFrom"},
                           {"type": "key", "fill": {"KeyId": "DestinationKeyId"}, "action": "svc:ReEncryptTo"}]}
    document["operations"] = dict(sorted({**document["operations"], "svc:ReEncrypt": entry}.items()))
    assert r.validate(document) == []
    unchecked = dict(entry, resources=[{"type": "key", "fill": {"KeyId": "SourceKeyId"}}])
    document["operations"]["svc:ReEncrypt"] = unchecked
    assert any("none of the actions" in problem for problem in r.validate(document))
    isolated.setattr(r, "PERMISSION_SOURCES", {})
    document["operations"]["svc:ReEncrypt"] = entry
    assert any("vendored permissions" in problem for problem in r.validate(document))


def test_every_permission_source_is_written_by_its_own_fetcher():
    assert set(r.PERMISSION_FETCHERS) == set(r.PERMISSION_SOURCES)
    for namespace, path in r.PERMISSION_SOURCES.items():
        assert r.PERMISSION_FETCHERS[namespace].OUTPUT == path, namespace


def test_each_permission_source_is_verified_against_the_pages_its_own_fetcher_reads(isolated, tmp_path):
    class Fetcher:
        def __init__(self, page: str):
            self.page = page

        def fetch_all(self):
            return (self.page,)

        def stale(self, source, page):
            return source["says"] != page

    sources = {}
    for namespace, says in (("first", "kept"), ("second", "kept")):
        sources[namespace] = tmp_path / f"{namespace}.json"
        sources[namespace].write_text(json.dumps({"_source": {"pages": [f"https://{namespace}"],
                                                              "fetcher": f"fetch_{namespace}.py"}, "says": says}))
    isolated.setattr(r, "PERMISSION_SOURCES", sources)
    isolated.setattr(r, "PERMISSION_FETCHERS", {"first": Fetcher("kept"), "second": Fetcher("kept")})
    assert r.verify_permission_sources() == []
    isolated.setattr(r, "PERMISSION_FETCHERS", {"first": Fetcher("kept"), "second": Fetcher("changed")})
    assert r.verify_permission_sources() == ["https://second and the pages they link to no longer give what "
                                             "second.json holds for second: run fetch_second.py"]


# --------------------------------------------------------------------------- #
# PERMISSION_SOURCES: the vendored DynamoDB permissions
# --------------------------------------------------------------------------- #
TABLES_FORMATS = {"table": "arn:${Partition}:dynamodb:${Region}:${Account}:table/${TableName}",
                  "index": "arn:${Partition}:dynamodb:${Region}:${Account}:table/${TableName}/index/${IndexName}"}
TABLES_ACTIONS = {"dynamodb:PutItem": ["table"], "dynamodb:DeleteItem": ["table"], "dynamodb:GetItem": ["table"],
                  "dynamodb:ConditionCheckItem": ["table"], "dynamodb:PartiQLSelect": ["index", "table"],
                  "dynamodb:PartiQLInsert": ["table"]}
TABLES_SOURCE = {
    "partiql": {"items": [
        {"text": "To read items using PartiQL for DynamoDB, you must have dynamodb:PartiQLSelect permission on the "
                 "table or index.", "code": ["dynamodb:PartiQLSelect"]},
        {"text": "To insert items using PartiQL for DynamoDB, you must have dynamodb:PartiQLInsert permission on the "
                 "table or index.", "code": ["dynamodb:PartiQLInsert"]}]},
    "transactions": {"sentences": [
        {"text": "Permissions for Put, Delete, and Get actions are governed by the permissions used for the underlying "
                 "PutItem, DeleteItem, and GetItem operations.",
         "code": ["Put", "Delete", "Get", "PutItem", "DeleteItem", "GetItem"]},
        {"text": "For the ConditionCheck action, you can use the dynamodb:ConditionCheckItem permission in IAM "
                 "policies.", "code": ["ConditionCheck", "dynamodb:ConditionCheckItem"]}]},
}


def dynamodb_model() -> dict:
    """A model shaped as DynamoDB's: a transaction's items, PartiQL statements alone and in a list, a batch keyed by
    the shape a table's name has, and a map keyed by any string."""
    shapes = {
        "TableArn": {"type": "string"}, "PartiQLStatement": {"type": "string"}, "Name": {"type": "string"},
        "Item": {"type": "structure", "members": {"TableName": {"shape": "TableArn"}}},
        "TransactWriteItem": {"type": "structure", "members": {"ConditionCheck": {"shape": "Item"},
                                                                "Put": {"shape": "Item"}, "Delete": {"shape": "Item"}}},
        "TransactWriteItemList": {"type": "list", "member": {"shape": "TransactWriteItem"}},
        "BatchStatementRequest": {"type": "structure", "members": {"Statement": {"shape": "PartiQLStatement"}}},
        "PartiQLBatchRequest": {"type": "list", "member": {"shape": "BatchStatementRequest"}},
        "BatchGetRequestMap": {"type": "map", "key": {"shape": "TableArn"}, "value": {"shape": "Item"}},
        "VariablesMap": {"type": "map", "key": {"shape": "Name"}, "value": {"shape": "Name"}},
        "TransactWriteItemsInput": {"type": "structure", "members": {"TransactItems": {"shape":
                                                                                        "TransactWriteItemList"}}},
        "ExecuteStatementInput": {"type": "structure", "members": {"Statement": {"shape": "PartiQLStatement"}}},
        "BatchExecuteStatementInput": {"type": "structure",
                                       "members": {"Statements": {"shape": "PartiQLBatchRequest"}}},
        "BatchGetItemInput": {"type": "structure", "members": {"RequestItems": {"shape": "BatchGetRequestMap"},
                                                               "Variables": {"shape": "VariablesMap"}}},
    }
    operations = {name: {"name": name, "input": {"shape": f"{name}Input"}}
                  for name in ("TransactWriteItems", "ExecuteStatement", "BatchExecuteStatement", "BatchGetItem")}
    return {"metadata": {"protocol": "json", "endpointPrefix": "dynamodb"}, "operations": operations, "shapes": shapes}


def test_each_transaction_item_is_checked_as_the_action_the_vendored_page_gives_its_kind():
    kinds = r.item_actions(TABLES_SOURCE, "dynamodb")
    assert kinds == {"Put": "dynamodb:PutItem", "Delete": "dynamodb:DeleteItem", "Get": "dynamodb:GetItem",
                     "ConditionCheck": "dynamodb:ConditionCheckItem"}
    assert r.item_resources("dynamodb:TransactWriteItems", kinds, TABLES_ACTIONS, TABLES_FORMATS,
                            {"dynamodb": dynamodb_model()}) == [
        {"type": "table", "fill": {"TableName": "TransactItems[].ConditionCheck.TableName"},
         "action": "dynamodb:ConditionCheckItem"},
        {"type": "table", "fill": {"TableName": "TransactItems[].Put.TableName"}, "action": "dynamodb:PutItem"},
        {"type": "table", "fill": {"TableName": "TransactItems[].Delete.TableName"}, "action": "dynamodb:DeleteItem"}]
    assert r.item_resources("dynamodb:ExecuteStatement", kinds, TABLES_ACTIONS, TABLES_FORMATS,
                            {"dynamodb": dynamodb_model()}) == []
    with pytest.raises(ValueError, match="pairs no kinds"):
        r.item_actions({"transactions": {"sentences": [{"text": "Put.", "code": ["Put"]}]}}, "dynamodb")


def test_each_statement_is_checked_as_the_action_of_its_verb_on_its_table_or_index():
    models = {"dynamodb": dynamodb_model()}
    assert r.statement_resources("dynamodb:ExecuteStatement", TABLES_SOURCE, TABLES_ACTIONS, TABLES_FORMATS,
                                 models) == [
        {"types": ["index", "table"], "statement": "Statement", "verb": "SELECT",
         "names": {"TableName": "table", "IndexName": "index"}, "action": "dynamodb:PartiQLSelect"},
        {"types": ["table"], "statement": "Statement", "verb": "INSERT", "names": {"TableName": "table"},
         "action": "dynamodb:PartiQLInsert"}]
    assert [row["statement"] for row in r.statement_resources(
        "dynamodb:BatchExecuteStatement", TABLES_SOURCE, TABLES_ACTIONS, TABLES_FORMATS, models)] == \
        ["Statements[].Statement", "Statements[].Statement"]
    assert r.statement_resources("dynamodb:TransactWriteItems", TABLES_SOURCE, TABLES_ACTIONS, TABLES_FORMATS,
                                 models) == []
    unknown = {"partiql": {"items": [{"text": "To scan items using PartiQL for DynamoDB, you must have "
                                              "dynamodb:PartiQLScan permission on the table.",
                                      "code": ["dynamodb:PartiQLScan"]}]}}
    with pytest.raises(ValueError, match="names no action"):
        r.statement_resources("dynamodb:ExecuteStatement", unknown, TABLES_ACTIONS, TABLES_FORMATS, models)


def test_a_map_keyed_by_the_shape_a_types_name_has_names_that_type_by_its_keys():
    models = {"dynamodb": dynamodb_model()}
    assert r.with_map_keys([], ["table"], TABLES_FORMATS, models, "BatchGetItem") == [
        {"type": "table", "fill": {"TableName": "RequestItems{}"}}]
    # A type a rule already names, and a map keyed by any string, add nothing.
    named = [{"type": "table", "fill": {"TableName": "TableName"}}]
    assert r.with_map_keys(named, ["table"], TABLES_FORMATS, models, "BatchGetItem") == named
    assert r.FILL_PATH_RE.match("RequestItems{}") and r.FILL_PATH_RE.match("TransactItems[].Put.TableName")


def test_a_statement_resource_names_its_member_verb_and_every_variable_of_its_types():
    good = {"types": ["index", "table"], "statement": "Statements[].Statement", "verb": "SELECT",
            "names": {"TableName": "table", "IndexName": "index"}}
    assert r._resource_problems("dynamodb:BatchExecuteStatement", good, {"index", "table"}, TABLES_FORMATS) == []
    for bad in (dict(good, verb="select"), dict(good, names={"TableName": "table"}),
                dict(good, statement="a.b.c.d"), dict(good, types=["stream"])):
        assert r._resource_problems("dynamodb:BatchExecuteStatement", bad, {"index", "table"}, TABLES_FORMATS)


# --------------------------------------------------------------------------- #
# PERMISSION_SOURCES: the vendored IAM identifiers
# --------------------------------------------------------------------------- #
USERS_FORMATS = {"user": "arn:${Partition}:iam::${Account}:user/${UserNameWithPath}",
                 "group": "arn:${Partition}:iam::${Account}:group/${GroupNameWithPath}",
                 "saml-provider": "arn:${Partition}:iam::${Account}:saml-provider/${SamlProviderName}",
                 "mfa": "arn:${Partition}:iam::${Account}:mfa/${MfaTokenIdWithPath}",
                 "role": "arn:${Partition}:iam::${Account}:role/${RoleNameWithPath}"}
USERS_ARNS = {"syntax": ["arn:aws:iam::{account}:root", "arn:aws:iam::{account}:user/{user-name-with-path}",
                         "arn:aws:iam::{account}:group/{group-name-with-path}",
                         "arn:aws:iam::{account}:mfa/{virtual-device-name-with-path}",
                         "arn:aws:iam::{account}:saml-provider/{provider-name}"],
              "paths": ["If you use the IAM API or AWS Command Line Interface (AWS CLI) to create IAM resources, you "
                        "can add an optional path."]}


def iam_model() -> dict:
    """A model shaped as IAM's: creates taking a path, a rename, an operation acting on its signer, a device by its
    serial number and a deletion task."""
    shapes = {"name": {"type": "string"}}
    members = {
        "CreateUser": ["Path", "UserName"], "CreateVirtualMFADevice": ["Path", "VirtualMFADeviceName"],
        "UpdateGroup": ["GroupName", "NewPath", "NewGroupName"], "UpdateUser": ["UserName", "NewUserName"],
        "GetUser": ["UserName"], "ChangePassword": ["OldPassword", "NewPassword"], "TagMFADevice": ["SerialNumber"],
        "GetServiceLinkedRoleDeletionStatus": ["DeletionTaskId"],
    }
    for operation, names in members.items():
        shapes[f"{operation}Request"] = {"type": "structure", "members": {name: {"shape": "name"} for name in names}}
    operations = {name: {"name": name, "input": {"shape": f"{name}Request"}} for name in members}
    return {"metadata": {"protocol": "query", "endpointPrefix": "iam"}, "operations": operations, "shapes": shapes}


def test_the_identifiers_give_the_types_whose_arn_holds_a_name_after_its_path():
    assert r.path_types(USERS_ARNS, USERS_FORMATS) == {
        "user": (["user", "name"], "path"), "group": (["group", "name"], "path"),
        "mfa": (["virtual", "device", "name"], "path")}


def test_a_create_names_what_it_makes_by_its_name_after_its_path():
    models = {"iam": iam_model()}
    assert r.with_paths([{"type": "user", "fill": {"UserNameWithPath": "Path"}}], "iam:CreateUser", USERS_ARNS,
                        USERS_FORMATS, models) == [
        {"type": "user", "fill": {"UserNameWithPath": "UserName"}, "path": "Path"}]
    assert r.with_paths([{"type": "mfa", "fill": {"MfaTokenIdWithPath": "Path"}}], "iam:CreateVirtualMFADevice",
                        USERS_ARNS, USERS_FORMATS, models) == [
        {"type": "mfa", "fill": {"MfaTokenIdWithPath": "VirtualMFADeviceName"}, "path": "Path"}]
    # A request giving no path, and identifiers saying nothing of paths a create gives, leave the resources as they are.
    named = [{"type": "user", "fill": {"UserNameWithPath": "UserName"}}]
    assert r.with_paths(named, "iam:GetUser", USERS_ARNS, USERS_FORMATS, models) == named
    assert r.with_paths([{"type": "user", "fill": {"UserNameWithPath": "Path"}}], "iam:CreateUser",
                        dict(USERS_ARNS, paths=[]), USERS_FORMATS, models) == [
        {"type": "user", "fill": {"UserNameWithPath": "Path"}}]


def test_a_rename_also_names_the_resource_under_its_new_name_and_path():
    models = {"iam": iam_model()}
    sentence = [{"operation": "UpdateGroup", "parameter": None,
                 "sentence": "The person making the request (the principal), must have permission to change the role "
                             "group with the old name and the new name."}]
    old = [{"type": "group", "fill": {"GroupNameWithPath": "GroupName"}}]
    assert r.with_renames(old, "iam:UpdateGroup", sentence, USERS_ARNS, USERS_FORMATS, models) == old + [
        {"type": "group", "fill": {"GroupNameWithPath": "NewGroupName"}, "path": "NewPath", "renames": "GroupName"}]
    assert r.with_renames(old, "iam:UpdateGroup", [], USERS_ARNS, USERS_FORMATS, models) == old
    # A rename whose request gives no new path is refused rather than read half.
    with pytest.raises(ValueError, match="no NewUserName and NewPath"):
        r.with_renames([{"type": "user", "fill": {"UserNameWithPath": "UserName"}}], "iam:UpdateUser", sentence,
                       USERS_ARNS, USERS_FORMATS, models)


def test_an_operation_acting_on_its_signer_names_that_user_when_its_request_names_none():
    models = {"iam": iam_model()}
    get_user = [{"operation": "GetUser", "parameter": None,
                 "sentence": "If you do not specify a user name, IAM determines the user name implicitly based on the "
                             "AWS access key ID used to sign the request."},
                {"operation": "GetUser", "parameter": "UserName",
                 "sentence": "If it is not included, it defaults to the user making the request."}]
    assert r.with_signing_user([], "iam:GetUser", ["user"], get_user, models) == [
        {"types": ["user"], "signer": True, "when": {"UserName": [None]}}]
    change = [{"operation": "ChangePassword", "parameter": None,
               "sentence": "Changes the password of the IAM user who is calling this operation."}]
    assert r.with_signing_user([], "iam:ChangePassword", ["user"], change, models) == [
        {"types": ["user"], "signer": True}]
    assert r.with_signing_user([], "iam:GetUser", ["user"], [], models) == []
    with pytest.raises(ValueError, match="not one of each"):
        r.with_signing_user([], "iam:GetUser", ["group"], get_user, models)


def test_a_member_whose_value_is_the_resources_arn_names_it_and_a_formats_type_is_named_through_it():
    models = {"iam": iam_model()}
    serial = [{"operation": "TagMFADevice", "parameter": "SerialNumber",
               "sentence": "For virtual MFA devices, the serial number is the same as the ARN."}]
    assert r.with_same_as_arn([], "iam:TagMFADevice", ["mfa"], serial, USERS_FORMATS, models) == [
        {"types": ["mfa"], "arn": "SerialNumber"}]
    task = [{"operation": "GetServiceLinkedRoleDeletionStatus", "parameter": "DeletionTaskId",
             "sentence": "This identifier is returned by the DeleteServiceLinkedRole operation in the format "
                         "task/aws-service-role/<service-principal-name>/<role-name>/<task-uuid>."}]
    assert r.with_formats([], "iam:GetServiceLinkedRoleDeletionStatus", ["role"], task, models) == [
        {"types": ["role"], "through": "DeletionTaskId"}]
    named = [{"type": "role", "fill": {"RoleNameWithPath": "RoleName"}}]
    assert r.with_formats(named, "iam:GetServiceLinkedRoleDeletionStatus", ["role"], task, models) == named


def test_a_resource_named_after_its_path_or_signed_for_is_checked():
    types = {"user", "group"}
    good = {"type": "user", "fill": {"UserNameWithPath": "UserName"}, "path": "Path"}
    renamed = {"type": "group", "fill": {"GroupNameWithPath": "NewGroupName"}, "path": "NewPath",
               "renames": "GroupName"}
    signer = {"types": ["user"], "signer": True, "when": {"UserName": [None]}}
    for resource in (good, renamed, signer, {"types": ["user"], "signer": True}):
        assert r._resource_problems("iam:Op", resource, types, USERS_FORMATS) == [], resource
    for bad in (dict(good, path="Paths[]"), dict(good, find=["UserName"]), dict(renamed, path=None),
                {"type": "group", "fill": {"GroupNameWithPath": "NewGroupName"}, "renames": "GroupName"},
                dict(signer, signer="yes"), dict(signer, types=["user", "group"]), dict(signer, fill={})):
        assert r._resource_problems("iam:Op", bad, types, USERS_FORMATS), bad
