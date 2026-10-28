#!/usr/bin/env python3
"""Generate the table of the IAM condition keys a request carries in its own members.

The AWS Service Reference lists every iam: condition key each IAM action can be evaluated with.
For each such action and key, pinned botocore says whether the request carries the value: the
operation of the same name has a top-level string member named after the key, ignoring case
(iam:PolicyARN is PolicyArn), and the key's type is String or ARN. Those pairs are read; every
other pair the reference lists is notRead, with the reason. An operation that has the member but
whose action does not list the key is left out, as AWS does not set the key for it.

--check re-derives read and notRead offline from the pairs in the file and pinned botocore, so a
hand-edited entry fails. --verify compares every pair and key type with the live reference and
fails on any difference, added pairs included: a key missing from the context stops a
conditioned Allow matching and a conditioned Deny applying. --source reads a saved reference.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

from regen_partitions import resolve_botocore_data
from regen_query_services import latest_model_dir, load_model

OUTPUT = Path(__file__).resolve().parents[2] / "src/main/resources/aws/iam-condition-keys.json"
URL = "https://servicereference.us-east-1.amazonaws.com/v1/iam/iam.json"
READ_TYPES = {"String", "ARN"}
# Keys whose value, on an action without the member, is state of the target the request names.
STORED = {
    "iam:PermissionsBoundary": "the value is the target's current permissions boundary",
}


def action_keys(reference):
    """Every (action, key) pair for an iam: key the reference lists, with each key's types."""
    resources = {r["Name"]: r.get("ConditionKeys", []) for r in reference["Resources"]}
    pairs = set()
    for action in reference["Actions"]:
        keys = set(action.get("ActionConditionKeys", []))
        keys |= {k for r in action.get("Resources", [])
                 for k in r.get("ConditionKeys", resources.get(r["Name"], []))}
        pairs |= {("iam:" + action["Name"], k) for k in keys if k.startswith("iam:") and "${" not in k}
    keys = {key for _, key in pairs}
    types = {k["Name"]: sorted(k.get("Types", [])) for k in reference["ConditionKeys"] if k["Name"] in keys}
    return sorted(pairs), dict(sorted(types.items()))


def classify(pairs, types, model):
    """The read entries and the notRead entries for the reference's pairs."""
    read, not_read = [], []
    for action, key in sorted(pairs):
        operation = model["operations"].get(action.split(":", 1)[1])
        local = key.split(":", 1)[1]
        members = model["shapes"][operation["input"]["shape"]].get("members", {}) \
            if operation and "input" in operation else {}
        member = next((m for m in sorted(members) if m.lower() == local.lower()), None)
        if operation is None:
            reason = "no SDK operation of this name"
        elif member is None:
            reason = STORED.get(key, "no member named after the key")
        elif not set(types.get(key, [])) <= READ_TYPES:
            reason = "the key's type is " + ", ".join(types.get(key) or ["not given"])
        elif model["shapes"][members[member]["shape"]]["type"] != "string":
            reason = f"{member} is not a string member"
        else:
            read.append({"action": action, "key": key, "member": member})
            continue
        not_read.append({"action": action, "key": key, "reason": reason})
    return read, not_read


def pairs_of(document):
    return sorted((e["action"], e["key"]) for e in document["read"] + document["notRead"])


def render(document):
    """The document with one entry to a line."""
    lines = ["{"]
    for key in ("source", "botocore", "types"):
        lines.append(f'  "{key}": {json.dumps(document[key])},')
    for section, tail in (("read", ","), ("notRead", "")):
        entries = document[section]
        lines.append(f'  "{section}": [')
        lines += [f"    {json.dumps(e)}{',' if i < len(entries) - 1 else ''}" for i, e in enumerate(entries)]
        lines.append("  ]" + tail)
    lines.append("}")
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--source", type=Path)
    parser.add_argument("--botocore-data", type=Path)
    args = parser.parse_args(argv)
    if args.check:
        document = json.loads(OUTPUT.read_text())
        pairs = pairs_of(document)
        if len(set(pairs)) != len(pairs):
            raise ValueError("an IAM condition key pair is listed twice")
        model = load_model(latest_model_dir(resolve_botocore_data(args.botocore_data)[0], "iam"))
        if classify(pairs, document["types"], model) != (document["read"], document["notRead"]):
            raise ValueError("IAM condition key members differ from pinned botocore")
        return 0
    try:
        if args.source:
            reference = json.loads(args.source.read_text())
        else:
            with urllib.request.urlopen(URL, timeout=30) as response:
                reference = json.load(response)
    except urllib.error.URLError as error:
        print(f"could not read {URL}: {error}", file=sys.stderr)
        return 2
    pairs, types = action_keys(reference)
    if args.verify:
        document = json.loads(OUTPUT.read_text())
        vendored = set(pairs_of(document))
        for action, key in sorted(set(pairs) - vendored):
            print(f"AWS lists {key} on {action}, which the table does not name", file=sys.stderr)
        for action, key in sorted(vendored - set(pairs)):
            print(f"the table names {key} on {action}, which AWS no longer lists", file=sys.stderr)
        if set(pairs) != vendored or document["types"] != types:
            raise ValueError("IAM condition keys differ from AWS Service Reference")
        return 0
    data, provenance = resolve_botocore_data(args.botocore_data)
    read, not_read = classify(pairs, types, load_model(latest_model_dir(data, "iam")))
    OUTPUT.write_text(render({"source": URL, "botocore": provenance, "types": types,
                              "read": read, "notRead": not_read}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
