#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/s3control-tag-condition-keys.json from AWS's Service Reference and botocore.

An S3 Control request is checked with the tags it carries as `aws:RequestTag/<key>` and `aws:TagKeys`
when its action takes them. The table comes from two sources, each answering what the other cannot:

- AWS's Service Reference for `s3`, which gives every action's condition keys, for the action and for
  each resource type it acts on, but not where a request carries the tags:

      https://servicereference.us-east-1.amazonaws.com/v1/s3/s3.json

- botocore's `s3control` model (Apache-2.0, https://github.com/boto/botocore, the pinned release),
  which gives each request's members but not which of them AWS checks.

`actions` holds every `s3` action that takes either key. `members` holds, for every S3 Control
operation, as `s3:<Operation>`, the request members that feed each key: a top-level body member
holding tags, found as `regen_iam_tag_on_create.py` finds them (a list of key and value structures,
named by its list element, or a map of strings), feeds both keys, and a top-level query string list
of tag keys feeds `aws:TagKeys`. A member nested deeper describes something else: a job's manifest,
a Storage Lens configuration's filters.

Only S3 Control's model is read. S3's own requests carry the tag keys `IamConditionContextResolver`
gives them, which were measured against AWS and do not all match the reference: `s3:PutBucketTagging`
takes `aws:RequestTag/<key>` and `aws:TagKeys` on AWS though the reference lists neither for it.

The Service Reference is live rather than a pinned dependency. The two gates split along the sources:

- `--check` is offline and needs the pinned botocore: `members` must be exactly what botocore gives.
- `--verify` is online and stdlib-only: `actions` must be exactly what the reference gives.

Run from anywhere in the repo:
    python3 tools/aws/regen_s3control_tag_condition_keys.py           # rewrite the vendored file in place
    python3 tools/aws/regen_s3control_tag_condition_keys.py --check   # offline: members match botocore
    python3 tools/aws/regen_s3control_tag_condition_keys.py --verify  # online: actions match AWS
    python3 tools/aws/regen_s3control_tag_condition_keys.py --source <s3.json>  # read a saved reference
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

from regen_iam_tag_on_create import (REQUEST_TAG_KEYS, TAG_MEMBER_RE, _collection, _condition_keys,
                                     _resource_keys, _serialized)
from regen_partitions import resolve_botocore_data
from regen_query_services import latest_model_dir, load_model

OUTPUT = Path(__file__).resolve().parents[2] / "src/main/resources/aws/s3control-tag-condition-keys.json"
URL = "https://servicereference.us-east-1.amazonaws.com/v1/s3/s3.json"
NAMESPACE = "s3"
MODEL = "s3control"
REQUEST_TAG = "aws:RequestTag/${TagKey}"
TAG_KEYS = "aws:TagKeys"
# A request member that lists the keys of the tags the operation removes.
TAG_KEY_LIST_RE = re.compile(r"^\w*tagkeys$", re.IGNORECASE)
BODY_LOCATIONS = (None, "")


def action_keys(reference: dict) -> dict[str, list[str]]:
    """Every action of the namespace that takes a request tag key, with the ones it takes."""
    resource_keys = _resource_keys([reference])
    found = {}
    for action in sorted(reference["Actions"], key=lambda entry: entry["Name"]):
        keys = REQUEST_TAG_KEYS & _condition_keys(NAMESPACE, action, resource_keys)
        if keys:
            found[f"{NAMESPACE}:{action['Name']}"] = sorted(keys)
    return found


def request_members(model: dict) -> dict[str, dict[str, list[dict]]]:
    """The request members of every operation that feed each request tag key."""
    found = {}
    for operation, spec in sorted(model["operations"].items()):
        if "input" not in spec:
            continue
        tags, tag_keys = [], []
        for name, member in sorted(model["shapes"][spec["input"]["shape"]].get("members", {}).items()):
            location = member.get("location")
            if location in BODY_LOCATIONS and TAG_MEMBER_RE.match(name):
                collection = _collection(model, member["shape"])
                if collection is not None:
                    tags.append({"member": _serialized(name, member, model) + collection.pop("step"), **collection})
            elif location == "querystring" and TAG_KEY_LIST_RE.match(name) and _is_string_list(model, member):
                tag_keys.append({"member": member.get("locationName", name), "location": "querystring"})
        keys = {}
        if tags:
            keys[REQUEST_TAG] = tags
        if tags or tag_keys:
            keys[TAG_KEYS] = tags + tag_keys
        if keys:
            found[f"{NAMESPACE}:{operation}"] = keys
    return found


def _is_string_list(model: dict, member: dict) -> bool:
    shape = model["shapes"][member["shape"]]
    return shape["type"] == "list" and model["shapes"][shape["member"]["shape"]]["type"] == "string"


def render(document: dict) -> str:
    return json.dumps(document, indent=2) + "\n"


def validate(document: dict) -> list[str]:
    """Problems that make the vendored file unusable, rather than merely out of date."""
    problems = []
    for section in ("actions", "members"):
        if not isinstance(document.get(section), dict) or not document[section]:
            problems.append(f"{section} is missing or empty")
    if problems:
        return problems
    for operation in ("s3:TagResource", "s3:UntagResource"):
        if operation not in document["actions"]:
            problems.append(f"{operation} takes no request tag key")
        if operation not in document["members"]:
            problems.append(f"{operation} names no member that carries tags")
    return problems


def _reference(source: Path | None) -> dict:
    if source is not None:
        return json.loads(source.read_text(encoding="utf-8"))
    request = urllib.request.Request(URL, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def _model(botocore_data: Path | None) -> tuple[dict, str]:
    data, provenance = resolve_botocore_data(botocore_data)
    return load_model(latest_model_dir(data, MODEL)), provenance


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="offline: members must match the pinned botocore")
    parser.add_argument("--verify", action="store_true", help="online: actions must match the Service Reference")
    parser.add_argument("--source", type=Path, help="a saved s3.json Service Reference document")
    parser.add_argument("--botocore-data", type=Path, help="botocore data directory")
    args = parser.parse_args(argv)

    if args.check or args.verify:
        document = json.loads(OUTPUT.read_text(encoding="utf-8"))
        problems = validate(document)
        if args.check:
            model, provenance = _model(args.botocore_data)
            if document["members"] != request_members(model):
                problems.append(f"members differ from {provenance}")
        if args.verify and document["actions"] != action_keys(_reference(args.source)):
            problems.append("actions differ from the AWS Service Reference")
        for problem in problems:
            print(f"{OUTPUT.name}: {problem}", file=sys.stderr)
        return 1 if problems else 0

    model, provenance = _model(args.botocore_data)
    document = {"_source": {"generator": "tools/aws/regen_s3control_tag_condition_keys.py", "reference": URL,
                            "botocore": provenance,
                            "note": "make s3control-tag-condition-keys-check re-derives members from botocore; "
                                    "make s3control-tag-condition-keys-verify checks actions against AWS."},
                "actions": action_keys(_reference(args.source)), "members": request_members(model)}
    problems = validate(document)
    if problems:
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        return 1
    OUTPUT.write_text(render(document), encoding="utf-8")
    print(f"wrote {OUTPUT.name} from {provenance}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
