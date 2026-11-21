#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/iam-authorized-actions.json from AWS's Service Reference.

Under IAM enforcement a request that names its operation, in a Query `Action` or an `X-Amz-Target`
header, is authorized as `<namespace>:<Operation>`, the operation's own name. For most operations
that is the IAM action AWS checks, but not for all: SQS `SendMessageBatch` is authorized as
`sqs:SendMessage`, SNS `PublishBatch` as `sns:Publish`, and `sqs:SendMessageBatch` is not an IAM
action at all. A least-privilege policy AWS honours for a batch call is then denied, and a policy
naming the batch operation grants something AWS would not.

AWS publishes the mapping per service, in the `Operations` list of each Service Reference file:

    https://servicereference.us-east-1.amazonaws.com/            (the index of services)
    https://servicereference.us-east-1.amazonaws.com/v1/<svc>/<svc>.json

Each operation names the actions it is authorized by. Only one shape is vendored: an operation
authorized by exactly one action of its own namespace under another name, where no action of the
operation's own name exists. Everything else stays authorized as the operation's own name, and
each exclusion is deliberate:

- **Several actions.** `CreateAutoScalingGroup` lists `autoscaling:CreateOrUpdateTags` and
  `iam:PassRole` beside its own action, but AWS checks those only when tags or a role are passed,
  which the reference does not say. Requiring them all would deny calls AWS allows.
- **No action.** Elastic Beanstalk lists almost every operation with none, which is missing data,
  not operations that need no permission.
- **Another namespace.** A lone `iam:PassRole` is the side permission with the operation's own
  action missing from the data, and a namespace alias such as `opensearch` to `es` is a question
  of which namespace a service is authorized under, not of the action's name.
- **An action of the operation's own name exists.** Glue lists `CreatePartitionIndex` as
  authorized by `glue:UpdateTable` while also publishing `glue:CreatePartitionIndex`, and no source
  says which one AWS checks. A name differing only in case falls here too: IAM matches action
  names without regard to case, so it is not a different action. The file still records the
  reference's spelling (`spellings`: MemoryDB's CreateACL is `memorydb:CreateAcl`), which a denial
  names the action by.
- **A tagging action alone.** EC2 lists `CreateCapacityReservationDateChangeQuote` as authorized by
  `ec2:CreateTags` only, the tag-on-create permission with the operation's own action missing.

Two kinds of listed action do not count towards "exactly one":

- **A Billing console permission.** Budgets lists `CreateNotification` as authorized by
  `budgets:ModifyBudget` and `aws-portal:ModifyBilling`, and `DescribeBudgets` by
  `budgets:ViewBudget` and `aws-portal:ViewBilling`. The `aws-portal` actions gate the Billing
  console: the Service Authorization Reference has `aws-portal:UpdateConsoleActionSetEnforced`
  choosing "whether existing or fine-grained IAM actions will be used to control authorization to
  Billing, Cost Management, and Account consoles", and the Billing and Cost Management user guide
  says the `aws-portal` namespace "reached the end of standard support on July 2023" and that
  "Programmatic requests to AWS Cost Explorer, AWS Cost and Usage Reports, and AWS Budgets remains
  unaffected".
- **A tagging action beside one other.** Budgets lists `CreateBudget` as authorized by
  `budgets:ModifyBudget` and `budgets:TagResource`, and the tagging action is the tag-on-create
  permission: the Service Authorization Reference has `ModifyBudget` "Grants permission to create
  and modify budgets", adding "Creating a budget with tags also requires the 'budgets:TagResource'
  permission".

Where the reference gives an S3 operation no one answer, the S3 user guide's "Required permissions for
Amazon S3 API operations" does, copied unchanged into tools/aws/sources/s3-required-actions.json by
tools/aws/fetch_s3_required_actions.py (`s3_required`): an operation's one required action
(ListObjectsV2 is `s3:ListBucket`, CopyObject `s3:PutObject`), an "Account-level" row's for S3 Control's
operation of a name S3 shares (`authorizedByModel`: its GetPublicAccessBlock is
`s3:GetAccountPublicAccessBlock`), and an Either row's choice by whether the request names the
parameter its sentences name (`versioned`: GetObject is `s3:GetObjectVersion` with a `versionId`).
The one answer the reference gives an operation several SDK clients list, all their routes vendored,
is the namesake client's (`namesake_by_model`: S3's GetBucketLifecycleConfiguration is
`s3:GetLifecycleConfiguration`, botocore's S3 Control client documenting its own call as
`s3-outposts:GetLifecycleConfiguration`). Where neither says it, `MEASURED_BY_MODEL` and
`MEASURED_VERSIONED` give what the Service Authorization Reference's actions table does, quoted
with its link.

