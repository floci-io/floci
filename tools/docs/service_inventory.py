#!/usr/bin/env python3
"""Reconcile every source that answers "how many AWS services does Floci support?".

There is no single number, and that is not a bug: twelve sources in this repo each
count something slightly different, and each is right on its own terms. A descriptor
is a signing endpoint, a matrix row is an API surface, a doc page is a service a user
browses to, a README category entry is a marketing name. Asked "how many services",
people reach for whichever is nearest and get a different answer every time.

This script prints all twelve, groups them into the five keyspaces that can actually
be compared, and for each comparison prints the delta *as a number and as the named
entries that explain it*. An unexplained delta is a finding; an explained one is a
design decision that should read as deliberate.

It is a human-facing report, not a CI gate: it is read-only, and it exits non-zero
only when a source cannot be parsed. It deliberately does not duplicate the two real
gates that already exist (check_service_matrix.py for catalog-to-matrix,
ServiceConfigYamlCoverageTest for config-to-YAML); both are named in the output so an
advisory comparison can never be mistaken for an enforced one.

    python3 tools/docs/service_inventory.py           # the reconciliation report
    python3 tools/docs/service_inventory.py --json    # the same data, machine-readable

**Never copy a number from this output into README.md or docs/.** The repo's rule is
that no service count is published anywhere, derived or not; user-facing pages link to
the Service Matrix instead. This tool exists so the question can be answered in
conversation, not so the answer can be pasted into a page that then goes stale.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import date, timedelta
from pathlib import Path

import yaml

import check_service_matrix as csm

CONFIG_SOURCE = "src/main/java/io/github/hectorvent/floci/config/EmulatorConfig.java"
APPLICATION_YML = "src/main/resources/application.yml"
README = "README.md"
SERVICES_YAML = "tools/docs/services.yaml"
PACKAGE_DIR = "src/main/java/io/github/hectorvent/floci/services"

# Warn this far ahead of a deferred entry's expiry, so a red main is a decision rather
# than a surprise on the morning the date passes.
DEFERRAL_WARN_DAYS = 30

# Entries under floci.services.* that emulate nothing from AWS. The config and YAML
# collectors count them correctly (they are services Floci runs), so they have to be
# subtracted before those counts can be compared with anything AWS-shaped.
NON_AWS_CONFIG_KEYS = {"duck", "ui"}

# Packages under services/ that back no descriptor of their own, each with the reason.
# The point of listing them is that the package-count delta reads as explained; an
# entry appearing in the UNREGISTERED list instead is the signal that a package was
# added without being registered in the catalog.
PACKAGE_NOTES = {
    "cloudwatch": "one package, two descriptors: cloudwatchlogs and cloudwatchmetrics",
    "floci": "Floci's own control surface, not an AWS service",
    "lambdamicrovms": "controllers attached to the lambda descriptor",
    "resourcegroupstagging": "registered under the AWS signing name, tagging",
    "securityadmin": "shared admin-accounts endpoint over GuardDuty and Macie",
    "signin": "controller attached to the signin descriptor, which is keyed iam",
    "ssoportal": "controller attached to the sso descriptor, which is keyed ssoadmin",
}

# README category names whose wording differs from the matrix row label they refer to.
# Matching is by normalised name, so only genuine wording differences need an entry,
# and one README name may cover several matrix rows. This assists a human-facing
# report and gates nothing, which is why it lives here rather than in
# service_matrix.yaml next to the aliases the CI gate reads.
README_NAME_ALIASES: dict[str, str | list[str]] = {
    "API Gateway REST": "API Gateway v1",
    "Bedrock": "Amazon Bedrock",
    "ELB Classic": "ELB Classic (v1)",
    "Firehose": "Data Firehose",
    "Resource Explorer 2": "Resource Explorer",
    "Service Catalog": "AWS Service Catalog",
    "IAM Identity Center (SSO Admin, OIDC, Access Portal, SCIM)": [
        "IAM Identity Center (SSO Admin)",
        "IAM Identity Center OIDC",
        "IAM Identity Center Access Portal",
    ],
}

CONFIG_ACCESSOR_RE = re.compile(r"^\s{8}[A-Za-z0-9]+ +([a-zA-Z0-9]+)\(\);", re.M)
ENABLED_CALL_RE = re.compile(r"config\.services\(\)\.([a-zA-Z0-9]+)\(\)\.enabled\(\)")
MATRIX_ROW_RE = re.compile(r"^\| \[([^\]]+)\]\(([a-z0-9][a-z0-9\-]*)\.md([^)]*)\)", re.M)


def _repo_root() -> Path:
    return Path(__file__).resolve().parent.parent.parent


def key(name: str) -> str:
    """Canonical form of a service key, for comparing across naming conventions.

    The same service is `bcmDataExports` as a config accessor, `bcm-data-exports` in
    application.yml and `bcmdataexports` as a package directory. Comparing the raw
    spellings reports eight bogus mismatches in a repo where nothing is actually wrong,
    so every cross-source comparison goes through here.
    """
    return re.sub(r"[^a-z0-9]", "", name.lower())


# --------------------------------------------------------------------------- #
# Java catalog
# --------------------------------------------------------------------------- #
def split_top_level_args(text: str, start: int) -> list[str]:
    """Split the argument list of a call whose opening paren is at or after `start`.

    Splits on commas at paren depth 1 only, so a nested `config.services().x().enabled()`
    or `Set.of("a", "b")` argument stays in one piece. Raises ValueError if the parens
    never balance.
    """
    depth = 0
    args: list[str] = []
    current: list[str] = []
    for i in range(start, len(text)):
        ch = text[i]
        if ch == "(":
            depth += 1
            if depth == 1:
                continue
        elif ch == ")":
            depth -= 1
            if depth == 0:
                args.append("".join(current).strip())
                return args
        elif ch == "," and depth == 1:
            args.append("".join(current).strip())
            current = []
            continue
        current.append(ch)
    raise ValueError("unbalanced parentheses in descriptor(...) call")


def parse_descriptors(java_source: str) -> list[dict]:
    """externalKey, configKey, enable toggles and includeInStatus of every call.

    check_service_matrix.py needs only the externalKey, so one regex serves it. This
    needs the first four arguments, and the fourth can sit on a continuation line:
    rds-data and redshift-data wrap because their enabled expression is a conjunction
    of two toggles. Matching off the opening line would read `true` from the wrong
    argument for exactly those two, so the arguments are split properly instead.
    """
    out: list[dict] = []
    for m in csm.ALL_DESCRIPTOR_CALLS_RE.finditer(java_source):
        open_paren = java_source.index("(", m.start())
        args = split_top_level_args(java_source, open_paren)
        if len(args) < 4:
            raise ValueError(f"descriptor(...) call with only {len(args)} arguments")
        external, config_key, enabled, include = args[0], args[1], args[2], args[3]
        if not (external.startswith('"') and external.endswith('"')):
            raise ValueError(
                f"descriptor(...) externalKey is not a plain string literal: {external!r}"
            )
        out.append(
            {
                "external_key": external.strip('"'),
                "config_key": config_key.strip('"') if config_key.startswith('"') else None,
                "toggles": sorted(set(ENABLED_CALL_RE.findall(enabled))),
                "in_status": include.strip() == "true",
            }
        )
    return out


# --------------------------------------------------------------------------- #
# Config plane
# --------------------------------------------------------------------------- #
def parse_config_accessors(java_source: str) -> list[str]:
    """Accessors on EmulatorConfig.ServicesConfig that return a per-service config.

    Scoped to that interface body, because the same accessor shape appears on other
    nested interfaces. `dockerNetwork()` is excluded by the return-type pattern: it
    returns `Optional<String>`, and the `<` keeps it out.
    """
    try:
        start = java_source.index("    interface ServicesConfig {")
    except ValueError:
        raise ValueError(f"{CONFIG_SOURCE}: interface ServicesConfig not found") from None
    end = java_source.index("\n    }", start)
    return sorted(CONFIG_ACCESSOR_RE.findall(java_source[start:end]))


def parse_yaml_service_keys(path: Path) -> tuple[list[str], list[str]]:
    """(every floci.services.* key, the subset declaring `enabled:`)."""
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    services = (data.get("floci") or {}).get("services") or {}
    keys = [k for k, v in services.items() if isinstance(v, dict)]
    return sorted(keys), sorted(k for k in keys if "enabled" in services[k])


# --------------------------------------------------------------------------- #
# Docs and README
# --------------------------------------------------------------------------- #
def parse_matrix_rows(md_source: str) -> list[dict]:
    """One entry per Service Matrix row: label, page slug, anchor.

    A row is a distinct API surface; a page is a documented service. They differ
    because eight pages carry two rows each (DynamoDB and DynamoDB Streams, IAM and
    AWS Sign-In, and so on). The facet test is **two rows sharing a slug**, not "the
    link carries an anchor": IoT Core and IoT Data both link plain `iot.md` with no
    anchor at all, so an anchor-based rule misses that pair and over-counts by one.
    """
    start = md_source.index(csm.MATRIX_HEADING)
    end = md_source.index(csm.MATRIX_END_HEADING, start)
    return [
        {"label": label, "slug": slug, "anchor": anchor.lstrip("#") or None}
        for label, slug, anchor in MATRIX_ROW_RE.findall(md_source[start:end])
    ]


def parse_readme_category_names(md_source: str) -> list[str]:
    """Service names from the README's Category/Services table.

    Split on commas at paren depth 0 only. One entry reads `IAM Identity Center (SSO
    Admin, OIDC, Access Portal, SCIM)`; splitting on every comma turns that single
    name into four, three of which ("OIDC", "SCIM)") are not service names at all and
    inflate the count by three.
    """
    start = md_source.index("| Category | Services |")
    end = md_source.index("For operation-level compatibility", start)
    names: list[str] = []
    for line in md_source[start:end].splitlines():
        if not line.startswith("| ") or line.startswith("|---") or line.startswith("| Category"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) < 2:
            continue
        depth = 0
        current: list[str] = []
        for ch in cells[1]:
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            elif ch == "," and depth == 0:
                names.append("".join(current).strip())
                current = []
                continue
            current.append(ch)
        if "".join(current).strip():
            names.append("".join(current).strip())
    return names


def parse_readme_detail_rows(md_source: str) -> list[str]:
    """Service names from the README's collapsed "Detailed service notes" table."""
    header = "| Service | How it works | Notable features |"
    start = md_source.index(header)
    end = md_source.index("</details>", start)
    rows = []
    for line in md_source[start:end].splitlines():
        if not line.startswith("| ") or line.startswith("|---") or line == header:
            continue
        rows.append(line.strip("|").split("|")[0].strip())
    return rows


