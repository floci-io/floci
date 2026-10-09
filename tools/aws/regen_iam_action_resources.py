#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/iam-action-resources.json from AWS's own IAM metadata.

Under IAM enforcement every request is authorized against a resource, and for an IAM action the
resource is whichever of IAM's own entities the action acts on. That is not derivable from the
request: `AddUserToGroup` takes a `UserName` and a `GroupName` and is authorized against the
group, `AddRoleToInstanceProfile` takes a `RoleName` and is authorized against the instance
profile, and `EnableMFADevice` takes a `SerialNumber` and is authorized against the user rather
than the device. Reading the type off the parameters would scope those to the wrong resource and
let a deny on the right one pass, so it has to come from AWS.

botocore is not the source here, as in `regen_service_namespaces.py` and for the same kind of
reason: a botocore operation model describes the wire shape and says nothing about what IAM
authorizes the operation against. AWS publishes that separately, per action, in the Service
Reference Information for IAM:

    https://servicereference.us-east-1.amazonaws.com/v1/iam/iam.json

Each action there carries the resource types it can be authorized against, and each type carries
the ARN formats AWS mints it in. Both halves are vendored: the types decide which entity a request
names, and the formats are what `ResourceArnBuilder` checks its own minting against, so a prefix
that drifts from AWS fails at startup rather than producing an ARN no policy matches.

That endpoint is live rather than a pinned dependency, so a fresh generation is not byte-identical:
AWS adds actions whenever it ships them. Byte-equality is therefore the wrong gate. What must be
guaranteed is that nothing here is invented, which is checkable directly:

- `--check` is offline and structural. Sorted, duplicate-free, every action name usable, every
  resource type usable, and it must still carry the cases that justify not deriving this from the
  request (`AddUserToGroup` against the group, `EnableMFADevice` against the user, `ListUsers`
  against nothing). This catches corruption and hand-editing.
- `--verify` is the real gate and needs the network. Every resource type the vendored file claims
  for an action must still be one AWS gives that action. A hand-typed type fails, and so does a
  type AWS has taken away, because the file then authorizes against something AWS does not. An
  action or a type AWS has *added* is reported without failing: being behind means Floci evaluates
  that action against `*`, which is where it was before any of this existed, so it is incomplete
  rather than wrong, and failing on it would break this repo's build for someone else's launch.

Run from anywhere in the repo:
    python3 tools/aws/regen_iam_action_resources.py           # rewrite the vendored file in place
    python3 tools/aws/regen_iam_action_resources.py --check   # offline: shape of the vendored file
    python3 tools/aws/regen_iam_action_resources.py --verify  # online: nothing in it is invented
    python3 tools/aws/regen_iam_action_resources.py --source <file>  # read a saved copy instead
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
OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-action-resources.json"
REFERENCE_URL = "https://servicereference.us-east-1.amazonaws.com/v1/iam/iam.json"
FETCH_TIMEOUT_SECONDS = 30

# An IAM action name as it arrives in the Query `Action` field.
ACTION_RE = re.compile(r"^[A-Z][A-Za-z0-9]{0,127}$")
# A resource type as the reference names it, which is also the ARN's resource prefix.
RESOURCE_TYPE_RE = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
# An ARN format with the reference's own ${Placeholder} segments left in. The region is always
# empty for IAM and the account is usually ${Account}, but role-template publishes a literal `aws`
# there and carries a colon inside its resource part, so neither segment is pinned to one spelling.
ARN_FORMAT_RE = re.compile(r"^arn:\$\{Partition}:iam:[^:]*:[^:]*:\S+$")

# Cases whose value is the whole point of vendoring this rather than reading the request. Each is
# an action whose resource is not the thing its parameters most obviously name, plus one action
# that is authorized against nothing at all.
ANCHORS = {
    "AddUserToGroup": ["group"],
    "RemoveUserFromGroup": ["group"],
    "AddRoleToInstanceProfile": ["instance-profile"],
    "EnableMFADevice": ["user"],
    "DeactivateMFADevice": ["user"],
    "CreateUser": ["user"],
    "ListUsers": [],
}


def fetch_reference(source: Path | None) -> tuple[dict, str]:
    """AWS's IAM service reference and a provenance label for where it came from."""
    if source is not None:
        return json.loads(source.read_text(encoding="utf-8")), f"--source {source}"
    request = urllib.request.Request(REFERENCE_URL, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=FETCH_TIMEOUT_SECONDS) as response:
        payload = response.read().decode("utf-8")
    return json.loads(payload), REFERENCE_URL


