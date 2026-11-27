#!/usr/bin/env python3
"""Vendor which role each Amazon EMR RunJobFlow member names, and the service it is passed to, into
tools/aws/sources/emr-roles.json, a source of the passed-roles table.

AWS's Service Reference passes RunJobFlow's roles to `application-autoscaling.amazonaws.com`, `ec2.amazonaws.com` and
`elasticmapreduce.amazonaws.com` without saying which member's role goes where, and botocore does not say it as data
either. The EMR management guide has a page for each role a cluster takes, naming its default role:

    The default role name is EMR_DefaultRole_V2.                 (Service role for Amazon EMR (EMR role))
    The default role name is EMR_EC2_DefaultRole.                (Service role for cluster EC2 instances)
    The default role name is EMR_AutoScaling_DefaultRole.        (Service role for automatic scaling)

and the full access policy it gives users passes each default role to its service alone:

    "Sid": "PassRoleForEC2", "Action": "iam:PassRole", "Resource": ["arn:aws:iam::*:role/EMR_EC2_DefaultRole"],
    "Condition": {"StringLike": {"iam:PassedToService": "ec2.amazonaws.com*"}}

    https://docs.aws.amazon.com/emr/latest/ManagementGuide/emr-managed-policy-fullaccess-v2.html

The EMR API reference describes each RunJobFlow member as the role it names, `JobFlowRole` as an instance profile
("Also called instance profile and Amazon EC2 role"), and `StepExecutionRoleArn` as the runtime role of the steps the
request runs, whose access the guide's page on runtime roles controls by a condition key, not by passing it:

    You can control access to the runtime role with the condition key elasticmapreduce:ExecutionRoleArn.

    https://docs.aws.amazon.com/emr/latest/APIReference/API_RunJobFlow.html
    https://docs.aws.amazon.com/emr/latest/ManagementGuide/emr-steps-runtime-roles.html

The AWS CLI reference's create-cluster examples, whose options the CLI sends as those members, give each role by its
name (`--service-role EMR_DefaultRole`, `--ec2-attributes InstanceProfile=EMR_EC2_DefaultRole`,
`--auto-scaling-role EMR_AutoScaling_DefaultRole`), so a member is also given a role by its name:

    https://docs.aws.amazon.com/cli/latest/reference/emr/create-cluster.html

This script copies each member's description as it stands, the title and default role of the page of its role, the
services the full access policy passes that default role to, and the value the CLI's examples give its option; for
the runtime role, the sentence naming the condition key that controls access to it. Nothing in the output is written by hand; regen_iam_passed_roles.py reads
it to choose, among the services the Service Reference passes RunJobFlow's roles to, the one each member's role goes
to.

Run from anywhere in the repo:
    python3 tools/aws/fetch_emr_roles.py           # rewrite the vendored file from the pages
    python3 tools/aws/fetch_emr_roles.py --verify  # online: the file still says what the pages do
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

API_PAGE = "https://docs.aws.amazon.com/emr/latest/APIReference/API_RunJobFlow.html"
GUIDE = "https://docs.aws.amazon.com/emr/latest/ManagementGuide/"
POLICY_PAGE = GUIDE + "emr-managed-policy-fullaccess-v2.html"
RUNTIME_ROLES_PAGE = GUIDE + "emr-steps-runtime-roles.html"
CLI_PAGE = "https://docs.aws.amazon.com/cli/latest/reference/emr/create-cluster.html"
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "tools/aws/sources/emr-roles.json"

# The RunJobFlow members that name a role, each with the guide's page of that role and the CLI option sending it, or,
# for the steps' runtime role, the guide's page on runtime roles.
MEMBERS = (
    {"member": "ServiceRole", "rolePage": GUIDE + "emr-iam-role.html", "cliOption": "--service-role"},
    {"member": "JobFlowRole", "rolePage": GUIDE + "emr-iam-role-for-ec2.html", "cliOption": "InstanceProfile="},
    {"member": "AutoScalingRole", "rolePage": GUIDE + "emr-iam-role-automatic-scaling.html",
     "cliOption": "--auto-scaling-role"},
    {"member": "StepExecutionRoleArn", "runtimeRolesPage": RUNTIME_ROLES_PAGE},
)
# A role page's sentence naming its default role.
DEFAULT_ROLE_RE = re.compile(r"\bThe default role name is (?P<role>EMR_[A-Za-z0-9_]+) \.")
# A member description naming the default role, which must be its page's.
DESCRIBED_DEFAULT_RE = re.compile(r"\bThe default role is (?P<role>EMR_[A-Za-z0-9_]+) \.")
# A member description saying the member names an instance profile.
INSTANCE_PROFILE_RE = re.compile(r"\bAlso called instance profile\b")
# The runtime roles page's sentence naming the condition key that controls access to the runtime role.
ACCESS_RE = re.compile(r"You can control access to the runtime role with the condition key "
                       r"(?P<key>[a-z0-9-]+:[A-Za-z]+) \.")
# A role ARN of the full access policy, its name the default role it passes.
POLICY_ROLE_RE = re.compile(r"^arn:aws:iam::\*:role/(?P<role>EMR_[A-Za-z0-9_]+)$")


def text(fragment: str) -> str:
    """A fragment of a page as text, spaces collapsed."""
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).strip()


def code(fragment: str) -> str:
    """A code block as the text it shows, the page's copy button left out."""
    fragment = re.sub(r'<div class="code-btn-container">.*?</div></div>', "", fragment, flags=re.S)
    return html.unescape(re.sub(r"<[^>]+>", "", re.sub(r"<!--.*?-->", "", fragment, flags=re.S))).strip()


