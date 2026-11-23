#!/usr/bin/env python3
"""Vendor what the API Gateway developer guide says API Gateway's management actions act on into
tools/aws/sources/apigateway-arn-formats.json, a source of the IAM tables.

API Gateway authorizes a management call as the HTTP method of its request, on the resource its path names. Its
developer guide says both, in "How Amazon API Gateway works with IAM":

    The API-managing Action expression has the format apigateway:action, where action is one of the following API
    Gateway actions: GET, POST, PUT, DELETE, PATCH (to update resources), or *, which is all of the previous actions.

    API Gateway resources have the following ARN format:
    arn:aws:apigateway:region::resource-path-specifier

    https://docs.aws.amazon.com/apigateway/latest/developerguide/security_iam_service-with-iam.html

and lists each resource's ARN in "API Gateway Amazon Resource Name (ARN) reference", a table for each kind of API,
its variables marked as the page marks them:

    https://docs.aws.amazon.com/apigateway/latest/developerguide/arn-format-reference.html

The Service Reference gives the resource types of HTTP and WebSocket APIs where a REST API's differ (its `Stage` is
`/apis/${ApiId}/stages/${StageName}`, a REST API's `/restapis/{api-id}/stages/{stage-name}`), and botocore does not
say which resource a route acts on. This script copies the two sentences and every row of the reference's tables as
they stand, each variable written `{name}`: nothing in the output is written by hand, and the generator reads it
against botocore's routes.

Run from anywhere in the repo:
    python3 tools/aws/fetch_apigateway_arn_formats.py           # rewrite the vendored file from the pages
    python3 tools/aws/fetch_apigateway_arn_formats.py --verify  # online: the file still says what the pages do
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

IAM_PAGE = "https://docs.aws.amazon.com/apigateway/latest/developerguide/security_iam_service-with-iam.html"
ARN_PAGE = "https://docs.aws.amazon.com/apigateway/latest/developerguide/arn-format-reference.html"
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "tools/aws/sources/apigateway-arn-formats.json"

ACTION_LEAD = "The API-managing Action expression has the format"
RESOURCE_LEAD = "API Gateway resources have the following ARN format:"
# The actions the action sentence names, each in bold.
BOLD_RE = re.compile(r"<b>(.*?)</b>", re.S)
VERBS = ("GET", "POST", "PUT", "DELETE", "PATCH")
# The reference's tables, one for each kind of resource: HTTP and WebSocket APIs, REST APIs, domain names and
# execute-api's. Far fewer rows means its layout changed under the parser.
MINIMUM_TABLES = 4
MINIMUM_ROWS = 60


def text(fragment: str) -> str:
    """A fragment of the page as text, each variable the page marks (`<code class="replaceable">`) written `{name}`,
    spaces collapsed."""
    marked = re.sub(r'<code class="replaceable">(?:<code[^>]*>)?(.*?)(?:</code>)?</code>',
                    lambda match: "{" + re.sub(r"<[^>]+>", "", match.group(1)).strip() + "}", fragment, flags=re.S)
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", "", marked))).strip()


def read_iam_page(page: str) -> dict:
    """The action sentence, with the actions it names, and the resource sentence, with the ARN format after it."""
    action = None
    for paragraph in re.findall(r"<p>(.*?)</p>", page, re.S):
        if text(paragraph).startswith(ACTION_LEAD):
            action = {"sentence": text(paragraph), "actions": [text(bold) for bold in BOLD_RE.findall(paragraph)]}
            break
    if action is None or not set(VERBS) <= set(action["actions"]):
        raise ValueError(f"{IAM_PAGE} gives no action sentence naming {', '.join(VERBS)}: its layout changed")
    resource = re.search(r"<p>\s*" + re.escape(RESOURCE_LEAD) + r"\s*</p>\s*<pre[^>]*>(.*?)</pre>", page, re.S)
    if resource is None:
        raise ValueError(f"{IAM_PAGE} gives no ARN format after '{RESOURCE_LEAD}': its layout changed")
    code = re.search(r'<code class="[^"]*">(.*)</code>', resource.group(1), re.S)
    return {"action": action,
            "resource": {"sentence": RESOURCE_LEAD, "format": text(code.group(1) if code else resource.group(1))}}


def read_arn_page(page: str) -> list[dict]:
    """Each table of the reference under the heading it stands under, each row's resource and ARN as given."""
    headings = [(match.start(), text(match.group(1))) for match in re.finditer(r"<h2[^>]*>(.*?)</h2>", page, re.S)]
    tables = []
    for table in re.finditer(r"<table.*?</table>", page, re.S):
        above = [heading for start, heading in headings if start < table.start()]
        if not above:
            raise ValueError(f"{ARN_PAGE} has a table under no heading: its layout changed")
        rows = []
        for row in re.findall(r"<tr>(.*?)</tr>", table.group(0), re.S):
            cells = re.findall(r"<td[^>]*>(.*?)</td>", row, re.S)
            if len(cells) == 2:
                rows.append({"resource": text(cells[0]), "arn": text(cells[1])})
        tables.append({"heading": above[-1], "rows": rows})
    return tables


def build(iam_page: str, arn_page: str) -> dict:
    tables = read_arn_page(arn_page)
    if len(tables) < MINIMUM_TABLES or sum(len(table["rows"]) for table in tables) < MINIMUM_ROWS:
        raise ValueError(f"{ARN_PAGE} gives {len(tables)} tables of "
                         f"{sum(len(table['rows']) for table in tables)} rows: its layout changed")
    return {
        "_source": {
            "pages": [IAM_PAGE, ARN_PAGE],
            "fetcher": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "The IAM page's action sentence with the actions it names, its resource sentence with the ARN "
                    "format after it, and each table of the ARN reference under its heading, each row's resource "
                    "and ARN, copied as they stand, each variable the pages mark written {name}.",
        },
        **read_iam_page(iam_page),
        "tables": tables,
    }


def fetch_url(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "floci-tools"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def stale(vendored: dict, iam_page: str, arn_page: str) -> bool:
    """Whether the pages now give something other than the vendored file, its date aside."""
    built = build(iam_page, arn_page)
    return any(vendored.get(key) != built[key] for key in built if key != "_source")


def main(argv: list[str] | None = None, fetch=fetch_url) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verify", action="store_true", help="online: the vendored file matches the pages")
    args = parser.parse_args(argv)
    try:
        iam_page, arn_page = fetch(IAM_PAGE), fetch(ARN_PAGE)
    except OSError as e:
        print(f"error: could not read the API Gateway developer guide ({e})", file=sys.stderr)
        return 2
    if args.verify:
        vendored = json.loads(OUTPUT.read_text(encoding="utf-8"))
        if stale(vendored, iam_page, arn_page):
            print(f"error: {OUTPUT.relative_to(REPO_ROOT)} no longer says what {IAM_PAGE} and {ARN_PAGE} do: run "
                  f"tools/aws/fetch_apigateway_arn_formats.py and then make iam-request-resources-sync",
                  file=sys.stderr)
            return 1
        print(f"{OUTPUT.relative_to(REPO_ROOT)} matches {IAM_PAGE} and {ARN_PAGE}")
        return 0
    built = build(iam_page, arn_page)
    OUTPUT.write_text(json.dumps(built, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)} ({sum(len(table['rows']) for table in built['tables'])} rows in "
          f"{len(built['tables'])} tables)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
