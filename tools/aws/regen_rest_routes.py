#!/usr/bin/env python3
"""Regenerate src/test/resources/aws/rest-routes.json from botocore's models.

A REST request is authorized by its route: `IamActionRegistry` maps the HTTP method and path to the
IAM action, and a route no rule maps is left unchecked. Whether every operation of a service reaches
the action AWS checks for it can only be tested against the operation's real route, which is what
this vendors: for each operation of the models below, its HTTP method and request URI exactly as
the SDKs send them. `IamRestRouteCoverageTest` builds a request on each route and compares the
action the registry gives with the one `iam-authorized-actions.json` names, or the operation's own.

The models are botocore's `<model>/<apiVersion>/service-2.json` (Apache-2.0,
https://github.com/boto/botocore), the latest API version of each, located as
`regen_query_services.py` locates them. Which models are read, and which credential scope Floci
authorizes each under, is Floci's choice and stays in this file.

Run from anywhere in the repo:
    python3 tools/aws/regen_rest_routes.py            # rewrite the vendored file in place
    python3 tools/aws/regen_rest_routes.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from regen_partitions import resolve_botocore_data, strip_source
from regen_query_services import latest_model_dir, load_model

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/test/resources/aws/rest-routes.json"

# botocore model -> the credential scope Floci authorizes its requests under.
MODELS: dict[str, str] = {
    "apigateway": "apigateway",
    "apigatewayv2": "apigateway",
    "dsql": "dsql",
    "eks": "eks",
    "lambda": "lambda",
    "lambda-core": "lambda",
    "lambda-microvms": "lambda",
    "pipes": "pipes",
    "s3": "s3",
    "scheduler": "scheduler",
}


def build(models: dict[str, dict], provenance: str) -> dict:
    """The vendored document from each loaded model."""
    routes = {}
    for name in sorted(models):
        model = models[name]
        if model["metadata"].get("protocol") not in ("rest-json", "rest-xml"):
            raise SystemExit(f"error: {name} is not a REST model")
        operations = {}
        for operation in sorted(model["operations"]):
            http = model["operations"][operation]["http"]
            operations[operation] = {"method": http["method"], "requestUri": http["requestUri"]}
        routes[name] = {
            "credentialScope": MODELS[name],
            "apiVersion": model["metadata"]["apiVersion"],
            "operations": operations,
        }
    return {
        "_source": {
            "generator": "tools/aws/regen_rest_routes.py",
            "botocore": provenance,
        },
        "models": routes,
    }


def load_models(data: Path) -> dict[str, dict]:
    return {name: load_model(latest_model_dir(data, name)) for name in MODELS}


def render(doc: dict) -> str:
    """The document with one operation to a line, so a model's routes read as a table."""
    lines = ["{", f'  "_source": {json.dumps(doc["_source"])},', '  "models": {']
    models = list(doc["models"].items())
    for index, (name, model) in enumerate(models):
        lines.append(f"    {json.dumps(name)}: {{")
        lines.append(f'      "credentialScope": {json.dumps(model["credentialScope"])},')
        lines.append(f'      "apiVersion": {json.dumps(model["apiVersion"])},')
        lines.append('      "operations": {')
        operations = list(model["operations"].items())
        for position, (operation, route) in enumerate(operations):
            comma = "," if position < len(operations) - 1 else ""
            lines.append(f"        {json.dumps(operation)}: {json.dumps(route)}{comma}")
        lines.append("      }")
        lines.append("    }" + ("," if index < len(models) - 1 else ""))
    lines += ["  }", "}"]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 when the vendored file differs from a fresh generation")
    parser.add_argument("--botocore-data", type=Path, help="botocore data directory")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    data, provenance = resolve_botocore_data(args.botocore_data)
    fresh = render(build(load_models(data), provenance))

    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if strip_source(current) != strip_source(fresh):
            print(f"{args.output} is stale (generated from {provenance}).", file=sys.stderr)
            return 1
        return 0

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(fresh, encoding="utf-8")
    shown = args.output.relative_to(REPO_ROOT) if args.output.is_relative_to(REPO_ROOT) else args.output
    print(f"wrote {shown} from {provenance}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