def read_description(page: str, member: str) -> str:
    """The member's description on the RunJobFlow page, as text."""
    match = re.search(r'id="EMR-RunJobFlow-request-' + re.escape(member) + r'"[^>]*>(?P<entry>.*?)</dd>', page, re.S)
    if match is None:
        raise ValueError(f"{API_PAGE} has no entry for {member}: its layout changed")
    entry = match.group("entry")
    described = re.search(r"<dd[^>]*>(?P<body>.*?)<p>\s*Type:", entry + "<p>Type:", re.S)
    return text(described.group("body") if described else entry)


def read_role_page(page: str, url: str) -> dict:
    """The title of a role's page and the default role it names."""
    title = re.search(r"<title>(?P<title>.*?)</title>", page, re.S)
    named = DEFAULT_ROLE_RE.search(text(page))
    if title is None or named is None:
        raise ValueError(f"{url} names no default role: its layout changed")
    return {"title": text(title.group("title")), "defaultRole": named.group("role")}


def read_policy(page: str) -> dict[str, list[str]]:
    """The services the full access policy passes each default role it names to."""
    passed: dict[str, set[str]] = {}
    for block in re.findall(r"<pre[^>]*>(.*?)</pre>", page, re.S):
        shown = code(block)
        if "iam:PassRole" not in shown:
            continue
        for statement in json.loads(shown)["Statement"]:
            actions = statement.get("Action")
            if "iam:PassRole" not in (actions if isinstance(actions, list) else [actions]):
                continue
            services = statement.get("Condition", {}).get("StringLike", {}).get("iam:PassedToService")
            resources = statement.get("Resource")
            for resource in resources if isinstance(resources, list) else [resources]:
                role = POLICY_ROLE_RE.match(resource or "")
                if role is None or services is None:
                    continue
                for service in services if isinstance(services, list) else [services]:
                    passed.setdefault(role.group("role"), set()).add(service.removesuffix("*"))
    if not passed:
        raise ValueError(f"{POLICY_PAGE} passes no default role to a service: its layout changed")
    return {role: sorted(services) for role, services in passed.items()}


