#!/usr/bin/env python3
"""Vendor where an Amazon API Gateway request names the roles it passes into tools/aws/sources/apigateway-roles.json, a
source of the passed-roles table.

AWS's Service Reference passes the roles of twelve API Gateway operations to `apigateway.amazonaws.com`, and botocore
gives their requests, but no member they name a role in matches the passed-roles rule (a name ending in `Role`,
`RoleArn` or `RoleName`): API Gateway's API references say which members take a role, and where.

- A member named for credentials takes a role, the API references say of the resource types that hold it:

      credentials: To specify an IAM Role for API Gateway to assume, use the role's Amazon Resource Name (ARN).
      https://docs.aws.amazon.com/apigateway/latest/api/API_Integration.html          (REST APIs, also Authorizer)
      https://docs.aws.amazon.com/apigatewayv2/latest/api-reference/apis-apiid-integrations-integrationid.html

- A REST API update names the property it changes by the path of a patch operation:

      For example, if the target resource has an updateable property of {"name":"value"}, the path for this
      property is /name.
      https://docs.aws.amazon.com/apigateway/latest/api/API_PatchOperation.html

- An import's body is an OpenAPI definition ("Currently, only OpenAPI definition JSON/YAML files are supported";
  "The OpenAPI definition"), whose API Gateway extensions name roles: the integration extension, "an extended
  property of the OpenAPI Operation object", its `credentials` ("For AWS IAM role-based credentials, specify the ARN
  of an appropriate IAM role"), and the authorizer extension, which "applies to the security definition in OpenAPI 2
  and the security scheme in OpenAPI 3", its `authorizerCredentials` ("in the form of an ARN of an IAM execution
  role"); the ANY method's extension "Specifies the OpenAPI Operation Object for the API Gateway catch-all ANY
  method".

This script copies each of those descriptions as it stands, with the member, type, operation or extension it is
about. Nothing in the output is written by hand; regen_iam_passed_roles.py reads it to find the role members of
API Gateway's requests.

Run from anywhere in the repo:
    python3 tools/aws/fetch_apigateway_roles.py           # rewrite the vendored file from the pages
    python3 tools/aws/fetch_apigateway_roles.py --verify  # online: the file still says what the pages do
"""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

V1 = "https://docs.aws.amazon.com/apigateway/latest/api/"
V2 = "https://docs.aws.amazon.com/apigatewayv2/latest/api-reference/"
GUIDE = "https://docs.aws.amazon.com/apigateway/latest/developerguide/"
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "tools/aws/sources/apigateway-roles.json"

# The resource types whose member takes a role, each by the API reference page describing it: a REST API type's
# page, or the HTTP API page of a resource and the schema it describes there.
ROLE_MEMBERS = (
    {"model": "apigateway", "type": "Integration", "member": "credentials", "page": V1 + "API_Integration.html"},
    {"model": "apigateway", "type": "Authorizer", "member": "authorizerCredentials",
     "page": V1 + "API_Authorizer.html"},
    {"model": "apigatewayv2", "type": "Integration", "member": "credentialsArn",
     "page": V2 + "apis-apiid-integrations-integrationid.html"},
    {"model": "apigatewayv2", "type": "Authorizer", "member": "authorizerCredentialsArn",
     "page": V2 + "apis-apiid-authorizers-authorizerid.html"},
)
PATCH_PAGE = V1 + "API_PatchOperation.html"
# The imports whose body is an OpenAPI definition, each by the API reference page describing it, and for an HTTP
# API page the schema of its body there.
DOCUMENTS = (
    {"model": "apigateway", "operation": "ImportRestApi", "member": "body", "page": V1 + "API_ImportRestApi.html"},
    {"model": "apigateway", "operation": "PutRestApi", "member": "body", "page": V1 + "API_PutRestApi.html"},
    {"model": "apigatewayv2", "operation": "ImportApi", "member": "body", "page": V2 + "apis.html",
     "schema": "importapiinput"},
    {"model": "apigatewayv2", "operation": "ReimportApi", "member": "body", "page": V2 + "apis-apiid.html",
     "schema": "reimportapiinput"},
)
# The OpenAPI extensions naming a role, each by its developer guide page and the property of it that names one.
EXTENSIONS = (
    {"extension": "x-amazon-apigateway-integration", "member": "credentials",
     "page": GUIDE + "api-gateway-swagger-extensions-integration.html"},
    {"extension": "x-amazon-apigateway-authorizer", "member": "authorizerCredentials",
     "page": GUIDE + "api-gateway-swagger-extensions-authorizer.html"},
)
ANY_METHOD_PAGE = GUIDE + "api-gateway-swagger-extensions-any-method.html"

