#!/usr/bin/env python3
"""Generate the table of the IAM condition keys a request carries in its own members.

The AWS Service Reference lists every iam: condition key each IAM action can be evaluated with.
For each such action and key, pinned botocore says whether the request carries the value: the
operation of the same name has a top-level string member named after the key, ignoring case
(iam:PolicyARN is PolicyArn), and the key's type is String or ARN. BY_HAND names the member for a
pair whose value sits in a member under another name; its type has to fit the key's. Those pairs
are read. A FROM_TARGET key the request does not carry is the stored role's or user's value, and its
pair goes in fromTarget with the member that names the target. Every other pair the reference lists
is notRead, with the reason. An operation that has the member but whose action does not list the key
is left out, as AWS does not set the key for it.

--check re-derives read, fromTarget and notRead offline from the pairs in the file and pinned botocore, so a
hand-edited entry fails, as does a BY_HAND member botocore no longer has or a BY_HAND pair the
file does not list. --verify compares every pair and key type with the live reference and
fails on any difference, added pairs included: a key missing from the context stops a
conditioned Allow matching and a conditioned Deny applying. --source reads a saved reference.

roleTags lists the resource tag keys of existing role APIs addressed by RoleName, excluding
PassRole. requestTags lists the request tag keys of TagRole/TagUser and UntagRole/UntagUser.
Their action/key coverage is checked against the live reference by --verify; --check validates
the stored targets and list shapes against pinned botocore.
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
# Pairs whose value is a request member under another name: the member path, dotted for a nested one.
BY_HAND = {
    ("iam:CreateDelegationRequest", "iam:DelegationDuration"): "SessionDuration",
    ("iam:CreateDelegationRequest", "iam:TemplateArn"): "Permissions.PolicyTemplateArn",
    ("iam:CreateServiceSpecificCredential", "iam:ServiceSpecificCredentialAgeDays"): "CredentialAgeDays",
    ("iam:CreateServiceSpecificCredential", "iam:ServiceSpecificCredentialServiceName"): "ServiceName",
}
# The botocore types a BY_HAND member may have for each key type; the context value is the member's text.
BY_HAND_TYPES = {"String": {"string", "integer"}, "ARN": {"string"}, "Numeric": {"integer", "long"}}
# Keys whose value, on an action without the member, is the stored target's: the IAM User Guide's
# iam:PermissionsBoundary "checks that the specified policy is attached as permissions boundary on the
# IAM principal resource". The target is the role or user the request names.
FROM_TARGET = {"iam:PermissionsBoundary"}
TARGET_MEMBERS = ("RoleName", "UserName")
# Keys whose value, on an action without the member, does not come from the request.
NOT_IN_REQUEST = {
    "iam:AccountPropertyNamespaces": "the value is the namespaces of the properties read or written",
    "iam:DelegationRequestOwner": "the value is the delegation request's owner",
    "iam:FIDO-FIPS-140-2-certification": "the value comes from the FIDO Alliance Metadata Service",
    "iam:FIDO-FIPS-140-3-certification": "the value comes from the FIDO Alliance Metadata Service",
    "iam:FIDO-certification": "the value comes from the FIDO Alliance Metadata Service",
    "iam:RegisterSecurityKey": "the value is the MFA device's registration state",
    "iam:RoleTemplateARN": "the value is the role template a service acquires the role from",
    "iam:ServiceSpecificCredentialServiceName": "the value is the named credential's service",
}


# Existing role APIs addressed by RoleName, plus explicit role/user tagging requests.
# CreateRole's request tags are handled by TagOnCreate, not by looking up an existing role.
# The IAM User Guide explicitly excludes PassRole from ResourceTag authorization.
RESOURCE_TAG_KEYS = {"aws:ResourceTag/${TagKey}", "iam:ResourceTag/${TagKey}"}
REQUEST_TAG_KEYS = {"aws:RequestTag/${TagKey}", "aws:TagKeys"}
TAG_OPERATIONS = {"TagRole", "TagUser", "UntagRole", "UntagUser"}


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


def member_type(model, operation, path):
    """The botocore type of the request member at a dotted path, or None when there is none."""
    shape = model["shapes"][operation["input"]["shape"]] if operation and "input" in operation else {}
    for name in path.split("."):
        member = shape.get("members", {}).get(name)
        if member is None:
            return None
        shape = model["shapes"][member["shape"]]
    return shape["type"]


def classify(pairs, types, model):
    """The read, fromTarget and notRead entries for the reference's pairs."""
    read, from_target, not_read = [], [], []
    for action, key in sorted(pairs):
        operation = model["operations"].get(action.split(":", 1)[1])
        if (action, key) in BY_HAND:
            path = BY_HAND[(action, key)]
            found = member_type(model, operation, path)
            if found is None:
                raise ValueError(f"{action}: BY_HAND member {path} for {key} is not in botocore")
            if not all(found in BY_HAND_TYPES.get(t, set()) for t in types.get(key) or [None]):
                raise ValueError(f"{action}: BY_HAND member {path} is {found}, which does not fit {key}")
            read.append({"action": action, "key": key, "member": path})
            continue
        local = key.split(":", 1)[1]
        members = model["shapes"][operation["input"]["shape"]].get("members", {}) \
            if operation and "input" in operation else {}
        member = next((m for m in sorted(members) if m.lower() == local.lower()), None)
        if operation is None:
            reason = "no SDK operation of this name"
        elif member is None and key in FROM_TARGET:
            target = next((m for m in TARGET_MEMBERS if m in members), None)
            if target is None:
                raise ValueError(f"{action}: no member names the role or user {key} is read from")
            from_target.append({"action": action, "key": key, "target": target})
            continue
        elif member is None:
            reason = NOT_IN_REQUEST.get(key, "no member named after the key")
        elif not set(types.get(key, [])) <= READ_TYPES:
            reason = "the key's type is " + ", ".join(types.get(key) or ["not given"])
        elif model["shapes"][members[member]["shape"]]["type"] != "string":
            reason = f"{member} is not a string member"
        else:
            read.append({"action": action, "key": key, "member": member})
            continue
        not_read.append({"action": action, "key": key, "reason": reason})
    return read, from_target, not_read


