#!/usr/bin/env python3
"""Vendor which role an Amazon EKS request member names, and the service that role is for, into
tools/aws/sources/eks-roles.json, a source of the passed-roles table.

AWS's Service Reference passes CreateCluster's and UpdateClusterConfig's roles to `ec2.amazonaws.com` and
`eks.amazonaws.com` without saying which member's role goes where, and botocore does not say it as data either. The
EKS API reference describes each member as the role it names, and the EKS user guide gives each role's trust policy,
the service it is for. CreateCluster's `roleArn` is the cluster role, its description linking the guide's page of it:

    The Amazon Resource Name (ARN) of the IAM role that provides permissions for the Kubernetes control plane to make
    calls to AWS API operations on your behalf. For more information, see Amazon EKS Service IAM Role in the Amazon
    EKS User Guide.

    https://docs.aws.amazon.com/eks/latest/APIReference/API_CreateCluster.html
    https://docs.aws.amazon.com/eks/latest/userguide/service_IAM_role.html   ("Service": "eks.amazonaws.com")

and EKS Auto Mode's `nodeRoleArn`, a member of the ComputeConfigRequest both operations take, is the node role, its
description naming the guide's IAM reference for EKS Auto Mode, whose section of that role links the guide's page of
it:

    The ARN of the IAM Role EKS will assign to EC2 Managed Instances in your EKS Auto Mode cluster. ... For more
    information, see the IAM Reference in the Amazon EKS User Guide.

    https://docs.aws.amazon.com/eks/latest/APIReference/API_ComputeConfigRequest.html
    https://docs.aws.amazon.com/eks/latest/userguide/auto-learn-iam.html     (section "Node IAM role")
    https://docs.aws.amazon.com/eks/latest/userguide/create-node-role.html   ("Service": "ec2.amazonaws.com")

This script copies each member's description as it stands, the guide's section that names its role where the
description names the IAM reference, and the title and trust policy's service principals of the page of the role.
Nothing in the output is written by hand; regen_iam_passed_roles.py reads it to choose, among the services the
Service Reference passes an operation's roles to, the one each member's role is for.

Run from anywhere in the repo:
    python3 tools/aws/fetch_eks_roles.py           # rewrite the vendored file from the pages
    python3 tools/aws/fetch_eks_roles.py --verify  # online: the file still says what the pages do
"""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

API_REFERENCE = "https://docs.aws.amazon.com/eks/latest/APIReference/"
USER_GUIDE = "https://docs.aws.amazon.com/eks/latest/userguide/"
AUTO_MODE_IAM_REFERENCE = USER_GUIDE + "auto-learn-iam.html"
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "tools/aws/sources/eks-roles.json"

# The members that name a role, each by the API reference page and anchor describing it: a request member of an
# operation (`operation`) or a member of a type a request holds (`type`). One whose description names the guide's IAM
# reference for EKS Auto Mode is given the section of that page naming its role.
MEMBERS = (
    {"operation": "CreateCluster", "member": "roleArn"},
    {"type": "ComputeConfigRequest", "member": "nodeRoleArn", "section": "Node IAM role"},
)
# A description naming the guide's IAM reference rather than linking a page.
IAM_REFERENCE_RE = re.compile(r"\bsee the IAM Reference in the Amazon EKS User Guide\b")
# A trust policy in a code block of a role's page.
TRUST_RE = re.compile(r'"Principal"\s*:\s*\{\s*"Service"')


def text(fragment: str) -> str:
    """A fragment of a page as text, spaces collapsed."""
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).strip()


def code(fragment: str) -> str:
    """A code block as the text it shows, the page's copy button left out."""
    fragment = re.sub(r'<div class="code-btn-container">.*?</div></div>', "", fragment, flags=re.S)
    return html.unescape(re.sub(r"<[^>]+>", "", re.sub(r"<!--.*?-->", "", fragment, flags=re.S))).strip()


def api_page(member: dict) -> str:
    """The API reference page describing the member: its operation's or its type's."""
    return API_REFERENCE + "API_" + (member.get("operation") or member["type"]) + ".html"


def anchor(member: dict) -> str:
    """The id of the member's entry on its API reference page."""
    if "operation" in member:
        return f"AmazonEKS-{member['operation']}-request-{member['member']}"
    return f"AmazonEKS-Type-{member['type']}-{member['member']}"


def read_description(page: str, member: dict) -> tuple[str, list[str]]:
    """The member's description on its API reference page, as text, and the pages it links."""
    match = re.search(r'id="' + re.escape(anchor(member)) + r'"[^>]*>(?P<entry>.*?)</dd>', page, re.S)
    if match is None:
        raise ValueError(f"{api_page(member)} has no entry {anchor(member)}: its layout changed")
    entry = match.group("entry")
    described = re.search(r"<dd[^>]*>(?P<body>.*?)<p>\s*Type:", entry + "<p>Type:", re.S)
    body = described.group("body") if described else entry
    links = [urllib.parse.urljoin(api_page(member), href) for href in re.findall(r'href="([^"#][^"]*)"', body)]
    return text(body), links


