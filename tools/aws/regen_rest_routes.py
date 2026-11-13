#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/rest-routes.json from botocore's models.

A REST request is authorized by its route: `IamActionRegistry` maps the HTTP method and path to the
IAM action, by its rules first and then by these routes, and a route neither maps is left unchecked.
This vendors, for each operation of the models below, its HTTP method and request URI exactly as the
SDKs send them, plus the explicit access-denied HTTP status of each model when it declares one.
A request no rule maps is the operation whose route it matches, checked as the action
`iam-authorized-actions.json` names for it, or as its own; `IamRestRouteCoverageTest` builds a
request on each route and compares the action the registry gives with that one.

The credential scopes in `HAND_ROUTED` are authorized by the registry's rules alone, each for its
reason, and their routes are vendored for the coverage test only. Within any other scope, two
operations on one route would leave a request matching it no single operation, so the generation
fails on them. Supported shared-controller alternatives are generated beside the modeled URI, with
member-location overrides for the bare route, and its own method where that is not the operation's. These
alternatives describe existing Floci compatibility paths, not additional botocore operations.

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
import re
import sys
from pathlib import Path

from regen_partitions import resolve_botocore_data, strip_source
from regen_query_services import latest_model_dir, load_model

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/rest-routes.json"

# Credential scopes the registry's rules authorize alone, and why.
HAND_ROUTED: dict[str, str] = {
    "s3":"S3 tells apart the operations it serves on one route by their query parameters and headers, which the "
          "registry's S3 rules and sub-resource overrides read, and an S3 Control route Floci does not serve "
          "runs as the S3 data plane, checked as the bucket or object operation that runs",
}

# botocore model -> the credential scope Floci authorizes its requests under.
MODELS: dict[str, str] = {
    "accessanalyzer": "access-analyzer",
    "account": "account",
    "apigateway": "apigateway",
    "apigatewayv2": "apigateway",
    "cloudfront": "cloudfront",
    "cloudfront-keyvaluestore": "cloudfront-keyvaluestore",
    "controlcatalog": "controlcatalog",
    "controltower": "controltower",
    "dsql": "dsql",
    "eks": "eks",
    "emr-serverless": "emr-serverless",
    "guardduty": "guardduty",
    "es": "es",
    "fis": "fis",
    "inspector2": "inspector2",
    "lakeformation": "lakeformation",
    "lambda": "lambda",
    "lambda-core": "lambda",
    "lambda-microvms": "lambda",
    "macie2": "macie2",
    "oam": "oam",
    "opensearch": "es",
    # The data plane is signed for the service, which is one scope for both of its planes.
    "payment-cryptography-data": "payment-cryptography",
    "pipes": "pipes",
    "quicksight": "quicksight",
    "ram": "ram",
    "resource-explorer-2": "resource-explorer-2",
    "route53": "route53",
    "s3": "s3",
    "s3control": "s3",
    "scheduler": "scheduler",
    "securityhub": "securityhub",
    "signer": "signer",
    "xray": "xray",
}

# Operations a model does not serve itself. The shared tags controller serves /tags/{resourceArn} for every
# service that is a TagHandler, the ARN taking the rest of the path, and the bare /tags too. API Gateway's v1
# tag routes are the shared controller's too. A route a service's filter moves under an internal prefix is
# vendored at the model's own path all the same: IAM matches it on the path without the prefix the filter
# records (SignedServiceRoute).
SHARED_ROUTES: dict[str, frozenset[str]] = {
    "apigateway": frozenset({"TagResource", "UntagResource", "GetTags"}),
    "controltower": frozenset({"TagResource", "UntagResource", "ListTagsForResource"}),
    "fis": frozenset({"TagResource", "UntagResource", "ListTagsForResource"}),
    "resource-explorer-2": frozenset({"TagResource", "UntagResource", "ListTagsForResource"}),
    "signer": frozenset({"TagResource", "UntagResource", "ListTagsForResource"}),
}

# Operations whose ARN label a model gives one segment, where the controller serving them lets it span
# the slashes the ARN holds: a request may send the ARN unencoded and still reach the operation, so its
# route has to take that request too or it would go unchecked. API Gateway v2's tag routes are
# `/v2/tags/{resourceArn: .+}` in its controller, and OAM's `/tags/{resourceArn:.+}` in its own and in the
# shared tags controller, which takes every `/tags/{arn}` request.
ARN_SPANNING_ROUTES: dict[str, frozenset[str]] = {
    "apigatewayv2": frozenset({"TagResource", "UntagResource", "GetTags"}),
    "oam": frozenset({"TagResource", "UntagResource", "ListTagsForResource"}),
}


def access_denied_status(model: dict) -> int | None:
    """The HTTP status explicitly modelled for a declared AccessDenied or AccessDeniedException error."""
    statuses = set()
    for operation in model["operations"].values():
        for reference in operation.get("errors", []):
            name = reference["shape"]
            error = model.get("shapes", {}).get(name, {}).get("error", {})
            if error.get("code", name) in ("AccessDenied", "AccessDeniedException") and "httpStatusCode" in error:
                status = error["httpStatusCode"]
                if type(status) is not int or not 400 <= status <= 599:
                    raise SystemExit(f"error: invalid access-denied HTTP status {status!r}")
                statuses.add(status)
    if len(statuses) > 1:
        raise SystemExit(f"error: a REST model has conflicting access-denied HTTP statuses {sorted(statuses)}")
    return next(iter(statuses), None)


