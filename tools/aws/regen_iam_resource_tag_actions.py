#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/iam-resource-tag-actions.json from AWS's Service Reference.

An IAM policy can condition a request on the tags already on the resource it targets through
``aws:ResourceTag/${TagKey}``. That key is not available for every action: the Service Reference
lists it on the resource type an action acts on, so an action whose service does not tag that
resource has no such key. Writing the action list by hand would silently drift, and a batch
operation is not an IAM action at all: SQS ``SendMessageBatch`` authorizes as ``sqs:SendMessage``
and SNS ``PublishBatch`` as ``sns:Publish``.

AWS publishes the answer per service:

    https://servicereference.us-east-1.amazonaws.com/v1/<service>/<service>.json

Each resource type carries the condition keys it supports, and each action names the resource types
it acts on. An action is in this table when it acts on a resource type whose ``ConditionKeys``
contain ``aws:ResourceTag/${TagKey}``.

The ``namespace`` keys are the vendored services. More services can be added when Floci resolves
their resource tags; the Java side asks whether an action is in the table, not which service it
belongs to.

The reference is live rather than a pinned dependency, so a fresh generation is not byte-identical.
The gates split the same way as the other IAM generators:

- ``--check`` is offline and structural. The file must be sorted, every entry ``<namespace>:<Action>``,
  and the actions that justify the table must still be present (``sqs:SendMessage``,
  ``sqs:ReceiveMessage``, ``sns:Publish``) while the batch names AWS does not publish as actions
  must stay out.
- ``--verify`` needs the network. Every vendored action must still act on a resource type carrying
  ``aws:ResourceTag``; an action AWS has added is reported without failing, because a missing entry
  means Floci leaves that action's condition key absent, which is where it was before this table.

Run from anywhere in the repo:
    python3 tools/aws/regen_iam_resource_tag_actions.py           # rewrite the vendored file
    python3 tools/aws/regen_iam_resource_tag_actions.py --check   # offline: shape of the file
    python3 tools/aws/regen_iam_resource_tag_actions.py --verify  # online: nothing invented
    python3 tools/aws/regen_iam_resource_tag_actions.py --source-dir <dir>
        # read <namespace>.json from <dir> instead of AWS
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-resource-tag-actions.json"
REFERENCE_URL_TEMPLATE = "https://servicereference.us-east-1.amazonaws.com/v1/{namespace}/{namespace}.json"
RESOURCE_TAG_KEY = "aws:ResourceTag/${TagKey}"
FETCH_TIMEOUT_SECONDS = 30

# The services whose resource tags Floci resolves today. Add a namespace here when its tag lookup
# lands; the generated file and the Java reader carry the same set.
NAMESPACES = ("sns", "sqs")

NAMESPACE_RE = re.compile(r"^[a-z0-9-]{1,64}$")
ACTION_RE = re.compile(r"^[A-Z][A-Za-z0-9]{0,127}$")

# Actions the table must still carry, and batch names that are operations but not IAM actions.
REQUIRED_ACTIONS = ("sqs:ReceiveMessage", "sqs:SendMessage", "sns:Publish")
FORBIDDEN_ACTIONS = ("sqs:SendMessageBatch", "sns:PublishBatch")


def fetch_reference(namespace: str, source_dir: Path | None) -> dict:
    """One service's reference document."""
    if source_dir is not None:
        path = source_dir / f"{namespace}.json"
        return json.loads(path.read_text(encoding="utf-8"))
    url = REFERENCE_URL_TEMPLATE.format(namespace=namespace)
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=FETCH_TIMEOUT_SECONDS) as response:
        payload = response.read().decode("utf-8")
    return json.loads(payload)


def resource_tag_actions_from(reference: dict, namespace: str) -> list[str]:
    """The namespace's actions whose resource type carries ``aws:ResourceTag``, sorted."""
    if not NAMESPACE_RE.match(namespace):
        raise ValueError(f"{namespace!r} is not usable as an IAM namespace")
    resource_types = reference.get("Resources")
    if not isinstance(resource_types, list):
        raise ValueError(f"{namespace} carries no Resources list")
    tagged_types = {
        resource["Name"]
        for resource in resource_types
        if isinstance(resource, dict)
        and "Name" in resource
        and RESOURCE_TAG_KEY in resource.get("ConditionKeys", [])
    }
    actions = reference.get("Actions")
    if not isinstance(actions, list):
        raise ValueError(f"{namespace} carries no Actions list")
    found: list[str] = []
    for action in actions:
        if not isinstance(action, dict) or "Name" not in action:
            raise ValueError(f"{namespace} has an action entry without a 'Name': {action!r}")
        name = action["Name"]
        if not ACTION_RE.match(name):
            raise ValueError(f"{namespace}:{name} is not usable as an IAM action")
        resources = action.get("Resources", [])
        if any(isinstance(resource, dict) and resource.get("Name") in tagged_types
               for resource in resources):
            found.append(f"{namespace}:{name}")
    return sorted(found)


