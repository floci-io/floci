#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/iam-passed-network-connectors.json from AWS's Service Reference and botocore.

A request that names a Lambda network connector also needs `lambda:PassNetworkConnector` on it. The Lambda
developer guide, for Web Functions: "The caller must have `lambda:PassNetworkConnector` on the referenced
connector whenever a `create-web-function` or `create-web-function-revision` call sets
`egressNetworkConnectorArn`." `PassedNetworkConnectors` finds the connectors a request passes, and this file
tells it where each operation's request carries them. It comes from the same two sources as
`regen_iam_passed_roles.py`:

- AWS's Service Reference, which lists `lambda:PassNetworkConnector` among an operation's `AuthorizedActions`
  but does not say which request member names the connector:

      https://servicereference.us-east-1.amazonaws.com/            (the index of services)
      https://servicereference.us-east-1.amazonaws.com/v1/<svc>/<svc>.json

- botocore's model of each SDK client the reference lists the operation for (Apache-2.0,
  https://github.com/boto/botocore, the pinned release), which gives the request's members.

Every operation the reference lists with the action is read, in any namespace. A member of its request names
a connector when its name ends in `NetworkConnector`, `NetworkConnectorArn` or the plural of one, and it is a
string or a list of strings without an enum. The members are kept in sorted order, which is the order AWS
checks them in: RunMicrovm's egress connectors before its ingress ones.

Where a request names no connector in a member, or an empty list, AWS checks the Lambda-managed connector the
MicroVM gets instead (measured, us-east-1). Those are recorded in `WHEN_ABSENT` and vendored as `whenAbsent`,
the connector's name, which Floci reads as `arn:<partition>:lambda:<region>:aws:network-connector:<name>`.
An operation whose request names no connector this rule reads is in `NOT_READ` with its reason, and generation
fails on one that is in neither.

The two gates split along the sources, as for the passed role table:

- `--check` is offline and needs the pinned botocore. The file must be well-formed, and every operation's
  members must be exactly what the rule and `WHEN_ABSENT` give from botocore for the models the file names.
- `--verify` is online and stdlib-only. Every operation the file carries must still list the action, for the
  same SDK clients. An operation AWS has added is reported without failing: until the next sync it passes
  connectors unchecked, which is where it was before this file existed.

Run from anywhere in the repo:
    python3 tools/aws/regen_iam_passed_network_connectors.py           # rewrite the vendored file in place
    python3 tools/aws/regen_iam_passed_network_connectors.py --check   # offline: members match botocore
    python3 tools/aws/regen_iam_passed_network_connectors.py --verify  # online: operations match AWS
    python3 tools/aws/regen_iam_passed_network_connectors.py --source-dir <dir>  # read saved <svc>.json files
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

from regen_iam_authorized_actions import fetch_references
from regen_iam_passed_roles import (BODY_LOCATIONS, MEMBER_PATH_RE, NAMESPACE_RE, OPERATION_RE, _leaf, _shown, _step,
                                    load_models, namespace_of, readable)
from regen_partitions import resolve_botocore_data

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-passed-network-connectors.json"

ACTION = ("lambda", "PassNetworkConnector")

# A member that names a connector: NetworkConnector, NetworkConnectorArn and their plurals.
CONNECTOR_MEMBER_RE = re.compile(r"networkconnector(arn)?s?$", re.IGNORECASE)
# A Lambda-managed connector's name.
MANAGED_CONNECTOR_RE = re.compile(r"^aws-network-connector:[A-Z_]+$")

# The connector AWS checks for a member the request leaves out or gives as an empty list, measured on AWS
# with a caller allowed every other action the request needs.
WHEN_ABSENT = {
    "lambda:CreateMicrovmImage": {"egressNetworkConnectors[]": "aws-network-connector:INTERNET_EGRESS"},
    "lambda:RunMicrovm": {"egressNetworkConnectors[]": "aws-network-connector:INTERNET_EGRESS",
                          "ingressNetworkConnectors[]": "aws-network-connector:HTTP_INGRESS"},
    "lambda:UpdateMicrovmImage": {"egressNetworkConnectors[]": "aws-network-connector:INTERNET_EGRESS"},
}

