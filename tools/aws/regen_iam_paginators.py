#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/iam-paginators.json from botocore's IAM model.

IAM pages its lists with a `Marker` request member and a `MaxItems` limit, and answers with
`IsTruncated` and the `Marker` that continues the list. `IamQueryHandler` pages every such list
through one path, which reads from this file what each operation pages by: the request member that
resumes it, the member that limits it and that limit's default, the response members that say
whether it was truncated and how to continue, and the lists it pages.

Every IAM operation whose request models `Marker` is kept. For the operations botocore pages
(`paginators-1.json`, 34 of them) the paginator names the tokens, the limit key and the result
keys. The rest page on the wire without a paginator (the Access Advisor and Organizations reports,
ListServiceSpecificCredentials, ListDelegationRequests, ListPoliciesGrantingServiceAccess), so
their members are read from the request and response shapes and they carry no result keys:
`"paginator": false` says so. ListPoliciesGrantingServiceAccess models no `MaxItems` at all, so
its `limitKey` is null.

No bound is kept here. `maxItemsType` (1 to 1000) and `markerType` (1 to 320 characters) are
constraints of the request, so `regen_iam_shapes.py` vendors them with the rest of each paged
operation's request and `IamRequestShapes` holds a request to them before it runs. The default page
size is not a constraint the model encodes but a sentence in the limit member's documentation ("the
number of items defaults to 100"), so it is read from there as `defaultItems`, null where the
documentation states none.

The model is botocore's `iam/2010-05-08/` (Apache-2.0, https://github.com/boto/botocore), located,
in order, at `--botocore-data`, the `local/aws/botocore` checkout, then the installed `botocore`
package, which `requirements.txt` pins so CI regenerates the same bytes.

Run from anywhere in the repo:
    python3 tools/aws/regen_iam_paginators.py            # rewrite the vendored file in place
    python3 tools/aws/regen_iam_paginators.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

from regen_partitions import resolve_botocore_data, strip_source
from regen_query_services import load_model

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-paginators.json"
MODEL_DIR = ("iam", "2010-05-08")
INPUT_TOKEN = "Marker"

DEFAULT_RE = re.compile(r"defaults to (\d+)")


def load_paginators(directory: Path) -> dict:
    path = directory / "paginators-1.json"
    if not path.exists():
        raise SystemExit(f"error: {path} not found")
    return json.loads(path.read_text(encoding="utf-8"))["pagination"]


def _as_list(value) -> list[str]:
    return [value] if isinstance(value, str) else list(value)


def operations_from(model: dict, paginators: dict) -> dict[str, dict]:
    """Every operation whose request models Marker, and what it pages by."""
    shapes = model["shapes"]
    found: dict[str, dict] = {}
    for name in sorted(model["operations"]):
        operation = model["operations"][name]
        request = shapes[operation["input"]["shape"]]["members"] if "input" in operation else {}
        if INPUT_TOKEN not in request:
            if name in paginators:
                raise ValueError(f"{name} is paginated but its request has no {INPUT_TOKEN}")
            continue
        response = shapes[operation["output"]["shape"]]["members"] if "output" in operation else {}
        paginator = paginators.get(name)
        if paginator is not None:
            input_token = paginator["input_token"]
            output_token = paginator["output_token"]
            limit_key = paginator.get("limit_key")
            more_results = paginator.get("more_results")
            result_keys = _as_list(paginator["result_key"])
        else:
            input_token = INPUT_TOKEN
            output_token = INPUT_TOKEN if INPUT_TOKEN in response else None
            limit_key = "MaxItems" if "MaxItems" in request else None
            # ListDelegationRequests spells it isTruncated, so match without regard to case.
            more_results = next((m for m in response if m.lower() == "istruncated"), None)
            result_keys = []
        problems = [f"request has no {key}" for key in (input_token, limit_key)
                    if key is not None and key not in request]
        problems += [f"response has no {key}" for key in (output_token, more_results, *result_keys)
                     if key is not None and key not in response]
        if output_token is None or more_results is None:
            problems.append("response models no continuation")
        if problems:
            raise ValueError(f"{name}: " + "; ".join(problems))
        entry = {
            "paginator": paginator is not None,
            "inputToken": input_token,
            "outputToken": output_token,
            "limitKey": limit_key,
            "moreResults": more_results,
            "resultKeys": result_keys,
        }
        if limit_key is not None:
            documented = DEFAULT_RE.search(request[limit_key].get("documentation", ""))
            entry["defaultItems"] = int(documented.group(1)) if documented else None
        found[name] = entry
    missing = sorted(set(paginators) - set(found))
    if missing:
        raise ValueError(f"paginated operations missing from the model: {', '.join(missing)}")
    return found


def build(model: dict, paginators: dict, provenance: str) -> dict:
    return {
        "_source": {
            "generator": "tools/aws/regen_iam_paginators.py",
            "botocore": provenance,
        },
        "operations": operations_from(model, paginators),
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
    directory = data.joinpath(*MODEL_DIR)
    fresh = render(build(load_model(directory), load_paginators(directory), provenance))

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
