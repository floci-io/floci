#!/usr/bin/env python3
"""Vendor which role an AWS Batch request member names, and the service that role is for, into
tools/aws/sources/batch-roles.json, a source of the passed-roles table.

AWS's Service Reference passes CreateComputeEnvironment's roles to `batch.amazonaws.com`, `ec2.amazonaws.com`,
`ecs.amazonaws.com` and `spotfleet.amazonaws.com`, and UpdateComputeEnvironment's to the first three, without saying
which member's role goes where, and botocore does not say it as data either. The Batch API reference describes each
member as the role it names, linking the user guide's page of that role, whose trust policy gives the service it is
for. A compute resource's `instanceRole` is an instance profile, the guide's page of its role giving the trust policy
on the page it lists:

    The Amazon ECS instance profile applied to Amazon EC2 instances in a compute environment. ... You can specify the
    short name or full Amazon Resource Name (ARN) of an instance profile. ... For more information, see Amazon ECS
    instance role in the AWS Batch User Guide.

    https://docs.aws.amazon.com/batch/latest/APIReference/API_ComputeResource.html
    https://docs.aws.amazon.com/batch/latest/userguide/instance_IAM_role.html
    https://docs.aws.amazon.com/batch/latest/userguide/batch-check-ecsinstancerole.html   ("Service": "ec2.amazonaws.com")

and a managed instances provider's `infrastructureRoleArn` names the service in its own description:

    ... You must have the iam:PassRole permission for this role with the condition iam:PassedToService:
    ecs.amazonaws.com.

This script copies each member's description as it stands, the service it names where it names one, and otherwise
the title of the page of its role and the service principals that page's trust policies name, or those of the pages
it lists, as a trust policy written out or the service-linked role's "trusts the following services". Nothing in the
output is written by hand; regen_iam_passed_roles.py reads it to choose, among the services the Service Reference
passes an operation's roles to, the one each member's role is for.

Run from anywhere in the repo:
    python3 tools/aws/fetch_batch_roles.py           # rewrite the vendored file from the pages
    python3 tools/aws/fetch_batch_roles.py --verify  # online: the file still says what the pages do
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

API_REFERENCE = "https://docs.aws.amazon.com/batch/latest/APIReference/"
USER_GUIDE = "https://docs.aws.amazon.com/batch/latest/userguide/"
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "tools/aws/sources/batch-roles.json"

# The members that name a role, each by the API reference page and anchor describing it: a request member of an
# operation (`operation`) or a member of a type a request holds (`type`). One that updates another's role
# (`updates`) names it in the same words, but for "updated", and its role is that member's.
MEMBERS = (
    {"operation": "CreateComputeEnvironment", "member": "serviceRole"},
    {"operation": "UpdateComputeEnvironment", "member": "serviceRole"},
    {"type": "ComputeResource", "member": "instanceRole"},
    {"type": "ComputeResource", "member": "spotIamFleetRole"},
    {"type": "ComputeResourceUpdate", "member": "instanceRole"},
    {"type": "ManagedInstancesProvider", "member": "infrastructureRoleArn"},
    {"type": "UpdateManagedInstancesProviderConfiguration", "member": "infrastructureRoleArn",
     "updates": {"type": "ManagedInstancesProvider", "member": "infrastructureRoleArn"}},
)
# A description naming the service the role is passed to.
PASSED_TO_RE = re.compile(r"\biam:PassedToService\s*:\s*(?P<service>[a-z0-9.-]+\.amazonaws\.com)")
# A description of a member naming an instance profile rather than a role.
INSTANCE_PROFILE_RE = re.compile(r"\bof an instance profile\b")
# A trust policy in a code block, written out or as a CLI argument.
TRUST_RE = re.compile(r'"Principal"\s*:\s*\{\s*"Service"')
# The description's sentence linking the guide's page of the role.
SEE_RE = re.compile(r'For more information, see\s*<a[^>]*href="(?P<href>[^"#][^"]*)"')
# A service-linked role page's sentence naming the services the role trusts.
TRUSTS_RE = re.compile(r"\btrusts the following services to assume the role:\s*(?P<services>[a-z0-9.-]+\.amazonaws\.com"
                       r"(?:\s*(?:,|and)\s*[a-z0-9.-]+\.amazonaws\.com)*)")
SERVICE_RE = re.compile(r"[a-z0-9.-]+\.amazonaws\.com")


def text(fragment: str) -> str:
    """A fragment of a page as text, spaces collapsed."""
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).strip()


def code(fragment: str) -> str:
    """A code block as the text it shows, the page's copy button left out."""
    fragment = re.sub(r'<div class="code-btn-container">.*?</div></div>', "", fragment, flags=re.S)
    return html.unescape(re.sub(r"<[^>]+>", "", re.sub(r"<!--.*?-->", "", fragment, flags=re.S))).strip()


def body(page: str) -> str:
    """The page's own content, its navigation left out."""
    match = re.search(r'<div id="main-col-body">(?P<body>.*?)(?:<div class="prev-next"|$)', page, re.S)
    return match.group("body") if match else page


def api_page(member: dict) -> str:
    """The API reference page describing the member: its operation's or its type's."""
    return API_REFERENCE + "API_" + (member.get("operation") or member["type"]) + ".html"


def anchor(member: dict) -> str:
    """The id of the member's entry on its API reference page."""
    if "operation" in member:
        return f"Batch-{member['operation']}-request-{member['member']}"
    return f"Batch-Type-{member['type']}-{member['member']}"


