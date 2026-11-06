#!/usr/bin/env python3
"""Generate and check the service-linked role table in src/main/resources/iam.

CreateServiceLinkedRole has to know two things per service principal that cannot be
derived from the principal: the role name AWS mints, and whether the service takes a
CustomSuffix. Capitalising the principal gets the name wrong for most of the services
covered here, and the API Reference says only that "some services do not support the
CustomSuffix parameter" without listing them. The counts live in the generated table's
own header, where this script keeps them right; quoting them here only creates a second
number to go stale.

Two sources, in order of authority:

  recording         An AWS-validated LocalStack snapshot. These are recorded create
                    responses, so the role name is what AWS actually returned, and the
                    test parameterisation says whether the suffix was accepted. Highest
                    authority, and the only source for the suffix column.
  policy-documents  src/main/resources/iam/managed-policy-documents.json, which is
                    vendored AWS data. Role ARNs there carry the principal and the role
                    name together. Names only: a policy document says nothing about
                    suffix support.
  docs              AWS's own per-service service-linked role page, which names the
                    role and the principal in prose and usually shows the full ARN.
                    Weaker than a recording only because nothing re-checks it offline,
                    so every such row carries the URL it came from.

--check runs offline and is the CI gate: the policy-documents rows must still match the
vendored JSON, and the table must be internally well formed. Recording-sourced rows
cannot be re-derived without the snapshot, which is why every row carries its source.

--refresh takes a snapshot path and re-derives the recording rows, so pulling in new
AWS evidence is a deliberate act rather than a side effect of running the tool.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TABLE = REPO_ROOT / "src/main/resources/iam/service-linked-roles.tsv"
POLICY_DOCUMENTS = REPO_ROOT / "src/main/resources/iam/managed-policy-documents.json"

ROLE_PREFIX = "AWSServiceRoleFor"

# A role ARN inside a policy document. Two shapes have to be rejected rather than read.
#
# The principal path is required: a wildcard like
# role/*AWSServiceRoleForAmazonGuardDutyMalwareProtection names a role without saying which
# principal mints it, and guessing from those once gave GuardDuty the MalwareProtection
# role's name.
#
# And a name followed by `*` is a prefix pattern, not a name. email.cognito-idp proves it:
# the policy writes AWSServiceRoleForAmazonCognitoIdpEmail* where the recorded create
# returns AWSServiceRoleForAmazonCognitoIdpEmailService. Reading the prefix as a name
# invents a role AWS never mints, so the terminator is captured and checked.
ROLE_ARN_RE = re.compile(
    r"aws-service-role/(?P<principal>[A-Za-z0-9.\-]+)/(?P<role>AWSServiceRoleFor[A-Za-z0-9_\-]*)"
    r"(?P<terminator>.?)"
)

SUFFIX_ALLOWED = "allowed"
SUFFIX_REFUSED = "refused"
SUFFIX_UNKNOWN = "unknown"

# Where both sources name a service and disagree, the recording wins: it is an actual
# create response, where a policy document may reference a related or superseded role.
# Listed rather than silently resolved, so a new disagreement is a failure and not a
# preference.
# Empty, and worth staying that way. The one entry this held was never a disagreement between
# sources: the policy wrote AWSServiceRoleForAmazonCognitoIdpEmail* and the prefix was being read
# as a name, where the recorded create returns AWSServiceRoleForAmazonCognitoIdpEmailService.
# Skipping wildcard patterns removed the cause. A genuine disagreement still belongs here.
KNOWN_SOURCE_CONFLICTS: dict[str, tuple[str, str]] = {}

# Services neither machine-readable source covers, taken from AWS's own documentation.
# The URL is part of the entry on purpose: a name with nowhere to check it is how
# guardduty sat in the tree for a year citing nothing but itself.
DOCUMENTED = {
    # "The AWSServiceRoleForAmazonGuardDuty service-linked role trusts the
    # guardduty.amazonaws.com service to assume the role."
    "guardduty.amazonaws.com": (
        "AWSServiceRoleForAmazonGuardDuty",
        "Amazon GuardDuty User Guide, Using service-linked roles: "
        "https://docs.aws.amazon.com/guardduty/latest/ug/slr-permissions.html",
    ),
    # "AWS Cloud9 uses the service-linked role named AWSServiceRoleForAWSCloud9. This
    # service-linked role trusts the service cloud9.amazonaws.com to assume the role."
    # This one was in IamService's override map before the table existed, and dropping it
    # regressed the name to the derived AWSServiceRoleForCloud9. The tests caught it; the
    # entry carries the source the override map never did.
    "cloud9.amazonaws.com": (
        "AWSServiceRoleForAWSCloud9",
        "AWS Cloud9 User Guide, Using service-linked roles: "
        "https://docs.aws.amazon.com/cloud9/latest/user-guide/using-service-linked-roles.html",
    ),
    # Same guide, next section. The vendored policies hold this role only as a wildcard
    # with no principal path, which is exactly the shape the extractor refuses to guess
    # from, so the guide is what supplies the principal:
    # "AWSServiceRoleForAmazonGuardDutyMalwareProtection service-linked role trusts the
    # malware-protection.guardduty.amazonaws.com service to assume the role."
    "malware-protection.guardduty.amazonaws.com": (
        "AWSServiceRoleForAmazonGuardDutyMalwareProtection",
        "Amazon GuardDuty User Guide, Service-linked role permissions for Malware "
        "Protection for EC2: https://docs.aws.amazon.com/guardduty/latest/ug/"
        "slr-permissions-malware-protection.html",
    ),
}


def names_from_policy_documents(path: Path) -> dict[str, str]:
    """Principal to role name, from the vendored AWS managed policy documents."""
    raw = path.read_text()
    names: dict[str, str] = {}
    for match in ROLE_ARN_RE.finditer(raw):
        if match.group("terminator") == "*":
            # A prefix pattern. The real name is this and something, and nothing here says what.
            continue
        principal = match.group("principal")
        # A policy matching suffixed roles writes the prefix as `...AmazonConnect_*`, so
        # the captured name can carry a trailing separator. An underscore inside a name
        # is real, though: ApplicationAutoScaling_CassandraTable is the whole name, and
        # splitting on the separator would truncate it.
        role = match.group("role").rstrip("_")
        names.setdefault(principal, role)
    return names


def rows_from_snapshot(path: Path) -> dict[str, tuple[str | None, str]]:
    """Principal to (role name, suffix support), from one AWS-validated snapshot."""
    snapshot = json.loads(path.read_text())
    names: dict[str, str] = {}
    suffix: dict[str, str] = {}
    for key, value in snapshot.items():
        principal = re.search(r"\[([a-z0-9.\-]+\.amazonaws\.com)\]", key)
        if not principal:
            continue
        service = principal.group(1)
        if "custom_suffix_not_allowed[" in key:
            suffix[service] = SUFFIX_REFUSED
        elif "lifecycle_custom_suffix[" in key:
            suffix[service] = SUFFIX_ALLOWED
        for body in (value.get("recorded-content") or {}).values():
            if not isinstance(body, dict) or not isinstance(body.get("Role"), dict):
                continue
            role = body["Role"].get("RoleName")
            if role:
                # Only the custom-suffix test appends the placeholder.
                names.setdefault(service, re.sub(r"_<suffix>$", "", role))
    return {
        service: (names.get(service), suffix.get(service, SUFFIX_UNKNOWN))
        for service in set(names) | set(suffix)
    }


def derived_name(principal: str) -> str:
    """What CreateServiceLinkedRole falls back to when the table does not cover a service."""
    core = principal
    for suffix in (".amazonaws.com",):
        if core.endswith(suffix):
            core = core[: -len(suffix)]
    return ROLE_PREFIX + "".join(
        part[0].upper() + part[1:] for part in re.split(r"[.\-]", core) if part
    )


class Row:
    """One service: its principal, the role name AWS mints, suffix support, and provenance."""

    __slots__ = ("principal", "role", "suffix", "source")

    def __init__(self, principal: str, role: str, suffix: str, source: str) -> None:
        self.principal = principal
        self.role = role
        self.suffix = suffix
        self.source = source

    def as_tsv(self) -> str:
        return "\t".join((self.principal, self.role, self.suffix, self.source))


def build(policy_documents: Path, snapshot: Path | None,
          conflicts: dict[str, tuple[str, str]] | None = None,
          documented: dict[str, tuple[str, str]] | None = None) -> tuple[list[Row], list[str]]:
    """The table, anything that needs a human, and anything worth saying out loud.

    Both curated overlays are parameters rather than read off the module, so that a
    caller working from a small fixture does not see every real conflict reported as
    stale, or a seeded name appear in a table built from two synthetic sources.
    """
    if conflicts is None:
        conflicts = KNOWN_SOURCE_CONFLICTS
    if documented is None:
        documented = DOCUMENTED
    problems: list[str] = []
    notes: list[str] = []
    vendored = names_from_policy_documents(policy_documents)
    recorded = rows_from_snapshot(snapshot) if snapshot else {}

    rows: list[Row] = []
    for principal in sorted(set(vendored) | set(recorded) | set(documented)):
        recorded_name, suffix = recorded.get(principal, (None, SUFFIX_UNKNOWN))
        vendored_name = vendored.get(principal)

        if recorded_name and vendored_name and recorded_name != vendored_name:
            expected = conflicts.get(principal)
            if expected != (vendored_name, recorded_name):
                problems.append(
                    f"{principal}: the sources disagree and the conflict is not recorded. "
                    f"policy documents say {vendored_name}, the recording says {recorded_name}. "
                    f"The recording is an actual create response, so it should win, but add the "
                    f"pair to KNOWN_SOURCE_CONFLICTS so the next disagreement still fails."
                )

        if recorded_name:
            rows.append(Row(principal, recorded_name, suffix, "recording"))
        elif vendored_name:
            rows.append(Row(principal, vendored_name, suffix, "policy-documents"))
        else:
            rows.append(Row(principal, documented[principal][0], suffix, "docs"))

    # A role name is unique within an account, and createServiceLinkedRole refuses a name
    # already taken, so two principals cannot share one. The policy documents hand out
    # collisions anyway: they reference the parent's role under a child principal path, as
    # with maintenance.elasticbeanstalk pointing at AWSServiceRoleForElasticBeanstalk. Taken
    # at face value that makes the sibling's create fail as a duplicate, so the weaker row is
    # dropped back to the derived fallback rather than asserting a name that would collide.
    AUTHORITY = {"recording": 0, "docs": 1, "policy-documents": 2}
    by_name: dict[str, list[Row]] = {}
    for row in rows:
        by_name.setdefault(row.role, []).append(row)
    dropped: set[str] = set()
    for role, sharing in by_name.items():
        if len(sharing) == 1:
            continue
        ranked = sorted(sharing, key=lambda row: AUTHORITY[row.source])
        keep, rest = ranked[0], ranked[1:]
        if AUTHORITY[rest[0].source] == AUTHORITY[keep.source]:
            problems.append(
                f"{role} is claimed by {', '.join(row.principal for row in sharing)}, all on "
                f"{keep.source} evidence, and a role name cannot be shared. Settle which "
                f"principal owns it and put the others in DOCUMENTED."
            )
            continue
        for row in rest:
            dropped.add(row.principal)
            problems_note = (
                f"{row.principal} dropped: its {row.source} evidence names {role}, which "
                f"{keep.principal} already owns on {keep.source} evidence. The policy document "
                f"references the parent's role under a child path, so the real name is unknown "
                f"and the derived fallback applies. Add a DOCUMENTED entry to fix it properly."
            )
            notes.append(problems_note)
    rows = [row for row in rows if row.principal not in dropped]

    for principal in conflicts:
        if principal not in {row.principal for row in rows}:
            problems.append(
                f"{principal} is listed in KNOWN_SOURCE_CONFLICTS but is in no source, so the "
                f"entry is stale and should come out."
            )
    return rows, problems, notes


HEADER = """\
# Service-linked roles: the role name AWS mints for a principal, and whether the service
# takes a CustomSuffix.
#
# Columns: principal, role name, suffix support, source.
#
# Generated by tools/slr/service_linked_roles.py. Do not hand-edit: run
# `make slr-table` to regenerate, or `make slr-check` to see what drifted.
#
# Neither column can be derived from the principal. Capitalising the principal gives the
# wrong name for {wrong} of these {total} services, and the API Reference says only that
# "some services do not support the CustomSuffix parameter" without listing which.
#
# Suffix support is only ever known from a recorded create: `unknown` means no recording
# covers it, and CreateServiceLinkedRole keeps taking a suffix there rather than refusing
# on an inference. Source is the authority the row rests on, weakest last:
#
#   recording         an AWS-validated create response, and the only source for suffix
#   policy-documents  a role ARN in vendored AWS managed policy documents, name only
#   docs              AWS's own service-linked role page for that service, cited in
#                     DOCUMENTED in the generator so the claim can be rechecked
#
# {counts}
# {recordings}
"""


RECORDINGS_RE = re.compile(r"^#\s*recordings: (?P<digest>[0-9a-f]{64}|none) ")


def snapshot_fingerprint(path: Path | None) -> str:
    """sha256 of the snapshot the recording rows came from, so a refresh can say whether it moved.

    The recording half of the table cannot be rechecked offline, which `--check` can only report
    rather than fix. A digest in the header turns the next `--refresh` into a diff against a known
    source instead of an act of faith: same digest means the recordings are unchanged and any
    difference in the table came from the vendored policy documents.
    """
    if path is None:
        return "none"
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fingerprint_in(table: Path) -> str | None:
    """The digest a table records, or None when it has none or does not exist."""
    if not table.exists():
        return None
    for line in table.read_text().splitlines():
        match = RECORDINGS_RE.match(line.strip())
        if match:
            return match.group("digest")
    return None


def render(rows: list[Row], recordings: str = "none") -> str:
    wrong = sum(1 for row in rows if derived_name(row.principal) != row.role)
    by_source: dict[str, int] = {}
    by_suffix: dict[str, int] = {}
    for row in rows:
        by_source[row.source] = by_source.get(row.source, 0) + 1
        by_suffix[row.suffix] = by_suffix.get(row.suffix, 0) + 1
    counts = (
        f"{len(rows)} services: "
        + ", ".join(f"{count} {source}" for source, count in sorted(by_source.items()))
        + " / suffix "
        + ", ".join(f"{count} {state}" for state, count in sorted(by_suffix.items()))
    )
    recordings_line = (
        f"recordings: {recordings} "
        + ("(no snapshot was given, so no row here rests on a recording)"
           if recordings == "none"
           else "(sha256 of the LocalStack tests/aws/services/iam/test_iam.snapshot.json "
                "the recording rows came from)")
    )
    header = HEADER.format(wrong=wrong, total=len(rows), counts=counts,
                           recordings=recordings_line)
    return header + "\n".join(row.as_tsv() for row in rows) + "\n"


def parse_table(path: Path) -> list[Row]:
    rows = []
    for line in path.read_text().splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        principal, role, suffix, source = stripped.split("\t")
        rows.append(Row(principal, role, suffix, source))
    return rows


def check(table: Path, policy_documents: Path,
          documented: dict[str, tuple[str, str]] | None = None) -> list[str]:
    """What can be verified without the snapshot, which is what CI can run.

    `documented` is a parameter for the same reason it is on build(): a caller checking a
    two-row fixture should not see every real DOCUMENTED entry reported missing from it.
    """
    if documented is None:
        documented = DOCUMENTED
    if not table.exists():
        return [f"{table} is missing; run `make slr-table`."]

    rows = parse_table(table)
    problems: list[str] = []

    seen: dict[str, Row] = {}
    for row in rows:
        if row.principal in seen:
            problems.append(f"{row.principal} appears twice in the table.")
        seen[row.principal] = row
        if not row.role.startswith(ROLE_PREFIX):
            problems.append(f"{row.principal}: {row.role} does not start with {ROLE_PREFIX}.")
        if row.suffix not in (SUFFIX_ALLOWED, SUFFIX_REFUSED, SUFFIX_UNKNOWN):
            problems.append(f"{row.principal}: {row.suffix} is not a suffix state.")
        if row.source not in ("recording", "policy-documents", "docs"):
            problems.append(f"{row.principal}: {row.source} is not a source.")
        if row.source != "recording" and row.suffix != SUFFIX_UNKNOWN:
            problems.append(
                f"{row.principal}: suffix support is {row.suffix} on a {row.source} row, but only "
                f"a recording can establish it."
            )

    if sorted(seen) != [row.principal for row in rows]:
        problems.append("the table is not sorted by principal; regenerate it.")

    # docs rows name a role from a page nothing here can fetch, but the expected value is in
    # DOCUMENTED, so the table and the generator can at least be held to each other.
    for row in rows:
        if row.source != "docs":
            continue
        documented_entry = documented.get(row.principal)
        if documented_entry is None:
            problems.append(
                f"{row.principal} is a docs row but has no DOCUMENTED entry, so nothing records "
                f"where {row.role} came from; add one or regenerate the table."
            )
        elif documented_entry[0] != row.role:
            problems.append(
                f"{row.principal}: the table says {row.role}, DOCUMENTED says {documented_entry[0]}; "
                f"regenerate the table."
            )
    for principal, (role, _source) in documented.items():
        if principal not in seen:
            problems.append(
                f"{principal} is in DOCUMENTED ({role}) but not in the table; regenerate it."
            )

    # The half that can be re-derived offline.
    vendored = names_from_policy_documents(policy_documents)
    for row in rows:
        if row.source != "policy-documents":
            continue
        current = vendored.get(row.principal)
        if current is None:
            problems.append(
                f"{row.principal} is a policy-documents row but no longer appears in "
                f"{policy_documents.name}; regenerate the table."
            )
        elif current != row.role:
            problems.append(
                f"{row.principal}: the table says {row.role}, the vendored policy documents now "
                f"say {current}; regenerate the table."
            )
    # The digest itself cannot be checked without the snapshot. Its absence can, and a table
    # that has quietly lost it is a table whose recording rows have no stated source.
    recorded_digest = fingerprint_in(table)
    if recorded_digest is None:
        problems.append(
            f"{table.name} has no `recordings:` line, so nothing says which snapshot its "
            f"recording rows came from; regenerate it."
        )
    elif recorded_digest == "none" and any(row.source == "recording" for row in rows):
        problems.append(
            f"{table.name} says it was built from no snapshot, yet carries recording rows; "
            f"regenerate it from the snapshot those came from."
        )

    owners = {row.role: row.principal for row in rows}
    for principal, role in vendored.items():
        if principal in seen:
            continue
        if owners.get(role) not in (None, principal):
            # Dropped by build()'s collision guard: the policy document points this principal
            # at a role another one owns, so it falls back to the derived name rather than
            # claiming a name that would make the sibling's create fail as a duplicate.
            continue
        problems.append(
            f"{principal} is in the vendored policy documents ({role}) but not in the table; "
            f"regenerate it so the new service is covered."
        )
    return problems


def audit(rows: list[Row]) -> str:
    lines = [f"{'principal':52} {'role name':58} {'suffix':8} source", "-" * 134]
    for row in rows:
        marker = "" if derived_name(row.principal) == row.role else "  (not derivable)"
        lines.append(f"{row.principal:52} {row.role:58} {row.suffix:8} {row.source}{marker}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true",
                      help="exit 1 when the table drifted from the vendored data (CI gate)")
    mode.add_argument("--refresh", type=Path, metavar="SNAPSHOT",
                      help="regenerate from an AWS-validated LocalStack IAM snapshot")
    mode.add_argument("--audit", action="store_true", help="print the table (the default)")
    parser.add_argument("--table", type=Path, default=TABLE, help=argparse.SUPPRESS)
    parser.add_argument("--policy-documents", type=Path, default=POLICY_DOCUMENTS,
                        help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    if args.refresh:
        rows, problems, notes = build(args.policy_documents, args.refresh)
        for note in notes:
            print(f"note: {note}", file=sys.stderr)
        if problems:
            print("\n".join(f"error: {problem}" for problem in problems), file=sys.stderr)
            return 1
        previous = fingerprint_in(args.table)
        current = snapshot_fingerprint(args.refresh)
        args.table.write_text(render(rows, current))
        if previous is None:
            print(f"recordings: {current} (the table recorded none before this)")
        elif previous == current:
            print(f"recordings: {current} (unchanged, so any difference came from the "
                  f"vendored policy documents)")
        else:
            print(f"recordings: {previous} -> {current} (the snapshot moved; the recording rows "
                  f"may have changed with it)")
        print(f"wrote {args.table.relative_to(REPO_ROOT)} with {len(rows)} services")
        return 0

    if args.check:
        problems = check(args.table, args.policy_documents)
        if problems:
            print("\n".join(f"error: {problem}" for problem in problems), file=sys.stderr)
            return 1
        print(f"{args.table.relative_to(REPO_ROOT)} matches the vendored data")
        return 0

    print(audit(parse_table(args.table)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
