#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/iam-request-resources.json from AWS's Service Reference and botocore.

Under IAM enforcement every request is checked against the resources it acts on. For the services
`ResourceArnBuilder` builds them for by hand that is their ARN; for every other service it is `*`,
so a grant scoped to a resource is refused and a deny scoped to one never applies. This file gives,
for each operation of the namespaces it reads, the resources its request names, and `RequestResources`
builds their ARNs from the request. It comes from two sources, each answering what the other cannot:

- AWS's Service Reference, which lists the resource types each action acts on and the ARN formats
  of each type, but not which request member names a resource:

      https://servicereference.us-east-1.amazonaws.com/            (the index of services)
      https://servicereference.us-east-1.amazonaws.com/v1/<svc>/<svc>.json

- botocore's model of each SDK client the reference lists the operation for (Apache-2.0,
  https://github.com/boto/botocore, the pinned release), which gives the request's members.

An operation is checked as its own action, or as the action of another name the reference gives it
(`regen_iam_authorized_actions.py` vendors that mapping). For each resource type that action acts
on, the request names a resource when one of its top-level members holds a string or a list of
strings and:

- holds the resource's ARN: its name ends in `Arn` or `Arns`. Any of the action's types may be the
  one it holds (`ResourceArn` holds whatever the action acts on, `RuleArn` a `listener-rule/app`),
  and a value names a resource of a type when it matches the type's ARN format, so an ARN of
  something the action does not act on is not checked.
- fills one of the variables of the type's ARN format: its stem (the name lowercased, `Arn`, `Name`,
  `Id`, `Identifier` and a plural dropped) is the variable's, ends with it or is the end of it, four
  letters at least (`AutoScalingGroupName` fills `${GroupFriendlyName}`, `GroupId` fills
  `${SecurityGroupId}`), and it is a name for a name and an id for an id (an `Identifier` either),
  or the member is a bare `Name`, `Id` or `Identifier`, the variable is named after the type and the
  operation is about the type, its name past the verb the type's (`DescribeTargetGroups`, but not
  `StartExecution` for a state machine, whose `name` is the execution's), and no member holds its ARN.
  The variables nothing fills are left open as `*`: a resource the request creates has the id its
  service mints, so `CreateTargetGroup` names `targetgroup/<Name>/*`.
- names neither, but the type is the one the operation is named after (`CreateCapacityReservation`
  and `capacity-reservation`): the request creates one, every variable open.

A member whose botocore shape is an account id (a string of pattern `[0-9]{12}`) gives the account of
the types the request names by their variables: ECR's `registryId` selects the registry its handler
acts in, so `repository/<name>` is that account's.

An operation that creates a type its action does not act on names the new resource from the ARN of the
parent it acts on, when the child's ARN format is the parent's with its resource type word replaced and
one id appended (`CreateListener` acts on a load balancer and makes `listener/app/<lb>/<id>/*`); the
tagging action a create's tags need is checked on it, as AWS checks it on the resource being tagged.

A type nothing names is not checked: an action lists the optional resources its requests may use
(`RunInstances` lists `elastic-gpu`), and checking one a request does not name would refuse a grant
that covers what it does name. An operation whose action acts on no resource type, or whose request
names none, is checked on `*`, as AWS checks it.