def parse_action_table_services(path: Path) -> list[str]:
    """Service keys in services.yaml, the opt-in list for generated action tables."""
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    return sorted(entry["service"] for entry in (data.get("services") or []))


# --------------------------------------------------------------------------- #
# Reconciliation
# --------------------------------------------------------------------------- #
def collect(repo_root: Path) -> dict:
    """Every source, parsed. Raises ValueError if any source cannot be read."""
    descriptors = parse_descriptors((repo_root / csm.CATALOG_SOURCE).read_text(encoding="utf-8"))
    if not descriptors:
        raise ValueError(f"no descriptor(...) calls found in {csm.CATALOG_SOURCE}")

    yaml_keys, yaml_enabled = parse_yaml_service_keys(repo_root / APPLICATION_YML)
    readme_src = (repo_root / README).read_text(encoding="utf-8")
    aliases, deferred, facet_pages = csm._load_registry(repo_root)

    return {
        "descriptors": descriptors,
        "config_accessors": parse_config_accessors(
            (repo_root / CONFIG_SOURCE).read_text(encoding="utf-8")
        ),
        "yaml_keys": yaml_keys,
        "yaml_enabled": yaml_enabled,
        "packages": sorted(p.name for p in (repo_root / PACKAGE_DIR).iterdir() if p.is_dir()),
        "matrix_rows": parse_matrix_rows(
            (repo_root / csm.MATRIX_DOC).read_text(encoding="utf-8")
        ),
        "doc_pages": sorted(
            p.stem for p in (repo_root / csm.SERVICES_DIR).glob("*.md") if p.name != "index.md"
        ),
        "readme_categories": parse_readme_category_names(readme_src),
        "readme_details": parse_readme_detail_rows(readme_src),
        "action_tables": parse_action_table_services(repo_root / SERVICES_YAML),
        "aliases": aliases,
        "facet_pages": sorted(facet_pages),
        "deferred": [{"key": d.key, "reason": d.reason, "by": d.by.isoformat()} for d in deferred],
    }


