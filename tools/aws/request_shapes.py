"""Shared generator for the vendored request shapes a service's request check reads.

`regen_dynamodb_shapes.py` and `regen_sesv2_shapes.py` each name a botocore model and an output
file and hand the rest to `main()`. The output keeps only what `RequestShapeModel` reads: the input
shape of every operation, reduced to the members carried in the JSON body, and for every shape
reachable from one, its type and the shapes of its members, list elements or map values.

Members bound to the URI, the query string or a header are left out: they never reach the body.
A model without such members, as DynamoDB's, comes out whole.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from regen_partitions import load_model as load_json_model
from regen_partitions import resolve_botocore_data, service_model, strip_source

REPO_ROOT = Path(__file__).resolve().parents[2]


def load_model(data: Path, model: Path) -> dict:
    """The `service-2.json` in the botocore version directory `model`, plain or gzipped."""
    source = service_model(data / model)
    if source is None:
        raise SystemExit(f"error: no service model found in {data / model}")
    return load_json_model(source)


def build(model: dict, provenance: str, generator: str) -> dict:
    shapes = model["shapes"]
    kept: dict[str, dict] = {}

    def keep(name: str, body_only: bool = False) -> None:
        if name in kept:
            return
        shape = shapes[name]
        kind = shape["type"]
        entry: dict = {"type": kind}
        kept[name] = entry
        if kind == "structure":
            members = {member: spec for member, spec in shape.get("members", {}).items()
                       if not (body_only and "location" in spec)}
            entry["members"] = {member: spec["shape"] for member, spec in members.items()}
            for spec in members.values():
                keep(spec["shape"])
        elif kind == "list":
            entry["member"] = shape["member"]["shape"]
            keep(shape["member"]["shape"])
        elif kind == "map":
            entry["value"] = shape["value"]["shape"]
            keep(shape["value"]["shape"])

    operations = {}
    for name, operation in sorted(model["operations"].items()):
        if "input" in operation:
            operations[name] = operation["input"]["shape"]
            keep(operation["input"]["shape"], body_only=True)

    return {
        "_source": {
            "generator": generator,
            "botocore": provenance,
        },
        "operations": operations,
        "shapes": dict(sorted(kept.items())),
    }


def render(doc: dict) -> str:
    return json.dumps(doc, indent=2, sort_keys=False) + "\n"


def generator_name(script: str) -> str:
    """The script's path from the repository root, as the vendored file's `_source` records it, or
    its file name when it runs from outside the repository."""
    path = Path(script).resolve()
    return path.relative_to(REPO_ROOT).as_posix() if path.is_relative_to(REPO_ROOT) else path.name


def main(argv: list[str] | None, *, script: str, model: Path, output: Path, description: str) -> int:
    parser = argparse.ArgumentParser(description=description, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 when the vendored file differs from a fresh generation")
    parser.add_argument("--botocore-data", type=Path, help="botocore data directory")
    parser.add_argument("--output", type=Path, default=output, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    data, provenance = resolve_botocore_data(args.botocore_data)
    fresh = render(build(load_model(data, model), provenance, generator_name(script)))

    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if strip_source(current) != strip_source(fresh):
            print(f"{args.output} is stale (generated from {provenance}).", file=sys.stderr)
            return 1
        return 0

    args.output.write_text(fresh, encoding="utf-8")
    shown = args.output.relative_to(REPO_ROOT) if args.output.is_relative_to(REPO_ROOT) else args.output
    print(f"wrote {shown} from {provenance}")
    return 0