Only the namespaces in `NAMESPACES` are read, and in them every operation, whether Floci serves it
or not. `HAND_BUILT` are the namespaces `ResourceArnBuilder` builds resources for itself, and
`NOT_COVERED` the ones Floci serves that are not read yet, each with its reason;
`RequestResourceNamespacesIntegrationTest` fails for a served namespace in none of the three.
`CORRECTIONS` gives an operation's resources where the rule gets them wrong, each with its reason; a
resource there may apply only `when` a member has one of some values (`Type` picks the load balancer
v2 `CreateLoadBalancer` creates), and an ARN member's value may be read only up to a character
`before` which its handler drops the rest (`StartExecution`'s `<stateMachineArn>#<test case>`).
`UNQUALIFIED` are the resource types whose ARN a request may give with a qualifier, one more `:<name>`
past its format, and whose service checks it without one: Step Functions checks a version or an alias
on its state machine's ARN.

The Service Reference is live rather than a pinned dependency, so a fresh generation is not
byte-identical. The two gates split along the sources:

- `--check` is offline and needs the pinned botocore. The file must be well-formed, and every
  operation's resources must be exactly what the rule and the corrections give from botocore for the
  resource types and ARN formats the file records, so a hand-edited entry fails.
- `--verify` is online and stdlib-only. Every operation must still be checked as the action, on the
  resource types, the file records, and every type must still have its ARN format. An operation AWS
  has added is reported without failing: until the next sync it is checked on `*`, where it was
  before this file existed.

Run from anywhere in the repo:
    python3 tools/aws/regen_iam_request_resources.py           # rewrite the vendored file in place
    python3 tools/aws/regen_iam_request_resources.py --check   # offline: resources match botocore
    python3 tools/aws/regen_iam_request_resources.py --verify  # online: actions and types match AWS
    python3 tools/aws/regen_iam_request_resources.py --source-dir <dir>  # read saved <svc>.json files
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

from regen_iam_authorized_actions import authorized_actions_from, fetch_references
from regen_iam_passed_roles import NAMESPACE_RE, OPERATION_RE, _shown, load_models, namespace_of
from regen_partitions import resolve_botocore_data

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-request-resources.json"

# The namespaces read, in batches: each moves here from NOT_COVERED with the tests that show its ARNs
# are the ones its handlers mint.
NAMESPACES = ("cognito-idp", "ecr", "elasticloadbalancing", "states")

# The namespaces whose resources ResourceArnBuilder builds itself.
HAND_BUILT = ("dsql", "dynamodb", "eks", "iam", "kinesis", "kms", "lambda", "logs", "pipes", "resource-groups",
              "s3", "scheduler", "secretsmanager", "sns", "sqs", "ssm", "sts")

_LATER = "not read yet: a later batch, with the tests that show its ARNs are the ones its handlers mint"
_REST = "REST: its requests name their resources in the path, which RequestResources does not read yet"

# Namespaces Floci serves whose resources are not read yet, and why.
NOT_COVERED: dict[str, str] = {
    "access-analyzer": _REST,
    "account": _REST,
    "acm": _LATER,
    "airflow": _REST,
    "apigateway": _REST,
    "app-integrations": _REST,
    "appconfig": _REST,
    "application-autoscaling": _LATER,
    "appsync": _REST,
    "aps": _REST,
    "athena": _LATER,
    "autoscaling": _LATER,
    "aws-marketplace": _LATER,
    "backup": _REST,
    "batch": _REST,
    "bcm-data-exports": _LATER,
    "bcm-pricing-calculator": _LATER,
    "bedrock": _LATER,
    "bedrock-agentcore": _REST,
    "budgets": _LATER,
    "ce": _LATER,
    "cloudformation": _LATER,
    "cloudfront": _REST,
    "cloudhsm": _LATER,
    "cloudtrail": _LATER,
    "cloudwatch": _LATER,
    "codeartifact": _REST,
    "codebuild": _LATER,
    "codedeploy": _LATER,
    "codeguru-reviewer": _REST,
    "codepipeline": _LATER,
    "cognito-identity": _LATER,
    "comprehend": _LATER,
    "config": _LATER,
    "connect": _REST,
    "controlcatalog": _REST,
    "controltower": _REST,
    "cur": _LATER,
    "datasync": _LATER,
    "detective": _REST,
    "dlm": _REST,
    "dms": _LATER,
    "ec2": _LATER,
    "ecs": _LATER,
    "elasticache": _LATER,
    "elasticbeanstalk": _LATER,
    "elasticfilesystem": _REST,
    "elasticmapreduce": _LATER,
    "emr-serverless": _REST,
    "es": _REST,
    "events": _LATER,
    "execute-api": _REST,
    "firehose": _LATER,
    "fis": _REST,
    "globalaccelerator": _LATER,
    "glue": _LATER,
    "guardduty": _REST,
    "identitystore": _LATER,
    "inspector2": _REST,
    "iot": _LATER,
    "kafka": _REST,
    "kinesisanalytics": _LATER,
    "lightsail": _LATER,
    "macie2": _REST,
    "memorydb": _LATER,
    "mq": _REST,
    "network-firewall": _LATER,
    "oam": _REST,
    "organizations": _LATER,
    "ram": _REST,
    "rds": _LATER,
    "rds-data": _REST,
    "redshift": _LATER,
    "redshift-data": _LATER,
    "redshift-serverless": _LATER,
    "rekognition": _LATER,
    "resource-explorer-2": _REST,
    "route53": _REST,
    "route53resolver": _LATER,
    "rum": _REST,
    "s3tables": _REST,
    "s3vectors": _REST,
    "sagemaker": _LATER,
    "securityhub": _REST,
    "servicecatalog": _LATER,
    "servicediscovery": _LATER,
    "servicequotas": _LATER,
    "ses": _LATER,
    "signin": _REST,
    "sso": _LATER,
    "sso-oauth": _REST,
    "swf": _LATER,
    "textract": _LATER,
    "timestream-influxdb": _LATER,
    "transcribe": _LATER,
    "transfer": _LATER,
    "translate": _LATER,
    "verifiedpermissions": _LATER,
    "wafv2": _LATER,
}

# An operation's resources where the rule gets them wrong: the resources, and why. A resource's `when` names the
# values of a member it applies for, null for the member absent.
CORRECTIONS: dict[str, tuple[list[dict], str]] = {
    "elasticloadbalancing:CreateLoadBalancer": ([
        {"type": "loadbalancer", "fill": {"LoadBalancerName": "LoadBalancerName"}},
        {"type": "loadbalancer/app/", "fill": {"LoadBalancerName": "Name"}, "when": {"Type": ["application", None]}},
        {"type": "loadbalancer/gwy/", "fill": {"LoadBalancerName": "Name"}, "when": {"Type": ["gateway"]}},
        {"type": "loadbalancer/net/", "fill": {"LoadBalancerName": "Name"}, "when": {"Type": ["network"]}},
    ], "the classic API names its load balancer by LoadBalancerName; the v2 API by Name, its type by Type, "
       "an application load balancer when it gives none, which the rule cannot tell apart"),
    **{f"states:{operation}": ([{"types": ["statemachine"], "arn": "stateMachineArn", "before": "#"}],
                              "Floci takes Step Functions Local's <stateMachineArn>#<test case> and runs the machine "
                              "the part before the # names, so that part is the one checked")
       for operation in ("StartExecution", "StartSyncExecution")},
}

# Resource types whose ARN a request may give with a qualifier, one more `:<name>` past its format, which their
# service drops to check it, and why.
UNQUALIFIED: dict[str, str] = {
    "states:statemachine": "Step Functions authorizes a version or an alias on its state machine's ARN, not on the "
                           "version's or the alias's ('To authorize a version or an alias to invoke an API action, "
                           "Step Functions uses the state machine's ARN instead of using the version ARN or alias ARN')",
}

# The variables of an ARN format the account, partition and Region fill.
KNOWN_VARIABLES = {"Partition", "Region", "Account"}
VARIABLE_RE = re.compile(r"\$\{(\w+)}")
STEM_SUFFIX_RE = re.compile(r"(arns|arn|names|name|identifiers|identifier|ids|id|s)$")
# A bare member that names the resource of the type its operation acts on.
BARE = {"name", "names", "id", "ids", "identifier", "identifiers"}
# The patterns botocore gives a string shape that holds an account id.
ACCOUNT_ID_PATTERNS = {"[0-9]{12}", "^[0-9]{12}$", r"\d{12}", r"^\d{12}$"}
# The verbs an operation that creates the resource it is named after starts with.
CREATING_RE = re.compile(r"^(Create|Run|Allocate|Register|Put|Import|Copy|Request|Start|Add)")
# An operation's verb: what follows is what it acts on.
SUBJECT_RE = re.compile(r"^[A-Z][a-z]+")
# The protocols whose requests RequestResources reads: form parameters or a JSON or CBOR body.
READ_PROTOCOLS = {"json", "query", "ec2", "smithy-rpc-v2-cbor"}
BODY_LOCATIONS = (None, "")


def stem(name: str) -> str:
    """The name lowercased, without what is not a letter or digit, and without a trailing `Arn`,
    `Name`, `Id`, `Identifier` or plural, twice over (`TargetGroupArns` is `targetgroup`)."""
    value = re.sub(r"[^a-z0-9]", "", name.lower())
    for _ in range(2):
        value = STEM_SUFFIX_RE.sub("", value)
    return value


def related(a: str, b: str) -> bool:
    """Whether two stems name the same thing: equal, or one ends with the other, four letters at least."""
    return a == b or (min(len(a), len(b)) >= 4 and (a.endswith(b) or b.endswith(a)))


# --------------------------------------------------------------------------- #
# The Service Reference
# --------------------------------------------------------------------------- #
def checked_actions(documents: list[dict]) -> dict[str, dict]:
    """Each operation of each namespace, as `<namespace>:<Operation>`, with the action Floci checks it
    as, that action's resource types (null when the namespace has no action of that name) and the
    operation's SDK clients."""
    mapped = authorized_actions_from(documents)
    found = {}
    for document in documents:
        namespace = document["Name"]
        actions = {action["Name"].lower(): action for action in document.get("Actions", [])}
        for operation in document.get("Operations", []):
            name = operation["Name"]
            action_name = mapped.get(namespace, {}).get(name, f"{namespace}:{name}").split(":", 1)[1]
            action = actions.get(action_name.lower())
            found[f"{namespace}:{name}"] = {
                "action": f"{namespace}:{action['Name']}" if action else f"{namespace}:{action_name}",
                "types": None if action is None else [resource["Name"] for resource in action.get("Resources") or []],
                "clients": sorted({sdk["Name"] for sdk in operation.get("SDK", []) if sdk.get("Package") == "Boto3"}),
            }
    return found


def arn_formats(documents: list[dict], namespaces: set[str]) -> dict[str, dict[str, str]]:
    """Each resource type of the namespaces given, as its first ARN format."""
    formats = {}
    for document in documents:
        if document["Name"] in namespaces:
            formats[document["Name"]] = dict(sorted(
                (resource["Name"], resource["ARNFormats"][0])
                for resource in document.get("Resources", []) if resource.get("ARNFormats")))
    return dict(sorted(formats.items()))


# --------------------------------------------------------------------------- #
# botocore
# --------------------------------------------------------------------------- #
def _protocols(model: dict) -> set[str]:
    metadata = model["metadata"]
    return set(metadata.get("protocols") or [metadata.get("protocol")])


def request_members(models: dict[str, dict], operation: str) -> dict[str, str]:
    """The top-level members of the operation's request that hold a string (`Name`) or a list of
    strings (`Name[]`), by name, as the vendored path reads them; empty for a request this file does
    not read."""
    found: dict[str, str] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or not READ_PROTOCOLS & _protocols(model):
            continue
        root = model["shapes"][spec["input"]["shape"]]
        for name, member in root.get("members", {}).items():
            if member.get("location") not in BODY_LOCATIONS:
                continue
            shape = model["shapes"][member["shape"]]
            if shape["type"] == "string":
                found.setdefault(name, name)
            elif shape["type"] == "list" and model["shapes"][shape["member"]["shape"]]["type"] == "string":
                found.setdefault(name, name + "[]")
    return found


def account_members(models: dict[str, dict], operation: str) -> list[str]:
    """The top-level members of the operation's request whose shape is an account id, by name."""
    found: set[str] = set()
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or not READ_PROTOCOLS & _protocols(model):
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            shape = model["shapes"][member["shape"]]
            if member.get("location") in BODY_LOCATIONS and shape["type"] == "string" \
                    and shape.get("pattern") in ACCOUNT_ID_PATTERNS:
                found.add(name)
    return sorted(found)


def with_account(resources: list[dict], formats: dict[str, str], accounts: list[str]) -> list[dict]:
    """The resources, each a type named by its variables taking its account from the request's one account id
    member, when it has exactly one and the type's format has an account."""
    if len(accounts) != 1:
        return resources
    return [dict(resource, account=accounts[0]) if "fill" in resource
            and "${Account}" in formats.get(resource["type"], "") else resource for resource in resources]


def kind_of(name: str) -> str:
    """Whether a name ends as a name, an id, or an identifier (which is either), or neither."""
    lower = name.lower()
    if re.search(r"identifiers?$", lower):
        return "identifier"
    if re.search(r"names?$", lower):
        return "name"
    if re.search(r"ids?$", lower):
        return "id"
    return ""


def compatible(member: str, variable: str) -> bool:
    """Whether a member can fill a variable: a name fills a name, an id an id, an identifier either."""
    member_kind, variable_kind = kind_of(member), kind_of(variable)
    return not member_kind or not variable_kind or member_kind == variable_kind or member_kind == "identifier"


def resources_for(operation: str, types: list[str], formats: dict[str, str], members: dict[str, str]) -> list[dict]:
    """The resources the rule finds the operation's request naming, as vendored entries."""
    name = operation.split(":", 1)[1]
    created = stem(CREATING_RE.sub("", name)) if CREATING_RE.match(name) else None
    subject = stem(SUBJECT_RE.sub("", name))
    arns = {member: path for member, path in members.items() if re.search(r"arns?$", member, re.IGNORECASE)}
    names = {member: path for member, path in members.items() if member not in arns}
    by_arn: dict[str, list[str]] = {}
    filled: dict[str, dict[str, str]] = {}
    complete: dict[str, bool] = {}
    for kind in types:
        if kind not in formats:
            continue
        kind_stem = stem(kind)
        for member in sorted(arns):
            by_arn.setdefault(member, []).append(kind)
        # An ARN member named for the type, or for whatever the action acts on, holds it, and a bare name
        # then names something else.
        held = [member for member in arns if stem(member) in ("", "resource")
                or related(stem(member), kind_stem) or kind_stem.startswith(stem(member))]
        variables = [v for v in VARIABLE_RE.findall(formats[kind]) if v not in KNOWN_VARIABLES]
        fill: dict[str, str] = {}
        for variable in sorted(variables, key=lambda v: (kind_of(v) == "", v)):
            choice = _filling(variable, kind_stem, len(variables), {m: p for m, p in names.items() if m not in fill.values()},
                              bare=not held and related(subject, kind_stem))
            if choice is not None:
                fill[variable] = choice
        fill = {variable: names[member] for variable, member in fill.items()}
        if len([path for path in fill.values() if path.endswith("[]")]) > 1:
            fill = {variable: path for variable, path in fill.items() if not path.endswith("[]")}
        if fill or (not held and (not variables or (created is not None and related(created, kind_stem)))):
            filled[kind] = fill
            complete[kind] = len(fill) == len(variables)
    # Types named only through the same members are alternatives the request picks one of: the one it names in
    # full, or none when that is not one type, rather than checking every one.
    by_members: dict[tuple, list[str]] = {}
    for kind, fill in filled.items():
        by_members.setdefault(tuple(sorted(set(fill.values()))), []).append(kind)
    kept = []
    for kinds in by_members.values():
        if len(kinds) == 1 or not by_members_key_is_empty(kinds, filled):
            full = [kind for kind in kinds if complete[kind]]
            kept += kinds if len(kinds) == 1 else (full if len(full) == 1 else [])
    specs = [{"types": kinds, "arn": arns[member]} for member, kinds in sorted(by_arn.items())]
    return specs + [{"type": kind, "fill": dict(sorted(filled[kind].items()))} for kind in types if kind in kept]


RESOURCE_PART_RE = re.compile(r"^arn:\$\{Partition}:[^:]*:[^:]*:[^:]*:([A-Za-z][A-Za-z0-9-]*)(.*)$")


def child_id(child: str, parent: str) -> str | None:
    """The variable a child type's ARN format adds to its parent's: the parent's with its leading resource type word
    replaced and one id appended (`listener/app/.../${ListenerId}` for `loadbalancer/app/...`); None for any other."""
    child_match, parent_match = RESOURCE_PART_RE.match(child), RESOURCE_PART_RE.match(parent)
    if child_match is None or parent_match is None or child_match.group(1) == parent_match.group(1):
        return None
    appended = re.fullmatch(re.escape(parent_match.group(2)) + r"[/:]\$\{(\w+)}", child_match.group(2))
    return appended.group(1) if appended else None


def creates_for(operation: str, types: list[str], formats: dict[str, str], members: dict[str, str]) -> list[dict]:
    """The resources a create of a type its action does not act on makes, named from the ARN of the parent it acts
    on: `CreateListener` acts on a load balancer and makes a listener under it, its id open."""
    name = operation.split(":", 1)[1]
    if not CREATING_RE.match(name):
        return []
    created = stem(CREATING_RE.sub("", name))
    arns = sorted(member for member in members if re.search(r"arns?$", member, re.IGNORECASE))
    found = []
    for child in sorted(formats):
        if child in types:
            continue
        for parent in types:
            appended = child_id(formats[child], formats.get(parent, ""))
            if appended is None or not related(stem(appended), created):
                continue
            for member in arns:
                if stem(member) in ("", "resource") or related(stem(member), stem(parent)) \
                        or stem(parent).startswith(stem(member)):
                    found.append({"type": child, "parent": parent, "arn": members[member]})
    return found


def by_members_key_is_empty(kinds: list[str], filled: dict[str, dict[str, str]]) -> bool:
    """Whether the types are named by no member at all: each a create's own type, all ids minted."""
    return all(not filled[kind] for kind in kinds)


def _filling(variable: str, kind_stem: str, variables: int, names: dict[str, str], bare: bool) -> str | None:
    """The member that fills the variable: one of a compatible kind whose stem relates to the variable's,
    or, for the variable named after the type or a type's only one, a member named after the type, or,
    when `bare`, a bare `Name` or `Id`, of a compatible kind; the closest of them, and a string before a
    list."""
    variable_stem = stem(variable)
    candidates = [member for member in names
                  if stem(member) and related(stem(member), variable_stem) and compatible(member, variable)]
    if not candidates and (related(variable_stem, kind_stem) or variables == 1):
        candidates = [member for member in names if compatible(member, variable)
                      and ((bare and member.lower() in BARE) or (stem(member) and related(stem(member), kind_stem)))]
    if not candidates:
        return None
    return min(candidates, key=lambda member: (stem(member) != variable_stem, names[member].endswith("[]"), len(member), member))


# --------------------------------------------------------------------------- #
# The vendored document
# --------------------------------------------------------------------------- #
def entry_for(operation: str, upstream: dict, formats: dict[str, str], models: dict[str, dict]) -> dict:
    """What the file says for one operation."""
    types = upstream["types"] or []
    members = request_members(models, operation.split(":", 1)[1])
    if operation in CORRECTIONS:
        resources = CORRECTIONS[operation][0]
    else:
        resources = resources_for(operation, types, formats, members)
    resources = with_account(resources, formats, account_members(models, operation.split(":", 1)[1]))
    entry = {"action": upstream["action"], "types": types, "resources": resources}
    creates = creates_for(operation, types, formats, members)
    if creates:
        entry["creates"] = creates
    return entry


def build(documents: list[dict], provenance: str, data: Path, botocore: str) -> dict:
    upstream = checked_actions(documents)
    covered = {operation: value for operation, value in upstream.items() if namespace_of(operation) in NAMESPACES}
    _check_overrides(covered)
    namespaces = {namespace: sorted({client for operation, value in covered.items()
                                     if namespace_of(operation) == namespace for client in value["clients"]})
                  for namespace in sorted(NAMESPACES)}
    formats = arn_formats(documents, set(NAMESPACES))
    unqualified = unqualified_types()
    missing = [f"UNQUALIFIED names {namespace}:{kind}, no resource type with an ARN format of a namespace the file reads"
               for namespace, kinds in unqualified.items() for kind in kinds if kind not in formats.get(namespace, {})]
    if missing:
        raise ValueError("; ".join(missing))
    operations = {}
    for operation, value in sorted(covered.items()):
        models = load_models(data, set(namespaces[namespace_of(operation)]))
        operations[operation] = entry_for(operation, value, formats.get(namespace_of(operation), {}), models)
    return {
        "_source": {
            "generator": "tools/aws/regen_iam_request_resources.py",
            "reference": provenance,
            "botocore": botocore,
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "make iam-request-resources-check re-derives every operation's resources from botocore; "
                    "make iam-request-resources-verify checks every action, type and format against AWS.",
        },
        "namespaces": namespaces,
        "handBuilt": list(HAND_BUILT),
        "notCovered": dict(sorted(NOT_COVERED.items())),
        "otherNamespaces": sorted(namespace for namespace in _with_resource_types(documents)
                                  if namespace not in NAMESPACES and namespace not in HAND_BUILT
                                  and namespace not in NOT_COVERED),
        "arnFormats": formats,
        "unqualified": unqualified,
        "operations": operations,
    }


def unqualified_types() -> dict[str, list[str]]:
    """`UNQUALIFIED` as the types of each namespace."""
    found: dict[str, list[str]] = {}
    for key in sorted(UNQUALIFIED):
        namespace, kind = key.split(":", 1)
        found.setdefault(namespace, []).append(kind)
    return found


def _with_resource_types(documents: list[dict]) -> set[str]:
    """The namespaces with an action that acts on a resource type."""
    return {document["Name"] for document in documents
            if any(action.get("Resources") for action in document.get("Actions", []))}


def _check_overrides(covered: dict[str, dict]) -> None:
    """Every correction names an operation the file reads, and no namespace is in two places."""
    stale = [f"CORRECTIONS names {operation}, which the file does not read" for operation in CORRECTIONS
             if operation not in covered]
    for left, right in (("NAMESPACES", "HAND_BUILT"), ("NAMESPACES", "NOT_COVERED"), ("HAND_BUILT", "NOT_COVERED")):
        stale += [f"{namespace} is in both {left} and {right}"
                  for namespace in sorted(set(globals()[left]) & set(globals()[right]))]
    if stale:
        raise ValueError("; ".join(stale))


def render(document: dict) -> str:
    """The document with one operation to a line."""
    lines = ["{"]
    for key in ("_source", "namespaces", "handBuilt", "notCovered", "otherNamespaces", "arnFormats", "unqualified"):
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
PATH_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\[])?$")


