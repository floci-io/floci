#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/iam-cloudtrail-shapes.json from botocore's IAM model.

CloudTrail records every IAM API call, and `IamCloudTrailEvents` writes each record's
`requestParameters` and `responseElements` from the call's request and result. What the record
says of a member is what the model says of it, so it is read from here rather than guessed:

- `operations`: each operation's input shape, its output shape and the element its result is
  wrapped in on the wire (`resultWrapper`, `CreateUserResult`), null where the model has none.
- `shapes`: every shape reachable from them, with its type and the shapes its members, list
  elements and map keys and values have. A number, a boolean or a timestamp is typed as one in the
  record because its shape says so. A shape the model marks `sensitive` (a password, a secret
  access key, a private key, a virtual MFA device's seed) carries `"sensitive": true`, and the
  record leaves it out.
- `errors`: every error IAM models, from the code it answers with to its shape's name.
  CloudTrail records an IAM error under the shape's name (`NoSuchEntity` as
  `NoSuchEntityException`).

The model is botocore's `iam/2010-05-08/` (Apache-2.0, https://github.com/boto/botocore), located,
in order, at `--botocore-data`, the `local/aws/botocore` checkout, then the installed `botocore`
package, which `requirements.txt` pins so CI regenerates the same bytes.

Run from anywhere in the repo:
    python3 tools/aws/regen_iam_cloudtrail_shapes.py            # rewrite the vendored file in place
    python3 tools/aws/regen_iam_cloudtrail_shapes.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from regen_partitions import resolve_botocore_data, strip_source
from regen_query_services import load_model

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-cloudtrail-shapes.json"
MODEL_DIR = ("iam", "2010-05-08")

# The shape types the record reads. Any other is one the Java side would pass over unread, so the
# generation stops rather than vendoring it.
TYPES = {"structure", "list", "map", "string", "integer", "long", "boolean", "timestamp", "blob",
         "double", "float"}


def shapes_from(model: dict) -> dict[str, dict]:
    """Every shape reachable from an operation's input or output, reduced to what the record reads."""
    shapes = model["shapes"]
    kept: dict[str, dict] = {}

    def keep(name: str) -> None:
        if name in kept:
            return
        shape = shapes[name]
        kind = shape["type"]
        if kind not in TYPES:
            raise ValueError(f"{name} is a {kind}, which the record does not read")
        entry: dict = {"type": kind}
        if shape.get("sensitive"):
            entry["sensitive"] = True
        kept[name] = entry
        if kind == "structure":
            members = shape.get("members", {})
            for member, spec in members.items():
                if set(spec) - {"shape", "documentation"}:
                    raise ValueError(f"{name}.{member} carries {sorted(set(spec) - {'shape', 'documentation'})}, "
                                     f"which the record does not read")
            entry["members"] = {member: spec["shape"] for member, spec in members.items()}
            for spec in members.values():
                keep(spec["shape"])
        elif kind == "list":
            entry["member"] = shape["member"]["shape"]
            keep(shape["member"]["shape"])
        elif kind == "map":
            entry["key"] = shape["key"]["shape"]
            entry["value"] = shape["value"]["shape"]
            keep(shape["key"]["shape"])
            keep(shape["value"]["shape"])

    for operation in model["operations"].values():
        for side in ("input", "output"):
            if side in operation:
                keep(operation[side]["shape"])
    return dict(sorted(kept.items()))


def operations_from(model: dict) -> dict[str, dict]:
    """Each operation's input and output shapes and the element its result is wrapped in."""
    found: dict[str, dict] = {}
    for name, operation in sorted(model["operations"].items()):
        output = operation.get("output", {})
        found[name] = {
            "input": operation.get("input", {}).get("shape"),
            "output": output.get("shape"),
            "resultWrapper": output.get("resultWrapper"),
        }
    return found


def errors_from(model: dict) -> dict[str, str]:
    """Every modeled error, from the code IAM answers it with to its shape's name."""
    found: dict[str, str] = {}
    for name, shape in model["shapes"].items():
        if not shape.get("exception"):
            continue
        code = shape.get("error", {}).get("code", name)
        if code in found:
            raise ValueError(f"{found[code]} and {name} both answer {code}")
        found[code] = name
    return dict(sorted(found.items()))


def build(model: dict, provenance: str) -> dict:
    return {
        "_source": {
            "generator": "tools/aws/regen_iam_cloudtrail_shapes.py",
            "botocore": provenance,
        },
        "operations": operations_from(model),
        "shapes": shapes_from(model),
        "errors": errors_from(model),
    }


def render(doc: dict) -> str:
    return json.dumps(doc, indent=2, sort_keys=False) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 when the vendored file differs from a fresh generation")
    parser.add_argument("--botocore-data", type=Path, help="botocore data directory")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    data, provenance = resolve_botocore_data(args.botocore_data)
    fresh = render(build(load_model(data.joinpath(*MODEL_DIR)), provenance))

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
