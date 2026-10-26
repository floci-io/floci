#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/iam-tag-on-create.json from AWS's Service Reference and botocore.

A request that tags the resource it creates also needs the service's tagging action. The SNS API
reference, for one: "To be able to tag a topic on creation, you must have the sns:CreateTopic and
sns:TagResource permissions." `TagOnCreate` finds the tags a request carries, and this file tells it
which tagging action each operation needs and where its request carries the tags. It comes from two
sources, each answering what the other cannot:

- AWS's Service Reference, which lists the tagging action among an operation's `AuthorizedActions`,
  and marks an action as one that only tags (`IsTaggingOnly`), but does not say which request member
  carries the tags:

      https://servicereference.us-east-1.amazonaws.com/            (the index of services)
      https://servicereference.us-east-1.amazonaws.com/v1/<svc>/<svc>.json

- botocore's model of each SDK client the reference lists the operation for (Apache-2.0,
  https://github.com/boto/botocore, the pinned release), which gives the request's members but
  not which of them AWS checks.

An operation is read when it is not a tagging action itself and the reference lists, beside its
own, an action that only tags and adds tags: one whose name starts with Tag, Add, Create, Put, Set or
Replicate. That leaves out the untagging actions an update lists, which remove tags rather than
attach them. A member of the operation's request carries the tags when it is a top-level member named
`Tags`, `TagList`, `TagsList`, `TagSpecification` or `TagSpecifications`, or ending in `Tags`
(`ResourceTags`, `UserPoolTags`), and it holds a map of strings, a list of structures with a key and a
value, or a list of structures holding one of those, as EC2's tag specifications do. A member nested
deeper tags something else: the tasks a pipe starts, a workflow step's files. What the rule gets wrong
for an operation is corrected here, each with its reason: a member it sees that carries no tags the
operation attaches (`EXCLUDED_MEMBERS`), and the name a member goes by in the Query protocol where the
model no longer gives it (`QUERY_MEMBERS`).

Only the namespaces in `NAMESPACES` are read, and in them every operation the reference lists with a
tagging action, whether Floci serves it or not, so an operation Floci adds is checked from the start.
Each of those operations ends up in exactly one of three places, and generation fails on an operation
that would end up in none:

- `tags`: the members it reads.
- `byHand`: operations Floci checks itself, because they need one of several tagging actions.
  `TagOnCreateMembersTest` holds the two lists to each other.
- `notRead`: operations whose request carries no tags this table can read, each with its reason.