def action_resources_from(reference: dict) -> dict[str, list[str]]:
    """Every action the reference publishes, mapped to the resource types it names, sorted.

    An action with no resource type keeps an empty list rather than being left out, so the Java
    side can tell "AWS authorizes this against nothing" from "AWS has an action we have not
    vendored yet". The first is a wildcard on purpose; the second is a gap to close.
    """
    actions = reference.get("Actions")
    if not isinstance(actions, list) or not actions:
        raise ValueError("the IAM service reference must carry a non-empty Actions list")
    found: dict[str, list[str]] = {}
    for action in actions:
        if not isinstance(action, dict) or "Name" not in action:
            raise ValueError(f"action entry without a 'Name' field: {action!r}")
        name = action["Name"]
        if not ACTION_RE.match(name):
            raise ValueError(f"{name!r} is not usable as an IAM action name")
        types = sorted({resource["Name"] for resource in action.get("Resources", [])})
        for resource_type in types:
            if not RESOURCE_TYPE_RE.match(resource_type):
                raise ValueError(f"{resource_type!r} is not usable as an IAM resource type")
        found[name] = types
    return dict(sorted(found.items()))


def arn_formats_from(reference: dict) -> dict[str, list[str]]:
    """Every resource type the reference publishes, mapped to its ARN formats, sorted."""
    resources = reference.get("Resources")
    if not isinstance(resources, list) or not resources:
        raise ValueError("the IAM service reference must carry a non-empty Resources list")
    found: dict[str, list[str]] = {}
    for resource in resources:
        if not isinstance(resource, dict) or "Name" not in resource:
            raise ValueError(f"resource entry without a 'Name' field: {resource!r}")
        name = resource["Name"]
        if not RESOURCE_TYPE_RE.match(name):
            raise ValueError(f"{name!r} is not usable as an IAM resource type")
        formats = sorted(resource.get("ARNFormats", []))
        for arn_format in formats:
            if not ARN_FORMAT_RE.match(arn_format):
                raise ValueError(f"{arn_format!r} is not an IAM ARN format")
        found[name] = formats
    return dict(sorted(found.items()))


def build(reference: dict, provenance: str) -> dict:
    return {
        "_source": {
            "generator": "tools/aws/regen_iam_action_resources.py",
            "reference": provenance,
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "make iam-action-resources-verify checks every entry against AWS.",
        },
        "actionResources": action_resources_from(reference),
        "resourceArnFormats": arn_formats_from(reference),
    }


def render(document: dict) -> str:
    return json.dumps(document, indent=2) + "\n"


def validate(document: dict) -> list[str]:
    """Problems that make the vendored file unusable, rather than merely out of date."""
    problems: list[str] = []
    actions = document.get("actionResources")
    formats = document.get("resourceArnFormats")
    if not isinstance(actions, dict) or not actions:
        return ["actionResources is missing or empty"]
    if not isinstance(formats, dict) or not formats:
        problems.append("resourceArnFormats is missing or empty")
        formats = {}
    if list(actions) != sorted(actions):
        problems.append("actionResources is not sorted by action name")
    if list(formats) != sorted(formats):
        problems.append("resourceArnFormats is not sorted by resource type")
    for action, types in actions.items():
        if not ACTION_RE.match(action):
            problems.append(f"{action!r} is not usable as an IAM action name")
        if not isinstance(types, list):
            problems.append(f"{action!r} does not carry a list of resource types")
            continue
        if types != sorted(types):
            problems.append(f"{action!r} lists its resource types out of order")
        if len(types) != len(set(types)):
            problems.append(f"{action!r} lists a resource type twice")
        for resource_type in types:
            if not isinstance(resource_type, str) or not RESOURCE_TYPE_RE.match(resource_type):
                problems.append(f"{action!r} names {resource_type!r}, which is not a resource type")
            elif resource_type not in formats:
                problems.append(f"{action!r} names {resource_type!r}, which has no ARN format")
    for resource_type, arn_formats in formats.items():
        if not isinstance(arn_formats, list) or not arn_formats:
            problems.append(f"{resource_type!r} carries no ARN format")
            continue
        for arn_format in arn_formats:
            if not isinstance(arn_format, str) or not ARN_FORMAT_RE.match(arn_format):
                problems.append(f"{resource_type!r} carries {arn_format!r}, not an ARN format")
    for action, expected in ANCHORS.items():
        if action not in actions:
            problems.append(f"expected {action!r} to be present")
        elif actions[action] != expected:
            problems.append(f"{action!r} should be authorized against {expected}, "
                            f"not {actions[action]}")
    return problems


def verify_against(vendored: dict[str, list[str]],
                   upstream: dict[str, list[str]]) -> tuple[list[str], list[str]]:
    """What the vendored file claims and AWS does not, and what AWS has since added.

    A type the file claims for an action AWS does not give it is a failure: the request would be
    authorized against a resource AWS does not use, which is the one outcome worse than the
    wildcard. A type or an action AWS has added is only a file that is behind.
    """
    untrue: list[str] = []
    added: list[str] = []
    for action, types in vendored.items():
        if action not in upstream:
            untrue.append(f"{action!r} is not an action AWS publishes")
            continue
        for resource_type in types:
            if resource_type not in upstream[action]:
                untrue.append(f"{action!r} is not authorized against {resource_type!r} by AWS")
    for action, types in upstream.items():
        if action not in vendored:
            added.append(f"{action} (new action)")
            continue
        for resource_type in types:
            if resource_type not in vendored[action]:
                added.append(f"{action} -> {resource_type}")
    return untrue, sorted(added)