# A description saying its member takes a role's ARN.
ROLE_RE = re.compile(r"\bTo specify an IAM role for API Gateway to assume, use the role's Amazon Resource Name "
                     r"\(ARN\)", re.IGNORECASE)
EXTENSION_ROLE_RE = re.compile(r"\bARN of an? (?:appropriate )?IAM (?:execution )?role\b")
PATCH_RE = re.compile(r"the path for this property is /name\.")
DOCUMENT_RE = re.compile(r"\bOpenAPI definition\b")
# Where an extension sits, by what its page says it extends.
PLACEMENTS = (
    ("operation", re.compile(r"This extension is an extended property of the OpenAPI Operation object\.")),
    ("securityScheme", re.compile(r"This extension applies to the security definition in OpenAPI 2 and the "
                                  r"security scheme in OpenAPI 3\s?\.")),
)
ANY_METHOD_RE = re.compile(r"(?P<extension>x-amazon-apigateway-any-method) object Specifies the OpenAPI Operation "
                           r"Object for the API Gateway catch-all ANY method in an OpenAPI Path Item Object \.")


def text(fragment: str) -> str:
    """A fragment of a page as text, spaces collapsed."""
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).strip()


def main_text(page: str) -> str:
    """A page's main column as text."""
    start = page.find('id="main-col-body"')
    return text(page[start:] if start >= 0 else page)


def definition(page: str, url: str, anchor: str) -> str:
    """The description a REST API reference page gives the entry `anchor`."""
    match = re.search(r'id="' + re.escape(anchor) + r'"[^>]*>.*?<dd>(?P<body>.*?)</dd>', page, re.S)
    if match is None:
        raise ValueError(f"{url} has no entry {anchor}: its layout changed")
    return re.sub(r"\s*Type: .*$", "", text(match.group("body")))


def property_row(page: str, url: str, anchor: str) -> str:
    """The description an HTTP API reference page's property table gives the row `anchor`."""
    match = re.search(r'id="' + re.escape(anchor) + r'".*?</td>(?P<cells>.*?)</tr>', page, re.S)
    if match is None:
        raise ValueError(f"{url} has no property {anchor}: its layout changed")
    cells = re.findall(r"<td[^>]*>(.*?)</td>", match.group("cells"), re.S)
    if len(cells) < 3:
        raise ValueError(f"{url} gives {anchor} no description: its layout changed")
    return text(cells[2])


def slug(url: str) -> str:
    return url.rsplit("/", 1)[1].removesuffix(".html")


def read_role_member(entry: dict, page: str) -> dict:
    if entry["model"] == "apigateway":
        description = definition(page, entry["page"], f"apigw-Type-{entry['type']}-{entry['member']}")
    else:
        description = property_row(page, entry["page"],
                                   f"{slug(entry['page'])}-prop-{entry['type'].lower()}-{entry['member'].lower()}")
    if not ROLE_RE.search(description):
        raise ValueError(f"{entry['page']} no longer says {entry['member']} takes a role's ARN: '{description}'")
    return {**entry, "description": description}


def read_document(entry: dict, page: str) -> dict:
    if entry["model"] == "apigateway":
        description = definition(page, entry["page"], f"apigw-{entry['operation']}-request-{entry['member']}")
    else:
        description = property_row(page, entry["page"], f"{slug(entry['page'])}-prop-{entry['schema']}-"
                                                        f"{entry['member']}")
    if not DOCUMENT_RE.search(description):
        raise ValueError(f"{entry['page']} no longer says {entry['operation']}'s {entry['member']} is an OpenAPI "
                         f"definition: '{description}'")
    return {key: value for key, value in entry.items() if key != "schema"} | {"description": description}