The file also lists, for each credential scope whose REST routes `rest-routes.json` reads (the
scopes it leaves to the registry's rules, `handRouted`, aside), the actions of that namespace the
reference gives no resource type, from its `Actions`. A REST request no rule of `IamActionRegistry`
maps is checked as the operation whose route it matches only when that operation's action is one of
them. Floci checks a REST request on `*` until it reads the resources in its path, and AWS authorizes
such an action on `*` too, so the check is the one AWS makes. An action AWS authorizes on a resource
stays unchecked instead: checked on `*`, a grant scoped to its resource would be refused. These are
read from `Actions`, not `Operations`, because the reference lists the operations of one SDK client
per namespace: OpenSearch's are the `es` client's alone, none of the `opensearch` client's, whose
operations are named after `es` actions all the same.

It lists too, for those scopes, the operations a request on whose route is checked as the action its HTTP
method names, `authorizedByMethod`. API Gateway's management API is authorized by method: its developer
guide has "The API-managing Action expression has the format apigateway:action, where action is one of the
following API Gateway actions: GET, POST, PUT, DELETE, PATCH (to update resources)". The reference lists each
operation the actions of the methods its routes use, in v1 and v2 alike (`TagResource` is a PUT in v1 and a
POST in v2), beside actions AWS checks only when the request carries what they guard: the Service
Authorization Reference has `UpdateRestApiPolicy` as "an additional authorization control for managing an
API due to the sensitive nature of the resource policy", `AddCertificateToDomain` and
`RemoveCertificateFromDomain` alike for mTLS, `iam:PassRole` passes a role, and the developer guide has a
tagged resource's `PATCH` beside the tags' own method: "To update the tags for a resource, the user must have
PATCH permissions for that resource". An operation is listed when, for every route the vendored routes give
it, a shared controller's alternative included, the reference lists the action named after that route's
method, and the one-action shape above does not already name its action. An operation an action of whose
own name exists keeps it, as everywhere in this file: `CreatePortal` lists `apigateway:CreatePortal` beside
`apigateway:POST`.

The endpoint is live rather than a pinned dependency, so a fresh generation is not byte-identical.
As with `regen_iam_action_resources.py`, what must hold is that nothing here is invented:

- `--check` is offline and structural: sorted, every name usable, every action in its own
  namespace and differing from the operation, and the anchor cases still present; the actions
  without resource types are listed for exactly the scopes `rest-routes.json` reads routes for; and
  an operation authorized by method is one a route of its scope serves, named nowhere else here.
- `--verify` is the real gate and needs the network. Every vendored entry must still be what AWS
  publishes for that operation, every action listed without resource types must still have none,
  and every operation authorized by method must still list its routes' methods. An entry AWS has
  added is reported without failing: being behind leaves that operation authorized as its own name,
  or that route unchecked, which is where it was before this existed.

Run from anywhere in the repo:
    python3 tools/aws/regen_iam_authorized_actions.py           # rewrite the vendored file in place
    python3 tools/aws/regen_iam_authorized_actions.py --check   # offline: shape of the vendored file
    python3 tools/aws/regen_iam_authorized_actions.py --verify  # online: nothing in it is invented
    python3 tools/aws/regen_iam_authorized_actions.py --source-dir <dir>  # read saved <svc>.json files
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

from regen_partitions import resolve_botocore_data
from regen_query_services import latest_model_dir, load_model

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-authorized-actions.json"
ROUTES = REPO_ROOT / "src/main/resources/aws/rest-routes.json"
# The S3 user guide's required actions of each S3 operation, as tools/aws/fetch_s3_required_actions.py copies them.
S3_REQUIRED = REPO_ROOT / "tools/aws/sources/s3-required-actions.json"
LEVEL_RE = re.compile(r"^([A-Z][A-Za-z0-9]+)(?: \((Bucket|Account)-level\))?$")
SPECIFYING_RE = re.compile(r"\bspecifying (\w+) in the request")
_S3_ACTIONS_TABLE = "https://docs.aws.amazon.com/service-authorization/latest/reference/list_amazons3.html"
INDEX_URL = "https://servicereference.us-east-1.amazonaws.com/"
FETCH_TIMEOUT_SECONDS = 30
FETCH_WORKERS = 8

# An IAM service namespace, the prefix of an action.
NAMESPACE_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
# An operation or action name.
NAME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9]{0,127}$")
# The tagging actions AWS lists as a side permission of a create-with-tags operation.
TAGGING_ACTION_RE = re.compile(r"^(CreateTags|DeleteTags|TagResource|UntagResource|CreateOrUpdateTags"
                               r"|AddTags\w*|RemoveTags\w*)$")

# The namespaces of console permissions, which an operation's authorized actions may list beside
# the action its API call is authorized as.
CONSOLE_NAMESPACES = {"aws-portal"}

# The cases that justify vendoring this: batch operations authorized as their single-item action,
# and Budgets operations authorized as ModifyBudget or ViewBudget, none of them an IAM action of
# its own.
ANCHORS = {
    ("sqs", "SendMessageBatch"): "sqs:SendMessage",
    ("sqs", "DeleteMessageBatch"): "sqs:DeleteMessage",
    ("sqs", "ChangeMessageVisibilityBatch"): "sqs:ChangeMessageVisibility",
    ("sns", "PublishBatch"): "sns:Publish",
    ("dynamodb", "TransactGetItems"): "dynamodb:GetItem",
    ("budgets", "CreateBudget"): "budgets:ModifyBudget",
    ("budgets", "DescribeBudgets"): "budgets:ViewBudget",
}


# ---------------------------------------------------------------------------------------------------------------------
# What AWS documents where neither the Service Reference nor the S3 user guide's page says it: the page heads S3 Control's
# account-level GetPublicAccessBlock "GetAccessPointPolicy (Account-level)", and gives the version annotation actions
# nowhere. Each entry quotes the Service Authorization Reference's actions table, which links the action to its
# operation's API page. An entry is refused without a link, or when the page gives it (`s3_required`).
# ---------------------------------------------------------------------------------------------------------------------
MEASURED_BY_MODEL: dict[tuple[str, str, str], tuple[str, str]] = {
    ("s3", "s3control", "GetPublicAccessBlock"): (
        "s3:GetAccountPublicAccessBlock",
        f"the Service Authorization Reference's Amazon S3 actions table ({_S3_ACTIONS_TABLE}) gives "
        "s3:GetAccountPublicAccessBlock 'Grants permission to retrieve the PublicAccessBlock configuration for an AWS "
        "account' and links it to https://docs.aws.amazon.com/AmazonS3/latest/API/API_control_GetPublicAccessBlock.html"),
}
MEASURED_VERSIONED: dict[tuple[str, str], tuple[str, str, str, str]] = {
    ("s3", operation): ("versionId", f"s3:{operation}", f"s3:{version}",
                        f"the Service Authorization Reference's Amazon S3 actions table ({_S3_ACTIONS_TABLE}) gives "
                        f"s3:{version} '{sentence}' and links it to "
                        f"https://docs.aws.amazon.com/AmazonS3/latest/API/API_{operation}.html, whose request names "
                        f"the version by versionId")
    for operation, version, sentence in (
        ("GetObjectAnnotation", "GetObjectVersionAnnotation",
         "Grants permission to retrieve an annotation from a specific version of an object"),
        ("PutObjectAnnotation", "PutObjectVersionAnnotation",
         "Grants permission to add or replace an annotation on a specific version of an object"),
        ("DeleteObjectAnnotation", "DeleteObjectVersionAnnotation",
         "Grants permission to delete an annotation from a specific version of an object"),
        ("ListObjectAnnotations", "ListObjectVersionAnnotations",
         "Grants permission to list annotations on a specific version of an object"))
}


