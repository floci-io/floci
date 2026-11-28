#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/cloudtrail-s3-data-events.json, the name CloudTrail records each S3 operation's
data event under and whether it records it read-only, from botocore's S3 model,
tools/aws/sources/cloudtrail-s3-data-events.json and AWS's S3 service reference.

CloudTrail records an S3 call on an object of a general purpose bucket as a data event when the S3 user guide's data
events table lists its API for `AWS::S3::Object`, the resource type of every object Floci serves. The vendored source
copies that table and the guide's sentences naming the event a call is recorded with
(fetch_cloudtrail_s3_data_events.py); this file reads them against botocore's operations:

- An operation the row lists is recorded under its own name, or under the name a sentence gives its call
  (`PutObjectRetention` as `PutObjectLockRetention`).
- An operation the row does not list, whose name ends in a version (`ListObjectsV2`), is recorded under the
  version-less name when the row lists that one: the guide lists the versioned name beside it for the resource types
  that record it apart (`AWS::S3::Access Point` lists both `ListObjects` and `ListObjectsV2`), and only the
  version-less one for `AWS::S3::Object`.
- Any other operation is not a data event, and is absent.

Each event carries `readOnly`, as CloudTrail records it: CloudTrail "considers an action read-only if it does not have
any mutating effect on a resource" (https://docs.aws.amazon.com/IAM/latest/UserGuide/cloudtrail-integration.html), so
an operation is read-only when none of the actions the service reference authorizes it with has a mutating access
level, the reading regen_iam_action_resources.py vendors IAM's `readOnlyActions` by. An operation the reference lists
no S3 action for is authorized by the action of its own name, as regen_iam_authorized_actions.py takes it.

The service reference is live rather than a pinned dependency, so `--check` is offline and reads the operations and
their event names against botocore and the vendored page, and `--verify` reads `readOnly` against the reference.

The model is botocore's `s3/2006-03-01/` (Apache-2.0, https://github.com/boto/botocore), located, in order, at
`--botocore-data`, the `local/aws/botocore` checkout, then the installed `botocore` package, which `requirements.txt`
pins so CI regenerates the same bytes.

Run from anywhere in the repo:
    python3 tools/aws/regen_cloudtrail_s3_data_events.py            # rewrite the vendored file (online)
    python3 tools/aws/regen_cloudtrail_s3_data_events.py --check    # offline: the event names are botocore's and the page's
    python3 tools/aws/regen_cloudtrail_s3_data_events.py --verify   # online: readOnly is still what the reference gives
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

from regen_iam_action_resources import read_only_actions_from
from regen_partitions import resolve_botocore_data
from request_shapes import REPO_ROOT, generator_name, load_model, render

OUTPUT = REPO_ROOT / "src/main/resources/aws/cloudtrail-s3-data-events.json"
SOURCE = REPO_ROOT / "tools/aws/sources/cloudtrail-s3-data-events.json"
MODEL = Path("s3/2006-03-01")
REFERENCE_URL = "https://servicereference.us-east-1.amazonaws.com/v1/s3/s3.json"
FETCH_TIMEOUT_SECONDS = 30
# The resource type of an object in a general purpose bucket, the only kind of bucket Floci serves.
RESOURCE_TYPE = "AWS::S3::Object"

API_RE = re.compile(r"\w+")
VERSIONED_RE = re.compile(r"^(\w+?)V\d+$")


def api_name(cell: str) -> str:
    """The API a cell of the data events table names, without the note some carry ("(same-region copies only)")."""
    match = API_RE.match(cell)
    if match is None:
        raise ValueError(f"the data events table lists {cell!r}, which names no API")
    return match.group(0)


def data_events(source: dict, operations: list[str]) -> dict[str, str]:
    """Each operation CloudTrail records as a data event on a general purpose bucket's object, with the event name
    it is recorded under."""
    rows = source["dataEvents"]
    if RESOURCE_TYPE not in rows:
        raise ValueError(f"the vendored data events table has no {RESOURCE_TYPE} row")
    listed = {api_name(cell) for cell in rows[RESOURCE_TYPE]}
    renamed = {sentence["call"]: sentence["eventName"] for sentence in source["recordedAs"]}
    events = {}
    for operation in sorted(operations):
        if operation in listed:
            events[operation] = renamed.get(operation, operation)
            continue
        versioned = VERSIONED_RE.match(operation)
        if versioned and versioned.group(1) in listed:
            events[operation] = renamed.get(versioned.group(1), versioned.group(1))
    return events


def read_only(reference: dict, operations: list[str]) -> dict[str, bool]:
    """Whether CloudTrail records each operation read-only: none of the S3 actions the reference authorizes it with,
    or else the action of its own name, has a mutating access level."""
    readable = set(read_only_actions_from(reference))
    actions = {action["Name"] for action in reference.get("Actions", [])}
    authorized = {operation["Name"]: [action["Name"] for action in operation.get("AuthorizedActions", [])
                                      if action.get("Service") == "s3"]
                  for operation in reference.get("Operations", [])}
    found = {}
    for operation in operations:
        checked = authorized.get(operation) or ([operation] if operation in actions else [])
        if not checked:
            raise ValueError(f"the S3 service reference authorizes {operation} with no action")
        found[operation] = all(action in readable for action in checked)
    return found


