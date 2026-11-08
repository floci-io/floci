#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/cfn-property-types.json from CloudFormation's resource schemas.

CloudFormation checks every resource property against the type the resource schema declares
before it touches any resource. `CfnPropertyTypes` does the same for the resource types whose
provisioners read their properties through it, and needs only the types: for each resource type
its properties, and for every type reachable from one, whether it is an object, its members and
the ones it requires, a list and its items, a map and its values, or a scalar. Members keep the
schema's order, which a resource handler's model validation reports violations in.

The schemas are the ones AWS publishes for us-east-1, the same `describe-type` returns:

    https://schema.cloudformation.us-east-1.amazonaws.com/CloudformationSchema.zip

cfn-lint ships copies of them, but widens some types (a Lambda environment variable's value takes
any JSON type there, a string in the published schema), so they are not used.

The output has the request shapes' layout, so `RequestShapeModel` reads it: `operations` names each
resource type's properties shape, and `shapes` holds a `structure` for an object with properties
(with `required` when the schema requires some of them), a `list`, a `map` for an object keyed by pattern, `string`, `integer`, `boolean` or `double`, and
`json` for anything else (a free-form object, a value of several types), which is not checked.

The archive is live rather than a pinned dependency and keeps no older copy, so, as with
`regen_iam_authorized_actions.py`:

- `--check` is offline and structural: every resource type listed here is present, every shape a
  shape names exists, and the anchor cases still hold;
- `--verify` is online: a fresh generation must equal the vendored file, so a schema AWS has
  changed since is reported; it exits 2 when the archive cannot be read.

Run from anywhere in the repo:
    python3 tools/aws/regen_cfn_property_types.py            # fetch and rewrite the vendored file
    python3 tools/aws/regen_cfn_property_types.py --check    # offline: the vendored file is well-formed
    python3 tools/aws/regen_cfn_property_types.py --verify   # online: the vendored file matches AWS
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/cfn-property-types.json"
ARCHIVE_URL = "https://schema.cloudformation.us-east-1.amazonaws.com/CloudformationSchema.zip"
FETCH_TIMEOUT_SECONDS = 60

# The resource types whose provisioners read their properties through CfnPropertyTypes.
RESOURCE_TYPES = ["AWS::AutoScaling::LaunchConfiguration", "AWS::DSQL::Cluster", "AWS::EC2::Instance",
                  "AWS::EC2::LaunchTemplate", "AWS::ECS::TaskDefinition", "AWS::Events::Rule",
                  "AWS::Lambda::Function", "AWS::Lambda::LayerVersion"]

# The shared shape each scalar body is written as, inline; a definition keeps its own name.
SCALAR_SHAPES = {"string": "String", "integer": "Integer", "boolean": "Boolean", "double": "Double", "json": "Json"}
SCHEMA_SCALARS = {"string": "string", "integer": "integer", "boolean": "boolean", "number": "double"}
SHAPE_TYPES = {"structure", "list", "map", *SCALAR_SHAPES}

# Types the provisioners rely on, each measured on CloudFormation: (shape, member) -> member shape,
# and for a list or a map the shape of its items or values.
ANCHORS = {
    ("AWS::Events::Rule/RetryPolicy", "MaximumRetryAttempts"): "Integer",
    ("AWS::Events::Rule/EcsParameters", "EnableECSManagedTags"): "Boolean",
    ("AWS::Events::Rule/AwsVpcConfiguration", "Subnets"): ["String"],
    ("AWS::Lambda::Function/Environment", "Variables"): {"String"},
    ("AWS::Lambda::Function/EphemeralStorage", "Size"): "Integer",
    ("AWS::AutoScaling::LaunchConfiguration/MetadataOptions", "HttpPutResponseHopLimit"): "Integer",
    ("AWS::EC2::Instance/MetadataOptions", "HttpTokens"): "String",
    ("AWS::EC2::Instance/MetadataOptions", "HttpPutResponseHopLimit"): "Integer",
    ("AWS::EC2::LaunchTemplate/MetadataOptions", "HttpTokens"): "String",
    ("AWS::EC2::LaunchTemplate/MetadataOptions", "HttpPutResponseHopLimit"): "Integer",
    ("AWS::ECS::TaskDefinition/ContainerDefinition", "Memory"): "Integer",
    ("AWS::ECS::TaskDefinition/ContainerDefinition", "Essential"): "Boolean",
    ("AWS::ECS::TaskDefinition/KernelCapabilities", "Add"): ["String"],
}

