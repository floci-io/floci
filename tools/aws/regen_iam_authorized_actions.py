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
  names without regard to case, so it is not a different action.
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

The endpoint is live rather than a pinned dependency, so a fresh generation is not byte-identical.
As with `regen_iam_action_resources.py`, what must hold is that nothing here is invented:

- `--check` is offline and structural: sorted, every name usable, every action in its own
  namespace and differing from the operation, and the anchor cases still present.
- `--verify` is the real gate and needs the network. Every vendored entry must still be what AWS
  publishes for that operation. An entry AWS has added is reported without failing: being behind
  leaves that operation authorized as its own name, which is where it was before this existed.

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

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-authorized-actions.json"
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

    An operation keeps its own name when the reference gives it no single answer: when it is listed
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


def build(documents: list[dict], provenance: str) -> dict:
    return {
        "_source": {
            "generator": "tools/aws/regen_iam_authorized_actions.py",
            "reference": provenance,
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "make iam-authorized-actions-verify checks every entry against AWS.",
        },
        "authorizedActions": authorized_actions_from(documents),
    }


def render(document: dict) -> str:
    return json.dumps(document, indent=2) + "\n"


def validate(document: dict) -> list[str]:
    """Problems that make the vendored file unusable, rather than merely out of date."""
    problems: list[str] = []
    namespaces = document.get("authorizedActions")
    if not isinstance(namespaces, dict) or not namespaces:
        return ["authorizedActions is missing or empty"]
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
    for (namespace, operation), expected in ANCHORS.items():
        actual = namespaces.get(namespace, {}).get(operation)
        if actual != expected:
            problems.append(f"expected {namespace}:{operation} to be authorized as {expected}, "
                            f"not {actual}")
    return problems


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
    args = parser.parse_args(argv)

    if args.check:
        if not args.output.exists():
            print(f"error: {args.output} does not exist", file=sys.stderr)
            return 1
        document = json.loads(args.output.read_text(encoding="utf-8"))
        problems = validate(document)
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        if problems:
            return 1
        entries = sum(len(operations) for operations in document["authorizedActions"].values())
        print(f"{_shown(args.output)} is well-formed "
              f"({entries} operations over {len(document['authorizedActions'])} namespaces)")
        return 0

    if args.verify:
        if not args.output.exists():
            print(f"error: {args.output} does not exist", file=sys.stderr)
            return 1
        vendored = json.loads(args.output.read_text(encoding="utf-8"))["authorizedActions"]
        try:
            documents, provenance = fetch_references(args.source_dir)
            upstream = authorized_actions_from(documents)
        except (OSError, ValueError) as e:
            # The gate could not run, which CI has to tell apart from having found something
            # invented. ValueError covers a malformed or truncated response as well.
            print(f"error: could not read the Service Reference ({e})", file=sys.stderr)
            return 2
        untrue, added = verify_against(vendored, upstream)
        for claim in untrue:
            print(f"error: {claim}", file=sys.stderr)
        entries = sum(len(operations) for operations in vendored.values())
        if untrue:
            print(f"error: {len(untrue)} vendored claim(s) do not hold upstream; those operations "
                  f"would be authorized as an action AWS does not check.", file=sys.stderr)
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
        document = build(documents, provenance)
    except (OSError, ValueError) as e:
        print(f"error: could not read the Service Reference ({e})", file=sys.stderr)
        return 2
    problems = validate(document)
    for problem in problems:
        print(f"error: {problem}", file=sys.stderr)
    if problems:
        return 1
    args.output.write_text(render(document), encoding="utf-8")
    entries = sum(len(operations) for operations in document["authorizedActions"].values())
    print(f"wrote {_shown(args.output)} ({entries} operations over "
          f"{len(document['authorizedActions'])} namespaces, from {provenance})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
