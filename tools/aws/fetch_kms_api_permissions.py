#!/usr/bin/env python3
"""Vendor what the AWS KMS developer guide and API reference say each KMS operation needs permission for into
tools/aws/sources/kms-api-permissions.json, a source of the IAM tables.

The developer guide's "AWS KMS permissions" table gives, for each API operation, the actions it is authorized by and
the resources each is checked on, in words where there are several:

    ReEncrypt: To use this operation, the caller needs permission on two KMS keys:
      kms:ReEncryptFrom on the KMS key used to decrypt
      kms:ReEncryptTo on the KMS key used to encrypt

    UpdateAlias: To use this operation, the caller needs kms:UpdateAlias permission on three resources:
      The alias
      The currently associated KMS key
      The newly associated KMS key

    https://docs.aws.amazon.com/kms/latest/developerguide/kms-api-permissions-reference.html

The Service Reference lists `alias` and `key` for the alias actions and ReEncrypt's two actions, but not which of them
each resource of a request is, nor which key ReEncrypt checks each action on; botocore does not say it as data
either. The API reference of an operation whose request may leave its key out says where KMS reads it from then, as
Decrypt's `KeyId` does:

    If you used a symmetric encryption KMS key, AWS KMS can get the KMS key from metadata that it adds to the
    symmetric ciphertext blob.

    https://docs.aws.amazon.com/kms/latest/APIReference/API_Decrypt.html

and its "Key identifiers" give the format of each identifier a request may give, an alias name being the alias ARN's
resource, while the Service Reference's `alias` format, `alias/${Alias}`, leaves the request's `alias/` to its variable:

    Alias ARN: The format of an alias ARN is as follows: arn:<partition>:kms:<region>:<account-id>:alias/<alias-name>
    Alias name: In the AWS KMS API, alias names always begin with alias/. The format of an alias name is as follows:
    alias/<alias-name>

    https://docs.aws.amazon.com/kms/latest/developerguide/concepts.html

This script copies each operation of the table as it stands (the actions it names, its description, the resources
its list names, and the policy type and resources column of each row it spans) and, from the API reference page it links
to, each request parameter's sentence saying KMS gets a resource from metadata, and each key identifier's format, each
variable written `{name}`. Nothing in the output is written by hand, and the generator reads it against botocore's
requests.

Run from anywhere in the repo:
    python3 tools/aws/fetch_kms_api_permissions.py           # rewrite the vendored file from the pages
    python3 tools/aws/fetch_kms_api_permissions.py --verify  # online: the file still says what the pages do
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

PERMISSIONS_PAGE = "https://docs.aws.amazon.com/kms/latest/developerguide/kms-api-permissions-reference.html"
CONCEPTS_PAGE = "https://docs.aws.amazon.com/kms/latest/developerguide/concepts.html"
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "tools/aws/sources/kms-api-permissions.json"

# An action the table names, alone in its paragraph under the operation.
ACTION_RE = re.compile(r"^[a-z0-9-]+:[A-Za-z0-9]+$")
# A sentence of a request parameter saying KMS reads a resource out of another parameter's metadata.
FROM_METADATA_RE = re.compile(r"\bcan get the [^.]* from metadata that it adds to\b")
# A request parameter of an API reference page and its description.
PARAMETER_RE = re.compile(
    r'<dt id="KMS-(?P<operation>\w+)-request-(?P<parameter>\w+)">.*?</dt>\s*<dd>(?P<body>.*?)</dd>', re.S)
# A key identifier of the concepts page, its term and its description.
IDENTIFIER_RE = re.compile(r'<dt id="key-id-[^"]*">(?P<term>.*?)</dt>\s*<dd>(?P<body>.*?)</dd>', re.S)
# The format an identifier's description gives, after the sentence saying it does.
FORMAT_RE = re.compile(r'<p>(?P<lead>[^<]*\bformat of [^<]*\bis as follows:)</p>\s*<pre[^>]*>(?P<format>.*?)</pre>',
                       re.S)
HEADINGS = ("Actions and permissions", "Policy type", "Cross-account use", "Resources (for IAM policies)")
# The table has a row for each of the fifty-odd operations; far fewer means its layout changed under the parser.
MINIMUM_ROWS = 40
FETCH_WORKERS = 8


def text(fragment: str) -> str:
    """A fragment of the page as text, spaces collapsed."""
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).strip()


def read_permissions(page: str) -> list[dict]:
    """Each operation of the permissions table: the API reference page it links to, the actions it names, its
    description's paragraphs, the items of its list, and each policy type and resources column it gives, one for each
    row its first cell spans (CreateAlias's IAM policy for the alias and key policy for the KMS key)."""
    tables = re.findall(r"<table.*?</table>", page, re.S)
    if len(tables) != 1:
        raise ValueError(f"{PERMISSIONS_PAGE} has {len(tables)} tables, not the one table of permissions: its layout "
                         f"changed")
    rows = re.findall(r"<tr>(.*?)</tr>", tables[0], re.S)
    headings = [text(cell) for cell in re.findall(r"<th[^>]*>(.*?)</th>", rows[0], re.S)] if rows else []
    if tuple(headings[:len(HEADINGS)]) != HEADINGS:
        raise ValueError(f"{PERMISSIONS_PAGE}'s table is headed {headings}, not {list(HEADINGS)}: its layout changed")
    found = []
    spanned = 0
    for row in rows[1:]:
        cells = re.findall(r"<td[^>]*>(.*?)</td>", row, re.S)
        if spanned:
            # A row the operation's first cell spans gives another policy type and resources column of it.
            spanned -= 1
            found[-1]["policies"].append({"policyType": text(cells[0]), "resources": text(cells[2])})
            continue
        if len(cells) < len(HEADINGS):
            continue
        link = re.search(r'<a href="(?P<href>[^"]+)">(?P<operation>[A-Za-z0-9]+)</a>', cells[0])
        if link is None:
            raise ValueError(f"{PERMISSIONS_PAGE} has a row naming no operation: its layout changed")
        span = re.match(r'<td[^>]*\browspan="(\d+)"', re.search(r"<td[^>]*>", row).group(0))
        spanned = int(span.group(1)) - 1 if span else 0
        items = [text(item) for item in re.findall(r"<li[^>]*>(.*?)</li>", cells[0], re.S)]
        outside = re.sub(r"<ul.*?</ul>", "", cells[0], flags=re.S)
        # The operation's link stands in a paragraph of its own, or before the first one.
        paragraphs = [text(paragraph) for paragraph in re.findall(r"<p>(.*?)</p>", outside, re.S)
                      if text(paragraph) != link.group("operation")]
        found.append({
            "operation": link.group("operation"),
            "apiReference": urllib.parse.urljoin(PERMISSIONS_PAGE, link.group("href")),
            "actions": [paragraph for paragraph in paragraphs if ACTION_RE.match(paragraph)],
            "description": [paragraph for paragraph in paragraphs if not ACTION_RE.match(paragraph)],
            "items": items,
            "policies": [{"policyType": text(cells[1]), "resources": text(cells[3])}],
        })
    return found


def marked(fragment: str) -> str:
    """A fragment as text, each variable the page marks (`<code class="replaceable">&lt;alias-name&gt;</code>`) written
    `{name}`, the page's copy button left out."""
    fragment = re.sub(r'<div class="code-btn-container">.*?</div></div>', "", fragment, flags=re.S)
    return text(re.sub(r'<code class="replaceable">(.*?)</code>',
                       lambda match: "{" + text(match.group(1)).strip("<>") + "}", fragment, flags=re.S))


def read_identifiers(page: str) -> list[dict]:
    """Each key identifier of the concepts page that gives its format, with the sentence giving it and the format."""
    found = []
    for match in IDENTIFIER_RE.finditer(page):
        given = FORMAT_RE.search(match.group("body"))
        if given:
            found.append({"identifier": text(match.group("term")), "sentence": text(given.group("lead")),
                          "format": marked(given.group("format"))})
    if not {"Alias name", "Alias ARN"} <= {identifier["identifier"] for identifier in found}:
        raise ValueError(f"{CONCEPTS_PAGE} gives no alias name and alias ARN formats: its layout changed")
    return found


def read_from_metadata(page: str) -> list[dict]:
    """Each request parameter of an API reference page whose description says KMS gets a resource from metadata,
    with that sentence."""
    found = []
    for match in PARAMETER_RE.finditer(page):
        for paragraph in re.findall(r"<p>(.*?)</p>", match.group("body"), re.S):
            for sentence in re.split(r"(?<=\.)\s+", text(paragraph)):
                if FROM_METADATA_RE.search(sentence):
                    found.append({"operation": match.group("operation"), "parameter": match.group("parameter"),
                                  "sentence": sentence})
    return found


def build(permissions_page: str, reference_pages: dict[str, str], concepts_page: str) -> dict:
    permissions = read_permissions(permissions_page)
    if len(permissions) < MINIMUM_ROWS:
        raise ValueError(f"{PERMISSIONS_PAGE} gives {len(permissions)} rows: its layout changed")
    from_metadata = [found for row in permissions for found in read_from_metadata(reference_pages[row["apiReference"]])]
    return {
        "_source": {
            "pages": [PERMISSIONS_PAGE, CONCEPTS_PAGE],
            "fetcher": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "Each operation of the developer guide's permissions table, copied as it stands (the API "
                    "reference page it links to, the actions it names, its description, the items of its list, and "
                    "the policy type and resources column of each row it spans), and each request parameter "
                    "sentence of those API reference pages saying KMS gets a resource from metadata, and each key "
                    "identifier the concepts page gives a format of, each variable written {name}.",
        },
        "permissions": permissions,
        "fromMetadata": from_metadata,
        "identifiers": read_identifiers(concepts_page),
    }


def fetch_url(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "floci-tools"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def fetch_all(fetch=fetch_url) -> tuple[str, dict[str, str], str]:
    """The permissions page, the API reference page of each operation its table links to, and the concepts page."""
    permissions_page = fetch(PERMISSIONS_PAGE)
    links = sorted({row["apiReference"] for row in read_permissions(permissions_page)})
    with ThreadPoolExecutor(max_workers=FETCH_WORKERS) as pool:
        return permissions_page, dict(zip(links, pool.map(fetch, links))), fetch(CONCEPTS_PAGE)


def stale(vendored: dict, permissions_page: str, reference_pages: dict[str, str], concepts_page: str) -> bool:
    """Whether the pages now give something other than the vendored file, its date aside."""
    built = build(permissions_page, reference_pages, concepts_page)
    return any(vendored.get(key) != built[key] for key in built if key != "_source")


def main(argv: list[str] | None = None, fetch=fetch_url) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verify", action="store_true", help="online: the vendored file matches the pages")
    args = parser.parse_args(argv)
    try:
        pages = fetch_all(fetch)
    except OSError as e:
        print(f"error: could not read the AWS KMS documentation ({e})", file=sys.stderr)
        return 2
    if args.verify:
        vendored = json.loads(OUTPUT.read_text(encoding="utf-8"))
        if stale(vendored, *pages):
            print(f"error: {OUTPUT.relative_to(REPO_ROOT)} no longer says what {PERMISSIONS_PAGE}, the API "
                  f"reference pages it links to and {CONCEPTS_PAGE} do: run tools/aws/fetch_kms_api_permissions.py "
                  f"and then make iam-request-resources-sync", file=sys.stderr)
            return 1
        print(f"{OUTPUT.relative_to(REPO_ROOT)} matches {PERMISSIONS_PAGE}, the API reference pages it links to and "
              f"{CONCEPTS_PAGE}")
        return 0
    built = build(*pages)
    OUTPUT.write_text(json.dumps(built, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)} ({len(built['permissions'])} operations, "
          f"{len(built['fromMetadata'])} parameters read from metadata, "
          f"{len(built['identifiers'])} identifier formats)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