An operation whose tagging action takes the namespace's `CreateAction` condition key, as EC2's
`ec2:CreateTags` does, is marked `createAction`, and Floci gives that key the operation's name. One
whose own action takes `aws:RequestTag/${TagKey}` or `aws:TagKeys`, for the action or for a resource
it acts on, is marked `requestTags`, and Floci checks that action with the request's tags too, as it
checks the tagging action. Its own action is the one of the operation's name, in any case
(MemoryDB's `CreateACL` is `memorydb:CreateAcl`), or else the other actions of its namespace that
the reference lists for it (Budgets' `CreateBudget` is `budgets:ModifyBudget`).

A tag specification names the type of the resource its tags go to, as EC2's `ResourceType` does, so
its member says which of the specification's members that is (`resourceType`), and `resourceArns`
gives, for each namespace with such a member, every resource type the reference lists as the ARN of
one whose name is not known yet: its first ARN format, with every variable but the partition, Region
and account as `*` (`arn:${Partition}:ec2:${Region}:${Account}:instance/*`). Floci checks the tagging
action for a specification's tags on that ARN, as AWS checks it on the resource being tagged.

The Service Reference is live rather than a pinned dependency, so a fresh generation is not
byte-identical. The two gates split along the sources:

- `--check` is offline and needs the pinned botocore. The file must be well-formed, and every
  operation's members must be exactly what the rule and the corrections give from botocore for the
  models the file names, so a hand-edited member fails.
- `--verify` is online and stdlib-only. Every operation the file carries must still list the same
  tagging actions, with the same `createAction` and `requestTags`, for the same SDK clients, and
  every resource type in `resourceArns` must still have its ARN. An operation AWS has added
  is reported without failing: until the next sync it tags on creation unchecked, which is where it
  was before this file existed.

Run from anywhere in the repo:
    python3 tools/aws/regen_iam_tag_on_create.py           # rewrite the vendored file in place
    python3 tools/aws/regen_iam_tag_on_create.py --check   # offline: members match botocore
    python3 tools/aws/regen_iam_tag_on_create.py --verify  # online: operations match AWS
    python3 tools/aws/regen_iam_tag_on_create.py --source-dir <dir>  # read saved <svc>.json files
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

from regen_iam_authorized_actions import fetch_references
from regen_iam_passed_roles import (BODY_LOCATIONS, MEMBER_PATH_RE, NAMESPACE_RE, OPERATION_RE, _shown, load_models,
                                    namespace_of)
from regen_partitions import resolve_botocore_data

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-tag-on-create.json"

# The IAM namespaces whose operations this file reads: those Floci serves over the JSON and Query
# protocols and Smithy RPC v2 CBOR, and the REST ones whose routes Floci authorizes by operation.
NAMESPACES = (
    "acm", "application-autoscaling", "athena", "autoscaling", "bcm-data-exports", "bcm-pricing-calculator",
    "budgets", "ce", "cloudformation", "cloudhsm", "cloudtrail", "cloudwatch", "codedeploy", "codepipeline",
    "cognito-identity", "cognito-idp", "comprehend", "config", "cur", "datasync", "dms", "dsql", "dynamodb",
    "ec2", "ecr", "ecs", "eks", "elasticache", "elasticloadbalancing", "elasticmapreduce", "events",
    "firehose", "globalaccelerator", "glue", "iam", "kinesis", "kinesisanalytics", "kms", "lambda",
    "lightsail", "logs", "memorydb", "network-firewall", "organizations", "pipes", "rds", "redshift",
    "redshift-serverless", "rekognition", "resource-groups", "route53resolver", "sagemaker", "scheduler",
    "secretsmanager", "servicediscovery", "ses", "sns", "sqs", "ssm", "sso", "states", "sts", "swf", "textract",
    "timestream-influxdb", "transcribe", "transfer", "translate", "wafv2",
)

_NO_ROUTES = "REST routes no rule maps yet"
# Namespaces Floci serves whose operations list a tagging action but are not read here yet, each with
# why. `TagOnCreateNamespacesIntegrationTest` fails for a namespace Floci serves that is in neither list.
NOT_COVERED = {
    "access-analyzer": _NO_ROUTES, "airflow": _NO_ROUTES, "app-integrations": _NO_ROUTES, "appconfig": _NO_ROUTES,
    "appsync": _NO_ROUTES, "aps": _NO_ROUTES, "aws-marketplace": _NO_ROUTES, "backup": _NO_ROUTES,
    "batch": _NO_ROUTES, "bedrock": _NO_ROUTES, "bedrock-agentcore": _NO_ROUTES,
    "cloudfront": _NO_ROUTES, "codeguru-reviewer": _NO_ROUTES, "connect": _NO_ROUTES,
    "controltower": _NO_ROUTES, "detective": _NO_ROUTES, "dlm": _NO_ROUTES,
    "elasticfilesystem": _NO_ROUTES, "emr-serverless": _NO_ROUTES, "es": _NO_ROUTES, "fis": _NO_ROUTES,
    "guardduty": _NO_ROUTES, "inspector2": _NO_ROUTES, "iot": _NO_ROUTES, "kafka": _NO_ROUTES,
    "macie2": _NO_ROUTES, "mq": _NO_ROUTES, "oam": _NO_ROUTES, "ram": _NO_ROUTES,
    "resource-explorer-2": _NO_ROUTES, "rum": _NO_ROUTES, "s3tables": _NO_ROUTES, "securityhub": _NO_ROUTES,
    "s3": "authorized by route; S3 checks s3:PutObjectTagging for an object it creates with tags itself",
    "servicecatalog": "its operations that tag are AppRegistry's, whose REST routes no rule maps yet",
}

# An action that adds tags, among those the reference marks as only tagging.
ADDING_ACTION_RE = re.compile(r"^(Tag|Add|Create|Put|Set|Replicate)")
# A request member that carries the tags the operation attaches.
TAG_MEMBER_RE = re.compile(r"^(\w*tags|tags?list|tagspecifications?)$", re.IGNORECASE)
# The members of a tag structure that hold its key and its value.
KEY_NAMES = {"key", "tagkey"}
VALUE_NAMES = {"value", "tagvalue"}
CREATE_ACTION = "CreateAction"
# The condition keys that give an action the tags a request carries.
REQUEST_TAG_KEYS = {"aws:RequestTag/${TagKey}", "aws:TagKeys"}
# The protocols whose requests TagOnCreate reads: form parameters, EC2's among them, or a JSON or CBOR body.
READ_PROTOCOLS = {"json", "query", "ec2", "rest-json", "smithy-rpc-v2-cbor"}
# The member of a tag specification that names the type of the resource its tags go to.
RESOURCE_TYPE = "resourcetype"
# The variables of an ARN format a resource's ARN is built with before its name is known; the rest become `*`.
ARN_VARIABLE_RE = re.compile(r"\$\{(\w+)}")
KNOWN_ARN_VARIABLES = {"Partition", "Region", "Account"}

# Operations Floci checks itself, and why.
BY_HAND = {
    "logs:CreateLogGroup": "either logs:TagResource on the log group's ARN or the older logs:TagLogGroup",
}

NO_TAGS = "no member of the request carries tags"
# Operations the reference lists with a tagging action whose request carries no tags this file reads.
NOT_READ = {
    "cloudformation:ExecuteChangeSet": NO_TAGS,
    "dynamodb:UpdateTable": NO_TAGS,
    "ec2:RequestSpotFleet": "its tags are in spotFleetRequestConfig, deeper than the members this file reads",
    "ecr:BatchGetImage": NO_TAGS,
    "ecs:CreateGateway": NO_TAGS,
    "iam:AcquireRole": NO_TAGS,
    "rds:CreateDBCapacityReservation": NO_TAGS,
    "rds:DeleteDBCluster": NO_TAGS,
    "rds:DeleteDBInstance": NO_TAGS,
    "rds:DeleteTenantDatabase": NO_TAGS,
    "rds:StopDBInstance": NO_TAGS,
}

# Members the rule reads that carry no tags the operation attaches, each with why.
EXCLUDED_MEMBERS = {
    "ec2:CreateIpamPool": {"AllocationResourceTag[item]": "the tags a resource needs to allocate from the pool, "
                                                          "\"Tags that are required for resources that use CIDRs from "
                                                          "this IPAM pool\""},
}

# The name a member goes by in the Query protocol, which Floci serves the namespace in as well, where
# the model, now JSON only, no longer gives it.
QUERY_MEMBERS = {
    "sqs:CreateQueue": {"tags{}": ("Tag{}", "Floci's SQS Query handler reads the tags as Tag.N.Key and Tag.N.Value")},
}


# --------------------------------------------------------------------------- #
# The Service Reference: which operations need a tagging action
# --------------------------------------------------------------------------- #
def _actions(documents: list[dict]) -> dict[str, dict]:
    """Every action of every namespace, by `<namespace>:<action>`."""
    actions: dict[str, dict] = {}
    for document in documents:
        namespace = document.get("Name")
        if not isinstance(namespace, str) or not NAMESPACE_RE.match(namespace):
            raise ValueError(f"{namespace!r} is not usable as an IAM namespace")
        for action in document.get("Actions", []):
            if isinstance(action, dict) and isinstance(action.get("Name"), str):
                actions[f"{namespace}:{action['Name']}"] = action
    return actions


def _tagging_only(action: dict | None) -> bool:
    properties = ((action or {}).get("Annotations") or {}).get("Properties") or {}
    return properties.get("IsTaggingOnly") is True


def _resource_keys(documents: list[dict]) -> dict[str, list[str]]:
    """The condition keys of every resource type of every namespace, by `<namespace>:<resource>`."""
    keys: dict[str, list[str]] = {}
    for document in documents:
        for resource in document.get("Resources") or []:
            if isinstance(resource, dict) and isinstance(resource.get("Name"), str):
                keys[f"{document['Name']}:{resource['Name']}"] = resource.get("ConditionKeys") or []
    return keys


def _condition_keys(namespace: str, action: dict, resource_keys: dict[str, list[str]]) -> set[str]:
    """The condition keys an action takes, for itself and for each resource it acts on: the resource's own
    when the action names them, as EC2's do, else the ones its namespace gives the resource type."""
    keys = set(action.get("ActionConditionKeys") or [])
    for resource in action.get("Resources") or []:
        if isinstance(resource, dict):
            keys |= set(resource["ConditionKeys"] if "ConditionKeys" in resource
                        else resource_keys.get(f"{namespace}:{resource.get('Name')}", []))
    return keys


def _takes_request_tags(namespace: str, name: str, authorized: list[dict], actions: dict[str, dict],
                        lowered: dict[str, dict], resource_keys: dict[str, list[str]]) -> bool:
    """Whether the operation's own action takes the request's tags: the action of its name in any case, or
    else the other actions of its namespace the reference lists for it, none of them only tagging."""
    own = [lowered[f"{namespace}:{name}".lower()]] if f"{namespace}:{name}".lower() in lowered else []
    if not own:
        own = [actions[key] for key in (f"{entry.get('Service')}:{entry.get('Name')}" for entry in authorized)
               if namespace_of(key) == namespace and key in actions and not _tagging_only(actions[key])]
    return any(REQUEST_TAG_KEYS & _condition_keys(namespace, action, resource_keys) for action in own)


def tag_on_create_operations(documents: list[dict]) -> dict[str, dict]:
    """Every operation of every namespace that lists an action adding tags beside its own, with those
    actions, whether one takes its namespace's CreateAction key, whether the operation's own action takes
    the request's tags, and the SDK clients it is listed for."""
    actions = _actions(documents)
    lowered = {key.lower(): action for key, action in actions.items()}
    resource_keys = _resource_keys(documents)
    found: dict[str, dict] = {}
    for document in documents:
        namespace = document["Name"]
        for operation in document.get("Operations", []):
            name = operation.get("Name") if isinstance(operation, dict) else None
            if not isinstance(name, str):
                raise ValueError(f"{namespace}: operation entry without a usable 'Name': {operation!r}")
            if _tagging_only(actions.get(f"{namespace}:{name}")):
                continue
            authorized = operation.get("AuthorizedActions") or []
            if not isinstance(authorized, list) or not all(isinstance(entry, dict) for entry in authorized):
                raise ValueError(f"{namespace}:{name}: 'AuthorizedActions' is not a list of objects")
            tagging = sorted({f"{entry.get('Service')}:{entry.get('Name')}" for entry in authorized
                              if isinstance(entry.get("Name"), str) and ADDING_ACTION_RE.match(entry["Name"])
                              and _tagging_only(actions.get(f"{entry.get('Service')}:{entry.get('Name')}"))})
            if not tagging:
                continue
            create_action = any(f"{namespace_of(action)}:{CREATE_ACTION}"
                                in actions[action].get("ActionConditionKeys", []) for action in tagging)
            clients = {sdk["Name"] for sdk in operation.get("SDK") or []
                       if isinstance(sdk, dict) and sdk.get("Package") == "Boto3" and isinstance(sdk.get("Name"), str)}
            key = f"{namespace}:{name}"
            entry = found.setdefault(key, {"tagging": set(), "createAction": False, "requestTags": False,
                                           "clients": set()})
            entry["tagging"] |= set(tagging)
            entry["createAction"] = entry["createAction"] or create_action
            entry["requestTags"] = (entry["requestTags"]
                                    or _takes_request_tags(namespace, name, authorized, actions, lowered,
                                                           resource_keys))
            entry["clients"] |= clients
    return {key: {"tagging": sorted(value["tagging"]), "createAction": value["createAction"],
                  "requestTags": value["requestTags"], "clients": sorted(value["clients"])}
            for key, value in sorted(found.items())}


# --------------------------------------------------------------------------- #
# botocore: which members of the request carry the tags
# --------------------------------------------------------------------------- #
def _protocols(model: dict) -> set[str]:
    metadata = model["metadata"]
    return set(metadata.get("protocols") or [metadata.get("protocol")])


def readable(model: dict) -> bool:
    """Whether the model's requests come as form parameters or a JSON or CBOR body."""
    return bool(READ_PROTOCOLS & _protocols(model))


def _serialized(name: str, member: dict, model: dict) -> str:
    """The name the member goes by on the wire: EC2's Query names are capitalized, and give way to a
    `queryName`; everything else goes by its `locationName`."""
    if "ec2" in _protocols(model):
        if "queryName" in member:
            return member["queryName"]
        location = member.get("locationName", name)
        return location[:1].upper() + location[1:]
    return member.get("locationName", name)


def _pair(model: dict, shape: dict) -> tuple[str, str] | None:
    """The wire names of a tag structure's key and value members; None for any other structure."""
    if shape["type"] != "structure":
        return None
    members = shape.get("members", {})
    key = [name for name in members if name.lower() in KEY_NAMES]
    value = [name for name in members if name.lower() in VALUE_NAMES]
    if len(key) != 1 or len(value) != 1:
        return None
    return _serialized(key[0], members[key[0]], model), _serialized(value[0], members[value[0]], model)


def _collection(model: dict, shape_name: str) -> dict | None:
    """How a shape holds tags, as the vendored path's last step and a tag structure's key and value
    names: a map of strings, or a list of tag structures. None for any other shape."""
    shape = model["shapes"][shape_name]
    if shape["type"] == "map":
        key = model["shapes"][shape["key"]["shape"]]
        value = model["shapes"][shape["value"]["shape"]]
        return {"step": "{}"} if key["type"] == "string" and value["type"] == "string" else None
    if shape["type"] == "list":
        pair = _pair(model, model["shapes"][shape["member"]["shape"]])
        if pair is not None:
            return {"step": "[" + shape["member"].get("locationName", "") + "]", "key": pair[0], "value": pair[1]}
    return None


def tag_members(model: dict, operation: str) -> list[dict]:
    """The members of the operation's request the rule reads, as vendored entries."""
    spec = model["operations"].get(operation)
    if spec is None or "input" not in spec or not readable(model):
        return []
    root = model["shapes"][spec["input"]["shape"]]
    if root.get("payload") is not None:
        return []
    found = []
    for name, member in root.get("members", {}).items():
        if member.get("location") not in BODY_LOCATIONS or not TAG_MEMBER_RE.match(name):
            continue
        serialized = _serialized(name, member, model)
        collection = _collection(model, member["shape"])
        if collection is not None:
            found.append({"member": serialized + collection.pop("step"), **collection})
            continue
        shape = model["shapes"][member["shape"]]
        if shape["type"] != "list":
            continue
        element = model["shapes"][shape["member"]["shape"]]
        for inner_name, inner in (element.get("members", {}).items() if element["type"] == "structure" else []):
            inner_collection = _collection(model, inner["shape"]) if TAG_MEMBER_RE.match(inner_name) else None
            if inner_collection is not None:
                path = (serialized + "[" + shape["member"].get("locationName", "") + "]."
                        + _serialized(inner_name, inner, model) + inner_collection.pop("step"))
                kinds = [name for name in element["members"] if name.lower() == RESOURCE_TYPE]
                if len(kinds) == 1:
                    inner_collection["resourceType"] = _serialized(kinds[0], element["members"][kinds[0]], model)
                found.append({"member": path, **inner_collection})
    return found


def resource_arns(documents: list[dict], namespaces: set[str]) -> dict[str, dict[str, str]]:
    """Every resource type of each namespace given, as the ARN of one whose name is not known yet: its first
    ARN format, with every variable but the partition, Region and account as `*`."""
    arns = {}
    for document in documents:
        if document["Name"] not in namespaces:
            continue
        arns[document["Name"]] = dict(sorted(
            (resource["Name"], ARN_VARIABLE_RE.sub(
                lambda m: m.group(0) if m.group(1) in KNOWN_ARN_VARIABLES else "*", resource["ARNFormats"][0]))
            for resource in document.get("Resources", []) if resource.get("ARNFormats")))
    return dict(sorted(arns.items()))


# --------------------------------------------------------------------------- #
# The vendored document
# --------------------------------------------------------------------------- #
def entry_for(operation: str, upstream: dict, models: dict[str, dict]) -> dict:
    """What the file says for one operation, from its tagging actions and the models of its namespace."""
    head = {"tagging": upstream["tagging"]}
    if upstream["createAction"]:
        head["createAction"] = True
    if upstream["requestTags"]:
        head["requestTags"] = True
    if operation in BY_HAND:
        return {**head, "byHand": BY_HAND[operation]}
    if operation in NOT_READ:
        return {**head, "notRead": NOT_READ[operation]}
    if len(upstream["tagging"]) != 1:
        raise ValueError(f"{operation}: needs one of {upstream['tagging']}; check it by hand (BY_HAND)")
    name = operation.split(":", 1)[1]
    members: dict[str, dict] = {}
    for model in models.values():
        for member in tag_members(model, name):
            members.setdefault(member["member"], member)
    for path in EXCLUDED_MEMBERS.get(operation, {}):
        members.pop(path, None)
    for path, (query, _reason) in QUERY_MEMBERS.get(operation, {}).items():
        if path not in members:
            raise ValueError(f"{operation}: QUERY_MEMBERS names {path}, which its request does not have")
        members[path] = {**members[path], "query": query}
    if not members:
        raise ValueError(f"{operation}: no request member carries tags; say why it is not read (NOT_READ) "
                         f"or check it by hand (BY_HAND)")
    return {**head, "tags": [members[path] for path in sorted(members)]}


def build(documents: list[dict], provenance: str, data: Path, botocore: str) -> dict:
    upstream = tag_on_create_operations(documents)
    covered = {operation: value for operation, value in upstream.items() if namespace_of(operation) in NAMESPACES}
    _check_overrides(covered)
    namespaces: dict[str, list[str]] = {}
    for namespace in sorted(NAMESPACES):
        namespaces[namespace] = sorted({client for operation, value in covered.items()
                                        if namespace_of(operation) == namespace for client in value["clients"]})
    operations = {}
    unread = []
    for operation, value in covered.items():
        models = load_models(data, set(namespaces[namespace_of(operation)]))
        try:
            operations[operation] = entry_for(operation, value, models)
        except ValueError as e:
            unread.append(str(e))
    unread += _unmatched_exclusions(covered, namespaces, data)
    if unread:
        raise ValueError("\n".join(unread))
    elsewhere = sorted({namespace_of(operation) for operation in upstream} - set(NAMESPACES))
    typed = {namespace_of(operation) for operation, entry in operations.items()
             for member in entry.get("tags", []) if "resourceType" in member}
    return {
        "_source": {
            "generator": "tools/aws/regen_iam_tag_on_create.py",
            "reference": provenance,
            "botocore": botocore,
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "make iam-tag-on-create-check re-derives every member from botocore; "
                    "make iam-tag-on-create-verify checks every operation against AWS.",
        },
        "namespaces": namespaces,
        "notCovered": dict(sorted(NOT_COVERED.items())),
        "otherNamespaces": [namespace for namespace in elsewhere if namespace not in NOT_COVERED],
        "resourceArns": resource_arns(documents, typed),
        "operations": operations,
    }


def _unmatched_exclusions(covered: dict[str, dict], namespaces: dict[str, list[str]], data: Path) -> list[str]:
    """An exclusion that its operation's request does not have, so none outlives its member."""
    problems = []
    for operation, paths in EXCLUDED_MEMBERS.items():
        if operation not in covered:
            continue
        name = operation.split(":", 1)[1]
        found = {member["member"] for model in load_models(data, set(namespaces[namespace_of(operation)])).values()
                 for member in tag_members(model, name)}
        problems += [f"EXCLUDED_MEMBERS names {path} for {operation}, which its request does not have"
                     for path in paths if path not in found]
    return problems


def _check_overrides(covered: dict[str, dict]) -> None:
    """Every correction names an operation the reference lists with a tagging action, so none outlives it."""
    stale = []
    for table_name, table in (("BY_HAND", BY_HAND), ("NOT_READ", NOT_READ),
                              ("EXCLUDED_MEMBERS", EXCLUDED_MEMBERS), ("QUERY_MEMBERS", QUERY_MEMBERS)):
        stale += [f"{table_name} names {key}, which the reference does not list with a tagging action"
                  for key in table if key not in covered]
    stale += [f"{operation} is both checked by hand and not read" for operation in sorted(set(BY_HAND) & set(NOT_READ))]
    if stale:
        raise ValueError("; ".join(stale))


def render(document: dict) -> str:
    """The document with one operation to a line."""
    lines = ["{"]
    for key in ("_source", "namespaces", "notCovered", "otherNamespaces", "resourceArns"):
        lines.append(f'  "{key}": {json.dumps(document[key])},')
    lines.append('  "operations": {')
    operations = list(document["operations"].items())
    for index, (operation, entry) in enumerate(operations):
        tail = "," if index < len(operations) - 1 else ""
        lines.append(f"    {json.dumps(operation)}: {json.dumps(entry)}{tail}")
    lines += ["  }", "}"]
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------- #
# Gates
# --------------------------------------------------------------------------- #
def validate(document: dict) -> list[str]:
    """Problems that make the vendored file unusable, rather than out of date."""
    problems: list[str] = []
    namespaces = document.get("namespaces")
    operations = document.get("operations")
    if not isinstance(namespaces, dict) or not namespaces:
        return ["namespaces is missing or empty"]
    if not isinstance(operations, dict) or not operations:
        return ["operations is missing or empty"]
    if list(namespaces) != sorted(namespaces):
        problems.append("namespaces is not sorted")
    if list(operations) != sorted(operations):
        problems.append("operations is not sorted")
    arns = document.get("resourceArns", {})
    if not isinstance(arns, dict) or not all(
            namespace in namespaces and isinstance(types, dict) and types and all(
                isinstance(name, str) and name and isinstance(arn, str) and arn.startswith("arn:${Partition}:")
                for name, arn in types.items())
            for namespace, types in arns.items()):
        problems.append("resourceArns is not an ARN for each resource type of namespaces the file reads")
        arns = {}
    for operation, entry in operations.items():
        if not OPERATION_RE.match(operation) or namespace_of(operation) not in namespaces:
            problems.append(f"{operation!r} is not an operation of a namespace the file reads")
            continue
        kinds = [kind for kind in ("tags", "byHand", "notRead") if kind in entry]
        if len(kinds) != 1:
            problems.append(f"{operation} is not exactly one of tags, byHand and notRead")
            continue
        tagging = entry.get("tagging")
        if not isinstance(tagging, list) or not tagging or not all(
                isinstance(action, str) and OPERATION_RE.match(action) for action in tagging):
            problems.append(f"{operation} has no usable tagging")
            continue
        if (set(entry) - {"tagging", "createAction", "requestTags", kinds[0]}
                or entry.get("createAction", True) is not True or entry.get("requestTags", True) is not True):
            problems.append(f"{operation} carries an unknown field")
            continue
        if kinds[0] != "tags":
            if not isinstance(entry[kinds[0]], str) or not entry[kinds[0]]:
                problems.append(f"{operation} gives no reason for {kinds[0]}")
            continue
        if len(tagging) != 1:
            problems.append(f"{operation} reads its tags for {tagging}, not one tagging action")
        members = entry["tags"]
        if not isinstance(members, list) or not members:
            problems.append(f"{operation} reads no tags")
            continue
        for member in members:
            problems += _member_problems(operation, member)
            if isinstance(member, dict) and "resourceType" in member and namespace_of(operation) not in arns:
                problems.append(f"{operation}: resourceArns has no resource types of {namespace_of(operation)}")
    return problems


def _member_problems(operation: str, member: object) -> list[str]:
    path = member.get("member") if isinstance(member, dict) else None
    if not isinstance(path, str) or not MEMBER_PATH_RE.match(path) or not re.search(r"(\[\w*]|\{})$", path):
        return [f"{operation} reads {member!r}, which is no path to a tag list or map"]
    problems = []
    query = member.get("query")
    if query is not None and (not isinstance(query, str) or not MEMBER_PATH_RE.match(query)
                              or query.endswith("}") != path.endswith("}")):
        problems.append(f"{operation}: the tags at {path} go by {query!r} in the Query protocol, which is no path")
    pair = [member.get("key"), member.get("value")]
    if path.endswith("]") and not all(isinstance(name, str) and name for name in pair):
        problems.append(f"{operation}: the tag list at {path} names no key and value")
    if path.endswith("}") and pair != [None, None]:
        problems.append(f"{operation}: the tag map at {path} names a key and value")
    kind = member.get("resourceType")
    if kind is not None and (not isinstance(kind, str) or not kind or "]." not in path):
        problems.append(f"{operation}: the tags at {path} name {kind!r} as their resource type, "
                        f"though they are no list of tag specifications")
    if set(member) - {"member", "key", "value", "query", "resourceType"}:
        problems.append(f"{operation}: the tags at {path} carry an unknown field")
    return problems


def derive(document: dict, data: Path) -> list[str]:
    """Where the members the file gives differ from what botocore gives for the models it names."""
    problems: list[str] = []
    for operation, entry in document["operations"].items():
        models = load_models(data, set(document["namespaces"][namespace_of(operation)]))
        upstream = {"tagging": entry["tagging"], "createAction": entry.get("createAction", False),
                    "requestTags": entry.get("requestTags", False)}
        try:
            expected = entry_for(operation, upstream, models)
        except ValueError as e:
            problems.append(str(e))
            continue
        if expected != entry:
            problems.append(f"{operation}: the file has {json.dumps(entry)}, botocore gives {json.dumps(expected)}")
    return problems


def verify_against(vendored: dict, upstream: dict[str, dict],
                   arns: dict[str, dict[str, str]] | None = None) -> tuple[list[str], list[str]]:
    """What the file claims and AWS does not, and the operations AWS has added since. `arns` are the resource
    ARNs AWS gives the namespaces the file has them for."""
    untrue: list[str] = []
    for namespace, types in vendored.get("resourceArns", {}).items():
        changed = [] if arns is None else sorted(name for name, arn in types.items()
                                                 if arns.get(namespace, {}).get(name) != arn)
        if changed:
            untrue.append(f"AWS no longer gives {namespace}'s {', '.join(changed)} the ARN the file does")
    for operation, entry in vendored["operations"].items():
        actual = upstream.get(operation)
        if actual is None:
            untrue.append(f"AWS no longer lists a tagging action for {operation}")
        elif actual["tagging"] != entry["tagging"]:
            untrue.append(f"AWS lists {actual['tagging']} for {operation}, not {entry['tagging']}")
        elif actual["createAction"] != entry.get("createAction", False):
            untrue.append(f"AWS's tagging action for {operation} {'takes' if actual['createAction'] else 'no longer takes'} "
                          f"the CreateAction key")
        elif actual["requestTags"] != entry.get("requestTags", False):
            untrue.append(f"AWS's own action for {operation} {'takes' if actual['requestTags'] else 'no longer takes'} "
                          f"the request's tags")
        elif not set(actual["clients"]) <= set(vendored["namespaces"][namespace_of(operation)]):
            untrue.append(f"AWS lists {operation} for {actual['clients']}, which the file does not read")
    added = sorted(operation for operation in upstream
                   if namespace_of(operation) in vendored["namespaces"] and operation not in vendored["operations"])
    return untrue, added


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="offline: members match the pinned botocore")
    parser.add_argument("--verify", action="store_true", help="online: operations match AWS")
    parser.add_argument("--source-dir", type=Path, default=None,
                        help="read saved <service>.json reference files instead of fetching them")
    parser.add_argument("--botocore-data", type=Path, default=None, help="botocore data directory")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    if args.check or args.verify:
        if not args.output.exists():
            print(f"error: {args.output} does not exist", file=sys.stderr)
            return 1
        document = json.loads(args.output.read_text(encoding="utf-8"))
        problems = validate(document)
        if not problems and args.check:
            data, _ = resolve_botocore_data(args.botocore_data)
            problems = derive(document, data)
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        if problems:
            return 1
        entries = len(document["operations"])
        if args.check:
            print(f"{_shown(args.output)} matches botocore ({entries} operations over "
                  f"{len(document['namespaces'])} namespaces)")
            return 0
        try:
            documents, provenance = fetch_references(args.source_dir)
            upstream = tag_on_create_operations(documents)
            arns = resource_arns(documents, set(document.get("resourceArns", {})))
        except (OSError, ValueError) as e:
            print(f"error: could not read the Service Reference ({e})", file=sys.stderr)
            return 2
        untrue, added = verify_against(document, upstream, arns)
        for claim in untrue:
            print(f"error: {claim}", file=sys.stderr)
        if untrue:
            print(f"error: {len(untrue)} vendored operation(s) do not hold upstream.", file=sys.stderr)
            return 1
        if added:
            print(f"{_shown(args.output)}: all {entries} operations verified. AWS has since added "
                  f"{len(added)}, so the file is incomplete but not wrong (run without --verify to refresh): "
                  f"{', '.join(added[:10])}{'...' if len(added) > 10 else ''}")
        else:
            print(f"{_shown(args.output)}: all {entries} operations verified against {provenance}, "
                  f"nothing added upstream.")
        return 0

    data, botocore = resolve_botocore_data(args.botocore_data)
    try:
        documents, provenance = fetch_references(args.source_dir)
        document = build(documents, provenance, data, botocore)
    except (OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    problems = validate(document)
    for problem in problems:
        print(f"error: {problem}", file=sys.stderr)
    if problems:
        return 1
    args.output.write_text(render(document), encoding="utf-8")
    print(f"wrote {_shown(args.output)} ({len(document['operations'])} operations over "
          f"{len(document['namespaces'])} namespaces, from {provenance})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