def validate(document: dict) -> list[str]:
    """Problems that make the vendored file unusable, rather than out of date."""
    namespaces = document.get("namespaces")
    operations = document.get("operations")
    formats = document.get("arnFormats")
    if not isinstance(namespaces, dict) or not namespaces:
        return ["namespaces is missing or empty"]
    if not isinstance(operations, dict) or not operations:
        return ["operations is missing or empty"]
    problems: list[str] = []
    if not isinstance(formats, dict) or set(formats) - set(namespaces) or not all(
            isinstance(types, dict) and all(isinstance(arn, str) and arn.startswith("arn:${Partition}:")
                                            for arn in types.values()) for types in formats.values()):
        return ["arnFormats is not an ARN format for each resource type of namespaces the file reads"]
    if list(operations) != sorted(operations):
        problems.append("operations is not sorted")
    unqualified = document.get("unqualified")
    if not isinstance(unqualified, dict) or not all(
            isinstance(kinds, list) and kinds and all(isinstance(kind, str) and kind in formats.get(namespace, {})
                                                      for kind in kinds) for namespace, kinds in unqualified.items()):
        problems.append("unqualified is not resource types with an ARN format of each namespace the file reads")
    for key in ("handBuilt", "otherNamespaces"):
        if not isinstance(document.get(key), list) or not all(isinstance(n, str) and NAMESPACE_RE.match(n)
                                                              for n in document[key]):
            problems.append(f"{key} is not a list of namespaces")
    if not isinstance(document.get("notCovered"), dict) or not all(
            isinstance(reason, str) and reason for reason in document["notCovered"].values()):
        problems.append("notCovered does not give each namespace a reason")
    for operation, entry in operations.items():
        namespace = namespace_of(operation)
        if not OPERATION_RE.match(operation) or namespace not in namespaces:
            problems.append(f"{operation!r} is not an operation of a namespace the file reads")
            continue
        if not isinstance(entry, dict) or not {"action", "types", "resources"} <= set(entry) <= {"action", "types", "resources", "creates"}:
            problems.append(f"{operation} is not an action, its types and its resources")
            continue
        if not isinstance(entry["action"], str) or not OPERATION_RE.match(entry["action"]) \
                or namespace_of(entry["action"]) != namespace:
            problems.append(f"{operation} is checked as no action of its namespace")
        types = entry["types"]
        if not isinstance(types, list) or not all(isinstance(kind, str) for kind in types):
            problems.append(f"{operation} has no list of resource types")
            continue
        for resource in entry["resources"] if isinstance(entry["resources"], list) else [None]:
            problems += _resource_problems(operation, resource, set(types), formats.get(namespace, {}))
        for created in entry.get("creates", []) if isinstance(entry.get("creates", []), list) else [None]:
            problems += _created_problems(operation, created, set(types), formats.get(namespace, {}))
    return problems