# Operations the reference lists with the action whose request names no connector this file can read.
NOT_READ = {
    # AWS checks the connector in httpConfig.networkConnectorArn (measured, us-east-1), which botocore's
    # model does not have yet.
    "appsync:CreateDataSource": "botocore's model has no request member that names a connector",
    "appsync:UpdateDataSource": "botocore's model has no request member that names a connector",
}


# --------------------------------------------------------------------------- #
# The Service Reference: which operations pass a connector
# --------------------------------------------------------------------------- #
def passed_connector_operations(documents: list[dict]) -> dict[str, dict]:
    """Every operation of every namespace that lists lambda:PassNetworkConnector, with the SDK clients it is
    listed for."""
    found: dict[str, set[str]] = {}
    for document in documents:
        namespace = document.get("Name")
        if not isinstance(namespace, str) or not NAMESPACE_RE.match(namespace):
            raise ValueError(f"{namespace!r} is not usable as an IAM namespace")
        for operation in document.get("Operations", []):
            name = operation.get("Name") if isinstance(operation, dict) else None
            if not isinstance(name, str):
                raise ValueError(f"{namespace}: operation entry without a usable 'Name': {operation!r}")
            authorized = operation.get("AuthorizedActions") or []
            if not isinstance(authorized, list) or not all(isinstance(entry, dict) for entry in authorized):
                raise ValueError(f"{namespace}:{name}: 'AuthorizedActions' is not a list of objects")
            if not any((entry.get("Service"), entry.get("Name")) == ACTION for entry in authorized):
                continue
            clients = {sdk["Name"] for sdk in operation.get("SDK") or []
                       if isinstance(sdk, dict) and sdk.get("Package") == "Boto3" and isinstance(sdk.get("Name"), str)}
            found.setdefault(f"{namespace}:{name}", set()).update(clients)
    return {key: {"clients": sorted(clients)} for key, clients in sorted(found.items())}


# --------------------------------------------------------------------------- #
# botocore: which members of the request name a connector
# --------------------------------------------------------------------------- #
def connector_members(model: dict, operation: str) -> list[str]:
    """The members of the operation's request the rule reads, as vendored paths, sorted."""
    spec = model["operations"].get(operation)
    if spec is None or "input" not in spec or not readable(model):
        return []
    root = model["shapes"][spec["input"]["shape"]]
    payload = root.get("payload")
    found: set[str] = set()

    def walk(shape_name: str, prefix: str, top: bool, seen: frozenset) -> None:
        if shape_name in seen:
            return
        shape = model["shapes"][shape_name]
        seen = seen | {shape_name}
        if shape["type"] == "list":
            walk(shape["member"]["shape"], prefix + _step(shape), False, seen)
        elif shape["type"] == "map":
            walk(shape["value"]["shape"], prefix + _step(shape), False, seen)
        elif shape["type"] == "structure":
            for name, member in shape.get("members", {}).items():
                if top and member.get("location") not in BODY_LOCATIONS:
                    continue
                serialized = member.get("locationName", name)
                path = f"{prefix}.{serialized}" if prefix else serialized
                if CONNECTOR_MEMBER_RE.search(name) and _leaf(model, member["shape"]) is not None:
                    found.add(path + _step(model["shapes"][member["shape"]]))
                else:
                    walk(member["shape"], path, False, seen)

    if payload is not None:
        walk(root["members"][payload]["shape"], "", False, frozenset())
    else:
        walk(spec["input"]["shape"], "", True, frozenset())
    return sorted(found)


# --------------------------------------------------------------------------- #
# The vendored document
# --------------------------------------------------------------------------- #
def entry_for(operation: str, models: dict[str, dict]) -> dict:
    """What the file says for one operation, from the models of its namespace."""
    name = operation.split(":", 1)[1]
    members = sorted({path for model in models.values() for path in connector_members(model, name)})
    if operation in NOT_READ:
        if members:
            raise ValueError(f"{operation}: NOT_READ says it names no connector, but its request has {members}")
        return {"notRead": NOT_READ[operation]}
    if not members:
        raise ValueError(f"{operation}: no request member names a network connector; "
                         f"say why it is not read (NOT_READ)")
    absent = WHEN_ABSENT.get(operation, {})
    unknown = sorted(set(absent) - set(members))
    if unknown:
        raise ValueError(f"{operation}: WHEN_ABSENT names {unknown}, which its request does not have")
    connectors = []
    for path in members:
        connector: dict = {"member": path}
        if path in absent:
            connector["whenAbsent"] = absent[path]
        connectors.append(connector)
    return {"connectors": connectors}