def require_by_hand_listed(pairs):
    stale = sorted(set(BY_HAND) - set(pairs))
    if stale:
        raise ValueError(f"BY_HAND names pairs the reference does not list: {stale}")



def tag_context(reference, model):
    resources = {r["Name"]: r.get("ConditionKeys", []) for r in reference["Resources"]}
    role_tags, request_tags = [], []
    for action in sorted(reference["Actions"], key=lambda a: a["Name"]):
        name = action["Name"]
        operation = model["operations"].get(name)
        if (not name.startswith("Create") and name != "PassRole"
                and member_type(model, operation, "RoleName") == "string"):
            keys = {key for resource in action.get("Resources", []) if resource["Name"] == "role"
                    for key in resource.get("ConditionKeys", resources.get("role", []))}
            role_tags += [{"action": "iam:" + name, "key": key, "target": "RoleName"}
                          for key in sorted(keys & RESOURCE_TAG_KEYS)]
        if name in TAG_OPERATIONS:
            for key in sorted(set(action.get("ActionConditionKeys", [])) & REQUEST_TAG_KEYS):
                member = "TagKeys" if name.startswith("Untag") else "Tags"
                request_tags.append({"action": "iam:" + name, "key": key, "member": member})
    validate_tag_context(role_tags, request_tags, model)
    return role_tags, request_tags


def validate_tag_context(role_tags, request_tags, model):
    for entry in role_tags:
        name = entry["action"].removeprefix("iam:")
        if (entry["key"] not in RESOURCE_TAG_KEYS or entry["target"] != "RoleName"
                or name.startswith("Create") or name == "PassRole"
                or member_type(model, model["operations"].get(name), "RoleName") != "string"):
            raise ValueError(f"Invalid stored role tag target: {entry}")
    for entry in request_tags:
        name = entry["action"].removeprefix("iam:")
        member = "TagKeys" if name.startswith("Untag") else "Tags"
        if (name not in TAG_OPERATIONS or entry["key"] not in REQUEST_TAG_KEYS
                or (name.startswith("Untag") and entry["key"] != "aws:TagKeys")
                or entry["member"] != member
                or member_type(model, model["operations"].get(name), member) != "list"):
            raise ValueError(f"Invalid IAM request tag member: {entry}")
        operation = model["operations"][name]
        shape = model["shapes"][operation["input"]["shape"]]
        shape = model["shapes"][shape["members"][member]["shape"]]
        item = model["shapes"][shape["member"]["shape"]]
        valid = item["type"] == "string" if member == "TagKeys" else (
            item["type"] == "structure" and all(
                field in item.get("members", {})
                and model["shapes"][item["members"][field]["shape"]]["type"] == "string"
                for field in ("Key", "Value")))
        if not valid:
            raise ValueError(f"Invalid IAM request tag list shape: {entry}")


def pairs_of(document):
    return sorted((e["action"], e["key"])
                  for e in document["read"] + document["fromTarget"] + document["notRead"])


def render(document):
    """The document with one entry to a line."""
    lines = ["{"]
    for key in ("source", "botocore", "types"):
        lines.append(f'  "{key}": {json.dumps(document[key])},')
    for section, tail in (("read", ","), ("fromTarget", ","), ("notRead", ","),
                          ("roleTags", ","), ("requestTags", "")):
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
        require_by_hand_listed(pairs)
        model = load_model(latest_model_dir(resolve_botocore_data(args.botocore_data)[0], "iam"))
        if classify(pairs, document["types"], model) != (
                document["read"], document["fromTarget"], document["notRead"]):
            raise ValueError("IAM condition key members differ from pinned botocore")
        validate_tag_context(document["roleTags"], document["requestTags"], model)
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
        model = load_model(latest_model_dir(resolve_botocore_data(args.botocore_data)[0], "iam"))
        if tag_context(reference, model) != (document["roleTags"], document["requestTags"]):
            raise ValueError("IAM tag condition keys differ from AWS Service Reference")
        return 0
    require_by_hand_listed(pairs)
    data, provenance = resolve_botocore_data(args.botocore_data)
    model = load_model(latest_model_dir(data, "iam"))
    read, from_target, not_read = classify(pairs, types, model)
    role_tags, request_tags = tag_context(reference, model)
    OUTPUT.write_text(render({"source": URL, "botocore": provenance, "types": types,
                              "read": read, "fromTarget": from_target, "notRead": not_read,
                              "roleTags": role_tags, "requestTags": request_tags}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