def _resource_problems(operation: str, resource: object, types: set[str], formats: dict[str, str]) -> list[str]:
    if not isinstance(resource, dict) or not ({"types", "arn"} <= set(resource) <= {"types", "arn", "before"}
                                              or {"type", "fill"} <= set(resource) <= {"type", "fill", "when", "account"}):
        return [f"{operation} names {resource!r}, which is neither an ARN member nor a type's variables"]
    kinds = resource.get("types") or [resource.get("type")]
    if not all(isinstance(kind, str) and kind in types and kind in formats for kind in kinds):
        return [f"{operation} names {kinds}, not resource types its action acts on with an ARN format"]
    if "arn" in resource:
        if not isinstance(resource["arn"], str) or not PATH_RE.match(resource["arn"]):
            return [f"{operation} reads an ARN at {resource['arn']!r}, which is no member"]
        before = resource.get("before", "#")
        return [] if isinstance(before, str) and len(before) == 1 else [
            f"{operation} reads its ARN before {before!r}, which is no one character"]
    fill = resource["fill"]
    variables = set(VARIABLE_RE.findall(formats[kinds[0]])) - KNOWN_VARIABLES
    if not isinstance(fill, dict) or set(fill) - variables or not all(
            isinstance(path, str) and PATH_RE.match(path) for path in fill.values()):
        return [f"{operation} fills {fill!r}, which are no variables of {kinds[0]}'s ARN by members"]
    if sum(path.endswith("[]") for path in fill.values()) > 1:
        return [f"{operation} fills {kinds[0]} from more than one list"]
    when = resource.get("when", {})
    if not isinstance(when, dict) or not all(
            isinstance(path, str) and PATH_RE.match(path) and not path.endswith("[]") and isinstance(values, list) and values
            and all(value is None or isinstance(value, str) for value in values) for path, values in when.items()):
        return [f"{operation} names {kinds[0]} when {when!r}, which is no member's values"]
    account = resource.get("account")
    if account is not None and (not isinstance(account, str) or not PATH_RE.match(account) or account.endswith("[]")
                                or "${Account}" not in formats[kinds[0]]):
        return [f"{operation} takes {kinds[0]}'s account from {account!r}, which is no member or no account of its ARN"]
    return []


