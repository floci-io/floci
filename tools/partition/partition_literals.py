#!/usr/bin/env python3
"""Keep hardcoded AWS-partition assumptions in src/main from growing back.

Floci is moving from a commercial-only (`aws`) emulator to one that serves every AWS
partition (`aws-cn`, `aws-us-gov`, the ISO partitions, `aws-eusc`). The blockers are
string literals that bake the commercial partition into a code path: an `arn:aws:`
prefix, an `amazonaws.com` host, a Route 53 hosted zone that only exists in one
partition, a hand-rolled `arn:aws[a-z-]*:` regex that recognises some partitions but
not others. Each one is a silent bug in a non-commercial deployment, and each one is
trivial to add by accident, because the literal is exactly what an `aws` deployment
prints.

This script inventories those literals in `src/main/java` by category and gates them
against a checked-in per-file baseline (`baseline.tsv`):

- a literal in a file the baseline does not know fails (new files start clean);
- a file whose count grew fails;
- a file whose count dropped also fails, until the baseline is regenerated, so the
  "N remaining" number stays exact and the drop is visible in the PR diff.

Comments never count. A literal that is genuinely partition-invariant is excused in one
of two ways, both of which must state a reason: an entry in `allowlist.yaml` (a whole
file, or a regex matched against the source line), or a trailing
`// partition-literal: <reason>` comment on the line. The audit always prints the
escapes so they are reviewed, not forgotten.

Run from anywhere in the repo:
    python3 tools/partition/partition_literals.py                   # per-package audit
    python3 tools/partition/partition_literals.py --check           # CI gate against baseline.tsv
    python3 tools/partition/partition_literals.py --write-baseline  # regenerate baseline.tsv
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = REPO_ROOT / "src/main/java"
PACKAGE_ROOT = SOURCE_ROOT / "io/github/hectorvent/floci"
TOOL_DIR = Path(__file__).resolve().parent
ALLOWLIST = TOOL_DIR / "allowlist.yaml"
BASELINE = TOOL_DIR / "baseline.tsv"

ESCAPE_RE = re.compile(r"//\s*partition-literal:\s*(?P<reason>\S.*?)\s*$")

REGION_PREFIXES = "us|eu|ap|ca|sa|me|af|il|mx|cn|us-gov|us-iso|us-isob|eu-isoe|us-isof|eusc-de"


@dataclass(frozen=True)
class Category:
    name: str
    regex: re.Pattern
    gated: bool
    description: str


# Regexes run against the content of one string literal (one line of a text block), never
# against comments. Order is the audit column order.
CATEGORIES: tuple[Category, ...] = (
    Category(
        "arn-literal",
        re.compile(r"\barn:aws(?:-[a-z]+)*:"),
        True,
        "an ARN prefix naming one partition (arn:aws:, arn:aws-cn:, ...); mint through AwsArnUtils",
    ),
    Category(
        "arn-regex-dialect",
        re.compile(r"arn:aws(?=[\[(\\])"),
        True,
        "a hand-rolled partition alternation in a regex; use AwsArnUtils.PARTITION_REGEX",
    ),
    Category(
        "dns-suffix",
        re.compile(r"amazonaws\.com(?!\.cn)|cloudfront\.net|signin\.aws|public\.ecr\.aws"),
        True,
        "a commercial DNS suffix or host; derive from the partition's dnsSuffix",
    ),
    Category(
        "hosted-zone-id",
        re.compile(r"^Z[0-9A-Z]{12,14}$"),
        True,
        "a Route 53 hosted zone id, which differs per region and partition",
    ),
    Category(
        "region-literal",
        re.compile(
            r"(?<![a-z0-9-])(?:" + REGION_PREFIXES + r")-[a-z]+-\d[a-z]?(?![a-z0-9-])"
        ),
        False,
        "a region or availability-zone id; report-only until the us-east-1 assumptions are swept",
    ),
)

CATEGORY_BY_NAME = {category.name: category for category in CATEGORIES}
GATED = tuple(category for category in CATEGORIES if category.gated)


@dataclass(frozen=True)
class Literal:
    line: int
    text: str


@dataclass(frozen=True)
class Finding:
    category: str
    path: str
    line: int
    source: str
    excuse: str | None = None

    @property
    def counted(self) -> bool:
        return self.excuse is None


def scan_java(text: str) -> tuple[list[Literal], dict[int, str]]:
    """Extracts string literals (one per line for text blocks) and per-line escape comments.

    A tiny lexer rather than a regex: `//` inside a string is a URL, not a comment, and a
    `"` inside a comment or char literal is not a string. Block comments and text blocks
    span lines, so line numbers are tracked by hand.
    """
    literals: list[Literal] = []
    escapes: dict[int, str] = {}
    i = 0
    n = len(text)
    line = 1
    while i < n:
        char = text[i]
        if char == "\n":
            line += 1
            i += 1
        elif text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            match = ESCAPE_RE.match(text[i:end])
            if match:
                escapes[line] = match.group("reason")
            i = end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            line += text.count("\n", i, end)
            i = end
        elif text.startswith('"""', i):
            j = i + 3
            while j < n and not text.startswith('"""', j):
                j += 2 if text[j] == "\\" else 1
            block = text[i + 3:j]
            for offset, part in enumerate(block.split("\n")):
                if part.strip():
                    literals.append(Literal(line + offset, part))
            line += block.count("\n")
            i = min(j + 3, n)
        elif char == '"':
            j = i + 1
            buffer: list[str] = []
            while j < n and text[j] not in '"\n':
                if text[j] == "\\":
                    buffer.append(text[j:j + 2])
                    j += 2
                else:
                    buffer.append(text[j])
                    j += 1
            literals.append(Literal(line, "".join(buffer)))
            i = j + 1 if j < n and text[j] == '"' else j
        elif char == "'":
            j = i + 1
            while j < n and text[j] not in "'\n":
                j += 2 if text[j] == "\\" else 1
            i = j + 1 if j < n and text[j] == "'" else j
        else:
            i += 1
    return literals, escapes