# Required members the provisioners rely on, measured the same way: shape -> its required members.
REQUIRED_ANCHORS = {
    "AWS::ECS::TaskDefinition/ContainerDefinition": ["Name", "Image"],
    "AWS::ECS::TaskDefinition/Tmpfs": ["Size"],
}


def file_name(resource_type: str) -> str:
    """The archive's file for a resource type: AWS::Events::Rule is aws-events-rule.json."""
    return resource_type.lower().replace("::", "-") + ".json"


def fetch_schemas(source_dir: Path | None) -> tuple[dict[str, dict], str]:
    """The schema of every resource type listed here and a provenance label for where they came from."""
    if source_dir is not None:
        return ({resource_type: json.loads((source_dir / file_name(resource_type)).read_text(encoding="utf-8"))
                 for resource_type in RESOURCE_TYPES}, f"--source-dir {source_dir}")
    with urllib.request.urlopen(ARCHIVE_URL, timeout=FETCH_TIMEOUT_SECONDS) as response:
        archive = zipfile.ZipFile(io.BytesIO(response.read()))
    schemas = {}
    for resource_type in RESOURCE_TYPES:
        try:
            schemas[resource_type] = json.loads(archive.read(file_name(resource_type)).decode("utf-8"))
        except KeyError as missing:
            raise ValueError(f"{resource_type} has no {file_name(resource_type)} in {ARCHIVE_URL}") from missing
    return schemas, ARCHIVE_URL


def build(schemas: dict[str, dict], provenance: str) -> dict:
    shapes: dict[str, dict] = {name: {"type": kind} for kind, name in SCALAR_SHAPES.items()}
    operations = {}
    for resource_type in RESOURCE_TYPES:
        schema = schemas[resource_type]
        properties = {"type": "object", "properties": schema["properties"], "required": schema.get("required", [])}
        operations[resource_type] = shape_for(schema, resource_type, resource_type, properties, shapes)
    return {
        "_source": {
            "generator": "tools/aws/regen_cfn_property_types.py",
            "schemas": provenance,
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "make cfn-property-types-verify checks the file against AWS.",
        },
        "operations": operations,
        "shapes": dict(sorted(shapes.items())),
    }


def shape_for(schema: dict, resource_type: str, name: str, node: dict, shapes: dict) -> str:
    """The name of the shape for `node`, adding it, and every shape it reaches, to `shapes`. A
    definition is named `<resource type>/<definition>`, an inline object or list by its path, and an
    inline scalar is the shared shape of its type."""
    if "$ref" in node:
        definition = node["$ref"].removeprefix("#/definitions/")
        target = f"{resource_type}/{definition}"
        if target not in shapes:
            shapes[target] = {"type": "json"}  # until it is built, so a definition that refers to itself ends
            shapes[target] = body_of(schema, resource_type, target, schema["definitions"][definition], shapes)
        return target
    body = body_of(schema, resource_type, name, node, shapes)
    if body["type"] in SCALAR_SHAPES:
        return SCALAR_SHAPES[body["type"]]
    shapes[name] = body
    return name


def body_of(schema: dict, resource_type: str, name: str, node: dict, shapes: dict) -> dict:
    if "$ref" in node:
        return dict(shapes[shape_for(schema, resource_type, name, node, shapes)])
    kind = node.get("type")
    if isinstance(kind, list):
        kind = kind[0] if len(kind) == 1 else None
    if kind is None and "properties" in node:
        kind = "object"
    if kind == "object":
        if "properties" in node:
            structure = {"type": "structure", "members": {
                member: shape_for(schema, resource_type, f"{name}.{member}", spec, shapes)
                for member, spec in node["properties"].items()}}
            if node.get("required"):
                structure["required"] = list(node["required"])
            return structure
        values = list(node.get("patternProperties", {}).values())
        if isinstance(node.get("additionalProperties"), dict):
            values.append(node["additionalProperties"])
        if len(values) == 1:
            return {"type": "map", "value": shape_for(schema, resource_type, f"{name}.*", values[0], shapes)}
        return {"type": "json"}
    if kind == "array":
        items = node.get("items")
        member = shape_for(schema, resource_type, f"{name}[]", items, shapes) if isinstance(items, dict) else "Json"
        return {"type": "list", "member": member}
    return {"type": SCHEMA_SCALARS.get(kind, "json")}