def read_description(page: str, member: dict) -> tuple[str, list[str]]:
    """The member's description on its API reference page, as text, and the page its first "For more information,
    see" links, the guide's page of the role, a later one linking what the member's rules depend on (none when it
    links none)."""
    match = re.search(r'id="' + re.escape(anchor(member)) + r'"[^>]*>(?P<entry>.*?)</dd>', page, re.S)
    if match is None:
        raise ValueError(f"{api_page(member)} has no entry {anchor(member)}: its layout changed")
    entry = match.group("entry")
    described = re.search(r"<dd[^>]*>(?P<body>.*?)<p>\s*Type:", entry + "<p>Type:", re.S)
    found = described.group("body") if described else entry
    see = SEE_RE.search(found)
    links = [urllib.parse.urljoin(api_page(member), see.group("href"))] if see else []
    return text(found), links


def trusted_by(page: str) -> set[str]:
    """The service principals the page's trust policies name, or its service-linked role says it trusts."""
    services: set[str] = set()
    for block in re.findall(r"<pre[^>]*>(.*?)</pre>", page, re.S):
        shown = code(block)
        if not TRUST_RE.search(shown):
            continue
        policy = shown[shown.index("{"):shown.rindex("}") + 1]
        for statement in json.loads(policy)["Statement"]:
            service = statement["Principal"]["Service"]
            services.update([service] if isinstance(service, str) else service)
    for match in TRUSTS_RE.finditer(text(body(page))):
        services.update(SERVICE_RE.findall(match.group("services")))
    return services


def read_role(page: str, url: str, fetch) -> dict:
    """The title of a role's page and the service principals it trusts: those its own trust policies name, or else
    those of the user guide's pages it lists."""
    title = re.search(r"<title>(?P<title>.*?)</title>", page, re.S)
    services = trusted_by(page)
    listed = []
    if not services:
        for href in dict.fromkeys(re.findall(r'href="([^"#][^"]*)"', body(page))):
            link = urllib.parse.urljoin(url, href)
            if link.startswith(USER_GUIDE) and link != url:
                found = trusted_by(fetch(link))
                if found:
                    listed.append(link)
                    services |= found
    if title is None or not services:
        raise ValueError(f"{url} gives no trust policy naming a service: its layout changed")
    return {"title": text(title.group("title")), "trustedBy": sorted(services),
            **({"trustPages": listed} if listed else {})}


def role_page(member: dict, links: list[str], fetch) -> dict:
    """The page of the role the member names: the user guide page its description's "For more information, see"
    links, which trusts a service."""
    pages = []
    for link in dict.fromkeys(links):
        if not link.startswith(USER_GUIDE):
            continue
        try:
            pages.append((link, read_role(fetch(link), link, fetch)))
        except ValueError:
            continue
    if len(pages) != 1:
        raise ValueError(f"{member['member']}'s description links {len(pages)} pages of a role, not one: "
                         f"{[link for link, _ in pages] or links}")
    link, role = pages[0]
    return {"rolePage": link, **role}


def first_sentence(description: str) -> str:
    return description.split(". ", 1)[0].rstrip(". ")


def updated_role(member: dict, description: str, members: list[dict]) -> dict:
    """What the member it updates says of the role, when the update names that role in the same words."""
    target = member["updates"]
    for entry in members:
        if entry["member"] == target["member"] and entry.get("type") == target.get("type"):
            if first_sentence(description).replace("The updated ", "The ", 1) != first_sentence(entry["description"]):
                raise ValueError(f"{api_page(member)} no longer names {member['member']}'s role as "
                                 f"{api_page(target)} does")
            return {"sameRoleAs": {"type": target["type"], "member": target["member"]},
                    **{key: entry[key] for key in ("passedToService", "rolePage", "title", "trustedBy", "trustPages",
                                                   "instanceProfile") if key in entry}}
    raise ValueError(f"{member['member']} updates a member read after it")


def build(fetch) -> dict:
    members = []
    pages = []
    for member in MEMBERS:
        description, links = read_description(fetch(api_page(member)), member)
        named = {key: member[key] for key in ("operation", "type") if key in member}
        entry = {**named, "member": member["member"], "description": description}
        passed_to = PASSED_TO_RE.search(description)
        if "updates" in member:
            entry.update(updated_role(member, description, members))
            pages.append(api_page(member))
        elif passed_to:
            entry["passedToService"] = [passed_to.group("service")]
            pages.append(api_page(member))
        else:
            role = role_page(member, links, fetch)
            entry.update(role)
            pages += [api_page(member), role["rolePage"]] + role.get("trustPages", [])
        if INSTANCE_PROFILE_RE.search(description) and "updates" not in member:
            entry["instanceProfile"] = True
        members.append(entry)
    return {
        "_source": {
            "pages": list(dict.fromkeys(pages)),
            "fetcher": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "Each member's description on its API reference page, copied as it stands; the service it names "
                    "where it names one, the role of the member it updates where it names that role in the same words "
                    "(sameRoleAs), and otherwise the title of the user guide's page of its role and the service "
                    "principals that page's trust policies name, or those of the pages it lists (trustPages).",
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
        print(f"error: could not read the AWS Batch documentation ({e})", file=sys.stderr)
        return 2
    if args.verify:
        vendored = json.loads(OUTPUT.read_text(encoding="utf-8"))
        if stale(vendored, pages):
            print(f"error: {OUTPUT.relative_to(REPO_ROOT)} no longer says what the Batch API reference and user "
                  f"guide do: run tools/aws/fetch_batch_roles.py and then make iam-passed-roles-sync", file=sys.stderr)
            return 1
        print(f"{OUTPUT.relative_to(REPO_ROOT)} matches the Batch API reference and user guide")
        return 0
    built = build(lambda url: pages[url])
    OUTPUT.write_text(json.dumps(built, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)} ({len(built['members'])} members' roles)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