def _get_json(url: str):
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=FETCH_TIMEOUT_SECONDS) as response:
        return json.loads(response.read().decode("utf-8"))


def fetch_references(source_dir: Path | None) -> tuple[list[dict], str]:
    """Every service's reference document and a provenance label for where they came from."""
    if source_dir is not None:
        documents = [json.loads(path.read_text(encoding="utf-8"))
                     for path in sorted(source_dir.glob("*.json")) if not path.name.startswith("_")]
        return documents, f"--source-dir {source_dir}"
    index = _get_json(INDEX_URL)
    if not isinstance(index, list) or not index:
        raise ValueError("the Service Reference index must be a non-empty list")
    urls = []
    for entry in index:
        if not isinstance(entry, dict) or "url" not in entry:
            raise ValueError(f"index entry without a 'url' field: {entry!r}")
        urls.append(entry["url"])
    with ThreadPoolExecutor(max_workers=FETCH_WORKERS) as pool:
        documents = list(pool.map(_get_json, urls))
    return documents, INDEX_URL


def authorized_actions_from(documents: list[dict]) -> dict[str, dict[str, str]]:
    """Each namespace's operations authorized by one action of another name, sorted.

    An operation whose own name is no action and that several authorize is the one its name starts
    with as a word, when one does. An operation keeps its own name when the reference gives it no single answer: when it is listed
    for several SDK clients, which the reference gives one action between them although AWS can
    authorize each client's call differently (S3's GetPublicAccessBlock is
    s3:GetBucketPublicAccessBlock, S3 Control's is the account's), or when its name is listed more
    than once with different answers.
    """
    answers: dict[str, dict[str, set[str | None]]] = {}
    for document in documents:
        namespace = document.get("Name")
        if not isinstance(namespace, str) or not NAMESPACE_RE.match(namespace):
            raise ValueError(f"{namespace!r} is not usable as an IAM namespace")
        own_actions = {action["Name"].lower() for action in document.get("Actions", [])
                       if isinstance(action, dict) and isinstance(action.get("Name"), str)}
        for operation in document.get("Operations", []):
            name = operation.get("Name") if isinstance(operation, dict) else None
            if not isinstance(name, str) or not NAME_RE.match(name):
                raise ValueError(f"{namespace}: operation entry without a usable 'Name': {operation!r}")
            authorized = _counted_actions(namespace, _authorized_actions(namespace, operation))
            if len(authorized) > 1 and name.lower() not in own_actions:
                # An operation whose own name is no action and that several authorize is the one of them its
                # name starts with as a word (CloudFront's CreateDistributionWithTags is
                # cloudfront:CreateDistribution), the others being what AWS checks beside it. A plural is no
                # such word: S3's DeleteObjects is checked for each object it deletes.
                prefixed = [(service, action) for service, action in authorized
                            if service == namespace and action != name and name.startswith(action)
                            and (name[len(action)].isupper() or name[len(action)].isdigit())]
                if len(prefixed) == 1:
                    authorized = prefixed
            mapped = None
            if len(authorized) == 1 and len(_sdk_clients(namespace, operation)) <= 1:
                service, action = authorized[0]
                if (service == namespace and NAME_RE.match(action) and action != name
                        and name.lower() not in own_actions and not TAGGING_ACTION_RE.match(action)):
                    mapped = f"{namespace}:{action}"
            answers.setdefault(namespace, {}).setdefault(name, set()).add(mapped)
    found = {namespace: {name: next(iter(mapped)) for name, mapped in sorted(operations.items())
                         if len(mapped) == 1 and None not in mapped}
             for namespace, operations in sorted(answers.items())}
    return {namespace: operations for namespace, operations in found.items() if operations}


def authorized_actions(documents: list[dict], page: dict | None = None, models: dict | None = None,
                       query: dict[str, list[str]] | None = None) -> dict[str, dict[str, str]]:
    """`authorized_actions_from`, an S3 operation the reference gives no one answer taking the one the S3 user guide
    requires (`s3_required`). Where both answer and disagree, the generation fails: which AWS checks is not known."""
    found = {namespace: dict(operations) for namespace, operations in authorized_actions_from(documents).items()}
    actions = {action["Name"].lower(): action["Name"] for document in documents if document.get("Name") == "s3"
               for action in document.get("Actions", [])}
    conflicts = []
    for operation, action in s3_sections(documents, page, models, query)[0].items():
        listed = found.get("s3", {}).get(operation)
        if listed is not None:
            if listed != action:
                conflicts.append(f"s3:{operation}: the reference gives {listed}, the S3 user guide {action}")
        elif action.lower() != f"s3:{operation}".lower() and action.split(":", 1)[1].lower() in actions:
            found.setdefault("s3", {})[operation] = action
    if conflicts:
        raise ValueError("; ".join(conflicts))
    return {namespace: dict(sorted(operations.items())) for namespace, operations in sorted(found.items())
            if operations}


def s3_query_parameters(data: Path | None = None) -> dict[str, list[str]]:
    """The query parameters botocore's S3 model gives each operation's request, from the botocore `regen_rest_routes`
    reads unless `data` names one."""
    model = load_model(latest_model_dir(data or resolve_botocore_data(None)[0], "s3"))
    shapes = model.get("shapes", {})
    found = {}
    for operation, spec in model["operations"].items():
        members = shapes.get(spec.get("input", {}).get("shape", ""), {}).get("members", {})
        found[operation] = sorted(member.get("locationName", name) for name, member in members.items()
                                  if member.get("location") == "querystring")
    return found