def read_cli_example(page: str, option: str) -> str:
    """The value the first of the CLI reference's examples that gives `option` gives it."""
    start = page.find('id="examples"')
    if start < 0:
        raise ValueError(f"{CLI_PAGE} has no examples: its layout changed")
    for block in re.findall(r"<pre[^>]*>(.*?)</pre>", page[start:], re.S):
        match = re.search(re.escape(option) + r"\s*(?P<value>[A-Za-z0-9_+=.@/:-]+)", code(block))
        if match is not None:
            return match.group("value").rstrip(".")
    raise ValueError(f"{CLI_PAGE} gives no example of {option}: its layout changed")


def read_access(page: str) -> dict:
    """The runtime roles page's sentence naming the condition key that controls access to the runtime role."""
    match = ACCESS_RE.search(text(page))
    if match is None:
        raise ValueError(f"{RUNTIME_ROLES_PAGE} names no condition key controlling access to the runtime role: "
                         f"its layout changed")
    return {"accessControlledBy": match.group("key"), "accessSentence": match.group(0)}


def build(fetch) -> dict:
    api = fetch(API_PAGE)
    policy = read_policy(fetch(POLICY_PAGE))
    cli = fetch(CLI_PAGE)
    members = []
    for member in MEMBERS:
        description = read_description(api, member["member"])
        entry: dict = {"operation": "RunJobFlow", "member": member["member"], "description": description}
        if "runtimeRolesPage" in member:
            entry.update({"runtimeRolesPage": member["runtimeRolesPage"],
                          **read_access(fetch(member["runtimeRolesPage"]))})
            members.append(entry)
            continue
        role = read_role_page(fetch(member["rolePage"]), member["rolePage"])
        described = DESCRIBED_DEFAULT_RE.search(description)
        if described is not None and described.group("role") != role["defaultRole"]:
            raise ValueError(f"{API_PAGE} names {described.group('role')} as {member['member']}'s default role, and "
                             f"{member['rolePage']} {role['defaultRole']}")
        if role["defaultRole"] not in policy:
            raise ValueError(f"{POLICY_PAGE} passes {role['defaultRole']} to no service: its layout changed")
        example = read_cli_example(cli, member["cliOption"])
        entry.update({"rolePage": member["rolePage"], **role, "policyPage": POLICY_PAGE,
                      "passedToService": policy[role["defaultRole"]], "cliOption": member["cliOption"],
                      "cliExample": example})
        if INSTANCE_PROFILE_RE.search(description):
            entry["instanceProfile"] = True
        elif not example.startswith("arn:"):
            entry["byName"] = True
        members.append(entry)
    return {
        "_source": {
            "pages": [API_PAGE, POLICY_PAGE, CLI_PAGE] + [m.get("rolePage") or m["runtimeRolesPage"] for m in MEMBERS],
            "fetcher": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "Each RunJobFlow member's description on the API reference, copied as it stands; the title and "
                    "default role of the guide's page of its role and the services the full access policy passes "
                    "that default role to, and the value the CLI reference's examples give the option sending it; "
                    "and for the steps' runtime role, the runtime roles page's sentence naming the condition key "
                    "that controls access to it.",
        },
        "members": members,
    }


def fetch_url(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "floci-tools"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def fetch_all(fetch=fetch_url) -> tuple:
    """The pages, each fetched once."""
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
        print(f"error: could not read the Amazon EMR documentation ({e})", file=sys.stderr)
        return 2
    if args.verify:
        vendored = json.loads(OUTPUT.read_text(encoding="utf-8"))
        if stale(vendored, pages):
            print(f"error: {OUTPUT.relative_to(REPO_ROOT)} no longer says what the EMR API reference and "
                  f"management guide do: run tools/aws/fetch_emr_roles.py and then make iam-passed-roles-sync",
                  file=sys.stderr)
            return 1
        print(f"{OUTPUT.relative_to(REPO_ROOT)} matches the EMR API reference and management guide")
        return 0
    built = build(lambda url: pages[url])
    OUTPUT.write_text(json.dumps(built, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)} ({len(built['members'])} members' roles)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
