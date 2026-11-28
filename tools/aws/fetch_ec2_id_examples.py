#!/usr/bin/env python3
"""Copy identifier examples and their XML member paths from the EC2 API reference.

The generator matches these response paths against botocore's output shapes to learn
identifier prefixes. The fetched data supplies no handwritten resource-type mapping.
"""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "tools/aws/sources/ec2-id-examples.json"
PAGES = {"CreateVpcEndpoint": "https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_CreateVpcEndpoint.html"}
ID_RE = re.compile(r"[a-z0-9]+-[0-9a-f]{8,17}")


def read_examples(operation: str, page: str) -> list[dict]:
    """Copy ID-valued leaves of response examples, retaining their serialized paths."""
    found = []
    responses = 0

    def walk(node: ET.Element, path: list[str]) -> None:
        if not len(node) and ID_RE.fullmatch((node.text or "").strip()):
            found.append({"path": path, "value": node.text.strip()})
        for child in node:
            walk(child, path + [child.tag.rsplit("}", 1)[-1]])

    for block in re.findall(r"<pre\b[^>]*>(.*?)</pre>", page, re.S):
        text = html.unescape(re.sub(r"<[^>]+>", "", block)).strip()
        if not re.match(r"<" + re.escape(operation) + r"Response(?:\s|>)", text):
            continue
        try:
            root = ET.fromstring(text)
        except ET.ParseError as error:
            raise ValueError(f"{operation} has a malformed XML response example") from error
        responses += 1
        walk(root, [])
    if not responses or not found:
        raise ValueError(f"{operation} has no response identifier examples: the page layout changed")
    return found


def fetch_url(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "floci-tools"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def build(pages: dict[str, str]) -> dict:
    return {
        "_source": {"fetcher": "tools/aws/fetch_ec2_id_examples.py",
                    "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
                    "pages": list(PAGES.values())},
        "namespace": "ec2",
        "operations": {operation: {"page": url, "examples": read_examples(operation, pages[url])}
                       for operation, url in PAGES.items()},
    }


def stale(vendored: dict, pages: dict[str, str]) -> bool:
    built = build(pages)
    return any(vendored.get(key) != built[key] for key in ("namespace", "operations"))


def fetch_all(fetch=None) -> tuple[dict[str, str]]:
    fetch = fetch or fetch_url
    return ({url: fetch(url) for url in PAGES.values()},)


def main(argv: list[str] | None = None, fetch=fetch_url) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args(argv)
    try:
        pages, = fetch_all(fetch)
        result = build(pages)
        if args.verify:
            if stale(json.loads(OUTPUT.read_text()), pages):
                print("error: EC2 identifier examples changed; rerun tools/aws/fetch_ec2_id_examples.py", file=sys.stderr)
                return 1
        else:
            OUTPUT.write_text(json.dumps(result, indent=2) + "\n")
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"error: could not read EC2 identifier examples ({error})", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