def verify_formats_against(vendored: dict[str, list[str]],
                           upstream: dict[str, list[str]]) -> tuple[list[str], list[str]]:
    """What the vendored ARN formats claim and AWS does not, and what AWS has since added.

    Checked separately from the action mapping because a format drifts invisibly: the shape check
    only asks whether it looks like an IAM ARN, and a changed one still does. The minting reads
    the type's format to place the resource type, so a vendored format AWS no longer publishes
    means a right-looking file building a wrong ARN.
    """
    untrue: list[str] = []
    added: list[str] = []
    for resource_type, formats in vendored.items():
        if resource_type not in upstream:
            untrue.append(f"{resource_type!r} is not a resource type AWS publishes")
            continue
        for arn_format in formats:
            if arn_format not in upstream[resource_type]:
                untrue.append(f"AWS does not spell a {resource_type} ARN as {arn_format!r}")
    for resource_type, formats in upstream.items():
        if resource_type not in vendored:
            added.append(f"{resource_type} (new resource type)")
            continue
        for arn_format in formats:
            if arn_format not in vendored[resource_type]:
                added.append(f"{resource_type} -> {arn_format}")
    return untrue, sorted(added)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true",
                        help="offline: validate the shape of the vendored file")
    parser.add_argument("--verify", action="store_true",
                        help="online: nothing in the vendored file is invented")
    parser.add_argument("--source", type=Path, default=None,
                        help="read a saved copy of the reference instead of fetching it")
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
        scoped = sum(1 for types in document["actionResources"].values() if types)
        print(f"{args.output.relative_to(REPO_ROOT)} is well-formed "
              f"({len(document['actionResources'])} actions, {scoped} with a resource type, "
              f"{len(document['resourceArnFormats'])} resource types)")
        return 0

    if args.verify:
        if not args.output.exists():
            print(f"error: {args.output} does not exist", file=sys.stderr)
            return 1
        document = json.loads(args.output.read_text(encoding="utf-8"))
        vendored = document["actionResources"]
        vendored_formats = document.get("resourceArnFormats", {})
        try:
            reference, provenance = fetch_reference(args.source)
            upstream = action_resources_from(reference)
            upstream_formats = arn_formats_from(reference)
        except (OSError, ValueError) as e:
            # Distinct from a data problem: the gate could not run, rather than having found
            # something invented. Separate exit code so CI can tell the two apart. ValueError
            # covers a malformed reference, including the JSONDecodeError a truncated or non-JSON
            # response raises, which is just as much "could not verify" as an unreachable host.
            print(f"error: could not read the IAM service reference ({e})", file=sys.stderr)
            return 2
        untrue, added = verify_against(vendored, upstream)
        format_untrue, format_added = verify_formats_against(vendored_formats, upstream_formats)
        untrue += format_untrue
        added += format_added
        for claim in untrue:
            print(f"error: {claim}", file=sys.stderr)
        if untrue:
            print(f"error: {len(untrue)} vendored claim(s) do not hold upstream; the file would "
                  f"authorize against a resource AWS does not use.", file=sys.stderr)
            return 1
        if added:
            print(f"{args.output.relative_to(REPO_ROOT)}: all {len(vendored)} actions verified. "
                  f"AWS has since added {len(added)}, so the mapping is incomplete but not wrong "
                  f"(run without --verify to refresh): {', '.join(added[:10])}"
                  f"{'...' if len(added) > 10 else ''}")
        else:
            print(f"{args.output.relative_to(REPO_ROOT)}: all {len(vendored)} actions and "
                  f"{len(vendored_formats)} ARN formats verified against {provenance}, nothing "
                  f"added upstream.")
        return 0

    try:
        reference, provenance = fetch_reference(args.source)
        document = build(reference, provenance)
    except (OSError, ValueError) as e:
        print(f"error: could not read the IAM service reference ({e})", file=sys.stderr)
        return 2
    problems = validate(document)
    for problem in problems:
        print(f"error: {problem}", file=sys.stderr)
    if problems:
        return 1
    args.output.write_text(render(document), encoding="utf-8")
    scoped = sum(1 for types in document["actionResources"].values() if types)
    print(f"wrote {args.output.relative_to(REPO_ROOT)} "
          f"({len(document['actionResources'])} actions, {scoped} with a resource type, "
          f"from {provenance})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