def _created_problems(operation: str, created: object, types: set[str], formats: dict[str, str]) -> list[str]:
    if not isinstance(created, dict) or set(created) != {"type", "parent", "arn"} or not isinstance(created["arn"], str) \
            or not PATH_RE.match(created["arn"]):
        return [f"{operation} creates {created!r}, which is no type made under a parent's ARN member"]
    if created["parent"] not in types or created["type"] not in formats or created["parent"] not in formats \
            or child_id(formats[created["type"]], formats[created["parent"]]) is None:
        return [f"{operation} creates a {created['type']} under a {created['parent']}, no parent of it its action acts on"]
    return []


def derive(document: dict, data: Path) -> list[str]:
    """Where the resources the file gives differ from what botocore gives for its types and formats."""
    problems: list[str] = []
    if document["unqualified"] != unqualified_types():
        problems.append(f"the file checks {json.dumps(document['unqualified'])} without a qualifier, "
                        f"UNQUALIFIED gives {json.dumps(unqualified_types())}")
    for operation, entry in document["operations"].items():
        namespace = namespace_of(operation)
        models = load_models(data, set(document["namespaces"][namespace]))
        expected = entry_for(operation, {"action": entry["action"], "types": entry["types"]},
                             document["arnFormats"].get(namespace, {}), models)
        if expected != entry:
            problems.append(f"{operation}: the file has {json.dumps(entry)}, botocore gives {json.dumps(expected)}")
    return problems