def actions_from(references: dict[str, dict]) -> dict[str, list[str]]:
    return {
        namespace: resource_tag_actions_from(references[namespace], namespace)
        for namespace in sorted(references)
    }


def build(references: dict[str, dict], provenance: str | None = None) -> dict:
    if provenance is None:
        provenance = ", ".join(REFERENCE_URL_TEMPLATE.format(namespace=namespace)
                               for namespace in sorted(references))
    return {
        "_source": {
            "generator": "tools/aws/regen_iam_resource_tag_actions.py",
            "reference": provenance,
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "make iam-resource-tag-actions-verify checks every entry against AWS.",
        },
        "namespaces": actions_from(references),
    }


def render(document: dict) -> str:
    return json.dumps(document, indent=2) + "\n"


def validate(document: dict) -> list[str]:
    """Problems that make the file unusable, rather than merely out of date."""
    namespaces = document.get("namespaces")
    if not isinstance(namespaces, dict) or not namespaces:
        return ["namespaces is missing or empty"]
    problems: list[str] = []
    if list(namespaces) != sorted(namespaces):
        problems.append("namespaces is not sorted")
    actions_seen: set[str] = set()
    for namespace, actions in namespaces.items():
        if not isinstance(namespace, str) or not NAMESPACE_RE.match(namespace):
            problems.append(f"{namespace!r} is not usable as an IAM namespace")
            continue
        if not isinstance(actions, list) or not actions:
            problems.append(f"{namespace} carries no actions")
            continue
        if actions != sorted(actions):
            problems.append(f"{namespace} actions are not sorted")
        if len(actions) != len(set(actions)):
            problems.append(f"{namespace} lists an action twice")
        for action in actions:
            if not isinstance(action, str) or not action.startswith(namespace + ":"):
                problems.append(f"{namespace} carries {action!r}, which is not its action")
                continue
            name = action[len(namespace) + 1:]
            if not ACTION_RE.match(name):
                problems.append(f"{action!r} is not usable as an IAM action")
            if action in actions_seen:
                problems.append(f"{action} is listed by more than one namespace")
            actions_seen.add(action)
    for required in REQUIRED_ACTIONS:
        if required not in actions_seen:
            problems.append(f"expected {required!r} to be present")
    for forbidden in FORBIDDEN_ACTIONS:
        if forbidden in actions_seen:
            problems.append(f"{forbidden!r} is an operation, not an IAM action")
    return problems


def verify_against(vendored: dict[str, list[str]],
                   upstream: dict[str, list[str]]) -> tuple[list[str], list[str]]:
    """What the file claims and AWS does not, and what AWS has since added."""
    untrue: list[str] = []
    added: list[str] = []
    for namespace, actions in vendored.items():
        upstream_actions = set(upstream.get(namespace, []))
        for action in actions:
            if action not in upstream_actions:
                untrue.append(f"{action!r} does not act on a resource carrying aws:ResourceTag")
        for action in sorted(upstream_actions - set(actions)):
            added.append(f"{action} (new action)")
    for namespace in upstream:
        if namespace not in vendored:
            for action in upstream[namespace]:
                added.append(f"{action} (new namespace)")
    return untrue, sorted(added)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true",
                        help="offline: validate the shape of the vendored file")
    parser.add_argument("--verify", action="store_true",
                        help="online: every vendored action must still carry aws:ResourceTag")
    parser.add_argument("--source-dir", type=Path,
                        help="read <namespace>.json from this directory instead of AWS")
    args = parser.parse_args(argv)

    if args.check:
        document = json.loads(OUTPUT.read_text(encoding="utf-8"))
        problems = validate(document)
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        return 1 if problems else 0

    if args.verify:
        try:
            references = {namespace: fetch_reference(namespace, args.source_dir)
                          for namespace in NAMESPACES}
        except OSError as error:
            print(f"error: could not read the Service Reference: {error}", file=sys.stderr)
            return 2
        upstream = actions_from(references)
        vendored = json.loads(OUTPUT.read_text(encoding="utf-8")).get("namespaces", {})
        untrue, added = verify_against(vendored, upstream)
        for action in untrue:
            print(f"error: {action}", file=sys.stderr)
        for action in added:
            print(f"note: AWS added {action}; run the sync to include it", file=sys.stderr)
        return 1 if untrue else 0

    references = {namespace: fetch_reference(namespace, args.source_dir)
                  for namespace in NAMESPACES}
    provenance = f"--source-dir {args.source_dir}" if args.source_dir is not None else None
    document = build(references, provenance)
    problems = validate(document)
    if problems:
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        return 1
    OUTPUT.write_text(render(document), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
