#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/partitions.json from AWS's published partition data.

Floci serves every AWS partition, and the Java side (`AwsPartitions`) reads one vendored
file for all of it: partition ids, DNS suffixes, region lists, the `<partition>-global`
pseudo-regions, which services each partition offers and which of them have a partition-wide
endpoint with its signing region. Nothing in that file is hand-typed; this script derives it
from two sources and a rule:

- botocore's `partitions.json` and `endpoints.json` (MIT, https://github.com/boto/botocore),
  the same data every AWS SDK resolves endpoints from. Located, in order, at `--botocore-data`,
  the `local/aws/botocore` checkout (the reference corpus everything else in this repo is
  grounded on), then the installed `botocore` package, which `requirements.txt` pins to the
  version the vendored file was generated from so CI regenerates the same bytes.
- `s3DualStackRegions` per partition: the regions whose S3 endpoint publishes a `dualstack`
  variant in `endpoints.json` (the service defaults cover every region in the commercial, China
  and GovCloud partitions; the ISO partitions list them per region and the EUSC one has none).
- botocore's per-service `service-2.json` metadata, for the services whose SigV4 signing name
  differs from their `endpoints.json` key (`ecr` signs what `api.ecr` publishes, `bedrock`
  covers `bedrock-runtime`). `signingNames` maps each such signing name to its endpoint
  prefixes so a request's credential scope can be checked against a partition's service list.
  A signing name is left out when a model whose own endpoint prefix is that signing name is
  listed in no partition (`execute-api` is Connect Participant's signing name, but also the
  endpoint prefix of API Gateway's WebSocket management API, which `endpoints.json` lists
  nowhere): the data cannot say where such a name is served.
