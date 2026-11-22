#!/usr/bin/env python3
"""Vendor what the Amazon DynamoDB developer guide says its PartiQL statements and transaction items need permission
for into tools/aws/sources/dynamodb-permissions.json, a source of the IAM tables.

The Service Reference authorizes ExecuteStatement, BatchExecuteStatement and ExecuteTransaction by the four PartiQL
actions and TransactWriteItems by ConditionCheckItem, DeleteItem, PutItem and UpdateItem, but not which statement or
item takes which; botocore does not say it either. The developer guide does:

    The following permissions are required:
      To read items using PartiQL for DynamoDB, you must have dynamodb:PartiQLSelect permission on the table or index.
      To insert items using PartiQL for DynamoDB, you must have dynamodb:PartiQLInsert permission on the table or index.
      (update and delete alike)

    https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/ql-iam.html

    Permissions for Put, Update, Delete, and Get actions are governed by the permissions used for the underlying
    PutItem, UpdateItem, DeleteItem, and GetItem operations. For the ConditionCheck action, you can use the
    dynamodb:ConditionCheckItem permission in IAM policies.

    https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis-iam.html

This script copies the PartiQL page's list of required permissions and the transactions page's paragraph on the
permissions of each kind of item, sentence by sentence, each with the names the page sets as code, as they stand.
Nothing in the output is written by hand, and the generator reads it against botocore's requests.

Run from anywhere in the repo:
    python3 tools/aws/fetch_dynamodb_permissions.py           # rewrite the vendored file from the pages
    python3 tools/aws/fetch_dynamodb_permissions.py --verify  # online: the file still says what the pages do
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

PARTIQL_PAGE = "https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/ql-iam.html"
TRANSACTIONS_PAGE = "https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis-iam.html"
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "tools/aws/sources/dynamodb-permissions.json"

PARTIQL_LEAD = "The following permissions are required:"
# The transactions page's paragraph on what each kind of item needs, by the words it starts with.
TRANSACTIONS_LEAD = "Permissions for"
CODE_RE = re.compile(r'<code class="code">(.*?)</code>', re.S)


def text(fragment: str) -> str:
    """A fragment of the page as text, spaces collapsed."""
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).strip()


def sentence(fragment: str) -> dict:
    """A sentence of the page as text, with the names it sets as code, in order."""
    return {"text": re.sub(r"\s+([.,])", r"\1", text(fragment)),
            "code": [text(code) for code in CODE_RE.findall(fragment)]}


def read_partiql(page: str) -> dict:
    """The PartiQL page's list of the permissions its statements require, each item with its code."""
    found = re.search(r"<p>\s*" + re.escape(PARTIQL_LEAD) + r"\s*</p>\s*<div[^>]*>\s*<ul[^>]*>(.*?)</ul>", page, re.S)
    if found is None:
        raise ValueError(f"{PARTIQL_PAGE} gives no list after '{PARTIQL_LEAD}': its layout changed")
    items = [sentence(item) for item in re.findall(r"<li[^>]*>(.*?)</li>", found.group(1), re.S)]
    if len(items) < 4 or not all(item["code"] for item in items):
        raise ValueError(f"{PARTIQL_PAGE}'s list names {len(items)} permissions, not one in each of its four items: "
                         f"its layout changed")
    return {"page": PARTIQL_PAGE, "lead": PARTIQL_LEAD, "items": items}


def read_transactions(page: str) -> dict:
    """The transactions page's paragraph on the permissions of each kind of item, sentence by sentence."""
    for paragraph in re.findall(r"<p>(.*?)</p>", page, re.S):
        if text(paragraph).startswith(TRANSACTIONS_LEAD):
            sentences = [sentence(part) for part in re.split(r"(?<=\.)\s*(?=[A-Z<])", paragraph.strip()) if text(part)]
            return {"page": TRANSACTIONS_PAGE, "sentences": sentences}
    raise ValueError(f"{TRANSACTIONS_PAGE} gives no paragraph starting '{TRANSACTIONS_LEAD}': its layout changed")


def build(partiql_page: str, transactions_page: str) -> dict:
    return {
        "_source": {
            "pages": [PARTIQL_PAGE, TRANSACTIONS_PAGE],
            "fetcher": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "The PartiQL page's list of required permissions and the transactions page's paragraph on the "
                    "permissions of each kind of item, sentence by sentence, each with the names the page sets as "
                    "code, copied as they stand.",
        },
        "partiql": read_partiql(partiql_page),
        "transactions": read_transactions(transactions_page),
    }


def fetch_url(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "floci-tools"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def fetch_all(fetch=fetch_url) -> tuple[str, str]:
    """The PartiQL page and the transactions page."""
    return fetch(PARTIQL_PAGE), fetch(TRANSACTIONS_PAGE)


def stale(vendored: dict, partiql_page: str, transactions_page: str) -> bool:
    """Whether the pages now give something other than the vendored file, its date aside."""
    built = build(partiql_page, transactions_page)
    return any(vendored.get(key) != built[key] for key in built if key != "_source")


def main(argv: list[str] | None = None, fetch=fetch_url) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verify", action="store_true", help="online: the vendored file matches the pages")
    args = parser.parse_args(argv)
    try:
        pages = fetch_all(fetch)
    except OSError as e:
        print(f"error: could not read the Amazon DynamoDB developer guide ({e})", file=sys.stderr)
        return 2
    if args.verify:
        vendored = json.loads(OUTPUT.read_text(encoding="utf-8"))
        if stale(vendored, *pages):
            print(f"error: {OUTPUT.relative_to(REPO_ROOT)} no longer says what {PARTIQL_PAGE} and "
                  f"{TRANSACTIONS_PAGE} do: run tools/aws/fetch_dynamodb_permissions.py and then make "
                  f"iam-request-resources-sync", file=sys.stderr)
            return 1
        print(f"{OUTPUT.relative_to(REPO_ROOT)} matches {PARTIQL_PAGE} and {TRANSACTIONS_PAGE}")
        return 0
    built = build(*pages)
    OUTPUT.write_text(json.dumps(built, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)} ({len(built['partiql']['items'])} PartiQL permissions, "
          f"{len(built['transactions']['sentences'])} transaction sentences)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