def read_section(page: str, heading: str) -> tuple[str, list[str]]:
    """The text of the section of the page under `heading`, to the next one, and the pages it links."""
    match = re.search(r"<h2[^>]*>\s*" + re.escape(heading) + r"\s*</h2>(?P<body>.*?)(?=<h2|$)", page, re.S)
    if match is None:
        raise ValueError(f"{AUTO_MODE_IAM_REFERENCE} has no section '{heading}': its layout changed")
    body = match.group("body")
    links = [urllib.parse.urljoin(AUTO_MODE_IAM_REFERENCE, href) for href in re.findall(r'href="([^"#][^"]*)"', body)]
    return text(body), links


def read_role(page: str, url: str) -> dict:
    """The title of a role's page and the service principals each trust policy on it names."""
    title = re.search(r"<title>(?P<title>.*?)</title>", page, re.S)
    services: set[str] = set()
    for block in re.findall(r"<pre[^>]*>(.*?)</pre>", page, re.S):
        shown = code(block)
        if not TRUST_RE.search(shown):
            continue
        for statement in json.loads(shown)["Statement"]:
            service = statement["Principal"]["Service"]
            services.update([service] if isinstance(service, str) else service)
    if title is None or not services:
        raise ValueError(f"{url} gives no trust policy naming a service: its layout changed")
    return {"title": text(title.group("title")), "trustedBy": sorted(services)}


def role_page(member: dict, description: str, links: list[str], fetch) -> tuple[dict, str]:
    """The page of the role the member names, and what the guide says names it there: the page its description links
    that has a trust policy, or the one the IAM reference's section of its role links."""
    found: dict = {}
    if IAM_REFERENCE_RE.search(description):
        if "section" not in member:
            raise ValueError(f"{api_page(member)} names the IAM reference for {member['member']}, and no section of it "
                             f"is given here")
        section, links = read_section(fetch(AUTO_MODE_IAM_REFERENCE), member["section"])
        found = {"reference": AUTO_MODE_IAM_REFERENCE, "section": member["section"], "sectionText": section}
    pages = []
    for link in dict.fromkeys(links):
        if not link.startswith(USER_GUIDE):
            continue
        try:
            pages.append((link, read_role(fetch(link), link)))
        except ValueError:
            continue
    if len(pages) != 1:
        raise ValueError(f"{member['member']}'s description links {len(pages)} pages of a role, not one: "
                         f"{[link for link, _ in pages] or links}")
    link, role = pages[0]
    return {**found, "rolePage": link, **role}, link


def build(fetch) -> dict:
    members = []
    pages = []
    for member in MEMBERS:
        description, links = read_description(fetch(api_page(member)), member)
        role, link = role_page(member, description, links, fetch)
        pages += [api_page(member)] + ([role["reference"]] if "reference" in role else []) + [link]
        named = {key: member[key] for key in ("operation", "type") if key in member}
        members.append({**named, "member": member["member"], "description": description, **role})
    return {
        "_source": {
            "pages": list(dict.fromkeys(pages)),
            "fetcher": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "Each member's description on its API reference page, copied as it stands; where it names the "
                    "EKS Auto Mode IAM reference, that page's section of its role; and the title of the user guide's "
                    "page of the role and the service principals its trust policies name.",
        },
        "members": members,
    }


def fetch_url(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "floci-tools"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def fetch_all(fetch=fetch_url) -> tuple:
    """The pages, each fetched once, as a reader of them."""
    cache: dict[str, str] = {}

    def cached(url: str) -> str:
        if url not in cache:
            cache[url] = fetch(url)
        return cache[url]

    build(cached)
    return (cache,)


def stale(vendored: dict, pages: dict[str, str]) -> bool:
    """Whether the pages now give something other than the vendored file, its date aside."""
    return vendored.get("members") != build(lambda url: pages[url])["members"]


def main(argv: list[str] | None = None, fetch=fetch_url) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verify", action="store_true", help="online: the vendored file matches the pages")
    args = parser.parse_args(argv)
    try:
        (pages,) = fetch_all(fetch)
    except OSError as e:
        print(f"error: could not read the Amazon EKS documentation ({e})", file=sys.stderr)
        return 2
    if args.verify:
        vendored = json.loads(OUTPUT.read_text(encoding="utf-8"))
        if stale(vendored, pages):
            print(f"error: {OUTPUT.relative_to(REPO_ROOT)} no longer says what the EKS API reference and user guide "
                  f"do: run tools/aws/fetch_eks_roles.py and then make iam-passed-roles-sync", file=sys.stderr)
            return 1
        print(f"{OUTPUT.relative_to(REPO_ROOT)} matches the EKS API reference and user guide")
        return 0
    built = build(lambda url: pages[url])
    OUTPUT.write_text(json.dumps(built, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)} ({len(built['members'])} members' roles)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
