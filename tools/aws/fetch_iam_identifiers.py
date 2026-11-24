#!/usr/bin/env python3
"""Vendor what the IAM User Guide and the IAM API reference say about the resources IAM's own requests name into
tools/aws/sources/iam-identifiers.json, a source of the IAM tables.

The Service Reference gives IAM's types ARN formats whose one variable holds a name with its path
(`user/${UserNameWithPath}`), and botocore's requests name a user by its name alone; neither says that the path is the
one the resource was created with, nor that a request leaving its user out acts on the user who signed it, nor that
a rename needs permission on both names. The IAM User Guide's "IAM identifiers" gives the ARNs' syntax and examples:

    arn:aws:iam::account:user/user-name-with-path
    arn:aws:iam::123456789012:user/division_abc/subdivision_xyz/Jane

    If you use the IAM API or AWS Command Line Interface (AWS CLI) to create IAM resources, you can add an optional
    path.

    https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_identifiers.html

and the API reference page of each operation says the rest, as GetUser, UpdateGroup and TagMFADevice do:

    If you do not specify a user name, IAM determines the user name implicitly based on the AWS access key ID used to
    sign the request to this operation.

    The person making the request (the principal), must have permission to change the role group with the old name
    and the new name.

    For virtual MFA devices, the serial number is the same as the ARN.

    https://docs.aws.amazon.com/IAM/latest/APIReference/API_Operations.html

This script copies the identifiers page's ARN syntax and examples, each variable written `{name}`, and its sentences
on paths given through the API, and, from the API reference page of each operation its list of actions links to, each
sentence of the operation's description or of a request parameter saying the operation acts on the user who signs it,
that the caller needs permission on the old and the new name, that a parameter's value is the resource's ARN, or the
format of an identifier. Nothing in the output is written by hand, and the generator reads it against botocore's
requests.

Run from anywhere in the repo:
    python3 tools/aws/fetch_iam_identifiers.py           # rewrite the vendored file from the pages
    python3 tools/aws/fetch_iam_identifiers.py --verify  # online: the file still says what the pages do
"""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

IDENTIFIERS_PAGE = "https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_identifiers.html"
OPERATIONS_PAGE = "https://docs.aws.amazon.com/IAM/latest/APIReference/API_Operations.html"
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "tools/aws/sources/iam-identifiers.json"

# The paragraphs the identifiers page leads its ARN syntax and its examples with.
SYNTAX_LEAD = "Syntax:"
EXAMPLES_LEAD = "Examples:"
# A sentence of the identifiers page on the path a resource takes when the API creates it.
PATH_RE = re.compile(r"\bIAM API\b.*\bcreate IAM resources\b.*\bpath\b")
# A sentence saying the operation acts on the user who signs the request, when it names none or always.
SIGNING_USER_RE = re.compile(r"\bimplicitly based on the AWS access key\b|\bdefaults to the (?:user|principal) making "
                             r"the request\b|\bassumes the user whose credentials are used to call the operation\b|"
                             r"\bthe IAM user who is calling this operation\b")
# A sentence saying the caller needs permission on the resource under its old name and under its new one.
BOTH_NAMES_RE = re.compile(r"\bwith the old name and the new name\b|\bon both the source object and the target "
                           r"object\b")
# A sentence of a request parameter saying its value is the resource's ARN.
SAME_AS_ARN_RE = re.compile(r"\bis the same as the ARN\b")
# A sentence of a request parameter giving the format of the identifier it holds.
FORMAT_RE = re.compile(r"\bin the format [a-z-]+/\S+")
# An operation the actions page lists, by the link to its page.
OPERATION_LINK_RE = re.compile(r'<a href="\./API_(?P<operation>[A-Za-z0-9]+)\.html">(?P=operation)</a>')
# The description of an API reference page, between its title and its request parameters.
DESCRIPTION_RE = re.compile(r'<h1 class="topictitle"[^>]*>.*?</h1>(?P<body>.*?)<h2 id="API_\w+_RequestParameters"',
                            re.S)
