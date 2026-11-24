#!/usr/bin/env python3
"""Vendor RPC v2 blob/timestamp shapes from the repository-pinned botocore models.

Only ancestors of wire-sensitive leaves are retained. Ordinary fields are copied verbatim by
RpcV2CborCodec. The API Gateway V2 JSON facade uses Smithy names and numeric dates, unlike its REST model.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from regen_partitions import load_model, resolve_botocore_data, service_model, strip_source
from request_shapes import REPO_ROOT

CATALOG = REPO_ROOT / "src/main/java/io/floci/aws/core/common/ResolvedServiceCatalog.java"
OUTPUT = REPO_ROOT / "src/main/resources/aws/rpcv2-cbor-shapes.json"
JSON_ONLY_PREFIXES = {"DynamoDB_20111205", "CloudWatchVersion20130116"}
# Floci's JSON facades/short alias do not use the model's targetPrefix.
MODEL_ALIASES = {"AmazonApiGatewayV2": "apigatewayv2", "CloudTrail_20131101": "cloudtrail",
                 "SNS_20100331": "sns"}
AGENT_PREFIX = "AmazonSSMMessageDeliveryService"
# The agent facade has no pinned botocore model. MDS Message fields, including CreatedDate and Payload, are strings.
# aws/amazon-ssm-agent agent/runcommand/mds/mdsservice.go, immutable blob:
# https://api.github.com/repos/aws/amazon-ssm-agent/git/blobs/f3e4d67147e5027ca2568d597077e56310921610


def project_model(model: dict, json_facade: bool = False) -> dict:
    shapes = model["shapes"]
    kept = {name for name, shape in shapes.items() if shape["type"] in {"blob", "timestamp"}}
    while True:
        before = len(kept)
        for name, shape in shapes.items():
            kind = shape["type"]
            if kind == "structure":
                members = shape.get("members", {})
                if any(spec["shape"] in kept or (not json_facade and spec.get("locationName", member) != member)
                       for member, spec in members.items()):
                    kept.add(name)
            elif kind in {"list", "map"}:
                spec = shape["member" if kind == "list" else "value"]
                if spec["shape"] in kept:
                    kept.add(name)
        if len(kept) == before:
            break
    projected = {}
    for name in sorted(kept):
        shape = shapes[name]
        kind = shape["type"]
        entry = {"type": kind}
        if "timestampFormat" in shape and not json_facade:
            entry["timestampFormat"] = shape["timestampFormat"]
        if kind == "structure":
            members = {}
            for member, spec in shape.get("members", {}).items():
                json_name = member if json_facade else spec.get("locationName", member)
                if spec["shape"] not in kept and json_name == member:
                    continue
                item = {"shape": spec["shape"] if spec["shape"] in kept else None}
                if json_name != member:
                    item["jsonName"] = json_name
                if "timestampFormat" in spec and not json_facade:
                    item["timestampFormat"] = spec["timestampFormat"]
                members[member] = item
            entry["members"] = members
        elif kind in {"list", "map"}:
            key = "member" if kind == "list" else "value"
            entry[key] = shape[key]["shape"]
        projected[name] = entry
    operations = {}
    for name, operation in sorted(model["operations"].items()):
        roots = {direction: operation.get(direction, {}).get("shape") for direction in ("input", "output")}
        entry = {key: root if root in kept else None for key, root in roots.items()}
        entry["hasOutput"] = "output" in operation
        output = shapes.get(roots["output"], {})
        for member in output.get("members", {}).values():
            if shapes[member["shape"]].get("eventstream"):
                entry["eventStream"] = member["shape"]
        if entry != {"input": None, "output": None, "hasOutput": True}:
            operations[name] = entry
    return {"timestampFormat": "iso8601" if not json_facade and model["metadata"].get("protocol") in {"rest-json", "query"}
            else "unixTimestamp", "operations": operations, "shapes": projected}


def build(data: Path, catalog: Path, provenance: str) -> dict:
    prefixes = set(re.findall(r'"([A-Za-z0-9_.-]+)\."', catalog.read_text())) - JSON_ONLY_PREFIXES
    models = {}
    for directory in sorted(path for path in data.iterdir() if path.is_dir()):
        files = [source for version in sorted(directory.iterdir(), reverse=True) if version.is_dir()
                 if (source := service_model(version)) is not None]
        if files:
            models[directory.name] = load_model(files[0])
    selected = {name for name, model in models.items() if model["metadata"].get("targetPrefix") in prefixes}
    selected.update(name for prefix, name in MODEL_ALIASES.items() if prefix in prefixes)
    services = {name: project_model(models[name], json_facade=name == "apigatewayv2") for name in sorted(selected)}
    aliases = {}
    for name in sorted(selected):
        metadata = models[name]["metadata"]
        for alias in (metadata.get("targetPrefix"), metadata.get("serviceId")):
            if alias:
                if alias in aliases and aliases[alias] != name:
                    raise ValueError(f"Ambiguous RPC v2 service alias: {alias}")
                aliases[alias] = name
    aliases.update({prefix: name for prefix, name in MODEL_ALIASES.items() if prefix in prefixes})
    aliases["AWS Marketplace Entitlement Service"] = "marketplace-entitlement"
    services["ec2messages"] = {"timestampFormat": "unixTimestamp", "shapes": {}, "operations": {}}
    aliases[AGENT_PREFIX] = "ec2messages"
    missing = prefixes - aliases.keys()
    if missing:
        raise ValueError(f"No RPC v2 wire model for catalog prefixes: {sorted(missing)}")
    return {"_source": {"generator": "tools/aws/regen_rpcv2_cbor_shapes.py", "botocore": provenance,
                         "ec2messages": "aws/amazon-ssm-agent agent/runcommand/mds/mdsservice.go blob f3e4d67147e5027ca2568d597077e56310921610"},
            "aliases": dict(sorted(aliases.items())), "services": dict(sorted(services.items()))}


def render(document: dict) -> str:
    """Keep each alias, operation and shape on one line, like the other vendored wire models."""
    lines = ["{", f'  "_source": {json.dumps(document["_source"])},', '  "aliases": {']
    aliases = list(document["aliases"].items())
    for index, (name, service) in enumerate(aliases):
        comma = "," if index < len(aliases) - 1 else ""
        lines.append(f"    {json.dumps(name)}: {json.dumps(service)}{comma}")
    lines += ["  },", '  "services": {']
    services = list(document["services"].items())
    for index, (name, service) in enumerate(services):
        lines.append(f"    {json.dumps(name)}: {{")
        lines.append(f'      "timestampFormat": {json.dumps(service["timestampFormat"])},')
        for group in ("operations", "shapes"):
            lines.append(f"      {json.dumps(group)}: {{")
            entries = list(service[group].items())
            for position, (key, value) in enumerate(entries):
                comma = "," if position < len(entries) - 1 else ""
                lines.append(f"        {json.dumps(key)}: {json.dumps(value)}{comma}")
            lines.append("      }" + ("," if group == "operations" else ""))
        lines.append("    }" + ("," if index < len(services) - 1 else ""))
    lines += ["  }", "}"]
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--botocore-data", type=Path)
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    data, provenance = resolve_botocore_data(args.botocore_data)
    result = build(data, args.catalog, provenance)
    if args.check:
        if strip_source(args.output.read_text()) != strip_source(json.dumps(result)):
            print(f"error: {args.output} is stale; run tools/aws/regen_rpcv2_cbor_shapes.py")
            return 1
    else:
        args.output.write_text(render(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