def s3_sections(documents: list[dict], page: dict | None, models: dict | None,
                query: dict[str, list[str]] | None = None) -> tuple[dict[str, str], dict, dict]:
    """`s3_required` for references that include S3's, nothing for any others (a test's, or a partial set); `query` is
    `s3_query_parameters()` unless given."""
    if not any(document.get("Name") == "s3" for document in documents):
        return {}, {}, {}
    return s3_required(page or {}, models or {}, s3_query_parameters() if query is None else query)


def s3_required(page: dict, models: dict, query: dict[str, list[str]]) -> tuple[dict[str, str], dict, dict]:
    """What the S3 user guide's required-permissions page gives S3's operations (`page`, its vendored `operations`),
    read against the vendored routes' `models` and botocore's S3 `query` parameters (`s3_query_parameters`): each one's
    single required s3 action, and an Either row's choice by the request parameter its sentences name (`versioned`),
    when botocore gives that parameter in the operation's query and the operation has no single required action of its
    own (CopyObject's Either is about its copy source; DeleteObjects names its versions in its body). An operation both
    S3 and S3 Control have takes the page's action for one model's requests alone (`authorizedByModel`): an
    Account-level row's for S3 Control's, any other row's for S3's, the page being S3's guide (S3 Control's
    GetBucketReplication is an S3 on Outposts call). An Account-level row whose S3 Control route names a resource in
    its path is refused. A level on an operation one model has tells nothing apart, so the page's
    "GetAccessPointPolicy (Account-level)" is not read; nor is a heading naming several operations
    ("CreateStorageLensGroup, TagResource"). `MEASURED_BY_MODEL` and `MEASURED_VERSIONED` add what the page does not
    say."""
    s3_ops = models.get("s3", {}).get("operations", {})
    control_ops = models.get("s3control", {}).get("operations", {})
    actions: dict[str, str] = {}
    by_model: dict[str, str] = {}
    s3_model: dict[str, str] = {}
    versioned: dict[str, dict] = {}
    for heading, entry in page.items():
        head = LEVEL_RE.match(heading)
        if head is None:
            continue
        operation, level = head.groups()
        singles = [action for action in entry.get("required", []) if action.startswith("s3:")]
        if level and not (operation in s3_ops and operation in control_ops):
            continue
        if level == "Account":
            if "{" in control_ops[operation]["requestUri"]:
                raise ValueError(f"the S3 user guide heads {operation} Account-level, but S3 Control's route "
                                 f"{control_ops[operation]['requestUri']} names a resource")
            if len(singles) == 1:
                by_model[operation] = singles[0]
            continue
        if len(singles) == 1 and operation in s3_ops and operation in control_ops:
            if singles[0].lower() != f"s3:{operation}".lower():
                s3_model[operation] = singles[0]
        elif len(singles) == 1:
            actions[operation] = singles[0]
        elif not singles and "either" in entry:
            sentences = entry["either"]["sentences"]
            named = {match.group(1) for match in map(SPECIFYING_RE.search, sentences.values()) if match}
            without = [action for action, sentence in sentences.items() if "without specifying" in sentence]
            with_ = [action for action, sentence in sentences.items() if "without specifying" not in sentence]
            if (len(named) == 1 and len(without) == 1 and len(with_) == 1
                    and next(iter(named)) in query.get(operation, [])):
                versioned[operation] = {"parameter": next(iter(named)), "without": without[0], "with": with_[0]}
    for (namespace, model, operation), (action, evidence) in MEASURED_BY_MODEL.items():
        if "https://" not in evidence:
            raise ValueError(f"MEASURED_BY_MODEL gives {model}:{operation} with no link to its evidence")
        if operation in by_model:
            raise ValueError(f"MEASURED_BY_MODEL gives {model}:{operation}, which the S3 user guide gives: take it out")
        by_model[operation] = action
    for (namespace, operation), (parameter, without, with_, evidence) in MEASURED_VERSIONED.items():
        if "https://" not in evidence:
            raise ValueError(f"MEASURED_VERSIONED gives {operation} with no link to its evidence")
        if operation in versioned:
            raise ValueError(f"MEASURED_VERSIONED gives {operation}, which the S3 user guide gives: take it out")
        if parameter not in query.get(operation, []):
            raise ValueError(f"MEASURED_VERSIONED gives {operation} a choice by {parameter}, which botocore does not "
                             f"give in its query")
        versioned[operation] = {"parameter": parameter, "without": without, "with": with_}
    models_actions = {model: dict(sorted(operations.items()))
                      for model, operations in (("s3", s3_model), ("s3control", by_model)) if operations}
    return (dict(sorted(actions.items())),
            {"s3": models_actions} if models_actions else {},
            {"s3": dict(sorted(versioned.items()))} if versioned else {})


def namesake_by_model(documents: list[dict], routed: set[str]) -> dict[str, dict[str, dict[str, str]]]:
    """The one answer the reference gives an operation it lists for several SDK clients, for the client named as the
    namespace, when every such client's routes are vendored (`routed`, the models of `rest-routes.json`) so a request's
    route tells which client's it is: S3's GetBucketLifecycleConfiguration is s3:GetLifecycleConfiguration, botocore's
    S3 Control client documenting its own call as s3-outposts:GetLifecycleConfiguration. `authorized_actions_from`
    gives such an operation no answer, AWS authorizing each client's call its own way."""
    found: dict[str, dict[str, dict[str, str]]] = {}
    for document in documents:
        namespace = document.get("Name")
        own_actions = {action["Name"].lower() for action in document.get("Actions", []) if isinstance(action, dict)}
        for operation in document.get("Operations", []):
            name = operation.get("Name")
            clients = _sdk_clients(namespace, operation)
            if len(clients) < 2 or namespace not in clients or not clients <= routed or name.lower() in own_actions:
                continue
            authorized = [(service, action) for service, action
                          in _counted_actions(namespace, _authorized_actions(namespace, operation))
                          if service == namespace]
            if len({action for _, action in authorized}) == 1:
                action = authorized[0][1]
                if NAME_RE.match(action) and action != name and not TAGGING_ACTION_RE.match(action):
                    found.setdefault(namespace, {}).setdefault(namespace, {})[name] = f"{namespace}:{action}"
    return {namespace: {client: dict(sorted(operations.items())) for client, operations in sorted(clients.items())}
            for namespace, clients in sorted(found.items())}