- botocore's per-service `endpoint-rule-set-1.json`, for the partitions a ruleset names in an
  explicit branch that resolves to an endpoint (IAM's `iam.eusc-de-east-1.amazonaws.eu`).
  `endpoints.json` omits some of those, so each partition's `services` adds them, recorded again
  under `servicesFromRulesets`. Only a service `endpoints.json` lists in some partition is added:
  a service it lists nowhere stays unknown everywhere rather than present in a few partitions.
- The AWS CDK's `region-info/lib/aws-entities.ts` (Apache-2.0), whose ordered region list
  carries two rule markers: regions before `RULE_S3_WEBSITE_REGIONAL_SUBDOMAIN` use the
  legacy `s3-website-<region>` endpoint form, and commercial regions after
  `RULE_CLASSIC_PARTITION_BECOMES_OPT_IN` are opt-in (DescribeRegions omits them by default).
  botocore publishes neither fact. Read from `--cdk` or `local/aws/aws-cdk`; when the checkout
  is absent (CI), the two flags are carried over from the existing output file, and a region
  with nothing to carry is an error rather than a guess.

Run from anywhere in the repo:
    python3 tools/aws/regen_partitions.py            # rewrite the vendored file in place
    python3 tools/aws/regen_partitions.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import argparse
import gzip
import importlib
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/partitions.json"
LOCAL_BOTOCORE_DATA = REPO_ROOT / "local/aws/botocore/botocore/data"
LOCAL_CDK = REPO_ROOT / "local/aws/aws-cdk"
CDK_ENTITIES = "packages/aws-cdk-lib/region-info/lib/aws-entities.ts"

RULE_S3_WEBSITE = "RULE_S3_WEBSITE_REGIONAL_SUBDOMAIN"
RULE_OPT_IN = "RULE_CLASSIC_PARTITION_BECOMES_OPT_IN"
COMMERCIAL = "aws"

ENTITY_REGION_RE = re.compile(r"^\s*'([a-z0-9-]+)',")
ENTITY_RULE_RE = re.compile(r"^\s*(RULE_[A-Z0-9_]+),")


# --------------------------------------------------------------------------- #
# Sources
# --------------------------------------------------------------------------- #
def resolve_botocore_data(explicit: Path | None) -> tuple[Path, str]:
    """The botocore data directory to read and a provenance label for it."""
    if explicit is not None:
        return explicit, f"--botocore-data {explicit}"
    if (LOCAL_BOTOCORE_DATA / "partitions.json").exists():
        return LOCAL_BOTOCORE_DATA, f"botocore {local_botocore_version()} (local/aws/botocore)"
    try:
        botocore = importlib.import_module("botocore")
        data = Path(botocore.__file__).resolve().parent / "data"
        if (data / "partitions.json").exists():
            return data, f"botocore {botocore.__version__} (installed package)"
    except ImportError:
        pass
    raise SystemExit("error: no botocore data found; check out local/aws/botocore, pass --botocore-data, "
                     "or pip install -r tools/aws/requirements.txt")


def local_botocore_version() -> str:
    init = LOCAL_BOTOCORE_DATA.parent / "__init__.py"
    if init.exists():
        match = re.search(r"__version__\s*=\s*'([^']+)'", init.read_text(encoding="utf-8"))
        if match:
            return match.group(1)
    return "unknown"


def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def service_model(version_dir: Path) -> Path | None:
    """A version directory's `service-2.json`, plain as the botocore checkout has it or gzipped
    as the botocore wheel ships it, or None when the directory holds neither."""
    for name in ("service-2.json", "service-2.json.gz"):
        if (version_dir / name).is_file():
            return version_dir / name
    return None


def ruleset_file(version_dir: Path) -> Path | None:
    """A version directory's `endpoint-rule-set-1.json`, plain or gzipped, or None."""
    for name in ("endpoint-rule-set-1.json", "endpoint-rule-set-1.json.gz"):
        if (version_dir / name).is_file():
            return version_dir / name
    return None


def load_model(path: Path) -> dict:
    if path.suffix == ".gz":
        with gzip.open(path, "rt", encoding="utf-8") as handle:
            return json.load(handle)
    return load_json(path)


def parse_cdk_entities(text: str) -> list[str]:
    """The ordered `AWS_REGIONS_AND_RULES` entries: region ids and RULE_ markers, in file order."""
    entries: list[str] = []
    inside = False
    for line in text.splitlines():
        if "AWS_REGIONS_AND_RULES" in line and "=" in line and "[" in line:
            inside = True
            continue
        if not inside:
            continue
        if line.strip().startswith("];"):
            break
        region = ENTITY_REGION_RE.match(line)
        if region:
            entries.append(region.group(1))
            continue
        rule = ENTITY_RULE_RE.match(line)
        if rule:
            entries.append(rule.group(1))
    if RULE_S3_WEBSITE not in entries or RULE_OPT_IN not in entries:
        raise ValueError("aws-entities.ts: AWS_REGIONS_AND_RULES lacks the two rule markers")
    return entries


def cdk_flags(entries: list[str], region: str, partition: str) -> tuple[bool, bool]:
    """(optIn, s3WebsiteDashForm) exactly as CDK's generate-static-data.ts derives them.

    `before(region, rule)` is false for a region the list does not know, so an unlisted
    region is never dash-form and, in the commercial partition, is opt-in.
    """
    website_rule = entries.index(RULE_S3_WEBSITE)
    opt_in_rule = entries.index(RULE_OPT_IN)
    try:
        index = entries.index(region)
    except ValueError:
        index = -1
    before_website = index != -1 and index < website_rule
    before_opt_in = index != -1 and index < opt_in_rule
    opt_in = partition == COMMERCIAL and not before_opt_in
    return opt_in, before_website


# --------------------------------------------------------------------------- #
# Generation
# --------------------------------------------------------------------------- #
def collect_signing_names(botocore_data: Path) -> dict[str, list[str]]:
    """Signing name -> endpoint prefixes, for the services where the two differ."""
    by_signing_name: dict[str, set[str]] = {}
    for service_dir in sorted(p for p in botocore_data.iterdir() if p.is_dir()):
        versions = sorted(v for v in service_dir.iterdir() if service_model(v) is not None)
        if not versions:
            continue
        metadata = load_model(service_model(versions[-1])).get("metadata", {})
        endpoint_prefix = metadata.get("endpointPrefix")
        signing_name = metadata.get("signingName") or endpoint_prefix
        if endpoint_prefix and signing_name != endpoint_prefix:
            by_signing_name.setdefault(signing_name, set()).add(endpoint_prefix)
    return {name: sorted(prefixes) for name, prefixes in sorted(by_signing_name.items())}


def collect_self_signed_prefixes(botocore_data: Path) -> set[str]:
    """Endpoint prefixes of the services that sign with their own endpoint prefix."""
    prefixes: set[str] = set()
    for service_dir in sorted(p for p in botocore_data.iterdir() if p.is_dir()):
        versions = sorted(v for v in service_dir.iterdir() if service_model(v) is not None)
        if not versions:
            continue
        metadata = load_model(service_model(versions[-1])).get("metadata", {})
        endpoint_prefix = metadata.get("endpointPrefix")
        if endpoint_prefix and (metadata.get("signingName") or endpoint_prefix) == endpoint_prefix:
            prefixes.add(endpoint_prefix)
    return prefixes


def partition_name_condition(condition: dict) -> str | None:
    """The partition id a `stringEquals(getAttr(<partition>, "name"), "<id>")` condition tests."""
    if condition.get("fn") != "stringEquals":
        return None
    argv = condition.get("argv", [])
    if len(argv) != 2:
        return None
    for attribute, value in ((argv[0], argv[1]), (argv[1], argv[0])):
        if (isinstance(attribute, dict) and attribute.get("fn") == "getAttr"
                and attribute.get("argv", [None, None])[1] == "name" and isinstance(value, str)):
            return value
    return None


def resolves_to_endpoint(rule: dict) -> bool:
    if rule.get("type") == "endpoint":
        return True
    return rule.get("type") == "tree" and any(resolves_to_endpoint(child) for child in rule.get("rules", []))


def ruleset_partitions(rules: list[dict]) -> set[str]:
    """Partitions a ruleset names in a branch that resolves to an endpoint. A branch that only
    raises an error, a negated test and the generic `{PartitionResult#dnsSuffix}` template name
    no partition."""
    named: set[str] = set()
    for rule in rules:
        if resolves_to_endpoint(rule):
            for condition in rule.get("conditions", []):
                partition_id = partition_name_condition(condition)
                if partition_id:
                    named.add(partition_id)
        if rule.get("type") == "tree":
            named |= ruleset_partitions(rule.get("rules", []))
    return named


def collect_ruleset_partitions(botocore_data: Path) -> dict[str, list[str]]:
    """Endpoint prefix -> the partitions its endpoint ruleset names explicitly."""
    by_prefix: dict[str, set[str]] = {}
    for service_dir in sorted(p for p in botocore_data.iterdir() if p.is_dir()):
        versions = sorted(v for v in service_dir.iterdir()
                          if service_model(v) is not None and ruleset_file(v) is not None)
        if not versions:
            continue
        endpoint_prefix = load_model(service_model(versions[-1])).get("metadata", {}).get("endpointPrefix")
        named = ruleset_partitions(load_model(ruleset_file(versions[-1])).get("rules", []))
        if endpoint_prefix and named:
            by_prefix.setdefault(endpoint_prefix, set()).update(named)
    return {prefix: sorted(partitions) for prefix, partitions in sorted(by_prefix.items())}


def build(partitions_doc: dict, endpoints_doc: dict, entities: list[str] | None,
          carried: dict | None, provenance: str,
          signing_names: dict[str, list[str]] | None = None,
          rulesets: dict[str, list[str]] | None = None,
          self_signed: set[str] | None = None) -> dict:
    endpoint_partitions = {p["partition"]: p for p in endpoints_doc["partitions"]}
    listed_anywhere = {service for p in endpoints_doc["partitions"] for service in p.get("services", {})}
    if signing_names is not None and self_signed is not None:
        signing_names = {name: prefixes for name, prefixes in signing_names.items()
                         if name not in self_signed or name in listed_anywhere}
    carried_flags = {}
    for partition in (carried or {}).get("partitions", []):
        for region in partition.get("regions", []):
            carried_flags[region["id"]] = (bool(region.get("optIn")), bool(region.get("s3WebsiteDashForm")))

    result_partitions = []
    for meta in partitions_doc["partitions"]:
        partition_id = meta["id"]
        outputs = meta["outputs"]
        endpoints_partition = endpoint_partitions.get(partition_id)
        if endpoints_partition is None:
            raise ValueError(f"endpoints.json has no partition {partition_id}")
        pseudo = f"{partition_id}-global"
        descriptions = {}
        for source in (meta.get("regions", {}), endpoints_partition.get("regions", {})):
            for region_id, info in source.items():
                if region_id == pseudo:
                    continue
                descriptions.setdefault(region_id, info.get("description", ""))
        regions = []
        for region_id in sorted(descriptions):
            if entities is not None:
                opt_in, dash = cdk_flags(entities, region_id, partition_id)
            elif region_id in carried_flags:
                opt_in, dash = carried_flags[region_id]
            else:
                raise ValueError(f"{region_id}: no aws-cdk checkout to derive optIn/s3WebsiteDashForm from and "
                                 f"nothing to carry over from {OUTPUT.name}; run with local/aws/aws-cdk present")
            regions.append({
                "id": region_id,
                "description": descriptions[region_id],
                "optIn": opt_in,
                "s3WebsiteDashForm": dash,
            })
        services = endpoints_partition.get("services", {})
        s3 = services.get("s3", {})
        s3_defaults_dualstack = any("dualstack" in v.get("tags", []) for v in s3.get("defaults", {}).get("variants", []))
        s3_dualstack_regions = sorted(
            region_id for region_id, endpoint in s3.get("endpoints", {}).items()
            if region_id in descriptions and (s3_defaults_dualstack
                                              or any("dualstack" in v.get("tags", []) for v in endpoint.get("variants", []))))
        global_services = {}
        for service_key in sorted(services):
            service = services[service_key]
            partition_endpoint = service.get("partitionEndpoint")
            if not partition_endpoint:
                continue
            endpoint = service.get("endpoints", {}).get(partition_endpoint, {})
            hostname = endpoint.get("hostname") or (
                endpoints_partition["defaults"]["hostname"]
                .replace("{service}", service_key)
                .replace("{region}", partition_endpoint)
                .replace("{dnsSuffix}", outputs["dnsSuffix"]))
            signing_region = endpoint.get("credentialScope", {}).get("region") or outputs["implicitGlobalRegion"]
            global_services[service_key] = {
                "hostname": hostname,
                "signingRegion": signing_region,
                "regionalized": service.get("isRegionalized", True) is not False,
            }
        from_rulesets = sorted(
            prefix for prefix, partitions in (rulesets or {}).items()
            if partition_id in partitions and prefix in listed_anywhere and prefix not in services)
        result_partitions.append({
            "id": partition_id,
            "name": endpoints_partition.get("partitionName", partition_id),
            "dnsSuffix": outputs["dnsSuffix"],
            "dualStackDnsSuffix": outputs["dualStackDnsSuffix"],
            "implicitGlobalRegion": outputs["implicitGlobalRegion"],
            "regionRegex": meta["regionRegex"],
            "globalPseudoRegion": pseudo if pseudo in meta.get("regions", {}) else None,
            "supportsDualStack": bool(outputs.get("supportsDualStack", False)),
            "supportsFips": bool(outputs.get("supportsFIPS", False)),
            "regions": regions,
            "services": sorted(set(services) | set(from_rulesets)),
            "servicesFromRulesets": from_rulesets,
            "globalServices": global_services,
            "s3DualStackRegions": s3_dualstack_regions,
        })
    result_partitions.sort(key=lambda p: (p["id"] != COMMERCIAL, p["id"]))
    return {
        "_source": {
            "generator": "tools/aws/regen_partitions.py",
            "botocore": provenance,
            "partitionsJsonVersion": partitions_doc.get("version"),
            "endpointsJsonVersion": endpoints_doc.get("version"),
            "regionFlags": "aws-cdk region-info/lib/aws-entities.ts rules" if entities is not None
                           else "carried over from the previous file (no aws-cdk checkout)",
        },
        "signingNames": signing_names if signing_names is not None else (carried or {}).get("signingNames", {}),
        "partitions": result_partitions,
    }


def render(document: dict) -> str:
    return json.dumps(document, indent=2, ensure_ascii=False) + "\n"


def generate(botocore_data: Path, provenance: str, cdk_root: Path | None, existing: Path) -> dict:
    partitions_doc = load_json(botocore_data / "partitions.json")
    endpoints_doc = load_json(botocore_data / "endpoints.json")
    entities = None
    if cdk_root is not None and (cdk_root / CDK_ENTITIES).exists():
        entities = parse_cdk_entities((cdk_root / CDK_ENTITIES).read_text(encoding="utf-8"))
    carried = load_json(existing) if existing.exists() else None
    return build(partitions_doc, endpoints_doc, entities, carried, provenance, collect_signing_names(botocore_data),
                 collect_ruleset_partitions(botocore_data), collect_self_signed_prefixes(botocore_data))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 when the vendored file differs from a fresh generation")
    parser.add_argument("--botocore-data", type=Path, help="botocore data directory (partitions.json, endpoints.json)")
    parser.add_argument("--cdk", type=Path, default=LOCAL_CDK, help="aws-cdk checkout root (default: local/aws/aws-cdk)")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    botocore_data, provenance = resolve_botocore_data(args.botocore_data)
    document = generate(botocore_data, provenance, args.cdk, args.output)
    rendered = render(document)
    rel = args.output.relative_to(REPO_ROOT) if args.output.is_relative_to(REPO_ROOT) else args.output

    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if strip_source(current) != strip_source(rendered):
            previous = json.loads(current).get("_source", {}).get("botocore", "unknown") if current.strip() else "nothing"
            print(f"error: {rel} (generated from {previous}) differs from a fresh generation against {provenance}.")
            print("       Run 'make aws-data-sync' and commit the result; if the two botocore versions differ, "
                  "align tools/aws/requirements.txt with the checkout first.")
            return 1
        print(f"{rel} is up to date ({len(document['partitions'])} partitions, "
              f"{sum(len(p['regions']) for p in document['partitions'])} regions)")
        return 0

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")
    print(f"wrote {rel} from {provenance}: {len(document['partitions'])} partitions, "
          f"{sum(len(p['regions']) for p in document['partitions'])} regions")
    return 0


def strip_source(text: str) -> str:
    """The document without its provenance block, so the check compares data, not labels."""
    if not text.strip():
        return ""
    document = json.loads(text)
    document.pop("_source", None)
    return json.dumps(document, sort_keys=True)


if __name__ == "__main__":
    sys.exit(main())
