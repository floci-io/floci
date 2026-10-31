#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/lambda-web-condition-keys.json from AWS's Service Reference and botocore.

A Lambda Web Functions request is checked with the endpoint's authType as
`lambda:Request/WebFunctionAuthType`, the one the request names, and `lambda:Resource/WebFunctionAuthType`,
the one the endpoint has before the request. The table comes from two sources:

- AWS's Service Reference for `lambda`, which gives each action's condition keys, for the action and
  for each resource type it acts on:

      https://servicereference.us-east-1.amazonaws.com/v1/lambda/lambda.json

- botocore's `lambda-web` model (Apache-2.0, https://github.com/boto/botocore, the pinned release),
  which gives where each request carries the authType.

`actions` holds every `lambda` action that takes either key, with the ones it takes. `members` holds,
for every action that takes the request key, the paths of the request members named `authType`,
dotted for a nested one. The resource key is read from the stored endpoint, so it has no member.

The two gates split along the sources:

- `--check` is offline and needs the pinned botocore: `members` must be exactly what botocore gives.
- `--verify` is online and stdlib-only: `actions` must be exactly what the reference gives.

Run from anywhere in the repo:
    python3 tools/aws/regen_lambda_web_condition_keys.py           # rewrite the vendored file in place
    python3 tools/aws/regen_lambda_web_condition_keys.py --check   # offline: members match botocore
    python3 tools/aws/regen_lambda_web_condition_keys.py --verify  # online: actions match AWS
    python3 tools/aws/regen_lambda_web_condition_keys.py --source <lambda.json>  # read a saved reference
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

from regen_dynamodb_condition_keys import member_paths
from regen_iam_tag_on_create import _condition_keys, _resource_keys
from regen_partitions import resolve_botocore_data
from regen_query_services import latest_model_dir, load_model

OUTPUT = Path(__file__).resolve().parents[2] / "src/main/resources/aws/lambda-web-condition-keys.json"
URL = "https://servicereference.us-east-1.amazonaws.com/v1/lambda/lambda.json"
NAMESPACE = "lambda"
MODEL = "lambda-web"
REQUEST_KEY = "lambda:Request/WebFunctionAuthType"
RESOURCE_KEY = "lambda:Resource/WebFunctionAuthType"
MEMBER = "authType"


def action_keys(reference: dict) -> dict[str, list[str]]:
    """Every action of the namespace that takes either key, with the ones it takes."""
    resource_keys = _resource_keys([reference])
    found = {}
    for action in sorted(reference["Actions"], key=lambda entry: entry["Name"]):
        keys = {REQUEST_KEY, RESOURCE_KEY} & _condition_keys(NAMESPACE, action, resource_keys)
        if keys:
            found[f"{NAMESPACE}:{action['Name']}"] = sorted(keys)
    return found


def request_members(model: dict, actions: dict[str, list[str]]) -> dict[str, dict[str, list[str]]]:
    """For every action that takes the request key, where its request carries the value."""
    found = {}
    for action, keys in sorted(actions.items()):
        if REQUEST_KEY not in keys:
            continue
        operation = model["operations"].get(action.split(":", 1)[1])
        if operation is None or "input" not in operation:
            raise ValueError(f"{action} takes {REQUEST_KEY} but botocore has no request for it")
        paths = member_paths(model, operation["input"]["shape"], {MEMBER})
        if not paths:
            raise ValueError(f"{action} takes {REQUEST_KEY} but its request has no {MEMBER} member")
        found[action] = {REQUEST_KEY: paths}
    return found


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
    if REQUEST_KEY not in document["actions"].get("lambda:CreateWebFunctionEndpoint", []):
        problems.append(f"lambda:CreateWebFunctionEndpoint takes no {REQUEST_KEY}")
    if RESOURCE_KEY not in document["actions"].get("lambda:GetWebFunctionEndpoint", []):
        problems.append(f"lambda:GetWebFunctionEndpoint takes no {RESOURCE_KEY}")
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
    parser.add_argument("--source", type=Path, help="a saved lambda.json Service Reference document")
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
    document = {"_source": {"generator": "tools/aws/regen_lambda_web_condition_keys.py", "reference": URL,
                            "botocore": provenance,
                            "note": "make lambda-web-condition-keys-check re-derives members from botocore; "
                                    "make lambda-web-condition-keys-verify checks actions against AWS."},
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