def render(document: dict) -> str:
    return json.dumps(document, indent=2) + "\n"


def validate(document: dict) -> list[str]:
    """Problems that make the vendored file unusable, rather than merely out of date."""
    problems: list[str] = []
    operations, shapes = document.get("operations"), document.get("shapes")
    if not isinstance(operations, dict) or not isinstance(shapes, dict):
        return ["operations or shapes is missing"]
    for resource_type in RESOURCE_TYPES:
        if operations.get(resource_type) not in shapes:
            problems.append(f"{resource_type} has no properties shape")
    for name, shape in shapes.items():
        kind = shape.get("type")
        if kind not in SHAPE_TYPES:
            problems.append(f"{name} has the unknown type {kind!r}")
        named = list(shape.get("members", {}).values()) + [shape[key] for key in ("member", "value") if key in shape]
        for target in named:
            if target not in shapes:
                problems.append(f"{name} names the missing shape {target!r}")
        for member in shape.get("required", []):
            if member not in shape.get("members", {}):
                problems.append(f"{name} requires {member!r}, which it does not declare")
    for (shape, member), expected in ANCHORS.items():
        target = shapes.get(shape, {}).get("members", {}).get(member)
        actual = shapes.get(target, {}) if target else {}
        if isinstance(expected, list):
            holds = actual.get("type") == "list" and actual.get("member") == expected[0]
        elif isinstance(expected, set):
            holds = actual.get("type") == "map" and actual.get("value") in expected
        else:
            holds = target == expected
        if not holds:
            problems.append(f"expected {shape}.{member} to be {expected}, not {target} {actual}")
    for shape, required in REQUIRED_ANCHORS.items():
        actual = shapes.get(shape, {}).get("required", [])
        if actual != required:
            problems.append(f"expected {shape} to require {required}, not {actual}")
    return problems


def _shown(path: Path) -> Path:
    try:
        return path.resolve().relative_to(REPO_ROOT)
    except ValueError:
        return path


def _without_source(document: dict) -> dict:
    return {key: value for key, value in document.items() if key != "_source"}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="offline: validate the shape of the vendored file")
    parser.add_argument("--verify", action="store_true", help="online: the vendored file matches AWS's schemas")
    parser.add_argument("--source-dir", type=Path, default=None,
                        help="read saved aws-<service>-<type>.json schemas instead of fetching the archive")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    if args.check or args.verify:
        if not args.output.exists():
            print(f"error: {args.output} does not exist", file=sys.stderr)
            return 1
        vendored = json.loads(args.output.read_text(encoding="utf-8"))

    if args.check:
        problems = validate(vendored)
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        if problems:
            return 1
        print(f"{_shown(args.output)} is well-formed ({len(vendored['shapes'])} shapes over "
              f"{len(vendored['operations'])} resource types)")
        return 0

    try:
        schemas, provenance = fetch_schemas(args.source_dir)
        document = build(schemas, provenance)
    except (OSError, ValueError, zipfile.BadZipFile) as e:
        # The gate could not run, which CI has to tell apart from having found a difference.
        print(f"error: could not read the CloudFormation resource schemas ({e})", file=sys.stderr)
        return 2

    if args.verify:
        if _without_source(vendored) != _without_source(document):
            print(f"error: {_shown(args.output)} no longer matches {provenance}; run "
                  f"'make cfn-property-types-sync' and commit the result.", file=sys.stderr)
            return 1
        print(f"{_shown(args.output)} matches {provenance}.")
        return 0

    problems = validate(document)
    for problem in problems:
        print(f"error: {problem}", file=sys.stderr)
    if problems:
        return 1
    args.output.write_text(render(document), encoding="utf-8")
    print(f"wrote {_shown(args.output)} ({len(document['shapes'])} shapes, from {provenance})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
