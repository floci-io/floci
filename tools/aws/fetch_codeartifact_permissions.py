#!/usr/bin/env python3
"""Vendor what the AWS CodeArtifact user guide says a request is checked on, where neither botocore nor the Service
Reference says it, into tools/aws/sources/codeartifact-permissions.json, a source of the IAM tables.

The guide's page on copying packages gives the permissions CopyPackageVersions is checked as, each on the repository
of the request it names:

    The user calling copy-package-versions must have the ReadFromRepository permission on the source repository and
    the CopyPackageVersions permission on the destination repository.

    https://docs.aws.amazon.com/codeartifact/latest/ug/copy-package.html

The Service Reference lists `package` and `repository` for CopyPackageVersions and `repository` for
ReadFromRepository, but not which repository of the request each is checked on, and botocore does not say it as data
either. Its page on repository policies gives the ARN of a package that has no namespace, which the Service
Reference's `package` format, `package/${DomainName}/${RepositoryName}/${PackageFormat}/${PackageNamespace}/
${PackageName}`, leaves to the request:

    The ARN for an npm package without a scope has the empty string for the namespace field. For example, the
    following is the ARN for a package without a scope and with name react in the my_repo repository in domain
    my_domain.
    arn:aws:codeartifact:region-id:111122223333:package/my_domain/my_repo/npm//react

    https://docs.aws.amazon.com/codeartifact/latest/ug/repo-policies.html

This script copies each sentence of the first kind as it stands, with the operation its command calls and each
permission it names and the resource it names it on, and each example ARN of the second page with the paragraph it
follows. Nothing in the output is written by hand, and the generator reads it against botocore's requests.

Run from anywhere in the repo:
    python3 tools/aws/fetch_codeartifact_permissions.py           # rewrite the vendored file from the pages
    python3 tools/aws/fetch_codeartifact_permissions.py --verify  # online: the file still says what the pages do
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

COPY_PAGE = "https://docs.aws.amazon.com/codeartifact/latest/ug/copy-package.html"
POLICIES_PAGE = "https://docs.aws.amazon.com/codeartifact/latest/ug/repo-policies.html"
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "tools/aws/sources/codeartifact-permissions.json"

# A sentence giving the permissions the command it names is checked as, each on a resource of its request.
CALLER_RE = re.compile(r"^The user calling (?P<command>[a-z]+(?:-[a-z]+)+) must have (?P<clauses>.+)\.$")
# One permission of such a sentence and the resource it is checked on.
CLAUSE_RE = re.compile(r"^the (?P<action>[A-Z][A-Za-z]+) permission on the (?P<resource>[a-z]+(?: [a-z]+)*)$")
# A paragraph saying the code block after it is the ARN of something.
ARN_LEAD_RE = re.compile(r"\bthe following is the ARN for\b")
# A paragraph and the code block right after it.
EXAMPLE_RE = re.compile(r"<p>(?P<lead>(?:(?!</p>).)*?)</p>\s*<pre[^>]*>(?P<code>.*?)</pre>", re.S)


def text(fragment: str) -> str:
    """A fragment of the page as text, spaces collapsed."""
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).strip()


def code(fragment: str) -> str:
    """A code block as the text it shows, the page's copy button left out and nothing put between its parts."""
    fragment = re.sub(r'<div class="code-btn-container">.*?</div></div>', "", fragment, flags=re.S)
    return html.unescape(re.sub(r"<[^>]+>", "", re.sub(r"<!--.*?-->", "", fragment, flags=re.S))).strip()


def operation_of(command: str) -> str:
    """The API operation a CLI command calls: `copy-package-versions` calls CopyPackageVersions."""
    return "".join(word.capitalize() for word in command.split("-"))


def read_caller_permissions(page: str) -> list[dict]:
    """Each sentence of the page giving the permissions a command is checked as, with the operation it calls and each
    permission it names, as an action of the namespace, and the resource of the request it names it on."""
    found = []
    for paragraph in re.findall(r"<p>(.*?)</p>", page, re.S):
        sentence = text(paragraph)
        match = CALLER_RE.match(sentence)
        if match is None:
            continue
        clauses = [CLAUSE_RE.match(clause) for clause in match.group("clauses").split(" and ")]
        if not all(clauses):
            raise ValueError(f"{COPY_PAGE} says '{sentence}', which does not name each permission and its "
                             f"resource as it did: its layout changed")
        found.append({"operation": operation_of(match.group("command")), "sentence": sentence,
                      "permissions": [{"action": "codeartifact:" + clause.group("action"),
                                       "resource": clause.group("resource")} for clause in clauses]})
    if not found:
        raise ValueError(f"{COPY_PAGE} says no permissions a command is checked as: its layout changed")
    return found


def read_arn_examples(page: str) -> list[dict]:
    """Each ARN the page gives in a code block after a paragraph saying what it is the ARN for, with that paragraph."""
    found = [{"paragraph": text(match.group("lead")), "arn": code(match.group("code"))}
             for match in EXAMPLE_RE.finditer(page) if ARN_LEAD_RE.search(text(match.group("lead")))]
    found = [example for example in found if example["arn"].startswith("arn:")]
    if not found:
        raise ValueError(f"{POLICIES_PAGE} gives no example ARN after a paragraph saying what it is for: its "
                         f"layout changed")
    return found


def build(copy_page: str, policies_page: str) -> dict:
    return {
        "_source": {
            "pages": [COPY_PAGE, POLICIES_PAGE],
            "fetcher": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "Each sentence of the copy page giving the permissions a command is checked as, copied as it "
                    "stands, with the operation its command calls and each permission it names and the resource it "
                    "names it on; and each example ARN of the repository policies page with the paragraph it "
                    "follows.",
        },
        "callerPermissions": read_caller_permissions(copy_page),
        "arnExamples": read_arn_examples(policies_page),
    }


def fetch_url(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "floci-tools"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


def fetch_all(fetch=fetch_url) -> tuple[str, str]:
    """The copy page and the repository policies page."""
    return fetch(COPY_PAGE), fetch(POLICIES_PAGE)


def stale(vendored: dict, copy_page: str, policies_page: str) -> bool:
    """Whether the pages now give something other than the vendored file, its date aside."""
    built = build(copy_page, policies_page)
    return any(vendored.get(key) != built[key] for key in built if key != "_source")


def main(argv: list[str] | None = None, fetch=fetch_url) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verify", action="store_true", help="online: the vendored file matches the pages")
    args = parser.parse_args(argv)
    try:
        pages = fetch_all(fetch)
    except OSError as e:
        print(f"error: could not read the AWS CodeArtifact documentation ({e})", file=sys.stderr)
        return 2
    if args.verify:
        vendored = json.loads(OUTPUT.read_text(encoding="utf-8"))
        if stale(vendored, *pages):
            print(f"error: {OUTPUT.relative_to(REPO_ROOT)} no longer says what {COPY_PAGE} and {POLICIES_PAGE} do: "
                  f"run tools/aws/fetch_codeartifact_permissions.py and then make iam-request-resources-sync",
                  file=sys.stderr)
            return 1
        print(f"{OUTPUT.relative_to(REPO_ROOT)} matches {COPY_PAGE} and {POLICIES_PAGE}")
        return 0
    built = build(*pages)
    OUTPUT.write_text(json.dumps(built, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(REPO_ROOT)} ({len(built['callerPermissions'])} operations' permissions, "
          f"{len(built['arnExamples'])} example ARNs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
