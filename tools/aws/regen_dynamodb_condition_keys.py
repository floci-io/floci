#!/usr/bin/env python3
"""Generate DynamoDB IAM condition action and request-member tables.

AWS Service Reference supplies every action's condition keys. Pinned botocore supplies
request paths; semantic condition keys (LeadingKeys, Attributes, Select) are derived
from the corresponding request members rather than a member named after the key.
--check re-derives members offline; --verify compares the complete action/key mapping
with AWS, including newly added keys. --source permits a saved reference document.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

from regen_partitions import resolve_botocore_data
from regen_query_services import latest_model_dir, load_model

OUTPUT = Path(__file__).resolve().parents[2] / "src/main/resources/aws/dynamodb-condition-keys.json"
URL = "https://servicereference.us-east-1.amazonaws.com/v1/dynamodb/dynamodb.json"
# These keys describe values derived from request structures, not scalar members.
DERIVED = {
    "Attributes": {"Key", "Keys", "Item", "AttributesToGet", "ProjectionExpression",
                   "ExpressionAttributeNames", "KeyConditions", "QueryFilter", "ScanFilter",
                   "AttributeUpdates", "Expected", "KeyConditionExpression", "FilterExpression",
                   "ConditionExpression", "UpdateExpression", "Statement"},
    "LeadingKeys": {"Key", "Keys", "Item", "KeyConditions", "KeyConditionExpression", "Statement"},
    "Select": {"Select", "ProjectionExpression", "AttributesToGet", "IndexName", "Statement"},
    "FullTableScan": {"Statement"},
}


def action_keys(reference):
    resources = {r["Name"]: r.get("ConditionKeys", []) for r in reference["Resources"]}
    return {"dynamodb:" + a["Name"]: sorted(set(a.get("ActionConditionKeys", [])) |
            {k for r in a.get("Resources", [])
             for k in r.get("ConditionKeys", resources.get(r["Name"], []))})
            for a in sorted(reference["Actions"], key=lambda a: a["Name"])}


def member_paths(model, shape, names, prefix="", seen=frozenset()):
    if shape in seen:
        return []
    seen = seen | {shape}
    spec = model["shapes"][shape]
    result = []
    for name, member in sorted(spec.get("members", {}).items()):
        path = prefix + name
        if name in names:
            result.append(path)
        result.extend(member_paths(model, member["shape"], names, path + ".", seen))
    for field in ("member", "value"):
        if field in spec:
            result.extend(member_paths(model, spec[field]["shape"], names, prefix + "*.", seen))
    return sorted(set(result))


def request_members(model, keys):
    result = {}
    for operation, spec in sorted(model["operations"].items()):
        if "input" not in spec:
            continue
        result[operation] = {key: member_paths(model, spec["input"]["shape"],
                             DERIVED.get(key.split(":", 1)[1], {key.split(":", 1)[1]}))
                             for key in sorted({k for values in keys.values() for k in values
                                                if k.startswith("dynamodb:")})}
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--source", type=Path)
    parser.add_argument("--botocore-data", type=Path)
    args = parser.parse_args(argv)
    if args.check:
        document = json.loads(OUTPUT.read_text())
        model = load_model(latest_model_dir(resolve_botocore_data(args.botocore_data)[0], "dynamodb"))
        expected = request_members(model, document["actions"])
        if (document["members"] != expected
                or document["attributesToGetMinimum"] != model["shapes"]["AttributeNameList"]["min"]):
            raise ValueError("DynamoDB condition request members differ from pinned botocore")
    else:
        if args.source:
            reference = json.loads(args.source.read_text())
        else:
            with urllib.request.urlopen(URL, timeout=30) as response:
                reference = json.load(response)
        keys = action_keys(reference)
        if args.verify:
            if json.loads(OUTPUT.read_text())["actions"] != keys:
                raise ValueError("DynamoDB condition actions differ from AWS Service Reference")
        else:
            data, provenance = resolve_botocore_data(args.botocore_data)
            model = load_model(latest_model_dir(data, "dynamodb"))
            document = {"source": URL, "botocore": provenance, "actions": keys,
                        "members": request_members(model, keys),
                        "attributesToGetMinimum": model["shapes"]["AttributeNameList"]["min"]}
            OUTPUT.write_text(json.dumps(document, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
