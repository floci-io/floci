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

An endpoint no botocore model describes is read from the AWS page that documents it (`DOCUMENTED`):
the table the page gives each operation, its REST path, IAM action and CloudTrail event, and the
operation's definition in the OpenAPI specification the row links, which gives its method and path.
Nothing in it is typed by hand. Reading the page needs the network, so a regeneration carries those
routes over from the vendored file, `--fetch-documented` reads them again, and `--verify` checks the
vendored ones against the page.

Run from anywhere in the repo:
    python3 tools/aws/regen_rest_routes.py                       # rewrite the vendored file in place
    python3 tools/aws/regen_rest_routes.py --check               # exit 1 when the vendored file is stale
    python3 tools/aws/regen_rest_routes.py --fetch-documented    # also read the documented endpoints again
    python3 tools/aws/regen_rest_routes.py --verify              # exit 1 when a documented endpoint's page differs
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from datetime import date
from html.parser import HTMLParser
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
    "emr-containers": "emr-containers",
    "emr-serverless": "emr-serverless",
    "guardduty": "guardduty",
    "es": "es",
    "fis": "fis",
    "inspector2": "inspector2",
    "lakeformation": "lakeformation",
    "lambda": "lambda",
    "lambda-core": "lambda",
    "lambda-microvms": "lambda",
    "lambda-web": "lambda",
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
    "s3tables": "s3tables",
    "s3vectors": "s3vectors",
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

# Endpoints no botocore model describes, each read from the AWS page that documents its operations, one table to an
# operation: its name, its type, its REST path, its IAM action, its CloudTrail event and the link to its definition in
# an OpenAPI specification. The rows of `type` are read, the operations Floci serves. AWS Glue's Iceberg REST endpoint
# serves the Apache Iceberg REST catalog's operations; the page's "AWS Glue extensions API" rows are routes Floci
# does not serve. A row is the Glue operation its CloudTrail event names, which has to be its IAM action too.
DOCUMENTED: dict[str, dict[str, str]] = {
    "glue-iceberg-rest": {
        "credentialScope": "glue",
        "page": "https://docs.aws.amazon.com/glue/latest/dg/iceberg-rest-apis.html",
        "type": "Iceberg REST Catalog API",
    },
}

# A definition link: https://github.com/<owner>/<repo>/blob/<ref>/<path>#L<line>, the line of the operation's method.
DEFINITION_LINK = re.compile(r"https://github\.com/([^/\s]+)/([^/\s]+)/blob/([^/\s]+)/(\S+?)\\?#L(\d+)")
DEFINITION_METHOD = re.compile(r"^    (get|post|put|delete|head|patch|options):\s*$")
DEFINITION_PATH = re.compile(r"^  (/\S*):\s*$")
PAGE_ROUTE = re.compile(r"^([A-Z]+)\s*(/\S*)$")


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


class PageTables(HTMLParser):
    """The rows of each table of an HTML page, each cell as its text."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.tables: list[list[list[str]]] = []
        self._row: list[str] | None = None
        self._cell: list[str] | None = None

    def handle_starttag(self, tag: str, attrs: list) -> None:
        if tag == "table":
            self.tables.append([])
        elif tag == "tr" and self.tables:
            self._row = []
        elif tag in ("td", "th") and self._row is not None:
            self._cell = []

    def handle_endtag(self, tag: str) -> None:
        if tag in ("td", "th") and self._row is not None and self._cell is not None:
            self._row.append(" ".join("".join(self._cell).split()))
            self._cell = None
        elif tag == "tr" and self._row is not None:
            self.tables[-1].append(self._row)
            self._row = None

    def handle_data(self, data: str) -> None:
        if self._cell is not None:
            self._cell.append(data)


def documented_rows(page: str) -> list[dict[str, str]]:
    """The operation tables of a page, in its order, each as its rows' first cell mapped to their second."""
    parser = PageTables()
    parser.feed(page)
    rows = []
    for table in parser.tables:
        fields = {row[0]: row[1] for row in table if len(row) == 2}
        if "Operation name" in fields:
            rows.append(fields)
    return rows


def raw_definition_url(link: str) -> tuple[str, int, str]:
    """The raw file a definition link names, the line it anchors, and the ref it pins."""
    match = DEFINITION_LINK.fullmatch(link.strip())
    if match is None:
        raise SystemExit(f"error: {link!r} is not a link to a line of a definition on GitHub")
    owner, repo, ref, path, line = match.groups()
    return f"https://raw.githubusercontent.com/{owner}/{repo}/{ref}/{path}", int(line), ref