def reconcile(data: dict, today: date | None = None) -> dict:
    """Counts, deltas, and the named entries that explain each delta."""
    today = today or date.today()
    descriptors = data["descriptors"]
    aliases = data["aliases"]

    # --- catalog ---------------------------------------------------------- #
    keys = [d["external_key"] for d in descriptors]
    page_of = {k: aliases.get(k, k) for k in keys}
    owners: dict[str, list[str]] = {}
    for k in keys:
        owners.setdefault(page_of[k], []).append(k)
    facet_descriptors = sorted(k for k in keys if owners[page_of[k]][0] != k)
    status_hidden = sorted(d["external_key"] for d in descriptors if not d["in_status"])

    toggle_users: dict[str, list[str]] = {}
    for d in descriptors:
        for t in d["toggles"]:
            toggle_users.setdefault(key(t), []).append(d["external_key"])
    shared = {t: v for t, v in sorted(toggle_users.items()) if len(v) > 1}

    # --- config plane ----------------------------------------------------- #
    accessors = {key(a): a for a in data["config_accessors"]}
    yaml_keys = {key(k): k for k in data["yaml_keys"]}
    aws_keys = {k: v for k, v in yaml_keys.items() if v not in NON_AWS_CONFIG_KEYS}
    untoggled = sorted(v for k, v in aws_keys.items() if k not in toggle_users)

    # --- packages --------------------------------------------------------- #
    descriptor_keys = {key(d["config_key"]) for d in descriptors if d["config_key"]}
    packages = data["packages"]
    noted = sorted(p for p in packages if p in PACKAGE_NOTES)
    unregistered = sorted(
        p for p in packages if key(p) not in descriptor_keys and p not in PACKAGE_NOTES
    )

    # --- docs ------------------------------------------------------------- #
    rows = data["matrix_rows"]
    slug_rows: dict[str, list[str]] = {}
    for r in rows:
        slug_rows.setdefault(r["slug"], []).append(r["label"])
    facet_rows = sorted(r["label"] for r in rows if slug_rows[r["slug"]][0] != r["label"])
    doc_pages = set(data["doc_pages"])
    expiring = [
        d for d in data["deferred"]
        if date.fromisoformat(d["by"]) <= today + timedelta(days=DEFERRAL_WARN_DAYS)
    ]

    # --- README ----------------------------------------------------------- #
    row_labels = {key(r["label"]) for r in rows}
    facet_norms = {key(label) for label in facet_rows}

    def cover(names: list[str]) -> tuple[set[str], list[str]]:
        """(matrix labels these README names account for, names matching no row).

        A name resolves through README_NAME_ALIASES when its wording differs from the
        matrix label, and one alias may cover several rows: the README names IAM
        Identity Center once where the matrix carries three rows for it.
        """
        covered: set[str] = set()
        unmatched: list[str] = []
        for name in names:
            target = README_NAME_ALIASES.get(name, name)
            norms = [key(t) for t in (target if isinstance(target, list) else [target])]
            if all(n in row_labels for n in norms):
                covered |= set(norms)
            else:
                unmatched.append(name)
        return covered, unmatched

    category_covered, unmatched_names = cover(data["readme_categories"])
    detail_covered, _ = cover(data["readme_details"])

    def uncovered(covered: set[str], facets: bool) -> list[str]:
        return [
            r["label"]
            for r in rows
            if key(r["label"]) not in covered and (key(r["label"]) in facet_norms) is facets
        ]

    return {
        "counts": {
            "catalog_descriptors": len(keys),
            "catalog_distinct_products": len(keys) - len(facet_descriptors),
            "catalog_status_visible": len(keys) - len(status_hidden),
            "catalog_toggles": len(toggle_users),
            "service_packages": len(packages),
            "config_accessors": len(accessors),
            "yaml_keys": len(yaml_keys),
            "yaml_enabled": len(data["yaml_enabled"]),
            "yaml_aws_keys": len(aws_keys),
            "matrix_rows": len(rows),
            "matrix_pages": len(slug_rows),
            "doc_pages": len(doc_pages),
            "readme_category_names": len(data["readme_categories"]),
            "readme_detail_rows": len(data["readme_details"]),
            "action_tables": len(data["action_tables"]),
        },
        "catalog": {
            "facet_descriptors": facet_descriptors,
            "status_hidden": status_hidden,
            "shared_toggles": {t: v for t, v in shared.items()},
        },
        "config": {
            "non_aws": sorted(v for k, v in yaml_keys.items() if v in NON_AWS_CONFIG_KEYS),
            "accessor_only": sorted(accessors[k] for k in accessors.keys() - yaml_keys.keys()),
            "yaml_only": sorted(yaml_keys[k] for k in yaml_keys.keys() - accessors.keys()),
            "no_enabled_key": sorted(set(data["yaml_keys"]) - set(data["yaml_enabled"])),
            "untoggled": untoggled,
            "toggle_without_key": sorted(
                t for t in toggle_users if t not in yaml_keys
            ),
        },
        "packages": {"noted": noted, "unregistered": unregistered},
        "docs": {
            "facet_rows": facet_rows,
            "pages_without_row": sorted(doc_pages - set(slug_rows) - set(data["facet_pages"])),
            "rows_without_page": sorted(set(slug_rows) - doc_pages),
            "facet_pages": data["facet_pages"],
            "deferred": data["deferred"],
            "deferred_expiring": expiring,
        },
        "readme": {
            "unmatched_category_names": unmatched_names,
            "missing_from_categories": uncovered(category_covered, facets=False),
            "missing_facet_rows": uncovered(category_covered, facets=True),
            "missing_from_details": uncovered(detail_covered, facets=False),
        },
    }


