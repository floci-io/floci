#!/usr/bin/env python3
"""Vendor AWS's Session Manager request and permission examples for the IAM tables."""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
import urllib.request
from collections.abc import Callable, Iterable, Mapping
from datetime import datetime, timezone
from pathlib import Path

ROOT = "https://docs.aws.amazon.com/systems-manager/latest"
DEFAULT_PAGE = f"{ROOT}/userguide/getting-started-default-session-document.html"
HYBRID_PAGE = f"{ROOT}/userguide/systems-manager-hybrid-multicloud.html"
SESSIONS_PAGE = f"{ROOT}/userguide/session-manager-working-with-sessions-start.html"
API_PAGES = {name: f"{ROOT}/APIReference/API_{name}.html" for name in ("StartSession", "GetConnectionStatus")}
PAGES = (DEFAULT_PAGE, HYBRID_PAGE, SESSIONS_PAGE, *API_PAGES.values())
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "tools/aws/sources/ssm-session-permissions.json"


def blocks(page: str, tag: str) -> list[str]:
    """Copy the contents of a paragraph or code block without the surrounding HTML."""
    return [html.unescape(re.sub(r"<[^>]+>", "", fragment)).strip()
            for fragment in re.findall(rf"<{tag}\b[^>]*>(.*?)</{tag}>", page, re.S)]


def one(values: Iterable[str], page: str, description: str) -> str:
    found = list(values)
    if len(found) != 1:
        raise ValueError(f"{page}: expected one {description}, found {len(found)}")
    return found[0]


def sample_request(page: str, operation: str, url: str) -> dict[str, object]:
    block = one((block for block in blocks(page, "pre")
                 if f"X-Amz-Target: AmazonSSM.{operation}\n" in block), url, "sample request")
    _, separator, body = block.partition("\n\n")
    if not separator:
        raise ValueError(f"{url}: sample request has no body")
    request = json.loads(body)
    if not isinstance(request, dict):
        raise ValueError(f"{url}: sample request body is not a JSON object")
    return request


def build(pages: Mapping[str, str]) -> dict[str, object]:
    paragraphs = [" ".join(block.split()) for block in blocks(pages[DEFAULT_PAGE], "p")]
    default_sentences = one((p for p in paragraphs if "This is the default session document." in p),
                            DEFAULT_PAGE, "default document description")
    default_use = one((p for p in paragraphs if p.startswith("By default, Session Manager uses")),
                      DEFAULT_PAGE, "default document usage")
    permission_sentence = one((p for p in paragraphs if p.startswith("To start a default shell session,")),
                              DEFAULT_PAGE, "default shell permission description")
    default_blocks = blocks(pages[DEFAULT_PAGE], "pre")
    command = one((block for block in default_blocks if block.startswith("aws ssm ")), DEFAULT_PAGE, "CLI example")
    policy = one((block for block in default_blocks if '"Statement"' in block), DEFAULT_PAGE, "IAM policy example")

    prefixes = one((" ".join(p.split()) for p in blocks(pages[HYBRID_PAGE], "p")
                    if "managed nodes are distinguished from Amazon EC2 instances" in " ".join(p.split())),
                   HYBRID_PAGE, "managed node ID prefix description")
    ecs_command = next((block for block in blocks(pages[SESSIONS_PAGE], "pre")
                        if block.startswith("aws ssm ") and "--target ecs:" in block), None)
    if ecs_command is None:
        raise ValueError(f"{SESSIONS_PAGE}: missing ECS CLI example")

    return {
        "_source": {
            "pages": list(PAGES),
            "fetcher": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "The default session document description, CLI and IAM policy examples, managed node ID "
                    "prefixes, an ECS CLI example and the API reference sample request bodies.",
        },
        "defaultDescription": default_sentences,
        "defaultUse": default_use,
        "defaultPermission": permission_sentence,
        "defaultCommand": command,
        "defaultPolicy": json.loads(policy),
        "managedNodePrefixes": prefixes,
        "ecsCommand": ecs_command,
        "sessionRequests": {name: sample_request(pages[url], name, url) for name, url in API_PAGES.items()},
    }


def fetch_url(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "floci-tools"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def fetch_all(fetch: Callable[[str], str] = fetch_url) -> tuple[dict[str, str]]:
    return ({url: fetch(url) for url in PAGES},)


def stale(vendored: Mapping[str, object], pages: Mapping[str, str]) -> bool:
    """Whether AWS gives different facts, ignoring the retrieval date."""
    built = build(pages)
    return any(vendored.get(key) != value for key, value in built.items() if key != "_source")


def main(argv: list[str] | None = None, fetch: Callable[[str], str] = fetch_url) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="online: the vendored file matches the pages")
    args = parser.parse_args(argv)
    try:
        pages, = fetch_all(fetch)
        if args.verify:
            if stale(json.loads(OUTPUT.read_text(encoding="utf-8")), pages):
                print(f"error: {OUTPUT.name} no longer matches AWS; run {Path(__file__).name}", file=sys.stderr)
                return 1
            print(f"{OUTPUT.name} matches the AWS Session Manager documentation")
        else:
            OUTPUT.write_text(json.dumps(build(pages), indent=2) + "\n", encoding="utf-8")
            print(f"wrote {OUTPUT.relative_to(REPO_ROOT)}")
    except (OSError, ValueError) as e:
        print(f"error: could not vendor AWS Session Manager permissions ({e})", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
