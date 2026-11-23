#!/usr/bin/env python3
"""Vendor which resource types the Service Authorization Reference marks required for each action of the namespaces
granted it into tools/aws/sources/required-resource-types.json, a source of the IAM tables.

The Service Reference gives each action the resource types it acts on, but not which of them it always acts on;
botocore does not say it either. Each service's page of the Service Authorization Reference does, by an asterisk on
the type in its actions table, whose column is headed "Resource types (*required)":

    DeleteGroupMembership   Grants permission to remove a member that is part of a group in the specified
                            IdentityStore   Group*  GroupMembership*  Identitystore*  User*

    https://docs.aws.amazon.com/service-authorization/latest/reference/list_awsidentitystore.html

A required type the request does not name is one the service finds from what it holds: a membership's group and user,
the group an alternate identifier names. This script copies each action's resource type cells, asterisks and all, as
they stand, for the namespaces in PAGES. Nothing in the output is written by hand, and the generator reads it against
the Service Reference's types.

Run from anywhere in the repo:
    python3 tools/aws/fetch_required_resource_types.py           # rewrite the vendored file from the pages
    python3 tools/aws/fetch_required_resource_types.py --verify  # online: the file still says what the pages do
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

# The namespaces the source is granted for, each with its page of the Service Authorization Reference.
PAGES = {
    "identitystore": "https://docs.aws.amazon.com/service-authorization/latest/reference/list_awsidentitystore.html",
}
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "tools/aws/sources/required-resource-types.json"

REQUIRED_HEADING = "Resource types (*required)"
TABLE_RE = re.compile(r"<table[^>]*>\s*<thead>(.*?)</thead>(.*?)</table>", re.S)
ROW_RE = re.compile(r"<tr>(.*?)</tr>", re.S)
ACTION_RE = re.compile(r'<span id="list_[a-z0-9]+-action-([A-Za-z0-9]+)"></span>')
RESOURCE_RE = re.compile(r'<a href="#list_[a-z0-9]+-resource-[^"]*">(.*?)</a>', re.S)


def text(fragment: str) -> str:
    """A fragment of the page as text, spaces collapsed."""
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).strip()


def read_actions(url: str, page: str) -> dict[str, list[str]]:
    """Each action of the page's actions table with its resource type cells, as they stand."""
    for table in TABLE_RE.finditer(page):
        headings = [text(heading) for heading in re.findall(r"<th[^>]*>(.*?)</th>", table.group(1), re.S)]
        if REQUIRED_HEADING not in headings:
            continue
        actions: dict[str, list[str]] = {}
        current = None
        for row in ROW_RE.findall(table.group(2)):
            started = ACTION_RE.search(row)
            if started:
                current = started.group(1)
                actions[current] = []
            if current is not None:
                actions[current] += [text(resource) for resource in RESOURCE_RE.findall(row)]
        if not actions:
            raise ValueError(f"{url}'s actions table names no action: its layout changed")
        return actions
    raise ValueError(f"{url} gives no table headed '{REQUIRED_HEADING}': its layout changed")


def build(pages: dict[str, str]) -> dict:
    return {
        "_source": {
            "pages": [PAGES[namespace] for namespace in sorted(pages)],
            "fetcher": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "Each action of each page's actions table with its resource type cells, an asterisk marking a "
                    "required type, copied as they stand.",
        },
        "namespaces": {namespace: {"page": PAGES[namespace], "actions": read_actions(PAGES[namespace], page)}
                       for namespace, page in sorted(pages.items())},
    }


def fetch_url(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "floci-tools"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def fetch_all(fetch=fetch_url) -> tuple[dict[str, str]]:
    """Each granted namespace's page."""
    return ({namespace: fetch(url) for namespace, url in PAGES.items()},)


def stale(vendored: dict, pages: dict[str, str]) -> bool:
    """Whether the pages now give something other than the vendored file, its date aside."""
    built = build(pages)
    return any(vendored.get(key) != built[key] for key in built if key != "_source")


def main(argv: list[str] | None = None, fetch=fetch_url) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verify", action="store_true", help="online: the vendored file matches the pages")
    args = parser.parse_args(argv)
    try:
        (pages,) = fetch_all(fetch)
    except OSError as e:
        print(f"error: could not read the Service Authorization Reference ({e})", file=sys.stderr)
        return 2
    if args.verify:
        vendored = json.loads(OUTPUT.read_text(encoding="utf-8"))
        if stale(vendored, pages):
            print(f"error: {OUTPUT.relative_to(REPO_ROOT)} no longer says what {', '.join(PAGES.values())} do: run "
                  f"tools/aws/fetch_required_resource_types.py and then make iam-request-resources-sync",
                  file=sys.stderr)
            return 1
        print(f"{OUTPUT.relative_to(REPO_ROOT)} matches {', '.join(PAGES.values())}")
        return 0
    built = build(pages)
    OUTPUT.write_text(json.dumps(built, indent=2) + "\n", encoding="utf-8")
    actions = sum(len(entry["actions"]) for entry in built["namespaces"].values())
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)} ({actions} actions over {len(built['namespaces'])} namespaces)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
