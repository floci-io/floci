#!/usr/bin/env python3
"""Vendor the actions the S3 user guide requires for each S3 API operation into
tools/aws/sources/s3-required-actions.json, a source of the IAM tables.

The Service Reference lists the actions that authorize each operation with no word on which one a request needs: it
gives ListObjectsV2 s3:ListBucket and s3:GetObjectAcl, and GetObject s3:GetObject and s3:GetObjectVersion alike, and
one entry for S3's and S3 Control's GetPublicAccessBlock. The S3 user guide's "Required permissions for Amazon S3 API
operations" does: each operation's "(Required)" action, its "(Conditionally required)" ones apart, the two operations
of one name told apart as "(Bucket-level)" and "(Account-level)", and the choice of a version action in one form:

    (Required) Either s3:GetObject or s3:GetObjectVersion
    s3:GetObject - Required if you want to get an object without specifying versionId in the request.
    s3:GetObjectVersion - Required if you want to get a specific version of an object by specifying versionId in the
    request.

    https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-with-s3-policy-actions.html

This script copies every operation's "(Required)" rows as they stand: the operation as the page heads it, its section,
each single required action, and an Either row's two actions with each one's sentence. Nothing in the output is
written by hand; the generators read them against botocore's operations and members.

Run from anywhere in the repo:
    python3 tools/aws/fetch_s3_required_actions.py           # rewrite the vendored file from the page
    python3 tools/aws/fetch_s3_required_actions.py --verify  # online: the file still says what the page does
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

PAGE = "https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-with-s3-policy-actions.html"
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "tools/aws/sources/s3-required-actions.json"
# A heading: the operation, or operations a row covers together ("CreateStorageLensGroup, TagResource"), with a note in
# brackets the page may add, which can run onto the next line; of the notes only a level tells two operations of one
# name apart ("GetPublicAccessBlock (Bucket-level)").
OPERATION_RE = re.compile(r"([A-Z][A-Za-z0-9]+(?:, [A-Z][A-Za-z0-9]+)*)(?: \((.*))?")
LEVEL_RE = re.compile(r"(Bucket|Account)-level\)")
SECTION_RE = re.compile(r".+ operations and permissions")
# A "(Required)" row's actions, other services' among them ("s3tables:CreateTable", "kms:ReEncrypt*"); one ending in a
# comma runs onto the next line ("(Required) s3:CreateStorageLensGroup," then "s3:TagResource").
ACTIONS = r"([a-z0-9-]+:[A-Za-z0-9*]+(?:, [a-z0-9-]+:[A-Za-z0-9*]+)*)(,?)"
REQUIRED_RE = re.compile(r"\(Required\) " + ACTIONS)
CONTINUED_RE = re.compile(ACTIONS)
EITHER_RE = re.compile(r"\(Required\) Either (s3:\w+) or (s3:\w+)")
SENTENCE_RE = re.compile(r"(s3:\w+) [–-] (Required if .*)")


def lines(page: str) -> list[str]:
    """The page's main column as text lines, spaces (non-breaking ones too) collapsed."""
    match = re.search(r'<div id="main-col-body">(.*?)<div class="prev-next"', page, re.S)
    body = match.group(1) if match else page
    body = re.sub(r"<(script|style).*?</\1>", "", body, flags=re.S)
    body = re.sub(r"<br\s*/?>|</p>|</li>|</h\d>|</tr>|</pre>|</dt>|</dd>", "\n", body)
    text = html.unescape(re.sub(r"<[^>]+>", "", body)).replace(" ", " ")
    return [re.sub(r"\s+", " ", line).strip() for line in text.splitlines() if line.strip()]


def required_actions(text: list[str], index: int, row: re.Match) -> list[str]:
    """The actions of the "(Required)" row at `index`, the lines it runs onto included."""
    actions = row.group(1).split(", ")
    more = row.group(2)
    for following in text[index + 1:]:
        if not more:
            break
        continued = CONTINUED_RE.fullmatch(following)
        if continued is None:
            raise ValueError(f"a required row ends in a comma and no action follows it: {text[index]}")
        actions += continued.group(1).split(", ")
        more = continued.group(2)
    return actions


def read_page(page: str) -> dict:
    """Each heading's "(Required)" rows, as the page gives them: its section, its single required actions, and an
    Either row's two actions with each one's sentence. A heading naming several operations keeps its rows as one
    entry under that heading ("CreateStorageLensGroup, TagResource")."""
    found: dict[str, dict] = {}
    operation = None
    section = None
    text = lines(page)
    for index, line in enumerate(text):
        if SECTION_RE.fullmatch(line):
            section = line
            continue
        heading = OPERATION_RE.fullmatch(line)
        if heading:
            level = LEVEL_RE.match(heading.group(2) or "")
            operation = heading.group(1) + (f" ({level.group(1)}-level)" if level else "")
            continue
        single = REQUIRED_RE.fullmatch(line)
        if single and operation is not None:
            entry = found.setdefault(operation, {"section": section})
            for action in required_actions(text, index, single):
                if action not in entry.setdefault("required", []):
                    entry["required"].append(action)
            continue
        either = EITHER_RE.fullmatch(line)
        if not either or operation is None:
            continue
        sentences = {}
        for following in text[index + 1:index + 4]:
            sentence = SENTENCE_RE.fullmatch(following)
            if sentence and sentence.group(1) in either.groups():
                sentences[sentence.group(1)] = sentence.group(2)
        if set(sentences) != set(either.groups()):
            raise ValueError(f"{operation}'s Either row does not give both actions a sentence: {line}")
        found.setdefault(operation, {"section": section})["either"] = {"actions": list(either.groups()),
                                                                       "sentences": sentences}
    return dict(sorted(found.items()))


# The page lists some 170 operations and eleven Either rows; far fewer means its layout changed under the parser.
MINIMUM_OPERATIONS = 100


def build(page: str) -> dict:
    operations = read_page(page)
    if len(operations) < MINIMUM_OPERATIONS or not any("either" in entry for entry in operations.values()):
        raise ValueError(f"{PAGE} gives {len(operations)} operations and no Either row: its layout changed")
    return {
        "_source": {
            "page": PAGE,
            "fetcher": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "Each heading's '(Required)' rows of the page, under the heading as it stands (one naming several "
                    "operations kept whole): its single required actions, and an Either row's two actions with the "
                    "sentence it gives each, copied as they stand.",
        },
        "operations": operations,
    }


def fetch_url(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "floci-tools"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def main(argv: list[str] | None = None, fetch=fetch_url) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verify", action="store_true", help="online: the vendored file matches the page")
    args = parser.parse_args(argv)
    built = build(fetch(PAGE))
    if args.verify:
        vendored = json.loads(OUTPUT.read_text(encoding="utf-8"))
        if vendored["operations"] != built["operations"]:
            print(f"error: {OUTPUT.relative_to(REPO_ROOT)} no longer says what {PAGE} does", file=sys.stderr)
            return 1
        print(f"{OUTPUT.relative_to(REPO_ROOT)} matches {PAGE}")
        return 0
    OUTPUT.write_text(json.dumps(built, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)} ({len(built['operations'])} operations)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
