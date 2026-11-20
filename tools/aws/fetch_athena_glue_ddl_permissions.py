#!/usr/bin/env python3
"""Vendor the Glue permissions Athena's documentation gives each DDL statement into
tools/aws/sources/athena-glue-ddl-permissions.json, a source of regen_iam_request_resources.py.

Athena runs a DDL statement against the AWS Glue Data Catalog with the caller's identity, so the caller needs the
Glue actions the statement calls. Neither botocore nor the Service Reference says which those are: StartQueryExecution
lists no dependent actions. Athena's user guide does, in the table "Examples of database and table-level permissions"
of "Configure access to databases and tables in the AWS Glue Data Catalog":

    https://docs.aws.amazon.com/athena/latest/ug/fine-grained-access-to-glue-resources.html

For each statement the table gives the example policy that allows it, written for a database `example_db` and a table
`test` ("In the examples, replace the example_db database and test table with your own database and table names").
This script copies that table as it stands: each statement's sentence and policy, the policy's statements, actions
and example ARNs unchanged, and the sentence naming the example database and table. Nothing in the output is written
by hand. The generator reads the actions and ARNs against the Service Reference.

When the page's layout changes so this script cannot read it, athena-glue-ddl-permissions.prompt.md says how to
rebuild the file from the page by hand and what to check afterwards.

Run from anywhere in the repo:
    python3 tools/aws/fetch_athena_glue_ddl_permissions.py           # rewrite the vendored file from the page
    python3 tools/aws/fetch_athena_glue_ddl_permissions.py --verify  # online: the file still says what the page does
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path

PAGE = "https://docs.aws.amazon.com/athena/latest/ug/fine-grained-access-to-glue-resources.html"
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "tools/aws/sources/athena-glue-ddl-permissions.json"
PROMPT = "tools/aws/sources/athena-glue-ddl-permissions.prompt.md"
# The header of the table's first column.
TABLE_HEADER = "DDL statement"
# The sentence naming the example database and table: "replace the example_db database and test table".
EXAMPLES_RE = re.compile(r"replace the (?P<database>\S+) database and (?P<table>\S+) table with your own", re.I)


class StatementTable(HTMLParser):
    """The rows of the table whose first header is `TABLE_HEADER`, each as its first cell's text, the second cell's
    text outside its code block and the code block's text, and the page's text outside tables."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.rows: list[tuple[str, str, str]] = []
        self.prose: list[str] = []
        self._depth = 0
        self._in_table = False
        self._header: list[str] | None = None
        self._cells: list[list[str]] | None = None
        self._code: list[str] | None = None
        self._in_pre = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "table":
            self._depth += 1
            self._header = []
        elif tag == "tr" and self._depth:
            self._cells = []
            self._code = []
        elif tag in ("td", "th") and self._cells is not None:
            self._cells.append([])
        elif tag == "pre" and self._cells:
            self._in_pre = True

    def handle_endtag(self, tag: str) -> None:
        if tag == "table" and self._depth:
            self._depth -= 1
            self._in_table = False
        elif tag == "pre":
            self._in_pre = False
        elif tag == "tr" and self._cells is not None:
            cells = [" ".join("".join(cell).split()) for cell in self._cells]
            if cells and cells[0] == TABLE_HEADER:
                self._in_table = True
            elif self._in_table and len(cells) == 2:
                self.rows.append((cells[0], cells[1], "".join(self._code or [])))
            self._cells = None
            self._code = None

    def handle_data(self, data: str) -> None:
        if self._in_pre and self._code is not None:
            self._code.append(data)
        elif self._cells:
            self._cells[-1].append(data)
        elif not self._depth:
            self.prose.append(data)


def read_page(page: str) -> dict:
    """The statements and examples of the page's table, as the vendored file holds them, its date aside."""
    parser = StatementTable()
    parser.feed(page)
    if not parser.rows:
        raise ValueError(f"the page has no table headed {TABLE_HEADER!r}: see {PROMPT}")
    prose = " ".join("".join(parser.prose).split())
    examples = EXAMPLES_RE.search(prose)
    if examples is None:
        raise ValueError(f"the page no longer says which database and table its examples name: see {PROMPT}")
    statements: dict[str, dict] = {}
    for name, text, code in parser.rows:
        try:
            policy = json.loads("[" + code + "]")
        except ValueError as e:
            raise ValueError(f"the policy the page gives {name} is not JSON ({e}): see {PROMPT}") from e
        if not policy or not all(isinstance(statement, dict) and statement.get("Effect") == "Allow"
                                 and isinstance(statement.get("Action"), list)
                                 and isinstance(statement.get("Resource"), list) for statement in policy):
            raise ValueError(f"the policy the page gives {name} is not statements allowing a list of actions on a "
                             f"list of resources: see {PROMPT}")
        if name in statements:
            raise ValueError(f"the page gives {name} twice: see {PROMPT}")
        statements[name] = {"text": text, "policy": policy}
    return {
        "examples": {examples.group("database"): "database", examples.group("table"): "table"},
        "statements": statements,
    }


def build(page: str) -> dict:
    return {
        "_source": {
            "page": PAGE,
            "fetcher": "tools/aws/fetch_athena_glue_ddl_permissions.py",
            "prompt": PROMPT,
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "The example policies of the page's table 'Examples of database and table-level permissions', "
                    "copied as they stand; examples maps the example names to what they stand for, as the page's "
                    "sentence 'replace the example_db database and test table with your own database and table "
                    "names' says. regen_iam_request_resources.py reads it.",
        },
        **read_page(page),
    }


def fetch_url(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "floci-aws-data-generator"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def stale(vendored: dict, page: str) -> bool:
    """Whether the page now gives something other than the vendored file, its date aside."""
    return {key: value for key, value in vendored.items() if key != "_source"} != read_page(page)


def main(argv: list[str] | None = None, fetch=fetch_url) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verify", action="store_true", help="online: the vendored file still says what the page does")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    try:
        page = fetch(PAGE)
    except OSError as e:
        print(f"error: could not read {PAGE} ({e})", file=sys.stderr)
        return 2
    try:
        if args.verify:
            vendored = json.loads(args.output.read_text(encoding="utf-8"))
            if stale(vendored, page):
                print(f"error: {PAGE} no longer gives what {OUTPUT.relative_to(REPO_ROOT)} holds: run "
                      f"tools/aws/fetch_athena_glue_ddl_permissions.py and then make iam-request-resources-sync",
                      file=sys.stderr)
                return 1
            print(f"{OUTPUT.relative_to(REPO_ROOT)} matches {PAGE}")
            return 0
        document = build(page)
    except (OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {args.output} ({len(document['statements'])} statements from {PAGE})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