# --------------------------------------------------------------------------- #
# Report
# --------------------------------------------------------------------------- #
def _names(items: list[str], limit: int = 10) -> str:
    if not items:
        return ""
    shown = ", ".join(items[:limit])
    return shown if len(items) <= limit else f"{shown}, +{len(items) - limit} more"


def render(result: dict) -> str:
    c = result["counts"]
    cat, cfg, pkg, docs, rd = (
        result["catalog"], result["config"], result["packages"],
        result["docs"], result["readme"],
    )
    out: list[str] = []
    w = out.append

    def line(label: str, count: int | str, note: str = "") -> None:
        w(f"  {label:<32}{str(count):>5}   {note}".rstrip())

    def delta(label: str, items: list[str], limit: int = 10) -> None:
        w(f"  {'- ' + label:<32}{len(items):>5}   {_names(items, limit)}".rstrip())

    def note(text: str) -> None:
        w(f"        {text}")

    w("")
    w("THE ANSWER")
    w(f"  Floci documents {c['matrix_pages']} AWS services, exposing {c['matrix_rows']} distinct")
    w(f"  API surfaces across {c['catalog_descriptors']} signing endpoints.")
    w("  Quote the first for \"how many services\", the second when the question is really")
    w("  about API coverage. Never write any of them into README.md or docs/: link to the")
    w("  Service Matrix instead.")
    w("")

    w("1. CATALOG  (keyspace: descriptor externalKey)")
    line("catalog descriptors", c["catalog_descriptors"], csm.CATALOG_SOURCE)
    delta("facet endpoints", cat["facet_descriptors"])
    line("= distinct products", c["catalog_distinct_products"])
    line("status-visible at runtime", c["catalog_status_visible"],
         f"GET /health; hidden: {_names(cat['status_hidden'])}")
    line("distinct enable toggles", c["catalog_toggles"])
    for toggle, users in cat["shared_toggles"].items():
        note(f"{toggle} gates {len(users)}: {_names(users)}")
    w("")

    w("2. CONFIG PLANE  (keyspace: floci.services.* key, naming normalised)")
    line("EmulatorConfig accessors", c["config_accessors"], CONFIG_SOURCE)
    line("application.yml keys", c["yaml_keys"], APPLICATION_YML)
    if cfg["accessor_only"] or cfg["yaml_only"]:
        delta("accessor with no YAML key", cfg["accessor_only"])
        delta("YAML key with no accessor", cfg["yaml_only"])
    else:
        line("= identical sets", "", "GATED by ServiceConfigYamlCoverageTest")
    delta("no `enabled:` in YAML", cfg["no_enabled_key"])
    delta("non-AWS entries", cfg["non_aws"])
    line("= AWS service keys", c["yaml_aws_keys"])
    delta("no descriptor reads its toggle", cfg["untoggled"])
    delta("toggle with no YAML key", cfg["toggle_without_key"])
    w("")

    w("3. SOURCE  (keyspace: Java package, naming normalised)")
    line("services/ packages", c["service_packages"], PACKAGE_DIR)
    delta("no descriptor, explained", pkg["noted"])
    for p in pkg["noted"]:
        note(f"{p}: {PACKAGE_NOTES[p]}")
    if pkg["unregistered"]:
        delta("UNREGISTERED", pkg["unregistered"])
        note(f"A package under {PACKAGE_DIR} that no descriptor(...) names.")
        note("Register it, or add it to PACKAGE_NOTES with the reason it has none.")
    else:
        line("= every other package registered", "", "ADVISORY, no gate")
    w("")

    w("4. DOCS  (keyspace: docs/services slug)")
    line("matrix rows", c["matrix_rows"], csm.MATRIX_DOC)
    delta("facet rows (share a page)", docs["facet_rows"])
    line("= matrix pages", c["matrix_pages"])
    line("docs/services/*.md", c["doc_pages"])
    if docs["pages_without_row"] or docs["rows_without_page"]:
        delta("page with no row", docs["pages_without_row"])
        delta("row with no page", docs["rows_without_page"])
    else:
        line("= identical sets", "", "GATED by check_service_matrix.py --strict")
    if docs["facet_pages"]:
        note(f"facet_pages, row-exempt by registry: {_names(docs['facet_pages'])}")
    for d in docs["deferred"]:
        flag = "  <-- EXPIRING" if d in docs["deferred_expiring"] else ""
        note(f"deferred: {d['key']} until {d['by']}{flag}")
        note(f"          {d['reason']}")
    w("")

    w("5. README  (keyspace: service name, matched to matrix row labels)")
    line("category-table names", c["readme_category_names"], f"{README}, Supported Services")
    line("matrix rows they cover",
         c["matrix_rows"] - len(rd["missing_from_categories"]) - len(rd["missing_facet_rows"]))
    delta("row missing from README", rd["missing_from_categories"])
    delta("facet row, absent by design", rd["missing_facet_rows"])
    if rd["unmatched_category_names"]:
        delta("README name matching no row", rd["unmatched_category_names"], limit=4)
        note("Wording drift, a service not in the matrix, or a missing")
        note("README_NAME_ALIASES entry. Read it, do not assume.")
    note("ADVISORY, no gate: docs/contributing.md asks contributors for a README")
    note("row as well as a matrix row, but only the matrix row is machine-checked.")
    w("")
    line("detail-table rows", c["readme_detail_rows"], "editorial \"notable features\"")
    delta("documented service with no note", rd["missing_from_details"], limit=6)
    note("Deliberately ungated: the detail table is editorial, and a row per")
    note(f"service would buy {len(rd['missing_from_details'])} lines of filler prose.")
    w("")

    w("6. ACTION TABLES  (keyspace: services.yaml entry)")
    line("generated action tables", c["action_tables"], SERVICES_YAML)
    note(f"Opt-in by design, not a gap: {c['doc_pages'] - c['action_tables']} pages carry no")
    note("generated operation table. regen_action_docs.py owns this list.")
    w("")
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--json", action="store_true", help="emit the raw data instead of the report"
    )
    args = parser.parse_args(argv)

    try:
        result = reconcile(collect(_repo_root()))
    except (ValueError, KeyError, yaml.YAMLError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    print(json.dumps(result, indent=2, sort_keys=True) if args.json else render(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
