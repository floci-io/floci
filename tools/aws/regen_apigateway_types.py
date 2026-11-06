#!/usr/bin/env python3
"""Generate API Gateway integration types from Botocore's public service model.

Source: apigateway/2015-07-09/service-2.json, shape IntegrationType.
Botocore is Apache-2.0 licensed: https://github.com/boto/botocore.
Run directly to regenerate, or with --check to detect drift in CI.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from regen_partitions import load_model, resolve_botocore_data, service_model, strip_source

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/apigateway-integration-types.json"
MODEL = Path("apigateway/2015-07-09")


def build(model: dict, provenance: str) -> dict:
    values = model["shapes"]["IntegrationType"]["enum"]
    if not values or any(not isinstance(value, str) or not value for value in values):
        raise ValueError("IntegrationType must be a nonempty string enum")
    return {
        "_source": {
            "generator": "tools/aws/regen_apigateway_types.py",
            "botocore": provenance,
            "model": str(MODEL / "service-2.json"),
            "shape": "IntegrationType",
        },
        "integrationTypes": sorted(set(values)),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--botocore-data", type=Path)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args(argv)
    data, provenance = resolve_botocore_data(args.botocore_data)
    source = service_model(data / MODEL)
    if source is None:
        raise SystemExit(f"error: no service model found in {data / MODEL}")
    fresh = json.dumps(build(load_model(source), provenance), indent=2) + "\n"
    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if strip_source(current) != strip_source(fresh):
            print(f"{args.output} is stale (generated from {provenance}).", file=sys.stderr)
            return 1
        return 0
    args.output.write_text(fresh, encoding="utf-8")
    print(f"wrote {args.output} from {provenance}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