def disagreeing(events: dict[str, dict]) -> list[str]:
    """The event names whose operations are not all read-only or all not: a record carries one flag for its name, so
    the operations recorded under it must agree."""
    names = {event.get("eventName") for event in events.values()}
    return sorted(name for name in names if name is not None
                  and len({event.get("readOnly") for event in events.values() if event.get("eventName") == name}) > 1)


def build(model: dict, source: dict, reference: dict, provenance: str, generator: str) -> dict:
    names = data_events(source, list(model["operations"]))
    flags = read_only(reference, list(names))
    events = {operation: {"eventName": name, "readOnly": flags[operation]} for operation, name in names.items()}
    conflicting = disagreeing(events)
    if conflicting:
        raise ValueError(f"the operations recorded as {conflicting} are not all read-only or all not")
    unmodeled = sorted({api_name(cell) for cell in source["dataEvents"][RESOURCE_TYPE]} - set(names)
                       - set(names.values()))
    return {
        "_source": {
            "botocore": provenance,
            "page": source["_source"]["page"],
            "reference": REFERENCE_URL,
            "generator": generator,
            "note": f"Each S3 operation CloudTrail records as a data event on an {RESOURCE_TYPE}, with the event "
                    "name it is recorded under and whether it is recorded read-only. `unmodeled` lists the APIs the "
                    "guide names that botocore models no operation for.",
        },
        "dataEvents": events,
        "unmodeled": unmodeled,
    }


def fetch_reference(source: Path | None) -> dict:
    """AWS's S3 service reference, from `source` when given."""
    if source is not None:
        return json.loads(source.read_text(encoding="utf-8"))
    request = urllib.request.Request(REFERENCE_URL, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=FETCH_TIMEOUT_SECONDS) as response:
        return json.loads(response.read().decode("utf-8"))


def check(vendored: dict, model: dict, source: dict) -> list[str]:
    """Offline: the vendored operations and event names are the ones botocore and the page give, each with a
    `readOnly` flag the operations sharing its event name agree on."""
    events = vendored.get("dataEvents")
    if not isinstance(events, dict):
        return ["the file carries no dataEvents object"]
    problems = []
    names = data_events(source, list(model["operations"]))
    if {operation: event.get("eventName") for operation, event in events.items()} != names:
        problems.append("the operations or their event names differ from botocore's and the vendored page's")
    for operation, event in sorted(events.items()):
        if not isinstance(event.get("readOnly"), bool):
            problems.append(f"{operation} carries no readOnly flag")
    conflicting = disagreeing(events)
    if conflicting:
        problems.append(f"the operations recorded as {conflicting} are not all read-only or all not")
    return problems


def verify(vendored: dict, reference: dict) -> list[str]:
    """Online: each operation's `readOnly` is still what the service reference gives."""
    events = vendored["dataEvents"]
    upstream = read_only(reference, list(events))
    return [f"{operation} is vendored readOnly {event['readOnly']}, the service reference gives {upstream[operation]}"
            for operation, event in sorted(events.items()) if event["readOnly"] != upstream[operation]]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="offline: the event names are botocore's and the page's")
    parser.add_argument("--verify", action="store_true", help="online: readOnly is still what the reference gives")
    parser.add_argument("--botocore-data", type=Path, help="botocore data directory")
    parser.add_argument("--reference", type=Path, help="read the S3 service reference from this file")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    data, provenance = resolve_botocore_data(args.botocore_data)
    try:
        model = load_model(data, MODEL)
        source = json.loads(SOURCE.read_text(encoding="utf-8"))
        if args.check or args.verify:
            vendored = json.loads(args.output.read_text(encoding="utf-8"))
            problems = check(vendored, model, source) if args.check else verify(vendored, fetch_reference(args.reference))
        else:
            fresh = render(build(model, source, fetch_reference(args.reference), provenance, generator_name(__file__)))
    except OSError as e:
        print(f"error: could not read a source ({e})", file=sys.stderr)
        return 2
    except (ValueError, KeyError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    if args.check or args.verify:
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        if problems:
            print(f"error: {args.output} no longer matches; run make cloudtrail-s3-data-events-sync", file=sys.stderr)
            return 1
        print(f"{args.output.name}: {len(vendored['dataEvents'])} operations "
              f"{'match botocore and the vendored page' if args.check else 'match the S3 service reference'}")
        return 0
    args.output.write_text(fresh, encoding="utf-8")
    shown = args.output.relative_to(REPO_ROOT) if args.output.is_relative_to(REPO_ROOT) else args.output
    print(f"wrote {shown} ({len(json.loads(fresh)['dataEvents'])} operations) from {provenance} and {REFERENCE_URL}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