def spellings(documents: list[dict]) -> dict[str, dict[str, str]]:
    """The reference's spelling of each operation's action that differs from the operation's name only in case:
    MemoryDB's CreateACL is memorydb:CreateAcl, S3's PutBucketCors s3:PutBucketCORS."""
    found: dict[str, dict[str, str]] = {}
    for document in documents:
        namespace = document.get("Name")
        actions = {action["Name"].lower(): action["Name"] for action in document.get("Actions", [])
                   if isinstance(action, dict) and isinstance(action.get("Name"), str)}
        for operation in document.get("Operations", []):
            name = operation.get("Name")
            spelled = actions.get(name.lower()) if isinstance(name, str) else None
            if spelled is not None and spelled != name:
                found.setdefault(namespace, {})[name] = f"{namespace}:{spelled}"
    return {namespace: dict(sorted(operations.items())) for namespace, operations in sorted(found.items())}


def _merged_by_model(*sources: dict) -> dict:
    merged: dict = {}
    for source in sources:
        for namespace, clients in source.items():
            for client, operations in clients.items():
                merged.setdefault(namespace, {}).setdefault(client, {}).update(operations)
    return {namespace: {client: dict(sorted(operations.items())) for client, operations in sorted(clients.items())}
            for namespace, clients in sorted(merged.items())}


def load_s3_required(source: Path = S3_REQUIRED) -> dict:
    """The vendored S3 user guide page's operations, none when the file is missing."""
    return json.loads(source.read_text(encoding="utf-8"))["operations"] if source.exists() else {}


def _authorized_actions(namespace: str, operation: dict) -> list[tuple[str, str]]:
    """The operation's authorized actions as (service, name) pairs. A record of the wrong shape is
    a malformed response, not an operation to leave out: skipping it would vendor a map that
    silently lacks the operation."""
    authorized = operation.get("AuthorizedActions") or []
    if not isinstance(authorized, list) or not all(
            isinstance(entry, dict) and isinstance(entry.get("Service"), str)
            and isinstance(entry.get("Name"), str) for entry in authorized):
        raise ValueError(f"{namespace}:{operation['Name']}: 'AuthorizedActions' is not a list of "
                         f"objects with a string 'Service' and 'Name': {authorized!r}")
    return [(entry["Service"], entry["Name"]) for entry in authorized]


def _counted_actions(namespace: str, authorized: list[tuple[str, str]]) -> list[tuple[str, str]]:
    """The authorized actions that count towards the operation's one action: none of a console's,
    and, beside one other action, none of the namespace's tagging actions."""
    counted = [(service, action) for service, action in authorized if service not in CONSOLE_NAMESPACES]
    untagged = [(service, action) for service, action in counted
                if service != namespace or not TAGGING_ACTION_RE.match(action)]
    return untagged if len(untagged) == 1 else counted


def _sdk_clients(namespace: str, operation: dict) -> set[str]:
    """The SDK clients the operation is listed for."""
    sdks = operation.get("SDK") or []
    if not isinstance(sdks, list) or not all(
            isinstance(sdk, dict) and isinstance(sdk.get("Name"), str) for sdk in sdks):
        raise ValueError(f"{namespace}:{operation['Name']}: 'SDK' is not a list of objects with a "
                         f"string 'Name': {sdks!r}")
    return {sdk["Name"] for sdk in sdks}


def routed_namespaces(routes: dict) -> list[str]:
    """The credential scopes whose REST routes the vendored routes read: every model's but those the registry's
    rules authorize alone."""
    hand_routed = routes.get("handRouted") or {}
    return sorted({model["credentialScope"] for model in (routes.get("models") or {}).values()
                   if model["credentialScope"] not in hand_routed})


def actions_without_resource_types(documents: list[dict], namespaces: list[str]) -> dict[str, list[str]]:
    """Each of the namespaces' actions the reference gives no resource type, sorted."""
    by_name = {document.get("Name"): document for document in documents}
    missing = [namespace for namespace in namespaces if namespace not in by_name]
    if missing:
        raise ValueError(f"the Service Reference has no namespace {', '.join(missing)}, whose REST routes "
                         f"rest-routes.json reads")
    found = {}
    for namespace in namespaces:
        actions = by_name[namespace].get("Actions") or []
        if not all(isinstance(action, dict) and isinstance(action.get("Name"), str) for action in actions):
            raise ValueError(f"{namespace}: 'Actions' is not a list of objects with a string 'Name'")
        found[namespace] = sorted(action["Name"] for action in actions if not action.get("Resources"))
    return found


def route_methods(routes: dict) -> dict[str, dict[str, list[str]]]:
    """Each routed scope's operations, with the HTTP methods of every route the vendored routes give them, an
    alternative's own method included."""
    routed = set(routed_namespaces(routes))
    methods: dict[str, dict[str, set[str]]] = {}
    for model in (routes.get("models") or {}).values():
        scope = model["credentialScope"]
        if scope not in routed:
            continue
        for operation, route in (model.get("operations") or {}).items():
            found = methods.setdefault(scope, {}).setdefault(operation, set())
            found.add(route["method"])
            found.update(alternative.get("method", route["method"]) for alternative in route.get("alternatives", []))
    return {scope: {operation: sorted(found) for operation, found in sorted(operations.items())}
            for scope, operations in sorted(methods.items())}


