#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/request-condition-keys.json from AWS's Service Reference and botocore.

Some service condition keys take their value from the request, or from the resource the request acts on before
it. `SERVICES` names, for each namespace, the keys read and where:

- `eks`: a CreateCluster or UpdateClusterConfig request is checked with the access configuration and endpoint
  flags it names in its JSON body, as `eks:authenticationMode`, `eks:bootstrapClusterCreatorAdminPermissions`,
  `eks:endpointPrivateAccess` and `eks:endpointPublicAccess`; a member it leaves out names nothing.
- `secretsmanager`: existing secret tags supply `aws:ResourceTag/${TagKey}` and
  `secretsmanager:ResourceTag/tag-key` for the actions listed by the Service Reference.
- `lambda`: a Lambda Web Functions request is checked with the endpoint's authType as
  `lambda:Request/WebFunctionAuthType`, the one the request names, and `lambda:Resource/WebFunctionAuthType`,
  the one the endpoint has before the request, read from the stored endpoint.
- `redshift`: a Redshift request is checked with the database user it names as `redshift:DbUser`, so a policy
  can scope GetClusterCredentials and CreateClusterUser to `dbuser:<cluster>/${redshift:DbUser}`.
  `redshift:DbName` and `redshift:DurationSeconds` are not read yet.
- `ec2-instance-connect`: an OpenTunnel request is checked with the `remotePort`, `privateIpAddress` and
  `maxTunnelDuration` query parameters of its presigned WebSocket URL as the keys of the same names (the
  Service Reference spells the action's third key `MaxTunnelDuration`; IAM keys are case-insensitive).
  botocore does not model OpenTunnel (it is a WebSocket upgrade, not an API operation), so `SERVICES` gives
  its members itself (`"model": None`) and `--check` has no botocore model to compare them to.
- `route53`: a ChangeResourceRecordSets request is checked with every change's action, record type and record
  name as `route53:ChangeResourceRecordSetsActions`, `...RecordTypes` and `...NormalizedRecordNames`. The names
  are read as `DNSName` members, which Route 53 spells as AWS normalizes them for the key, through the spelling
  the service registers for that shape.

The table comes from two sources:

- AWS's Service Reference for each namespace, which gives each action's condition keys, for the action and
  for each resource type it acts on:

      https://servicereference.us-east-1.amazonaws.com/v1/<namespace>/<namespace>.json

- botocore's model of each namespace (Apache-2.0, https://github.com/boto/botocore, the pinned release), which
  gives where each request carries the value.

`actions` holds every action that takes a key a namespace reads, with the ones it takes. `members` holds, for
every action and key read from the request, the paths of the request members named as `SERVICES` gives, as the
request carries them: dotted for a nested one, `*` for each element of a list or map in a JSON body, and in an XML
body the elements botocore names, a list's own by the name of its member (`ChangeBatch.Changes.Change.Action`).
A Query request is read as a flat form, so its member must be top-level; an XML request is read from its body, so
its member must not be in the URI or a header, nor reached through a map or a flattened list. A key read from the
stored resource has no member, and an action whose request does not carry the key is in `not_in_request`, with
the reason. `memberShapes` gives each member's botocore shape, which a service may spell its own way (Route 53's
`DNSName`), and `protocols` each modelled namespace's protocol, which says how its requests are read.

The two gates split along the sources:

- `--check` is offline and needs the pinned botocore: `members` must be exactly what botocore gives.
- `--verify` is online and stdlib-only: `actions` must be exactly what the reference gives.

Run from anywhere in the repo:
    python3 tools/aws/regen_request_condition_keys.py           # rewrite the vendored file in place
    python3 tools/aws/regen_request_condition_keys.py --check   # offline: members match botocore
    python3 tools/aws/regen_request_condition_keys.py --verify  # online: actions match AWS
    python3 tools/aws/regen_request_condition_keys.py --source-dir <dir>  # read saved <namespace>.json files
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

OUTPUT = Path(__file__).resolve().parents[2] / "src/main/resources/aws/request-condition-keys.json"
REFERENCE = "https://servicereference.us-east-1.amazonaws.com/"
FLAT_PROTOCOLS = ("query", "ec2")

