#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/query-services.json from botocore's models.

A Query request that arrives without a usable credential scope is routed by its `Action` and the
`Version` it declares (`AwsQueryController.inferServiceFromAction`), and IAM enforcement authorizes
it under the service that routing picks. Routing therefore needs, for every Query service Floci
serves, the API versions its clients declare and every operation its model defines. This script
keeps exactly that, read from the service models, so a name a service defines reaches it whether or
not Floci implements it yet: an unimplemented one answers from the right service instead of falling
through to SQS.

Which services there are, and which botocore models each one serves, is Floci's choice and stays in
SERVICES below: the protocol field cannot pick them (SQS's model is JSON only now, CloudWatch lists
three protocols), RDS's scope also serves DocumentDB and Neptune, and Elastic Load Balancing serves
both of its APIs. The order the services are tried in is `AwsQueryController`'s.

The models are botocore's `<model>/<apiVersion>/service-2.json` (Apache-2.0,
https://github.com/boto/botocore), the latest API version of each. They are located, in order, at
`--botocore-data`, the `local/aws/botocore` checkout, then the installed `botocore` package, which
`requirements.txt` pins so CI regenerates the same bytes.

Run from anywhere in the repo:
    python3 tools/aws/regen_query_services.py            # rewrite the vendored file in place
    python3 tools/aws/regen_query_services.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import argparse
import gzip
import json
import sys
from pathlib import Path

from regen_partitions import resolve_botocore_data, strip_source

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/query-services.json"

# Floci's Query service name, as the credential scope names it, and the botocore models it serves.
SERVICES: dict[str, tuple[str, ...]] = {
    "autoscaling": ("autoscaling",),
    "cloudformation": ("cloudformation",),
    "ec2": ("ec2",),
    "elasticache": ("elasticache",),
    "elasticbeanstalk": ("elasticbeanstalk",),
    "elasticloadbalancing": ("elbv2", "elb"),
    "email": ("ses",),
    "iam": ("iam",),
    "monitoring": ("cloudwatch",),
    "rds": ("rds", "docdb", "neptune"),
    "redshift": ("redshift",),
    "sns": ("sns",),
    "sqs": ("sqs",),
    "sts": ("sts",),
}


def latest_model_dir(data: Path, model: str) -> Path:
    """The model's directory for its latest API version, which is what current SDKs send."""
    versions = sorted(path for path in (data / model).iterdir() if path.is_dir())
    if not versions:
        raise SystemExit(f"error: no API version of {model} under {data}")
    return versions[-1]


def load_model(directory: Path) -> dict:
    plain = directory / "service-2.json"
    if plain.exists():
        return json.loads(plain.read_text(encoding="utf-8"))
    compressed = plain.with_name(plain.name + ".gz")
    if compressed.exists():
        with gzip.open(compressed, "rt", encoding="utf-8") as f:
            return json.load(f)
    raise SystemExit(f"error: {plain} not found")


def build(models: dict[str, list[dict]], provenance: str) -> dict:
    """The vendored document from each service's loaded models."""
    services = {}
    for name in sorted(models):
        versions = sorted({model["metadata"]["apiVersion"] for model in models[name]})
        operations = sorted({operation for model in models[name] for operation in model["operations"]})
        services[name] = {"apiVersions": versions, "operations": operations}
    return {
        "_source": {
            "generator": "tools/aws/regen_query_services.py",
            "botocore": provenance,
        },
        "services": services,
    }


def load_models(data: Path) -> dict[str, list[dict]]:
    return {name: [load_model(latest_model_dir(data, model)) for model in models]
            for name, models in SERVICES.items()}


def render(doc: dict) -> str:
    return json.dumps(doc, indent=2, sort_keys=False) + "\n"


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

    args.output.write_text(fresh, encoding="utf-8")
    shown = args.output.relative_to(REPO_ROOT) if args.output.is_relative_to(REPO_ROOT) else args.output
    print(f"wrote {shown} from {provenance}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