def authorized_by_method(documents: list[dict], methods: dict[str, dict[str, list[str]]],
                         renamed: dict[str, dict[str, str]]) -> dict[str, list[str]]:
    """Each routed scope's operations a request on whose route is checked as the action its method names: those
    the reference lists, every time it lists them, with the action of each of their routes' methods, which the
    one-action shape does not already name and no action of their own name settles. Scopes with none are left
    out."""
    by_name = {document.get("Name"): document for document in documents}
    found = {}
    for scope, operations in methods.items():
        document = by_name.get(scope)
        if document is None:
            raise ValueError(f"the Service Reference has no namespace {scope}, whose REST routes rest-routes.json "
                             f"reads")
        own_actions = {action["Name"].lower() for action in document.get("Actions", [])
                       if isinstance(action, dict) and isinstance(action.get("Name"), str)}
        listed: dict[str, list[set[str]]] = {}
        for operation in document.get("Operations", []):
            name = operation.get("Name") if isinstance(operation, dict) else None
            if not isinstance(name, str) or not NAME_RE.match(name):
                raise ValueError(f"{scope}: operation entry without a usable 'Name': {operation!r}")
            listed.setdefault(name, []).append({action for service, action in _authorized_actions(scope, operation)
                                                if service == scope})
        chosen = [operation for operation, route_methods_of in operations.items()
                  if operation in listed and operation not in renamed.get(scope, {})
                  and operation.lower() not in own_actions
                  and all(set(route_methods_of) <= actions for actions in listed[operation])]
        if chosen:
            found[scope] = sorted(chosen)
    return found


def vendored_sections(documents: list[dict], page: dict | None, models: dict | None,
                      query: dict[str, list[str]] | None, renamed: dict[str, dict[str, str]]) -> dict[str, dict]:
    """The `authorizedByModel`, `versioned` and `spellings` sections, given the operations `authorized_actions` renames
    (`renamed`), whose spellings the renames already give."""
    _, by_model, versioned = s3_sections(documents, page, models, query)
    return {
        "authorizedByModel": _merged_by_model(by_model, namesake_by_model(documents, set(models or {}))),
        "versioned": versioned,
        "spellings": {namespace: {operation: action for operation, action in operations.items()
                                  if operation not in renamed.get(namespace, {})}
                      for namespace, operations in spellings(documents).items()},
    }


def build(documents: list[dict], provenance: str, routed: list[str],
          methods: dict[str, dict[str, list[str]]] | None = None, page: dict | None = None,
          models: dict | None = None, query: dict[str, list[str]] | None = None) -> dict:
    """The vendored document; `page` is the S3 user guide's operations (`load_s3_required`), `models` the vendored
    routes' models, `query` botocore's S3 query parameters (`s3_query_parameters`, unless given)."""
    if query is None and any(document.get("Name") == "s3" for document in documents):
        query = s3_query_parameters()
    renamed = authorized_actions(documents, page, models, query)
    return {
        "_source": {
            "generator": "tools/aws/regen_iam_authorized_actions.py",
            "reference": provenance,
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "make iam-authorized-actions-verify checks every entry against AWS.",
        },
        "authorizedActions": renamed,
        "actionsWithoutResourceTypes": actions_without_resource_types(documents, routed),
        "authorizedByMethod": authorized_by_method(documents, methods or {}, renamed),
        **vendored_sections(documents, page, models, query, renamed),
    }


def render(document: dict) -> str:
    return json.dumps(document, indent=2) + "\n"


def validate(document: dict, routed: list[str], methods: dict[str, dict[str, list[str]]] | None = None) -> list[str]:
    """Problems that make the vendored file unusable, rather than merely out of date. With the routed scopes'
    route methods, an operation authorized by method must be one of their operations."""
    problems = _unscoped_problems(document.get("actionsWithoutResourceTypes"), routed)
    namespaces = document.get("authorizedActions")
    if not isinstance(namespaces, dict) or not namespaces:
        return ["authorizedActions is missing or empty"]
    problems += _by_method_problems(document.get("authorizedByMethod"), routed, methods, namespaces)
    if list(namespaces) != sorted(namespaces):
        problems.append("authorizedActions is not sorted by namespace")
    for namespace, operations in namespaces.items():
        if not NAMESPACE_RE.match(namespace):
            problems.append(f"{namespace!r} is not usable as an IAM namespace")
        if not isinstance(operations, dict) or not operations:
            problems.append(f"{namespace!r} carries no operations")
            continue
        if list(operations) != sorted(operations):
            problems.append(f"{namespace!r} lists its operations out of order")
        for operation, action in operations.items():
            if not NAME_RE.match(operation):
                problems.append(f"{namespace}: {operation!r} is not usable as an operation name")
            prefix, _, name = action.partition(":") if isinstance(action, str) else ("", "", "")
            if prefix != namespace or not NAME_RE.match(name):
                problems.append(f"{namespace}:{operation} maps to {action!r}, "
                                f"which is not an action of {namespace}")
            elif name.lower() == operation.lower():
                problems.append(f"{namespace}:{operation} maps to its own name")
    problems += _section_problems(document)
    for (namespace, operation), expected in ANCHORS.items():
        actual = namespaces.get(namespace, {}).get(operation)
        if actual != expected:
            problems.append(f"expected {namespace}:{operation} to be authorized as {expected}, "
                            f"not {actual}")
    return problems