# For each namespace: its botocore model, each key read from the request with the name of the member that
# carries it, each key read from the stored resource, and the actions that take a key their request does not
# carry, with the reason.
SERVICES = {
    "secretsmanager": {
        "model": "secretsmanager",
        "request": {},
        "stored": ("aws:ResourceTag/${TagKey}", "secretsmanager:ResourceTag/tag-key"),
        "not_in_request": {},
    },
    "eks": {
        "model": "eks",
        "request": {
            "eks:authenticationMode": "authenticationMode",
            "eks:bootstrapClusterCreatorAdminPermissions": "bootstrapClusterCreatorAdminPermissions",
            "eks:endpointPrivateAccess": "endpointPrivateAccess",
            "eks:endpointPublicAccess": "endpointPublicAccess",
        },
        "stored": (),
        "not_in_request": {},
    },
    "lambda": {
        "model": "lambda-web",
        "request": {"lambda:Request/WebFunctionAuthType": "authType"},
        "stored": ("lambda:Resource/WebFunctionAuthType",),
        "not_in_request": {},
    },
    "ec2-instance-connect": {
        "model": None,
        "request": {"ec2-instance-connect:remotePort": "remotePort",
                    "ec2-instance-connect:privateIpAddress": "privateIpAddress",
                    "ec2-instance-connect:MaxTunnelDuration": "maxTunnelDuration"},
        "stored": (),
        "not_in_request": {},
    },
    "redshift": {
        "model": "redshift",
        "request": {"redshift:DbUser": "DbUser"},
        "stored": (),
        "not_in_request": {
            ("redshift:CreateClusterUser", "redshift:DbUser"): "no operation of its own: GetClusterCredentials "
                                                               "checks it when AutoCreate is true, with the DbUser "
                                                               "of its own request",
        },
    },
    "route53": {
        "model": "route53",
        "request": {"route53:ChangeResourceRecordSetsActions": "Action",
                    "route53:ChangeResourceRecordSetsNormalizedRecordNames": "Name",
                    "route53:ChangeResourceRecordSetsRecordTypes": "Type"},
        "stored": (),
        "not_in_request": {},
    },
}


def url(namespace: str) -> str:
    return f"{REFERENCE}v1/{namespace}/{namespace}.json"


def action_keys(namespace: str, reference: dict) -> dict[str, list[str]]:
    """Every action of the namespace that takes a key it reads, with the ones it takes."""
    service = SERVICES[namespace]
    read = set(service["request"]) | set(service["stored"])
    resource_keys = _resource_keys([reference])
    found = {}
    for action in sorted(reference["Actions"], key=lambda entry: entry["Name"]):
        keys = read & _condition_keys(namespace, action, resource_keys)
        if keys:
            found[f"{namespace}:{action['Name']}"] = sorted(keys)
    return found


def request_members(namespace: str, model: dict | None,
                    actions: dict[str, list[str]]) -> dict[str, dict[str, list[str]]]:
    """For every action of the namespace and key read from its request, where the request carries the value."""
    found = {}
    for action, key, path, _ in _located(namespace, model, actions):
        found.setdefault(action, {}).setdefault(key, []).append(path)
    return found


def member_shapes(namespace: str, model: dict | None,
                  actions: dict[str, list[str]]) -> dict[str, dict[str, str]]:
    """For every action of the namespace, the botocore shape of each request member a key is read from."""
    found = {}
    for action, _, path, shape in _located(namespace, model, actions):
        if shape is not None:
            found.setdefault(action, {})[path] = shape
    return found


def _located(namespace: str, model: dict | None, actions: dict[str, list[str]]):
    """Each (action, key, path, shape) of a key the namespace reads from its requests, the path as `members` gives
    it and the shape botocore gives the member (None for a namespace botocore does not model)."""
    service = SERVICES[namespace]
    protocol = model.get("metadata", {}).get("protocol") if model is not None else None
    for action, keys in sorted(actions.items()):
        if not action.startswith(f"{namespace}:"):
            continue
        for key in keys:
            if key not in service["request"] or (action, key) in service["not_in_request"]:
                continue
            name = service["request"][key]
            if model is None:  # not modelled by botocore: the member is the name SERVICES gives
                yield action, key, name, None
                continue
            operation = model["operations"].get(action.split(":", 1)[1])
            if operation is None or "input" not in operation:
                raise ValueError(f"{action} takes {key} but botocore has no request for it")
            paths = member_paths(model, operation["input"]["shape"], {name})
            if not paths:
                raise ValueError(f"{action} takes {key} but its request has no {name} member")
            nested = [path for path in paths if protocol in FLAT_PROTOCOLS and "." in path]
            if nested:
                raise ValueError(f"{action} takes {key} from {', '.join(nested)}, which a flat form does not carry")
            for path in paths:
                carried, shape = carried_member(model, operation["input"]["shape"], path, protocol == "rest-xml")
                yield action, key, carried, shape


