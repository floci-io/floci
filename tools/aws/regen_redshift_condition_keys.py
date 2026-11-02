#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/redshift-condition-keys.json from AWS's Service Reference and botocore.

A Redshift request is checked with the database user it names as `redshift:DbUser`, so a policy can scope
GetClusterCredentials and CreateClusterUser to `dbuser:<cluster>/${redshift:DbUser}`. The table comes from two
sources:

- AWS's Service Reference for `redshift`, which gives each action's condition keys, for the action and for
  each resource type it acts on:

      https://servicereference.us-east-1.amazonaws.com/v1/redshift/redshift.json

- botocore's `redshift` model (Apache-2.0, https://github.com/boto/botocore, the pinned release), which gives
  each request's members.

`actions` holds every `redshift` action that takes a key in `KEYS`, with the ones it takes. `members` holds,
for each of those, the request member that carries the value: the operation of the action's name has a
top-level string member named after the key, ignoring case, as `regen_iam_condition_keys.py` reads the `iam:`
keys. An action with no such member is in `NOT_IN_REQUEST`, with the reason. Only `KEYS` are read:
`redshift:DbName` and `redshift:DurationSeconds` are not yet.

The two gates split along the sources:

- `--check` is offline and needs the pinned botocore: `members` must be exactly what botocore gives.
- `--verify` is online and stdlib-only: `actions` must be exactly what the reference gives.

Run from anywhere in the repo:
    python3 tools/aws/regen_redshift_condition_keys.py           # rewrite the vendored file in place
    python3 tools/aws/regen_redshift_condition_keys.py --check   # offline: members match botocore
    python3 tools/aws/regen_redshift_condition_keys.py --verify  # online: actions match AWS
    python3 tools/aws/regen_redshift_condition_keys.py --source <redshift.json>  # read a saved reference
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

from regen_iam_tag_on_create import _condition_keys, _resource_keys
from regen_partitions import resolve_botocore_data
from regen_query_services import latest_model_dir, load_model

OUTPUT = Path(__file__).resolve().parents[2] / "src/main/resources/aws/redshift-condition-keys.json"
URL = "https://servicereference.us-east-1.amazonaws.com/v1/redshift/redshift.json"
NAMESPACE = "redshift"
MODEL = "redshift"
KEYS = ("redshift:DbUser",)
# Actions that take a key whose request does not carry it, and why.
NOT_IN_REQUEST = {
    ("redshift:CreateClusterUser", "redshift:DbUser"): "no operation of its own: GetClusterCredentials checks it "
                                                       "when AutoCreate is true, with the DbUser of its own request",
}


def action_keys(reference: dict) -> dict[str, list[str]]:
    """Every action of the namespace that takes a key in `KEYS`, with the ones it takes."""
    resource_keys = _resource_keys([reference])
    found = {}
    for action in sorted(reference["Actions"], key=lambda entry: entry["Name"]):
        keys = set(KEYS) & _condition_keys(NAMESPACE, action, resource_keys)
        if keys:
            found[f"{NAMESPACE}:{action['Name']}"] = sorted(keys)
    return found


def request_members(model: dict, actions: dict[str, list[str]]) -> dict[str, dict[str, str]]:
    """For every action and key, the top-level string member of its request named after the key."""
    found = {}
    for action, keys in sorted(actions.items()):
        for key in keys:
            if (action, key) in NOT_IN_REQUEST:
                continue
            member = _member(model, action.split(":", 1)[1], key.split(":", 1)[1])
            if member is None:
                raise ValueError(f"{action} takes {key} but its request has no string member of that name")
            found.setdefault(action, {})[key] = member
    return found


def _member(model: dict, operation: str, name: str) -> str | None:
    spec = model["operations"].get(operation)
    if spec is None or "input" not in spec:
        return None
    for member, shape in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
        if member.lower() == name.lower() and model["shapes"][shape["shape"]].get("type") == "string":
            return member
    return None


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
    if document["members"].get("redshift:GetClusterCredentials", {}).get("redshift:DbUser") != "DbUser":
        problems.append("redshift:GetClusterCredentials does not read redshift:DbUser from DbUser")
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
    parser.add_argument("--source", type=Path, help="a saved redshift.json Service Reference document")
    parser.add_argument("--botocore-data", type=Path, help="botocore data directory")
    args = parser.parse_args(argv)

    if args.check or args.verify:
        document = json.loads(OUTPUT.read_text(encoding="utf-8"))
        problems = validate(document)
        if args.check and not problems:
            model, provenance = _model(args.botocore_data)
            if document["members"] != request_members(model, document["actions"]):
                problems.append(f"members differ from {provenance}")
        if args.verify and document.get("actions") != action_keys(_reference(args.source)):
            problems.append("actions differ from the AWS Service Reference")
        for problem in problems:
            print(f"{OUTPUT.name}: {problem}", file=sys.stderr)
        return 1 if problems else 0

    model, provenance = _model(args.botocore_data)
    actions = action_keys(_reference(args.source))
    document = {"_source": {"generator": "tools/aws/regen_redshift_condition_keys.py", "reference": URL,
                            "botocore": provenance,
                            "note": "make redshift-condition-keys-check re-derives members from botocore; "
                                    "make redshift-condition-keys-verify checks actions against AWS."},
                "actions": actions, "members": request_members(model, actions)}
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