def _section_problems(document: dict) -> list[str]:
    """Problems with the shape of `authorizedByModel`, `versioned` and `spellings`."""
    problems = []
    def action_of(namespace: str, action: object) -> bool:
        prefix, _, name = action.partition(":") if isinstance(action, str) else ("", "", "")
        return prefix == namespace and bool(NAME_RE.match(name))
    for namespace, models in (document.get("authorizedByModel") or {}).items():
        for model, operations in (models.items() if isinstance(models, dict) else []):
            for operation, action in (operations.items() if isinstance(operations, dict) else []):
                if not NAME_RE.match(operation) or not action_of(namespace, action):
                    problems.append(f"authorizedByModel {namespace} {model}:{operation} maps to {action!r}, "
                                    f"which is not an action of {namespace}")
    for namespace, operations in (document.get("versioned") or {}).items():
        for operation, choice in (operations.items() if isinstance(operations, dict) else []):
            if not isinstance(choice, dict) or set(choice) != {"parameter", "without", "with"} \
                    or not NAME_RE.match(str(choice.get("parameter"))) \
                    or not action_of(namespace, choice.get("without")) or not action_of(namespace, choice.get("with")):
                problems.append(f"versioned {namespace}:{operation} is not a parameter and two actions of {namespace}")
    for namespace, operations in (document.get("spellings") or {}).items():
        for operation, action in (operations.items() if isinstance(operations, dict) else []):
            if not action_of(namespace, action) or action.split(":", 1)[1].lower() != operation.lower() \
                    or action.split(":", 1)[1] == operation:
                problems.append(f"spellings {namespace}:{operation} gives {action!r}, not its name spelled otherwise")
    return problems


def _unscoped_problems(unscoped: object, routed: list[str]) -> list[str]:
    """Problems with the actions without resource types: one sorted list of usable names for each scope whose
    routes are read, and for no other."""
    if not isinstance(unscoped, dict):
        return ["actionsWithoutResourceTypes is missing"]
    problems = []
    if list(unscoped) != routed:
        problems.append(f"actionsWithoutResourceTypes lists {list(unscoped)}, not the scopes rest-routes.json reads "
                        f"routes for, {routed}")
    for namespace, actions in unscoped.items():
        if not isinstance(actions, list) or not all(isinstance(action, str) and NAME_RE.match(action)
                                                    for action in actions):
            problems.append(f"actionsWithoutResourceTypes.{namespace} is not a list of action names")
        elif actions != sorted(set(actions)):
            problems.append(f"actionsWithoutResourceTypes.{namespace} is not sorted, or repeats an action")
    return problems


def _by_method_problems(by_method: object, routed: list[str], methods: dict[str, dict[str, list[str]]] | None,
                        renamed: dict) -> list[str]:
    """Problems with the operations authorized by method: for each routed scope listing any, a sorted list of
    usable names, none of them also given an action of another name, and each, when the routes are known, an
    operation a route of its scope serves."""
    if not isinstance(by_method, dict):
        return ["authorizedByMethod is missing"]
    problems = []
    if list(by_method) != sorted(by_method):
        problems.append("authorizedByMethod is not sorted by scope")
    for scope, operations in by_method.items():
        if scope not in routed:
            problems.append(f"authorizedByMethod lists {scope}, a scope rest-routes.json reads no routes for")
        if not isinstance(operations, list) or not operations or not all(
                isinstance(operation, str) and NAME_RE.match(operation) for operation in operations):
            problems.append(f"authorizedByMethod.{scope} is not a list of operation names")
            continue
        if operations != sorted(set(operations)):
            problems.append(f"authorizedByMethod.{scope} is not sorted, or repeats an operation")
        for operation in operations:
            if operation in (renamed.get(scope) or {}):
                problems.append(f"{scope}:{operation} is authorized by method and as {renamed[scope][operation]}")
            if methods is not None and operation not in methods.get(scope, {}):
                problems.append(f"authorizedByMethod lists {scope}:{operation}, which no route of {scope} serves")
    return problems


def verify_by_method_against(vendored: dict[str, list[str]],
                             upstream: dict[str, list[str]]) -> tuple[list[str], list[str]]:
    """The operations the vendored file authorizes by method that AWS no longer lists with their routes' methods,
    and those it has since added.

    A listed operation AWS no longer authorizes by its method is a failure: its request would be checked as an
    action AWS does not check for it. One AWS has added stays authorized as its own name until the next sync.
    """
    untrue = [f"AWS no longer authorizes {scope}:{operation} by the methods of its routes"
              for scope, operations in vendored.items() for operation in operations
              if operation not in upstream.get(scope, [])]
    added = sorted(f"{scope}:{operation} by method" for scope, operations in upstream.items()
                   for operation in operations if operation not in vendored.get(scope, []))
    return untrue, added


def verify_against(vendored: dict[str, dict[str, str]],
                   upstream: dict[str, dict[str, str]]) -> tuple[list[str], list[str]]:
    """What the vendored file claims and AWS does not, and what AWS has since added.

    A claim AWS no longer makes is a failure: the operation would be authorized as an action AWS
    does not check for it. An entry AWS has added only means the file is behind.
    """
    untrue: list[str] = []
    added: list[str] = []
    for namespace, operations in vendored.items():
        for operation, action in operations.items():
            actual = upstream.get(namespace, {}).get(operation)
            if actual != action:
                untrue.append(f"AWS does not authorize {namespace}:{operation} as {action}"
                              + (f" but as {actual}" if actual else ""))
    for namespace, operations in upstream.items():
        for operation, action in operations.items():
            if operation not in vendored.get(namespace, {}):
                added.append(f"{namespace}:{operation} -> {action}")
    return untrue, sorted(added)


def verify_unscoped_against(vendored: dict[str, list[str]],
                            upstream: dict[str, list[str]]) -> tuple[list[str], list[str]]:
    """The actions the vendored file lists without resource types that AWS gives one or no longer has, and those
    AWS has since added without one.

    A listed action AWS gives a resource type is a failure: a request would be checked on `*` where AWS checks
    the resource. One AWS has added only leaves its route unchecked until the next sync.
    """
    untrue = [f"AWS gives {namespace}:{action} a resource type, or no longer has it"
              for namespace, actions in vendored.items() for action in actions
              if action not in upstream.get(namespace, [])]
    added = sorted(f"{namespace}:{action}" for namespace, actions in upstream.items() for action in actions
                   if action not in vendored.get(namespace, []))
    return untrue, added