@dataclass(frozen=True)
class AllowRule:
    kind: str
    value: str
    reason: str


def load_allowlist(path: Path) -> list[AllowRule]:
    if not path.exists():
        return []
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    rules: list[AllowRule] = []
    for entry in data.get("files") or []:
        rules.append(AllowRule("file", str(entry["path"]), str(entry["reason"])))
    for entry in data.get("patterns") or []:
        rules.append(AllowRule("pattern", str(entry["regex"]), str(entry["reason"])))
    for rule in rules:
        if not rule.reason.strip():
            raise ValueError(f"allowlist entry {rule.value!r} has no reason")
    return rules


def excuse_for(rel_path: str, source_line: str, rules: list[AllowRule]) -> str | None:
    for rule in rules:
        if rule.kind == "file" and rule.value == rel_path:
            return f"allowlist file: {rule.reason}"
        if rule.kind == "pattern" and re.search(rule.value, source_line):
            return f"allowlist pattern {rule.value!r}: {rule.reason}"
    return None


def collect_findings(source_root: Path, rules: list[AllowRule]) -> list[Finding]:
    findings: list[Finding] = []
    for java in sorted(source_root.rglob("*.java")):
        rel_path = java.relative_to(REPO_ROOT).as_posix() if java.is_relative_to(REPO_ROOT) \
            else java.relative_to(source_root).as_posix()
        text = java.read_text(encoding="utf-8")
        lines = text.split("\n")
        literals, escapes = scan_java(text)
        for literal in literals:
            source_line = lines[literal.line - 1] if literal.line - 1 < len(lines) else ""
            for category in CATEGORIES:
                hits = len(category.regex.findall(literal.text))
                if hits == 0:
                    continue
                excuse = excuse_for(rel_path, source_line, rules)
                if excuse is None and literal.line in escapes:
                    excuse = f"escape: {escapes[literal.line]}"
                findings.extend(
                    Finding(category.name, rel_path, literal.line, source_line.strip(), excuse)
                    for _ in range(hits)
                )
    return findings


def count(findings: list[Finding], gated_only: bool = False) -> Counter:
    counter: Counter = Counter()
    for finding in findings:
        if not finding.counted:
            continue
        if gated_only and not CATEGORY_BY_NAME[finding.category].gated:
            continue
        counter[(finding.category, finding.path)] += 1
    return counter


def read_baseline(path: Path) -> Counter:
    counter: Counter = Counter()
    if not path.exists():
        return counter
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.startswith("#"):
            continue
        parts = raw.split("\t")
        if len(parts) != 3:
            raise ValueError(f"{path}: malformed baseline row {raw!r}")
        category, rel_path, value = parts
        if category not in CATEGORY_BY_NAME:
            raise ValueError(f"{path}: unknown category {category!r}")
        counter[(category, rel_path)] = int(value)
    return counter


def format_baseline(counter: Counter) -> str:
    rows = ["# category\tpath\tcount", "# Generated by tools/partition/partition_literals.py --write-baseline; do not hand-edit."]
    for (category, rel_path), value in sorted(counter.items()):
        rows.append(f"{category}\t{rel_path}\t{value}")
    return "\n".join(rows) + "\n"


def write_baseline(path: Path, findings: list[Finding]) -> Counter:
    counter = count(findings, gated_only=True)
    path.write_text(format_baseline(counter), encoding="utf-8")
    return counter


def check(findings: list[Finding], baseline: Counter) -> list[str]:
    """Returns one problem per (category, file) that drifted from the baseline; empty when green."""
    current = count(findings, gated_only=True)
    by_key: dict[tuple[str, str], list[Finding]] = defaultdict(list)
    for finding in findings:
        if finding.counted:
            by_key[(finding.category, finding.path)].append(finding)
    problems: list[str] = []
    for key in sorted(set(current) | set(baseline)):
        category, rel_path = key
        if not CATEGORY_BY_NAME[category].gated:
            continue
        now = current.get(key, 0)
        before = baseline.get(key, 0)
        if now == before:
            continue
        if key not in baseline:
            head = f"{rel_path}: {now} {category} literal(s) with no baseline entry for this file"
        elif now > before:
            head = f"{rel_path}: {category} grew from {before} to {now}"
        else:
            head = f"{rel_path}: {category} dropped from {before} to {now}; run 'make partition-baseline' and commit baseline.tsv"
        lines = [head]
        if now > before:
            lines.extend(f"    {f.path}:{f.line}: {f.source}" for f in by_key[key])
            lines.append(f"    {CATEGORY_BY_NAME[category].description}")
        problems.append("\n".join(lines))
    return problems