def verify_against(vendored: dict, upstream: dict[str, dict], formats: dict[str, dict[str, str]]) -> tuple[list[str], list[str]]:
    """What the file claims and AWS does not, and the operations AWS has added since."""
    untrue: list[str] = []
    for operation, entry in vendored["operations"].items():
        actual = upstream.get(operation)
        if actual is None:
            untrue.append(f"AWS no longer lists {operation}")
        elif actual["action"] != entry["action"]:
            untrue.append(f"AWS checks {operation} as {actual['action']}, not {entry['action']}")
        elif (actual["types"] or []) != entry["types"]:
            untrue.append(f"AWS gives {entry['action']} the resource types {actual['types']}, not {entry['types']}")
        elif not set(actual["clients"]) <= set(vendored["namespaces"][namespace_of(operation)]):
            untrue.append(f"AWS lists {operation} for {actual['clients']}, which the file does not read")
    for namespace, types in vendored["arnFormats"].items():
        changed = sorted(kind for kind, arn in types.items() if formats.get(namespace, {}).get(kind) != arn)
        if changed:
            untrue.append(f"AWS no longer gives {namespace}'s {', '.join(changed)} the ARN format the file does")
    added = sorted(operation for operation in upstream
                   if namespace_of(operation) in vendored["namespaces"] and operation not in vendored["operations"])
    return untrue, added


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="offline: resources match the pinned botocore")
    parser.add_argument("--verify", action="store_true", help="online: actions, types and formats match AWS")
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
            upstream = checked_actions(documents)
            formats = arn_formats(documents, set(document["namespaces"]))
        except (OSError, ValueError) as e:
            print(f"error: could not read the Service Reference ({e})", file=sys.stderr)
            return 2
        untrue, added = verify_against(document, upstream, formats)
        for claim in untrue:
            print(f"error: {claim}", file=sys.stderr)
        if untrue:
            print(f"error: {len(untrue)} vendored claim(s) do not hold upstream.", file=sys.stderr)
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