def _unscoped_count(document: dict) -> str:
    """How many actions without resource types the document lists, over how many scopes."""
    unscoped = document["actionsWithoutResourceTypes"]
    return (f"{sum(len(actions) for actions in unscoped.values())} actions without resource types over "
            f"{len(unscoped)} routed scopes")


def _routes(routes: Path) -> dict:
    """The vendored REST routes."""
    return json.loads(routes.read_text(encoding="utf-8"))


def _by_method_count(document: dict) -> str:
    """How many operations the document authorizes by method, over how many scopes."""
    by_method = document["authorizedByMethod"]
    return (f"{sum(len(operations) for operations in by_method.values())} operations authorized by method over "
            f"{len(by_method)} scopes")


def _shown(path: Path) -> Path:
    """The path relative to the repository when it is inside it, as the messages print it."""
    try:
        return path.resolve().relative_to(REPO_ROOT)
    except ValueError:
        return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true",
                        help="offline: validate the shape of the vendored file")
    parser.add_argument("--verify", action="store_true",
                        help="online: nothing in the vendored file is invented")
    parser.add_argument("--source-dir", type=Path, default=None,
                        help="read saved <service>.json reference files instead of fetching them")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    parser.add_argument("--routes", type=Path, default=ROUTES, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    if args.check:
        if not args.output.exists():
            print(f"error: {args.output} does not exist", file=sys.stderr)
            return 1
        document = json.loads(args.output.read_text(encoding="utf-8"))
        routes = _routes(args.routes)
        problems = validate(document, routed_namespaces(routes), route_methods(routes))
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        if problems:
            return 1
        entries = sum(len(operations) for operations in document["authorizedActions"].values())
        print(f"{_shown(args.output)} is well-formed "
              f"({entries} operations over {len(document['authorizedActions'])} namespaces, "
              f"{_unscoped_count(document)}, {_by_method_count(document)})")
        return 0

    if args.verify:
        if not args.output.exists():
            print(f"error: {args.output} does not exist", file=sys.stderr)
            return 1
        vendored_document = json.loads(args.output.read_text(encoding="utf-8"))
        vendored = vendored_document["authorizedActions"]
        unscoped = vendored_document.get("actionsWithoutResourceTypes") or {}
        by_method = vendored_document.get("authorizedByMethod") or {}
        try:
            documents, provenance = fetch_references(args.source_dir)
            page = load_s3_required()
            if args.source_dir is None:
                # The page the vendored copy was taken from must still say what the copy does.
                import fetch_s3_required_actions
                live = fetch_s3_required_actions.read_page(fetch_s3_required_actions.fetch_url(
                    fetch_s3_required_actions.PAGE))
                if live != page:
                    print(f"error: {S3_REQUIRED.relative_to(REPO_ROOT)} no longer says what "
                          f"{fetch_s3_required_actions.PAGE} does (run tools/aws/fetch_s3_required_actions.py)",
                          file=sys.stderr)
                    return 1
            models = _routes(args.routes)["models"]
            query = s3_query_parameters()
            upstream = authorized_actions(documents, page, models, query)
            upstream_unscoped = actions_without_resource_types(documents, list(unscoped))
            methods = route_methods(_routes(args.routes)) if by_method else {}
            upstream_by_method = authorized_by_method(
                documents, {scope: methods.get(scope, {}) for scope in by_method}, upstream)
        except (OSError, ValueError) as e:
            # The gate could not run, which CI has to tell apart from having found something
            # invented. ValueError covers a malformed or truncated response as well.
            print(f"error: could not read the Service Reference ({e})", file=sys.stderr)
            return 2
        untrue, added = verify_against(vendored, upstream)
        for section, value in vendored_sections(documents, page, models, query, upstream).items():
            if vendored_document.get(section, {}) != value:
                untrue.append(f"{section} differs from what AWS gives: expected {json.dumps(value)}")
        untrue_unscoped, added_unscoped = verify_unscoped_against(unscoped, upstream_unscoped)
        untrue_by_method, added_by_method = verify_by_method_against(by_method, upstream_by_method)
        untrue += untrue_unscoped + untrue_by_method
        added += added_unscoped + added_by_method
        for claim in untrue:
            print(f"error: {claim}", file=sys.stderr)
        entries = sum(len(operations) for operations in vendored.values())
        if untrue:
            print(f"error: {len(untrue)} vendored claim(s) do not hold upstream; those operations "
                  f"would be authorized as an action AWS does not check, or on a resource it does not.",
                  file=sys.stderr)
            return 1
        if added:
            print(f"{_shown(args.output)}: all {entries} entries verified. AWS has "
                  f"since added {len(added)}, so the file is incomplete but not wrong (run without "
                  f"--verify to refresh): {', '.join(added[:10])}{'...' if len(added) > 10 else ''}")
        else:
            print(f"{_shown(args.output)}: all {entries} entries verified against "
                  f"{provenance}, nothing added upstream.")
        return 0

    try:
        documents, provenance = fetch_references(args.source_dir)
        routes = _routes(args.routes)
        routed = routed_namespaces(routes)
        methods = route_methods(routes)
        document = build(documents, provenance, routed, methods, load_s3_required(), routes["models"])
    except (OSError, ValueError) as e:
        print(f"error: could not read the Service Reference ({e})", file=sys.stderr)
        return 2
    problems = validate(document, routed, methods)
    for problem in problems:
        print(f"error: {problem}", file=sys.stderr)
    if problems:
        return 1
    args.output.write_text(render(document), encoding="utf-8")
    entries = sum(len(operations) for operations in document["authorizedActions"].values())
    print(f"wrote {_shown(args.output)} ({entries} operations over "
          f"{len(document['authorizedActions'])} namespaces, {_unscoped_count(document)}, "
          f"{_by_method_count(document)}, from {provenance})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