def carried_member(model: dict, shape: str, path: str, xml: bool) -> tuple[str, str]:
    """The member at `path`, as `member_paths` spells it, as the request carries it, with its botocore shape: the
    same path in a form or a JSON body; in an XML body the elements botocore names, a list's own by the name of its
    member. An XML body is read from its elements alone, so a member in the URI or a header, or one reached through a
    map or a flattened list, which do not nest as these paths do, fails rather than being read wrong."""
    steps = []
    for index, step in enumerate(path.split(".")):
        spec = model["shapes"][shape]
        if step == "*":
            element = spec.get("member") or spec["value"]
            if xml and ("member" not in spec or spec.get("flattened")):
                raise ValueError(f"{path} reaches the elements of a map or a flattened list, "
                                 "which the XML reader does not read")
            steps.append(element.get("locationName", "member") if xml else "*")
            shape = element["shape"]
            continue
        member = spec["members"][step]
        if xml and index == 0 and "location" in member:
            raise ValueError(f"{path} is carried in the {member['location']}, which the XML reader does not read")
        steps.append(member.get("locationName", step) if xml else step)
        shape = member["shape"]
    return ".".join(steps), shape


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
    actions, members = document["actions"], document["members"]
    if "lambda:Request/WebFunctionAuthType" not in actions.get("lambda:CreateWebFunctionEndpoint", []):
        problems.append("lambda:CreateWebFunctionEndpoint takes no lambda:Request/WebFunctionAuthType")
    if "lambda:Resource/WebFunctionAuthType" not in actions.get("lambda:GetWebFunctionEndpoint", []):
        problems.append("lambda:GetWebFunctionEndpoint takes no lambda:Resource/WebFunctionAuthType")
    if members.get("redshift:GetClusterCredentials", {}).get("redshift:DbUser") != ["DbUser"]:
        problems.append("redshift:GetClusterCredentials does not read redshift:DbUser from DbUser")
    if members.get("ec2-instance-connect:OpenTunnel", {}).get("ec2-instance-connect:remotePort") != ["remotePort"]:
        problems.append("ec2-instance-connect:OpenTunnel does not read ec2-instance-connect:remotePort from remotePort")
    record_name = "ChangeBatch.Changes.Change.ResourceRecordSet.Name"
    if members.get("route53:ChangeResourceRecordSets", {}).get(
            "route53:ChangeResourceRecordSetsNormalizedRecordNames") != [record_name]:
        problems.append("route53:ChangeResourceRecordSets does not read its record names from " + record_name)
    if document.get("memberShapes", {}).get("route53:ChangeResourceRecordSets", {}).get(record_name) != "DNSName":
        problems.append(f"route53:ChangeResourceRecordSets does not give {record_name} the shape DNSName")
    if document.get("protocols", {}).get("route53") != "rest-xml":
        problems.append("route53 is not read as rest-xml")
    return problems


def _reference(namespace: str, source_dir: Path | None) -> dict:
    if source_dir is not None:
        return json.loads((source_dir / f"{namespace}.json").read_text(encoding="utf-8"))
    request = urllib.request.Request(url(namespace), headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def _actions(source_dir: Path | None) -> dict[str, list[str]]:
    actions = {}
    for namespace in SERVICES:
        actions.update(action_keys(namespace, _reference(namespace, source_dir)))
    return dict(sorted(actions.items()))


def _members(actions: dict[str, list[str]], botocore_data: Path | None) -> tuple[dict, str]:
    """The `members`, `memberShapes` and `protocols` sections botocore gives, and its provenance."""
    data, provenance = resolve_botocore_data(botocore_data)
    members, shapes, protocols = {}, {}, {}
    for namespace, service in SERVICES.items():
        model = service["model"] and load_model(latest_model_dir(data, service["model"]))
        members.update(request_members(namespace, model, actions))
        shapes.update(member_shapes(namespace, model, actions))
        if model:
            protocols[namespace] = model["metadata"]["protocol"]
    return {"members": dict(sorted(members.items())), "memberShapes": dict(sorted(shapes.items())),
            "protocols": dict(sorted(protocols.items()))}, provenance


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="offline: members must match the pinned botocore")
    parser.add_argument("--verify", action="store_true", help="online: actions must match the Service Reference")
    parser.add_argument("--source-dir", type=Path, help="read saved <namespace>.json Service Reference files")
    parser.add_argument("--botocore-data", type=Path, help="botocore data directory")
    args = parser.parse_args(argv)

    if args.check or args.verify:
        document = json.loads(OUTPUT.read_text(encoding="utf-8"))
        problems = validate(document)
        if args.check and not problems:
            sections, provenance = _members(document["actions"], args.botocore_data)
            for section, expected in sections.items():
                if document.get(section) != expected:
                    problems.append(f"{section} differ from {provenance}")
        if args.verify and document.get("actions") != _actions(args.source_dir):
            problems.append("actions differ from the AWS Service Reference")
        for problem in problems:
            print(f"{OUTPUT.name}: {problem}", file=sys.stderr)
        return 1 if problems else 0

    actions = _actions(args.source_dir)
    sections, provenance = _members(actions, args.botocore_data)
    document = {"_source": {"generator": "tools/aws/regen_request_condition_keys.py", "reference": REFERENCE,
                            "botocore": provenance,
                            "note": "make request-condition-keys-check re-derives members, their shapes and the "
                                    "protocols from botocore; make request-condition-keys-verify checks actions "
                                    "against AWS."},
                "actions": actions, **sections}
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