# The request parameters of an API reference page, up to its response elements or its errors.
PARAMETERS_RE = re.compile(r'<h2 id="API_\w+_RequestParameters".*?(?=<h2 id="API_\w+_(?:ResponseElements|Errors)")',
                           re.S)
# A request parameter of an API reference page and its description.
PARAMETER_RE = re.compile(r'<dt>\s*<span class="term">\s*<b>\s*(?P<parameter>\w+)\s*</b>.*?</dt>\s*<dd>(?P<body>.*?)'
                          r'</dd>', re.S)
# The actions page lists every one of IAM's one hundred and eighty-odd operations; far fewer means its layout changed.
MINIMUM_OPERATIONS = 150
FETCH_WORKERS = 8


def text(fragment: str) -> str:
    """A fragment of the page as text, spaces collapsed."""
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).strip()


def sentences(fragment: str) -> list[str]:
    """The sentences of each paragraph of a fragment of the page, as text."""
    return [sentence for paragraph in re.findall(r"<p>(.*?)</p>", fragment, re.S)
            for sentence in re.split(r"(?<=\.)\s+(?=[A-Z])", text(paragraph)) if sentence]


def marked(fragment: str) -> list[str]:
    """The lines of a code block of the page as text, each variable the page marks
    (`<code class="replaceable">user-name-with-path</code>`) written `{name}`, the page's copy button left out."""
    fragment = re.sub(r'<div class="code-btn-container">.*?</div></div>', "", fragment, flags=re.S)
    fragment = re.sub(r'<code class="replaceable">(.*?)</code>',
                      lambda match: "{" + text(match.group(1)).strip("<>") + "}", fragment, flags=re.S)
    return [line.strip() for line in html.unescape(re.sub(r"<[^>]+>", "", fragment)).splitlines() if line.strip()]


def code_after(page: str, lead: str) -> list[str]:
    """The lines of the first code block after the paragraph saying `lead`."""
    for match in re.finditer(r"<p>(.*?)</p>", page, re.S):
        if text(match.group(1)) == lead:
            block = re.search(r"<pre[^>]*>(.*?)</pre>", page[match.end():], re.S)
            if block:
                return marked(block.group(1))
    raise ValueError(f"{IDENTIFIERS_PAGE} gives no code after '{lead}': its layout changed")


def read_arns(page: str) -> dict:
    """The identifiers page's ARN syntax and examples, line by line, and its sentences on paths the API gives."""
    syntax = code_after(page, SYNTAX_LEAD)
    examples = code_after(page, EXAMPLES_LEAD)
    paths = [sentence for sentence in sentences(page) if PATH_RE.search(sentence)]
    if not any(line.endswith("-with-path}") for line in syntax) or not paths:
        raise ValueError(f"{IDENTIFIERS_PAGE} gives no ARN with a path and no sentence on paths: its layout changed")
    return {"page": IDENTIFIERS_PAGE, "syntax": syntax, "examples": examples, "paths": paths}


def read_operations(page: str) -> list[str]:
    """The operations the actions page lists, by the name its link to each one's page gives."""
    found = list(dict.fromkeys(OPERATION_LINK_RE.findall(page)))
    if len(found) < MINIMUM_OPERATIONS:
        raise ValueError(f"{OPERATIONS_PAGE} lists {len(found)} operations: its layout changed")
    return found


def page_of(operation: str) -> str:
    return urllib.parse.urljoin(OPERATIONS_PAGE, f"API_{operation}.html")