def read_extension(entry: dict, page: str) -> dict:
    body = main_text(page)
    placement = next(((on, match.group(0)) for on, pattern in PLACEMENTS if (match := pattern.search(body))), None)
    if placement is None:
        raise ValueError(f"{entry['page']} no longer says what {entry['extension']} extends: its layout changed")
    rows = [re.findall(r"<td[^>]*>(.*?)</td>", row, re.S) for row in re.findall(r"<tr>(.*?)</tr>", page, re.S)]
    described = [text(cells[-1]) for cells in rows if cells and text(cells[0]) == entry["member"]]
    if len(described) != 1 or not EXTENSION_ROLE_RE.search(described[0]):
        raise ValueError(f"{entry['page']} no longer says {entry['member']} is a role's ARN: {described}")
    return {**entry, "on": placement[0], "placement": placement[1], "description": described[0]}


def build(fetch) -> dict:
    patch = definition(fetch(PATCH_PAGE), PATCH_PAGE, "apigw-Type-PatchOperation-path")
    if not PATCH_RE.search(patch):
        raise ValueError(f"{PATCH_PAGE} no longer names a property's path as /name: '{patch}'")
    any_method = ANY_METHOD_RE.search(main_text(fetch(ANY_METHOD_PAGE)))
    if any_method is None:
        raise ValueError(f"{ANY_METHOD_PAGE} no longer names the ANY method's extension: its layout changed")
    return {
        "_source": {
            "pages": sorted({entry["page"] for entry in ROLE_MEMBERS + DOCUMENTS + EXTENSIONS}
                            | {PATCH_PAGE, ANY_METHOD_PAGE}),
            "fetcher": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "Each description copied as it stands from the API Gateway API references and developer guide: "
                    "the members of a resource type that take a role, how a REST API update's patch operation names "
                    "a property, the imports whose body is an OpenAPI definition, and the OpenAPI extensions and "
                    "their properties that name a role, with what each extends.",
        },
        "roleMembers": [read_role_member(entry, fetch(entry["page"])) for entry in ROLE_MEMBERS],
        "patchPath": {"model": "apigateway", "page": PATCH_PAGE, "description": patch},
        "documents": [read_document(entry, fetch(entry["page"])) for entry in DOCUMENTS],
        "extensions": [read_extension(entry, fetch(entry["page"])) for entry in EXTENSIONS],
        "anyMethod": {"extension": any_method.group("extension"), "page": ANY_METHOD_PAGE,
                      "description": any_method.group(0)},
    }


def fetch_url(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "floci-tools"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def fetch_all(fetch=fetch_url) -> tuple:
    """The pages, each fetched once."""
    cache: dict[str, str] = {}

    def cached(url: str) -> str:
        if url not in cache:
            cache[url] = fetch(url)
        return cache[url]

    build(cached)
    return (cache,)


def stale(vendored: dict, pages: dict[str, str]) -> bool:
    """Whether the pages now give something other than the vendored file, its date aside."""
    built = build(lambda url: pages[url])
    return any(vendored.get(key) != built[key] for key in built if key != "_source")


def main(argv: list[str] | None = None, fetch=fetch_url) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verify", action="store_true", help="online: the vendored file matches the pages")
    args = parser.parse_args(argv)
    try:
        (pages,) = fetch_all(fetch)
    except OSError as e:
        print(f"error: could not read the Amazon API Gateway documentation ({e})", file=sys.stderr)
        return 2
    if args.verify:
        vendored = json.loads(OUTPUT.read_text(encoding="utf-8"))
        if stale(vendored, pages):
            print(f"error: {OUTPUT.relative_to(REPO_ROOT)} no longer says what the API Gateway documentation does: "
                  f"run tools/aws/fetch_apigateway_roles.py and then make iam-passed-roles-sync", file=sys.stderr)
            return 1
        print(f"{OUTPUT.relative_to(REPO_ROOT)} matches the API Gateway documentation")
        return 0
    built = build(lambda url: pages[url])
    OUTPUT.write_text(json.dumps(built, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)} ({len(built['roleMembers'])} role members, "
          f"{len(built['documents'])} documents, {len(built['extensions'])} extensions)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