def defined_route(definition: str, line: int, link: str) -> tuple[str, str]:
    """The method and path of the operation whose method key is on the line of an OpenAPI definition."""
    lines = definition.split("\n")
    method = DEFINITION_METHOD.match(lines[line - 1]) if 0 < line <= len(lines) else None
    if method is None:
        raise SystemExit(f"error: {link} does not anchor an operation's method")
    for index in range(line - 2, -1, -1):
        path = DEFINITION_PATH.match(lines[index])
        if path is not None:
            return method.group(1).upper(), path.group(1)
    raise SystemExit(f"error: {link} anchors a method under no path")


def documented_route(page_route: str, defined_path: str) -> tuple[str, str, list[str] | None, bool]:
    """The route of a documented operation: its definition's path under the root and the prefix the page's REST path
    gives it, the definition's `{prefix}` taking the segments the page puts in its place. Returns the page's method,
    the route, the prefix's segments (None when the definition has none), and whether the page's path past the prefix
    differs from the definition's in a literal segment or in length."""
    match = PAGE_ROUTE.match(page_route.strip())
    if match is None:
        raise SystemExit(f"error: {page_route!r} is no method and path")
    page_method, page_path = match.groups()
    page = page_path.strip("/").split("/")
    defined = defined_path.strip("/").split("/")
    if "v1" not in page or not defined or defined[0] != "v1":
        raise SystemExit(f"error: {page_route!r} and {defined_path!r} do not share a /v1 root")
    root, page_rest, defined_rest = page[:page.index("v1")], page[page.index("v1") + 1:], defined[1:]
    prefix = None
    if defined_rest and defined_rest[0] == "{prefix}":
        defined_rest = defined_rest[1:]
        span = len(page_rest) - len(defined_rest)
        if span < 1:
            raise SystemExit(f"error: {page_route!r} gives {defined_path!r} no prefix")
        prefix, page_rest = page_rest[:span], page_rest[span:]
    differs = len(page_rest) != len(defined_rest) or any(
        not (re.fullmatch(r"\{[^}]+}", ours) and re.fullmatch(r"\{[^}]+}", theirs)) and ours != theirs
        for ours, theirs in zip(page_rest, defined_rest))
    route = "/" + "/".join(root + ["v1"] + (prefix or []) + defined_rest)
    return page_method, route, prefix, differs