def build(models: dict[str, dict], provenance: str) -> dict:
    """The vendored document from each loaded model."""
    routes = {}
    denial_statuses = {}
    for name in sorted(models):
        model = models[name]
        if model["metadata"].get("protocol") not in ("rest-json", "rest-xml"):
            raise SystemExit(f"error: {name} is not a REST model")
        shared = SHARED_ROUTES.get(name, frozenset())
        spanning = ARN_SPANNING_ROUTES.get(name, frozenset())
        operations = {}
        for operation in sorted(model["operations"]):
            http = model["operations"][operation]["http"]
            request_uri = http["requestUri"]
            if operation in shared or operation in spanning:
                # A tags route names its resource as an ARN, which holds slashes: the label has to
                # span them or the route matches no tag request at all.
                request_uri = re.sub(r"\{([^}]*[Aa]rn[^}]*)}", r"{\1+}", request_uri)
            route = {"method": http["method"], "requestUri": request_uri}
            if operation in shared:
                route["alternatives"] = [bare_tags_route(http["method"])]
            operations[operation] = route
        routes[name] = {
            "credentialScope": MODELS[name],
            "apiVersion": model["metadata"]["apiVersion"],
            "operations": operations,
        }
        status = access_denied_status(model)
        if status is not None:
            scope = MODELS[name]
            if scope in denial_statuses and denial_statuses[scope] != status:
                raise SystemExit(f"error: REST models of {scope} have conflicting access-denied HTTP statuses")
            denial_statuses[scope] = status
            routes[name]["accessDeniedStatus"] = status
    routes = dict(sorted(routes.items()))
    shared = shared_routes(routes)
    if shared:
        raise SystemExit("error: one route names more than one operation in a scope the rules do not route alone: "
                         + "; ".join(shared))
    return {
        "_source": {
            "generator": "tools/aws/regen_rest_routes.py",
            "botocore": provenance,
        },
        "handRouted": dict(sorted(HAND_ROUTED.items())),
        "models": routes,
    }


def bare_tags_route(method: str) -> dict:
    """The bare `/tags` route SharedTagsController also serves for a shared tags route of the method, with where it
    reads the ARN: a tagging request is a POST whose body names it, whether the service tags with POST or with
    PUT, and a GET or DELETE names it in its query. A route of another method than the operation's says so."""
    bare = "POST" if method in ("POST", "PUT") else method
    route = {"requestUri": "/tags", "located": {"resourceArn": "body" if bare == "POST" else "?resourceArn"}}
    return route if bare == method else {"method": bare, **route}


def route_key(method: str, request_uri: str) -> tuple:
    """A route as a request matches it: its method, its path's segments with every label alike, and its query."""
    path, _, query = request_uri.partition("?")
    segments = tuple("{+}" if re.fullmatch(r"\{[^}]+\+}", segment) else re.sub(r"\{[^}]+}", "{}", segment)
                     for segment in path.strip("/").split("/"))
    return method, segments, tuple(sorted(query.split("&"))) if query else ()


def shared_routes(routes: dict[str, dict]) -> list[str]:
    """The routes that name more than one operation within a scope `HAND_ROUTED` does not name."""
    named: dict[tuple, set[str]] = {}
    for name, model in routes.items():
        if model["credentialScope"] in HAND_ROUTED:
            continue
        for operation, route in model["operations"].items():
            for variant in [route, *route.get("alternatives", [])]:
                key = (model["credentialScope"],) + route_key(variant.get("method", route["method"]),
                                                              variant["requestUri"])
                named.setdefault(key, set()).add(operation)
    return [f"{key[0]} {key[1]} {'/'.join(key[2])}: {', '.join(sorted(operations))}"
            for key, operations in sorted(named.items()) if len(operations) > 1]


def load_models(data: Path) -> dict[str, dict]:
    return {name: load_model(latest_model_dir(data, name)) for name in MODELS}


def render(doc: dict) -> str:
    """The document with one operation to a line, so a model's routes read as a table."""
    lines = ["{", f'  "_source": {json.dumps(doc["_source"])},', '  "handRouted": {']
    hand = list(doc["handRouted"].items())
    for position, (scope, reason) in enumerate(hand):
        lines.append(f"    {json.dumps(scope)}: {json.dumps(reason)}" + ("," if position < len(hand) - 1 else ""))
    lines += ["  },", '  "models": {']
    models = list(doc["models"].items())
    for index, (name, model) in enumerate(models):
        lines.append(f"    {json.dumps(name)}: {{")
        lines.append(f'      "credentialScope": {json.dumps(model["credentialScope"])},')
        lines.append(f'      "apiVersion": {json.dumps(model["apiVersion"])},')
        if "accessDeniedStatus" in model:
            lines.append(f'      "accessDeniedStatus": {model["accessDeniedStatus"]},')
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
