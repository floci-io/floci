#!/usr/bin/env python3
"""Vendor the names CloudTrail records Amazon S3 data events under, as the S3 user guide gives them, into
tools/aws/sources/cloudtrail-s3-data-events.json, a source of regen_cloudtrail_s3_data_events.py.

Botocore models S3's operations but not which of them CloudTrail records as data events, nor the name a record
carries. The S3 user guide's "Amazon S3 CloudTrail events" page says both: its data events table lists, for each
resource type, the APIs CloudTrail logs, and a sentence gives the name of a call recorded under another one:

    S3    AWS::S3::Object    AbortMultipartUpload  CompleteMultipartUpload  CopyObject ...

    When you call PutObjectRetention, the CloudTrail data event is recorded with the eventName
    PutObjectLockRetention, ...

    https://docs.aws.amazon.com/AmazonS3/latest/userguide/cloudtrail-logging-s3-info.html

This script copies each row of that table, its API cells as they stand, and each such sentence with the call and
the name it gives. Nothing in the output is written by hand; the generator reads it against botocore's operations.

Run from anywhere in the repo:
    python3 tools/aws/fetch_cloudtrail_s3_data_events.py           # rewrite the vendored file from the page
    python3 tools/aws/fetch_cloudtrail_s3_data_events.py --verify  # online: the file still says what the page does
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

PAGE = "https://docs.aws.amazon.com/AmazonS3/latest/userguide/cloudtrail-logging-s3-info.html"
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "tools/aws/sources/cloudtrail-s3-data-events.json"

TYPE_HEADING = "resources.type value"
APIS_HEADING = "Data APIs logged to CloudTrail"
TABLE_RE = re.compile(r"<table[^>]*>(.*?)</table>", re.S)
ROW_RE = re.compile(r"<tr>(.*?)</tr>", re.S)
CELL_RE = re.compile(r"<t[dh][^>]*>(.*?)</t[dh]>", re.S)
ITEM_RE = re.compile(r"<li[^>]*>(.*?)</li>", re.S)
PARAGRAPH_RE = re.compile(r"<p>(.*?)</p>", re.S)
RECORDED_AS_RE = re.compile(r"^When you call (\w+), the CloudTrail data event is recorded with the eventName (\w+)\b")


def text(fragment: str) -> str:
    """A fragment of the page as text, spaces collapsed and no space left before a comma or a period."""
    collapsed = re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).strip()
    return re.sub(r" ([,.])", r"\1", collapsed)


def read_data_events(page: str) -> dict[str, list[str]]:
    """Each resource type of the page's data events table with the API cells it lists, as they stand."""
    for table in TABLE_RE.finditer(page):
        rows = ROW_RE.findall(table.group(1))
        headings = [text(cell) for cell in CELL_RE.findall(rows[0])] if rows else []
        if TYPE_HEADING not in headings or APIS_HEADING not in headings:
            continue
        kind, apis = headings.index(TYPE_HEADING), headings.index(APIS_HEADING)
        events: dict[str, list[str]] = {}
        for row in rows[1:]:
            cells = CELL_RE.findall(row)
            if len(cells) > max(kind, apis):
                events[text(cells[kind])] = [text(item) for item in ITEM_RE.findall(cells[apis])]
        if not events:
            raise ValueError(f"{PAGE}'s data events table lists no resource type: its layout changed")
        return events
    raise ValueError(f"{PAGE} gives no table headed '{TYPE_HEADING}' and '{APIS_HEADING}': its layout changed")


def read_recorded_as(page: str) -> list[dict[str, str]]:
    """Each sentence of the page giving the name a call's data event is recorded with, as it stands."""
    found = []
    for paragraph in PARAGRAPH_RE.findall(page):
        sentence = text(paragraph)
        match = RECORDED_AS_RE.match(sentence)
        if match:
            found.append({"call": match.group(1), "eventName": match.group(2), "sentence": sentence})
    return found


def build(page: str) -> dict:
    return {
        "_source": {
            "page": PAGE,
            "fetcher": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "Each row of the page's data events table with its API cells, and each sentence giving the "
                    "name a call's data event is recorded with, copied as they stand.",
        },
        "dataEvents": read_data_events(page),
        "recordedAs": read_recorded_as(page),
    }


def fetch_url(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "floci-tools"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def stale(vendored: dict, page: str) -> bool:
    """Whether the page now gives something other than the vendored file, its date aside."""
    built = build(page)
    return any(vendored.get(key) != built[key] for key in built if key != "_source")


def main(argv: list[str] | None = None, fetch=fetch_url) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verify", action="store_true", help="online: the vendored file matches the page")
    args = parser.parse_args(argv)
    try:
        page = fetch(PAGE)
    except OSError as e:
        print(f"error: could not read the S3 user guide ({e})", file=sys.stderr)
        return 2
    if args.verify:
        vendored = json.loads(OUTPUT.read_text(encoding="utf-8"))
        if stale(vendored, page):
            print(f"error: {OUTPUT.relative_to(REPO_ROOT)} no longer says what {PAGE} does: run "
                  f"tools/aws/fetch_cloudtrail_s3_data_events.py and then make cloudtrail-s3-data-events-sync",
                  file=sys.stderr)
            return 1
        print(f"{OUTPUT.relative_to(REPO_ROOT)} matches {PAGE}")
        return 0
    built = build(page)
    OUTPUT.write_text(json.dumps(built, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)} ({len(built['dataEvents'])} resource types, "
          f"{len(built['recordedAs'])} renamed calls)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