def fetch_url(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "floci-aws-data-generator"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def build_documented(spec: dict[str, str], fetch=fetch_url) -> dict:
    """The routes of a documented endpoint, read from its page and the definitions its rows link: one per Glue
    operation, its first row in the page's order the route and the others its alternatives, with their method where
    it is not the route's. Where the page's REST path differs from the definition its row links, the definition's
    method and path are taken and the difference recorded."""
    scope = spec["credentialScope"]
    rows = [row for row in documented_rows(fetch(spec["page"])) if row.get("Type") == spec["type"]]
    if not rows:
        raise SystemExit(f"error: {spec['page']} documents no operation of type {spec['type']!r}")
    definitions: dict[str, str] = {}
    refs = set()
    prefixes = set()
    differences = []
    operations: dict[str, dict] = {}
    for row in rows:
        name = row["Operation name"]
        action, event = row.get("IAM action", ""), row.get("CloudTrail event", "")
        if action != event or not action.startswith(scope + ":"):
            raise SystemExit(f"error: {name} on {spec['page']}: its IAM action {action!r} is not its CloudTrail "
                             f"event {event!r} in {scope}")
        raw, line, ref = raw_definition_url(row.get("Open API definition", ""))
        if raw not in definitions:
            definitions[raw] = fetch(raw)
        refs.add(ref)
        method, defined = defined_route(definitions[raw], line, row["Open API definition"])
        page_method, route, prefix, differs = documented_route(row.get("REST path", ""), defined)
        if prefix is not None:
            prefixes.add("/".join(prefix))
        if differs or page_method != method:
            differences.append(f"{name}: the page gives {row['REST path']}, its definition {method} {defined}")
        operation = action.split(":", 1)[1]
        if operation not in operations:
            operations[operation] = {"method": method, "requestUri": route}
        else:
            # An alternative says where it locates the members its path no longer holds: none, here.
            alternative = {"requestUri": route, "located": {}} if method == operations[operation]["method"] \
                else {"method": method, "requestUri": route, "located": {}}
            operations[operation].setdefault("alternatives", []).append(alternative)
    if len(prefixes) > 1 or len(refs) > 1:
        raise SystemExit(f"error: {spec['page']} gives its routes more than one prefix {sorted(prefixes)} or "
                         f"definition {sorted(refs)}")
    return {
        "credentialScope": scope,
        "apiVersion": next(iter(refs)),
        "documented": {"page": spec["page"], "definitions": sorted(definitions), "pageDiffers": differences,
                       "retrieved": date.today().isoformat()},
        "operations": dict(sorted(operations.items())),
    }


def carried_documented(current: str) -> dict[str, dict]:
    """The documented endpoints' routes the vendored file holds, each the endpoint `DOCUMENTED` reads from the same
    page under the same scope, with the routes a request can match."""
    models = json.loads(current).get("models", {}) if current.strip() else {}
    carried = {}
    for name, spec in DOCUMENTED.items():
        model = models.get(name)
        if model is None:
            raise SystemExit(f"error: the vendored file has no routes of {name}: run with --fetch-documented")
        if model.get("credentialScope") != spec["credentialScope"] \
                or model.get("documented", {}).get("page") != spec["page"]:
            raise SystemExit(f"error: the vendored routes of {name} are not read from {spec['page']} under "
                             f"{spec['credentialScope']}: run with --fetch-documented")
        operations = model.get("operations")
        if not operations or not all(isinstance(route, dict) and route.get("method") in METHODS
                                     and str(route.get("requestUri", "")).startswith("/")
                                     for route in operations.values()):
            raise SystemExit(f"error: the vendored routes of {name} are malformed: run with --fetch-documented")
        carried[name] = model
    return carried


METHODS = ("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")


def build(models: dict[str, dict], provenance: str, documented: dict[str, dict] | None = None) -> dict:
    """The vendored document from each loaded model, and the routes of each documented endpoint."""
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
    for name, model in (documented or {}).items():
        if name in routes:
            raise SystemExit(f"error: {name} is both a botocore model and a documented endpoint")
        routes[name] = model
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
        if "documented" in model:
            lines.append(f'      "documented": {json.dumps(model["documented"])},')
        lines.append('      "operations": {')
        operations = list(model["operations"].items())
        for position, (operation, route) in enumerate(operations):
            comma = "," if position < len(operations) - 1 else ""
            lines.append(f"        {json.dumps(operation)}: {json.dumps(route)}{comma}")
        lines.append("      }")
        lines.append("    }" + ("," if index < len(models) - 1 else ""))
    lines += ["  }", "}"]
    return "\n".join(lines) + "\n"


def verify_documented(vendored: dict[str, dict], fetch=fetch_url) -> int:
    """Whether each documented endpoint's page and definitions still give the vendored routes, the retrieval date
    aside."""
    stale = []
    for name, spec in DOCUMENTED.items():
        fresh = build_documented(spec, fetch)
        ours = json.loads(json.dumps(vendored[name]))
        ours["documented"].pop("retrieved", None)
        fresh["documented"].pop("retrieved", None)
        if ours != fresh:
            stale.append(name)
    for name in stale:
        print(f"error: the routes {DOCUMENTED[name]['page']} documents differ from the vendored {name}: run with "
              f"--fetch-documented", file=sys.stderr)
    if not stale:
        print(f"{len(DOCUMENTED)} documented endpoint(s) match their AWS pages")
    return 1 if stale else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 when the vendored file differs from a fresh generation")
    parser.add_argument("--fetch-documented", action="store_true",
                        help="online: read the documented endpoints' routes again from their AWS pages")
    parser.add_argument("--verify", action="store_true",
                        help="online: exit 1 when a documented endpoint's AWS page gives other routes than the vendored")
    parser.add_argument("--botocore-data", type=Path, help="botocore data directory")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
    if args.verify:
        return verify_documented(carried_documented(current))
    documented = {name: build_documented(spec) for name, spec in DOCUMENTED.items()} if args.fetch_documented \
        else carried_documented(current)
    data, provenance = resolve_botocore_data(args.botocore_data)
    fresh = render(build(load_models(data), provenance, documented))

    if args.check:
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
