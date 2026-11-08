#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/iam-service-namespaces.json from AWS's own service list.

IAM reports which services a principal can reach (`GetServiceLastAccessedDetails`), and a policy
that grants a service without naming it -- `"Action": "*"`, a globbed prefix such as `s3*`, or any
`NotAction` -- can only be expanded against a list of every IAM service namespace. Floci had no
such list, so those grants were left out of the report (#4487).

botocore is deliberately not the source here, even though every other vendored file in this repo
comes from it. A service's IAM namespace is not any botocore field: CloudWatch's `endpointPrefix`
is `monitoring` and its `serviceId` is `CloudWatch`, while its IAM namespace is `cloudwatch`; CloudWatch
Logs has `serviceId` `CloudWatch Logs` against an IAM namespace of `logs`. Any rule over botocore
metadata is wrong for some service, and a wrong namespace is worse than a missing one, because it
names something that does not exist in IAM.

The source is AWS's Service Reference Information index, which publishes one entry per service
whose `service` field is exactly the IAM namespace:

    https://servicereference.us-east-1.amazonaws.com/

That index is a live endpoint rather than a pinned dependency, so unlike `regen_partitions.py` a
fresh generation is not byte-identical: AWS adds services whenever it ships them. Byte-equality is
therefore the wrong gate, but it is not the guarantee that matters. What must be guaranteed is that
nothing in the vendored file is invented, and that is checkable directly:

- `--check` is offline and structural. The file must be sorted, free of duplicates, every entry
  usable as a namespace, and it must carry the cases that justify not deriving this from botocore
  (`cloudwatch` present, `monitoring` absent). This catches corruption and hand-editing of shape.
- `--verify` is the real gate and needs the network. Every namespace in the vendored file must
  still exist in the live index. A hand-typed or mistyped namespace fails, because AWS does not
  publish it; a namespace AWS has retired fails, because the file now claims something untrue.
  Services AWS has *added* since the last regeneration are reported but do not fail, since the file
  being behind makes the report incomplete rather than wrong, and failing on that would break this
  repo's build for someone else's launch.

Run from anywhere in the repo:
    python3 tools/aws/regen_service_namespaces.py          # rewrite the vendored file in place
    python3 tools/aws/regen_service_namespaces.py --check  # offline: shape of the vendored file
    python3 tools/aws/regen_service_namespaces.py --verify  # online: nothing in it is invented
    python3 tools/aws/regen_service_namespaces.py --source <file>   # read a saved index instead
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-service-namespaces.json"
INDEX_URL = "https://servicereference.us-east-1.amazonaws.com/"
FETCH_TIMEOUT_SECONDS = 30

# serviceNamespaceType in botocore's IAM model is `[\w-]*`, max 64. Every namespace AWS publishes
# is a subset of this; anything outside it would not be usable in a policy, so it is an error
# rather than something to sanitise silently.
NAMESPACE_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")


def fetch_index(source: Path | None) -> tuple[list[dict], str]:
    """The Service Reference index and a provenance label for where it came from."""
    if source is not None:
        return json.loads(source.read_text(encoding="utf-8")), f"--source {source}"
    request = urllib.request.Request(INDEX_URL, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=FETCH_TIMEOUT_SECONDS) as response:
        payload = response.read().decode("utf-8")
    return json.loads(payload), INDEX_URL


def namespaces_from(index: list[dict]) -> list[str]:
    """The sorted, de-duplicated IAM namespaces named by the index."""
    if not isinstance(index, list) or not index:
        raise ValueError("the service reference index must be a non-empty list")
    found: set[str] = set()
    for entry in index:
        if not isinstance(entry, dict) or "service" not in entry:
            raise ValueError(f"index entry without a 'service' field: {entry!r}")
        namespace = entry["service"]
        if not NAMESPACE_RE.match(namespace):
            raise ValueError(f"{namespace!r} is not usable as an IAM service namespace")
        found.add(namespace)
    return sorted(found)


def build(index: list[dict], provenance: str) -> dict:
    return {
        "_source": {
            "generator": "tools/aws/regen_service_namespaces.py",
            "index": provenance,
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "make iam-namespaces-verify checks every entry still exists upstream.",
        },
        "serviceNamespaces": namespaces_from(index),
    }


def render(document: dict) -> str:
    return json.dumps(document, indent=2) + "\n"


def validate(document: dict) -> list[str]:
    """Problems that make the vendored file unusable, rather than merely out of date."""
    problems: list[str] = []
    namespaces = document.get("serviceNamespaces")
    if not isinstance(namespaces, list) or not namespaces:
        return ["serviceNamespaces is missing or empty"]
    if namespaces != sorted(namespaces):
        problems.append("serviceNamespaces is not sorted")
    if len(namespaces) != len(set(namespaces)):
        problems.append("serviceNamespaces contains duplicates")
    for namespace in namespaces:
        if not isinstance(namespace, str) or not NAMESPACE_RE.match(namespace):
            problems.append(f"{namespace!r} is not usable as an IAM service namespace")
    # A handful of namespaces whose value is the whole point of not deriving this from botocore.
    for required in ("cloudwatch", "logs", "iam", "s3", "sts"):
        if required not in namespaces:
            problems.append(f"expected {required!r} to be present")
    if "monitoring" in namespaces:
        problems.append("'monitoring' is a credential scope, not an IAM namespace")
    return problems


def verify_against(vendored: list[str], upstream: list[str]) -> tuple[list[str], list[str]]:
    """Namespaces the vendored file claims but AWS does not publish, and ones AWS has added."""
    upstream_set = set(upstream)
    invented = [namespace for namespace in vendored if namespace not in upstream_set]
    added = sorted(upstream_set.difference(vendored))
    return invented, added


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true",
                        help="offline: validate the shape of the vendored file")
    parser.add_argument("--verify", action="store_true",
                        help="online: every vendored namespace must still exist in the live index")
    parser.add_argument("--source", type=Path, default=None,
                        help="read a saved copy of the index instead of fetching it")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    if args.check:
        if not args.output.exists():
            print(f"error: {args.output} does not exist", file=sys.stderr)
            return 1
        problems = validate(json.loads(args.output.read_text(encoding="utf-8")))
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        if problems:
            return 1
        count = len(json.loads(args.output.read_text(encoding="utf-8"))["serviceNamespaces"])
        print(f"{args.output.relative_to(REPO_ROOT)} is well-formed ({count} namespaces)")
        return 0

    if args.verify:
        if not args.output.exists():
            print(f"error: {args.output} does not exist", file=sys.stderr)
            return 1
        vendored = json.loads(args.output.read_text(encoding="utf-8"))["serviceNamespaces"]
        try:
            index, provenance = fetch_index(args.source)
            upstream = namespaces_from(index)
        except (OSError, ValueError) as e:
            # Distinct from a data problem: the gate could not run, rather than having found
            # something invented. Separate exit code so CI can tell the two apart. ValueError
            # covers a malformed index, including the JSONDecodeError a truncated or non-JSON
            # response raises, which is just as much "could not verify" as an unreachable host.
            print(f"error: could not read the service reference index ({e})", file=sys.stderr)
            return 2
        invented, added = verify_against(vendored, upstream)
        for namespace in invented:
            print(f"error: {namespace!r} is not published by {provenance}", file=sys.stderr)
        if invented:
            print(f"error: {len(invented)} vendored namespace(s) do not exist upstream; "
                  f"the file claims something untrue.", file=sys.stderr)
            return 1
        if added:
            print(f"{args.output.relative_to(REPO_ROOT)}: all {len(vendored)} namespaces verified. "
                  f"AWS has since added {len(added)}, so the report is incomplete but not wrong "
                  f"(run without --verify to refresh): {', '.join(added[:10])}"
                  f"{'...' if len(added) > 10 else ''}")
        else:
            print(f"{args.output.relative_to(REPO_ROOT)}: all {len(vendored)} namespaces verified "
                  f"against {provenance}, none added upstream.")
        return 0

    try:
        index, provenance = fetch_index(args.source)
        document = build(index, provenance)
    except (OSError, ValueError) as e:
        print(f"error: could not read the service reference index ({e})", file=sys.stderr)
        return 2
    problems = validate(document)
    for problem in problems:
        print(f"error: {problem}", file=sys.stderr)
    if problems:
        return 1
    args.output.write_text(render(document), encoding="utf-8")
    print(f"wrote {args.output.relative_to(REPO_ROOT)} "
          f"({len(document['serviceNamespaces'])} namespaces from {provenance})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