def read_sentences(operation: str, page: str, pattern: re.Pattern, description: bool = True) -> list[dict]:
    """Each sentence of an API reference page's description (with no parameter) and of each of its request
    parameters that `pattern` finds, in the page's order."""
    found: list[dict] = []
    described = DESCRIPTION_RE.search(page)
    if description and described:
        found += [{"operation": operation, "parameter": None, "sentence": sentence}
                  for sentence in sentences(described.group("body")) if pattern.search(sentence)]
    parameters = PARAMETERS_RE.search(page)
    for match in PARAMETER_RE.finditer(parameters.group(0) if parameters else ""):
        found += [{"operation": operation, "parameter": match.group("parameter"), "sentence": sentence}
                  for sentence in sentences(match.group("body")) if pattern.search(sentence)]
    return found


def build(identifiers_page: str, operations_page: str, reference_pages: dict[str, str]) -> dict:
    operations = read_operations(operations_page)

    def each(pattern: re.Pattern, description: bool = True) -> list[dict]:
        return [found for operation in operations
                for found in read_sentences(operation, reference_pages[page_of(operation)], pattern, description)]

    return {
        "_source": {
            "pages": [IDENTIFIERS_PAGE, OPERATIONS_PAGE],
            "fetcher": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "The identifiers page's ARN syntax and examples, each variable written {name}, and its sentences "
                    "on paths given through the API; and each sentence of the description or of a request parameter "
                    "of the API reference page of each operation the actions page lists that says the operation acts "
                    "on the user who signs it, that the caller needs permission on the old and the new name, that a "
                    "parameter's value is the resource's ARN, or the format of an identifier, copied as they stand.",
        },
        "arns": read_arns(identifiers_page),
        "signingUser": each(SIGNING_USER_RE),
        "bothNames": each(BOTH_NAMES_RE),
        "sameAsArn": each(SAME_AS_ARN_RE, description=False),
        "formats": each(FORMAT_RE, description=False),
    }


def fetch_url(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "floci-tools"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def fetch_all(fetch=fetch_url) -> tuple[str, str, dict[str, str]]:
    """The identifiers page, the actions page, and the API reference page of each operation it lists."""
    operations_page = fetch(OPERATIONS_PAGE)
    links = [page_of(operation) for operation in read_operations(operations_page)]
    with ThreadPoolExecutor(max_workers=FETCH_WORKERS) as pool:
        return fetch(IDENTIFIERS_PAGE), operations_page, dict(zip(links, pool.map(fetch, links)))


def stale(vendored: dict, identifiers_page: str, operations_page: str, reference_pages: dict[str, str]) -> bool:
    """Whether the pages now give something other than the vendored file, its date aside."""
    built = build(identifiers_page, operations_page, reference_pages)
    return any(vendored.get(key) != built[key] for key in built if key != "_source")


def main(argv: list[str] | None = None, fetch=fetch_url) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verify", action="store_true", help="online: the vendored file matches the pages")
    args = parser.parse_args(argv)
    try:
        pages = fetch_all(fetch)
    except OSError as e:
        print(f"error: could not read the IAM documentation ({e})", file=sys.stderr)
        return 2
    if args.verify:
        vendored = json.loads(OUTPUT.read_text(encoding="utf-8"))
        if stale(vendored, *pages):
            print(f"error: {OUTPUT.relative_to(REPO_ROOT)} no longer says what {IDENTIFIERS_PAGE} and the API "
                  f"reference pages {OPERATIONS_PAGE} links to do: run tools/aws/fetch_iam_identifiers.py and then "
                  f"make iam-request-resources-sync", file=sys.stderr)
            return 1
        print(f"{OUTPUT.relative_to(REPO_ROOT)} matches {IDENTIFIERS_PAGE} and the API reference pages "
              f"{OPERATIONS_PAGE} links to")
        return 0
    built = build(*pages)
    OUTPUT.write_text(json.dumps(built, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)} ({len(built['arns']['syntax'])} ARN formats, "
          f"{len(built['signingUser'])} signing-user sentences, {len(built['bothNames'])} rename sentences, "
          f"{len(built['sameAsArn'])} ARN parameters, {len(built['formats'])} identifier formats)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