def build(documents: list[dict], provenance: str, data: Path, botocore: str) -> dict:
    upstream = passed_connector_operations(documents)
    stale = sorted((set(WHEN_ABSENT) | set(NOT_READ)) - set(upstream)) + sorted(set(WHEN_ABSENT) & set(NOT_READ))
    if stale:
        raise ValueError(f"WHEN_ABSENT or NOT_READ names {stale}, which the reference does not list with "
                         f"{ACTION[0]}:{ACTION[1]}, or both name it")
    namespaces: dict[str, set[str]] = {}
    for operation, value in upstream.items():
        namespaces.setdefault(namespace_of(operation), set()).update(value["clients"])
    operations = {}
    unread = []
    for operation in upstream:
        try:
            operations[operation] = entry_for(operation, load_models(data, namespaces[namespace_of(operation)]))
        except ValueError as e:
            unread.append(str(e))
    if unread:
        raise ValueError("\n".join(unread))
    return {
        "_source": {
            "generator": "tools/aws/regen_iam_passed_network_connectors.py",
            "reference": provenance,
            "botocore": botocore,
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "make iam-passed-network-connectors-check re-derives every member from botocore; "
                    "make iam-passed-network-connectors-verify checks every operation against AWS.",
        },
        "namespaces": {namespace: sorted(clients) for namespace, clients in sorted(namespaces.items())},
        "operations": operations,
    }


def render(document: dict) -> str:
    """The document with one operation to a line and one connector to a line under it."""
    lines = ["{"]
    for key in ("_source", "namespaces"):
        lines.append(f'  "{key}": {json.dumps(document[key])},')
    lines.append('  "operations": {')
    operations = list(document["operations"].items())
    for index, (operation, entry) in enumerate(operations):
        if "connectors" not in entry:
            lines.append(f"    {json.dumps(operation)}: {json.dumps(entry)}{',' if index < len(operations) - 1 else ''}")
            continue
        lines.append(f'    {json.dumps(operation)}: {{"connectors": [')
        connectors = entry["connectors"]
        for connector_index, connector in enumerate(connectors):
            lines.append(f"      {json.dumps(connector)}{',' if connector_index < len(connectors) - 1 else ''}")
        lines.append(f"    ]}}{',' if index < len(operations) - 1 else ''}")
    lines += ["  }", "}"]
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------- #
# Gates
# --------------------------------------------------------------------------- #
def validate(document: dict) -> list[str]:
    """Problems that make the vendored file unusable, rather than out of date."""
    problems: list[str] = []
    namespaces = document.get("namespaces")
    operations = document.get("operations")
    if not isinstance(namespaces, dict) or not namespaces:
        return ["namespaces is missing or empty"]
    if not isinstance(operations, dict) or not operations:
        return ["operations is missing or empty"]
    if list(namespaces) != sorted(namespaces):
        problems.append("namespaces is not sorted")
    if list(operations) != sorted(operations):
        problems.append("operations is not sorted")
    for operation, entry in operations.items():
        if not OPERATION_RE.match(operation) or namespace_of(operation) not in namespaces:
            problems.append(f"{operation!r} is not an operation of a namespace the file reads")
            continue
        if isinstance(entry, dict) and set(entry) == {"notRead"}:
            if not isinstance(entry["notRead"], str) or not entry["notRead"]:
                problems.append(f"{operation} gives no reason for notRead")
            continue
        connectors = entry.get("connectors") if isinstance(entry, dict) else None
        if not isinstance(connectors, list) or not connectors or set(entry) != {"connectors"}:
            problems.append(f"{operation} is not exactly one of connectors and notRead")
            continue
        paths = [connector.get("member") if isinstance(connector, dict) else None for connector in connectors]
        if paths != sorted(path for path in paths if isinstance(path, str)):
            problems.append(f"{operation}: the connectors are not in sorted order")
        for connector in connectors:
            path = connector.get("member") if isinstance(connector, dict) else None
            if not isinstance(path, str) or not MEMBER_PATH_RE.match(path):
                problems.append(f"{operation} reads {connector!r}, which is no member path")
                continue
            if set(connector) - {"member", "whenAbsent"}:
                problems.append(f"{operation}: the connector at {path} carries an unknown field")
            absent = connector.get("whenAbsent")
            if absent is not None and (not isinstance(absent, str) or not MANAGED_CONNECTOR_RE.match(absent)):
                problems.append(f"{operation}: the connector at {path} falls back to {absent!r}, "
                                f"which is no Lambda-managed connector")
    return problems