def package_of(rel_path: str) -> str:
    try:
        relative = Path(rel_path).relative_to(PACKAGE_ROOT.relative_to(REPO_ROOT))
    except ValueError:
        return Path(rel_path).parent.as_posix()
    parts = relative.parts[:-1]
    return "/".join(parts[:2]) if parts else "(root)"


def format_audit(findings: list[Finding], baseline: Counter) -> str:
    names = [category.name for category in CATEGORIES]
    per_package: dict[str, Counter] = defaultdict(Counter)
    totals: Counter = Counter()
    for finding in findings:
        if finding.counted:
            per_package[package_of(finding.path)][finding.category] += 1
            totals[finding.category] += 1
    width = max([len("package"), len("total")] + [len(p) for p in per_package]) + 2
    header = f"{'package':<{width}}" + "".join(f"{name:>19}" for name in names) + f"{'total':>8}"
    out = [header, "-" * len(header)]
    for package, counter in sorted(per_package.items(), key=lambda item: (-sum(item[1].values()), item[0])):
        out.append(
            f"{package:<{width}}"
            + "".join(f"{counter.get(name, 0):>19}" for name in names)
            + f"{sum(counter.values()):>8}"
        )
    out.append("-" * len(header))
    out.append(
        f"{'total':<{width}}"
        + "".join(f"{totals.get(name, 0):>19}" for name in names)
        + f"{sum(totals.values()):>8}"
    )
    gate = "".join(
        f"{'gated' if category.gated else 'report-only':>19}" for category in CATEGORIES
    )
    out.append(f"{'':<{width}}{gate}")
    if baseline:
        baseline_totals: Counter = Counter()
        for (category, _), value in baseline.items():
            baseline_totals[category] += value
        out.append("")
        out.append("baseline.tsv: " + ", ".join(
            f"{category.name} {baseline_totals.get(category.name, 0)} -> {totals.get(category.name, 0)}"
            for category in GATED
        ))
    excused = [finding for finding in findings if not finding.counted]
    escapes = sorted({(f.path, f.line, f.excuse) for f in excused if f.excuse and f.excuse.startswith("escape:")})
    allowed: Counter = Counter(
        f.excuse for f in excused if f.excuse and f.excuse.startswith("allowlist")
    )
    out.append("")
    out.append(f"escapes (// partition-literal: <reason>): {len(escapes)}")
    out.extend(f"  {path}:{line}  {excuse[len('escape: '):]}" for path, line, excuse in escapes)
    out.append(f"allow-listed occurrences: {sum(allowed.values())}")
    out.extend(f"  {value:>4}  {excuse}" for excuse, value in sorted(allowed.items()))
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="exit 1 when a gated count drifted from the baseline")
    mode.add_argument("--write-baseline", action="store_true", help="regenerate the baseline from the current tree")
    mode.add_argument("--audit", action="store_true", help="print the per-package table (the default)")
    parser.add_argument("--source", type=Path, default=SOURCE_ROOT, help=argparse.SUPPRESS)
    parser.add_argument("--allowlist", type=Path, default=ALLOWLIST, help=argparse.SUPPRESS)
    parser.add_argument("--baseline", type=Path, default=BASELINE, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    rules = load_allowlist(args.allowlist)
    findings = collect_findings(args.source, rules)

    if args.write_baseline:
        counter = write_baseline(args.baseline, findings)
        print(f"wrote {args.baseline.relative_to(REPO_ROOT) if args.baseline.is_relative_to(REPO_ROOT) else args.baseline}: "
              f"{sum(counter.values())} gated literal(s) across {len({p for _, p in counter})} file(s)")
        return 0

    baseline = read_baseline(args.baseline)
    if args.check:
        problems = check(findings, baseline)
        for problem in problems:
            print(problem)
        if problems:
            print("")
            print(f"error: {len(problems)} file(s) drifted from tools/partition/baseline.tsv. "
                  "Route new ARNs through AwsArnUtils and hosts through the partition's dnsSuffix; "
                  "when a literal is genuinely partition-invariant, add it to tools/partition/allowlist.yaml "
                  "or end the line with '// partition-literal: <reason>'.")
            return 1
        gated_total = sum(count(findings, gated_only=True).values())
        print(f"partition literals match baseline.tsv ({gated_total} gated occurrence(s) remaining)")
        return 0

    print(format_audit(findings, baseline))
    return 0


if __name__ == "__main__":
    sys.exit(main())