def derive(document: dict, data: Path) -> list[str]:
    """Where the members the file gives differ from what botocore gives for the models it names."""
    problems: list[str] = []
    for operation, entry in document["operations"].items():
        models = load_models(data, set(document["namespaces"][namespace_of(operation)]))
        try:
            expected = entry_for(operation, models)
        except ValueError as e:
            problems.append(str(e))
            continue
        if expected != entry:
            problems.append(f"{operation}: the file has {json.dumps(entry)}, botocore gives {json.dumps(expected)}")
    return problems


def verify_against(vendored: dict, upstream: dict[str, dict]) -> tuple[list[str], list[str]]:
    """What the file claims and AWS does not, and the operations AWS has added since."""
    untrue: list[str] = []
    for operation in vendored["operations"]:
        actual = upstream.get(operation)
        if actual is None:
            untrue.append(f"AWS no longer lists {ACTION[0]}:{ACTION[1]} for {operation}")
        elif not set(actual["clients"]) <= set(vendored["namespaces"][namespace_of(operation)]):
            untrue.append(f"AWS lists {operation} for {actual['clients']}, which the file does not read")
    added = sorted(operation for operation in upstream if operation not in vendored["operations"])
    return untrue, added


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="offline: members match the pinned botocore")
    parser.add_argument("--verify", action="store_true", help="online: operations match AWS")
    parser.add_argument("--source-dir", type=Path, default=None,
                        help="read saved <service>.json reference files instead of fetching them")
    parser.add_argument("--botocore-data", type=Path, default=None, help="botocore data directory")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    if args.check or args.verify:
        if not args.output.exists():
            print(f"error: {args.output} does not exist", file=sys.stderr)
            return 1
        document = json.loads(args.output.read_text(encoding="utf-8"))
        problems = validate(document)
        if not problems and args.check:
            data, _ = resolve_botocore_data(args.botocore_data)
            problems = derive(document, data)
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        if problems:
            return 1
        entries = len(document["operations"])
        if args.check:
            print(f"{_shown(args.output)} matches botocore ({entries} operations)")
            return 0
        try:
            documents, provenance = fetch_references(args.source_dir)
            upstream = passed_connector_operations(documents)
        except (OSError, ValueError) as e:
            print(f"error: could not read the Service Reference ({e})", file=sys.stderr)
            return 2
        untrue, added = verify_against(document, upstream)
        for claim in untrue:
            print(f"error: {claim}", file=sys.stderr)
        if untrue:
            print(f"error: {len(untrue)} vendored operation(s) do not hold upstream.", file=sys.stderr)
            return 1
        if added:
            print(f"{_shown(args.output)}: all {entries} operations verified. AWS has since added "
                  f"{len(added)}, so the file is incomplete but not wrong (run without --verify to refresh): "
                  f"{', '.join(added)}")
        else:
            print(f"{_shown(args.output)}: all {entries} operations verified against {provenance}, "
                  f"nothing added upstream.")
        return 0

    data, botocore = resolve_botocore_data(args.botocore_data)
    try:
        documents, provenance = fetch_references(args.source_dir)
        document = build(documents, provenance, data, botocore)
    except (OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    problems = validate(document)
    for problem in problems:
        print(f"error: {problem}", file=sys.stderr)
    if problems:
        return 1
    args.output.write_text(render(document), encoding="utf-8")
    print(f"wrote {_shown(args.output)} ({len(document['operations'])} operations, from {provenance})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
