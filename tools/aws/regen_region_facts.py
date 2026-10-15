#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/region-facts.json from the AWS CDK, Terraform, botocore and user-guide sources.

AWS publishes a handful of per-region and per-partition constants only as prose tables (the
General Reference), never as SDK data. The two open-source projects that transcribe them, and the
China and GovCloud user guides for what neither transcribes, are the sources here, read from the
`local/aws` checkouts:

- Route 53 hosted zone ids for Classic and Application load balancers (one map, byte-identical
  in `terraform-provider-aws`), for Network load balancers (a different map), and for S3 static
  website endpoints: `internal/service/elb/hosted_zone_id_data_source.go`,
  `internal/service/elbv2/hosted_zone_id_data_source.go`, `internal/service/s3/hosted_zones.go`.
  The CDK's `ROUTE_53_BUCKET_WEBSITE_ZONE_IDS` (`region-info/build-tools/fact-tables.ts`) adds the
  ISO-F regions Terraform lacks; where the two disagree (af-south-1, eu-south-1) Terraform wins,
  because it cites the S3 website-endpoint table and the CDK values are the regular S3 zones.
- The CloudFront distribution hosted zone per partition:
  `internal/conns/awsclient.go` (`CloudFrontDistributionHostedZoneID`), cross-checked against the
  CDK's `aws-route53-targets/lib/cloudfront-target.ts` mapping.
- The SAML sign-on URL per partition: the CDK's `PARTITION_SAML_SIGN_ON_URL`.
- VPC endpoint service-name prefixes: the CDK's `aws-ec2/lib/vpc-endpoint.ts` exception table
  (China and the ISO/EUSC partitions reverse their DNS suffix, but only for the listed services)
  plus the `.cn` suffix exceptions; the per-partition `<reversed suffix>.vpce` prefix from
  `region-info/build-tools/generate-static-data.ts`.
- The management console host per partition, for `aws`, `aws-cn` and `aws-us-gov`: one sentence
  each from the user-guide snapshots under `aws-docs/partition-guides` (`CONSOLE_HOST_SENTENCES`).
  No source states it for the ISO or EUSC partitions, which get no field. The snapshots are these
  three pages, saved under the path after the arrow:
    https://docs.aws.amazon.com/govcloud-us/latest/UserGuide/govcloud-organizations.html -> gov/govcloud-organizations.md
    https://docs.amazonaws.cn/en_us/aws/latest/userguide/console.html -> cn/console.md
    https://docs.aws.amazon.com/govcloud-us/latest/UserGuide/configure-account.html -> gov/configure-account.md
  Either rendering parses: the page's text (what the `aws-refs` skill's docs driver extracts from
  the `.html`), or the Markdown AWS serves at the same URL with `.md` in place of `.html`, which
  writes the China host as a link.
- The suffix of a CloudFront distribution's domain name: `cloudfront.net` from the example the
  botocore CloudFront model gives for `Distribution$DomainName` and
  `DistributionSummary$DomainName`, and `cloudfront.cn` for `aws-cn` from the China guide's
  CloudFront page (`CLOUDFRONT_SUFFIX_SENTENCES`),
  https://docs.amazonaws.cn/en_us/aws/latest/userguide/cloudfront.html -> cn/cloudfront.md, whose
  Markdown rendering escapes the asterisk (`\\*.cloudfront.cn`). The other partitions have no
  CloudFront.
- The Route 53 hosted zone of an API Gateway regional endpoint, for the two GovCloud regions only:
  the GovCloud guide's API Gateway page (`APIGATEWAY_REGIONAL_ZONE_SENTENCE`),
  https://docs.aws.amazon.com/govcloud-us/latest/UserGuide/govcloud-abp.html -> gov/govcloud-abp.md,
  with the region
  resolved through the region descriptions in `partitions.json`. No source in `local/aws` gives
  the zone for any other region.

Nothing is hand-typed. When the checkouts are absent (CI), `--check` only verifies the vendored
file parses and keeps its shape; a regeneration needs `local/aws`.

Run from anywhere in the repo:
    python3 tools/aws/regen_region_facts.py            # rewrite the vendored file in place
    python3 tools/aws/regen_region_facts.py --check    # exit 1 when the vendored file is stale
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/region-facts.json"
PARTITIONS = REPO_ROOT / "src/main/resources/aws/partitions.json"
LOCAL_TERRAFORM = REPO_ROOT / "local/aws/terraform-provider-aws"
LOCAL_CDK = REPO_ROOT / "local/aws/aws-cdk"
LOCAL_GUIDES = REPO_ROOT / "local/aws/aws-docs/partition-guides"
LOCAL_BOTOCORE_DATA = REPO_ROOT / "local/aws/botocore/botocore/data"

TF_ELB = "internal/service/elb/hosted_zone_id_data_source.go"
TF_ELBV2 = "internal/service/elbv2/hosted_zone_id_data_source.go"
TF_S3 = "internal/service/s3/hosted_zones.go"
TF_CLIENT = "internal/conns/awsclient.go"
CDK_FACT_TABLES = "packages/aws-cdk-lib/region-info/build-tools/fact-tables.ts"
CDK_CLOUDFRONT_TARGET = "packages/aws-cdk-lib/aws-route53-targets/lib/cloudfront-target.ts"
CDK_VPC_ENDPOINT = "packages/aws-cdk-lib/aws-ec2/lib/vpc-endpoint.ts"
BOTOCORE_CLOUDFRONT = "cloudfront/2020-05-31/service-2.json"

GO_ROW_RE = re.compile(r'^\s*endpoints\.([A-Za-z0-9]+)RegionID:\s*"([A-Z0-9]+)",')
TS_ROW_RE = re.compile(r"^\s*'([a-z0-9-]+)':\s*'([A-Z0-9]+)',")
TS_ENUM_RE = re.compile(r"^\s*(\w+)\s*=\s*'([a-z-]+)',")
TS_PARTITION_ROW_RE = re.compile(r"^\s*\[Partition\.(\w+)\]:\s*'([^']+)',")
GO_CLOUDFRONT_RE = re.compile(r'endpoints\.(\w+)PartitionID\s*\{\s*\n\s*return "([A-Z0-9]+)"', re.MULTILINE)
GO_CLOUDFRONT_DEFAULT_RE = re.compile(r'\}\s*\n\s*return "([A-Z0-9]+)" // See https://docs\.aws\.amazon\.com')
CDK_CLOUDFRONT_RE = re.compile(r"\['([a-z-]+)'\]:\s*\{\s*zoneId:\s*'([A-Z0-9]+)'")
VPCE_LIST_RE = re.compile(r"'([a-z0-9-]+)':\s*\[([^\]]*)\]", re.DOTALL)
VPCE_CASE_RE = re.compile(r"((?:\s*case '[a-z0-9-]+':\n)+)\s*return '([a-z0-9.-]+)';")
# partition -> (guide page, the sentence that states the console host). The China guide spells the
# host two ways: `console.amazonaws.cn` on its console page (cn/console.md, and again in
# cn/accounts-and-credentials.md) and `console.www.amazonaws.cn` once (cn/managing-aws.md). The
# console page's own statement is the one vendored. The commercial host is taken from the GovCloud
# guide, which names it where it sends the reader back to the commercial Region. AWS's Markdown
# rendering of the China page writes the host as a link, `[https://host](https://host).`, where the
# page text has `https://host .`; the pattern takes both.
CONSOLE_HOST_SENTENCES = {
    "aws": ("gov/govcloud-organizations.md", re.compile(
        r"in the commercial Region, sign in to the Organizations console at https://([a-z0-9.-]+)/organizations")),
    "aws-cn": ("cn/console.md", re.compile(
        r"Management Console is available in both Chinese and English and can be accessed at \[?https://([a-z0-9.-]+)[\] ]")),
    "aws-us-gov": ("gov/configure-account.md", re.compile(
        r"Sign in to the AWS GovCloud \(US\) console and open the IAM console at https://([a-z0-9.-]+)/iam")),
}
# The commercial suffix is the one in the example distribution name of these two model members.
BOTOCORE_CLOUDFRONT_DOMAIN_SHAPES = ("Distribution", "DistributionSummary")
BOTOCORE_CLOUDFRONT_EXAMPLE_RE = re.compile(r"<code>d[0-9a-z]+\.(cloudfront\.[a-z]+)</code>")
CLOUDFRONT_SUFFIX_SENTENCES = {
    "aws-cn": ("cn/cloudfront.md", re.compile(
        r"You can.t use the default CloudFront domain, \\?\*\.(cloudfront\.[a-z]+), to serve content\.")),
}
# One sentence per region, each naming the region by the description partitions.json carries. The
# page writes "Route 53" with a no-break space.
APIGATEWAY_REGIONAL_ZONE_PAGE = "gov/govcloud-abp.md"
# The page states a zone for every region of this partition, so a run that finds fewer is refused.
APIGATEWAY_REGIONAL_ZONE_PARTITION = "aws-us-gov"
APIGATEWAY_REGIONAL_ZONE_SENTENCE = re.compile(
    r"The Amazon Route\s53 Hosted Zone ID for the regional endpoint in the (AWS GovCloud \([A-Za-z-]+\)) Region"
    r" is (Z[0-9A-Z]+)\.")
GUIDE_PAGES = sorted({page for page, _ in CONSOLE_HOST_SENTENCES.values()}
                     | {page for page, _ in CLOUDFRONT_SUFFIX_SENTENCES.values()}
                     | {APIGATEWAY_REGIONAL_ZONE_PAGE})
GO_PARTITION_IDS = {"AwsCn": "aws-cn", "AwsUsGov": "aws-us-gov", "AwsIso": "aws-iso", "AwsIsoB": "aws-iso-b",
                    "AwsIsoE": "aws-iso-e", "AwsIsoF": "aws-iso-f", "Aws": "aws"}


# --------------------------------------------------------------------------- #
# Parsers
# --------------------------------------------------------------------------- #
def region_id_from_go_constant(camel: str) -> str:
    """`UsGovEast1` -> `us-gov-east-1`; `ApSoutheast7` -> `ap-southeast-7`; `IlCentral1` -> `il-central-1`."""
    parts = re.findall(r"[A-Z][a-z]+|\d+", camel)
    return "-".join(part.lower() for part in parts)


def parse_go_map(text: str, name: str) -> dict[str, str]:
    """The rows of `var <name> = map[string]string{ ... }`, commented-out rows skipped."""
    start = text.index(f"var {name} = map[string]string{{")
    end = text.index("\n}", start)
    rows: dict[str, str] = {}
    for line in text[start:end].splitlines():
        match = GO_ROW_RE.match(line)
        if match:
            rows[region_id_from_go_constant(match.group(1))] = match.group(2)
    return rows


def parse_ts_table(text: str, name: str) -> dict[str, str]:
    """The `'region': 'value',` rows of `export const <name> ... = { ... };`."""
    start = text.index(f"export const {name}")
    end = text.index("\n};", start)
    rows: dict[str, str] = {}
    for line in text[start:end].splitlines():
        match = TS_ROW_RE.match(line)
        if match:
            rows[match.group(1)] = match.group(2)
    return rows


def parse_ts_partition_enum(text: str) -> dict[str, str]:
    start = text.index("enum Partition {")
    end = text.index("}", start)
    return {m.group(1): m.group(2) for m in (TS_ENUM_RE.match(l) for l in text[start:end].splitlines()) if m}


def parse_ts_partition_table(text: str, name: str, enum: dict[str, str]) -> dict[str, str]:
    start = text.index(f"export const {name}")
    end = text.index("\n};", start)
    rows: dict[str, str] = {}
    for line in text[start:end].splitlines():
        match = TS_PARTITION_ROW_RE.match(line)
        if match:
            rows[enum[match.group(1)]] = match.group(2)
    return rows


def parse_go_cloudfront_zones(text: str) -> dict[str, str]:
    zones = {GO_PARTITION_IDS[m.group(1)]: m.group(2) for m in GO_CLOUDFRONT_RE.finditer(text)}
    default = GO_CLOUDFRONT_DEFAULT_RE.search(text)
    if default is None:
        raise ValueError("awsclient.go: CloudFrontDistributionHostedZoneID default not found")
    zones["aws"] = default.group(1)
    return zones


def parse_cdk_cloudfront_zones(text: str) -> dict[str, str]:
    return {m.group(1): m.group(2) for m in CDK_CLOUDFRONT_RE.finditer(text)}


def parse_vpc_endpoint_exceptions(text: str) -> tuple[dict[str, list[str]], dict[str, str], dict[str, list[str]]]:
    """(prefix services per region, exception prefix per region, `.cn` suffix services per region)."""
    prefix_start = text.index("private getDefaultEndpointPrefix(")
    suffix_start = text.index("private getDefaultEndpointSuffix(")
    prefix_body = text[prefix_start:suffix_start]
    suffix_body = text[suffix_start:text.index("\n  }", suffix_start)]
    table_start = prefix_body.index("VPC_ENDPOINT_SERVICE_EXCEPTIONS")
    table_end = prefix_body.index("};", table_start)
    services = {region: sorted(s.strip().strip("'") for s in body.split(",") if s.strip())
                for region, body in VPCE_LIST_RE.findall(prefix_body[table_start:table_end])}
    prefixes: dict[str, str] = {}
    for cases, prefix in VPCE_CASE_RE.findall(prefix_body[table_end:]):
        for region in re.findall(r"case '([a-z0-9-]+)':", cases):
            prefixes[region] = prefix
    suffix_table_start = suffix_body.index("VPC_ENDPOINT_SERVICE_EXCEPTIONS")
    cn_suffix = {region: sorted(s.strip().strip("'") for s in body.split(",") if s.strip())
                 for region, body in VPCE_LIST_RE.findall(suffix_body[suffix_table_start:])}
    missing = set(services) - set(prefixes)
    if missing:
        raise ValueError(f"vpc-endpoint.ts: regions with exception services but no prefix: {sorted(missing)}")
    return services, prefixes, cn_suffix


def parse_guide_sentence(text: str, sentence: re.Pattern[str], page: str) -> str:
    """The one value `sentence` states in a guide page; a page that lost the sentence, or states two
    different values with it, is an error rather than a guess."""
    values = sorted({match.group(1).rstrip(".") for match in sentence.finditer(text)})
    if len(values) != 1:
        raise ValueError(f"{page}: expected one value from /{sentence.pattern}/, found {values}")
    return values[0]


def parse_guide_sentences(guides: Path, sentences: dict[str, tuple[str, re.Pattern[str]]]) -> dict[str, str]:
    return {partition: parse_guide_sentence((guides / page).read_text(encoding="utf-8"), sentence, page)
            for partition, (page, sentence) in sentences.items()}


def parse_console_hosts(guides: Path) -> dict[str, str]:
    return parse_guide_sentences(guides, CONSOLE_HOST_SENTENCES)


def parse_botocore_cloudfront_suffix(text: str) -> str:
    """The suffix of the example distribution name in the model's `DomainName` documentation."""
    shapes = json.loads(text)["shapes"]
    suffixes = set()
    for shape in BOTOCORE_CLOUDFRONT_DOMAIN_SHAPES:
        documentation = shapes[shape]["members"]["DomainName"].get("documentation", "")
        found = set(BOTOCORE_CLOUDFRONT_EXAMPLE_RE.findall(documentation))
        if len(found) != 1:
            raise ValueError(f"cloudfront model: {shape}$DomainName gives no single example distribution name: {found}")
        suffixes |= found
    if len(suffixes) != 1:
        raise ValueError(f"cloudfront model: the DomainName examples disagree on the suffix: {sorted(suffixes)}")
    return suffixes.pop()


def parse_cloudfront_suffixes(botocore_data: Path, guides: Path) -> dict[str, str]:
    suffixes = {"aws": parse_botocore_cloudfront_suffix((botocore_data / BOTOCORE_CLOUDFRONT).read_text(encoding="utf-8"))}
    suffixes.update(parse_guide_sentences(guides, CLOUDFRONT_SUFFIX_SENTENCES))
    return suffixes


def parse_apigateway_regional_zones(text: str, partitions_doc: dict) -> dict[str, str]:
    """region id -> hosted zone for every GovCloud region, from the GovCloud API Gateway page."""
    by_description = {r["description"]: r["id"] for p in partitions_doc["partitions"] for r in p["regions"]
                      if r.get("description")}
    zones: dict[str, str] = {}
    for description, zone in APIGATEWAY_REGIONAL_ZONE_SENTENCE.findall(text):
        region = by_description.get(description)
        if region is None:
            raise ValueError(f"{APIGATEWAY_REGIONAL_ZONE_PAGE}: {description!r} is not a region description "
                             "in partitions.json")
        if zones.setdefault(region, zone) != zone:
            raise ValueError(f"{APIGATEWAY_REGIONAL_ZONE_PAGE}: two regional hosted zones for {region}")
    if not zones:
        raise ValueError(f"{APIGATEWAY_REGIONAL_ZONE_PAGE}: no regional endpoint hosted zone sentence found")
    missing = sorted(apigateway_zone_regions(partitions_doc) - zones.keys())
    if missing:
        raise ValueError(f"{APIGATEWAY_REGIONAL_ZONE_PAGE}: no regional endpoint hosted zone for {', '.join(missing)}")
    return zones


def apigateway_zone_regions(partitions_doc: dict) -> set[str]:
    """The regions the GovCloud API Gateway page must state a regional hosted zone for."""
    return {region["id"] for partition in partitions_doc["partitions"]
            if partition["id"] == APIGATEWAY_REGIONAL_ZONE_PARTITION for region in partition["regions"]}


def guides_present(guides: Path) -> bool:
    return all((guides / page).exists() for page in GUIDE_PAGES)


# --------------------------------------------------------------------------- #
# Generation
# --------------------------------------------------------------------------- #
def build(terraform: Path, cdk: Path, guides: Path, botocore_data: Path, partitions_doc: dict) -> dict:
    elb = parse_go_map((terraform / TF_ELB).read_text(encoding="utf-8"), "hostedZoneIDPerRegionMap")
    elbv2_text = (terraform / TF_ELBV2).read_text(encoding="utf-8")
    alb = parse_go_map(elbv2_text, "hostedZoneIDPerRegionALBMap")
    nlb = parse_go_map(elbv2_text, "hostedZoneIDPerRegionNLBMap")
    s3_tf = parse_go_map((terraform / TF_S3).read_text(encoding="utf-8"), "hostedZoneIDsMap")
    fact_tables = (cdk / CDK_FACT_TABLES).read_text(encoding="utf-8")
    s3_cdk = parse_ts_table(fact_tables, "ROUTE_53_BUCKET_WEBSITE_ZONE_IDS")
    enum = parse_ts_partition_enum(fact_tables)
    saml = parse_ts_partition_table(fact_tables, "PARTITION_SAML_SIGN_ON_URL", enum)
    cloudfront_tf = parse_go_cloudfront_zones((terraform / TF_CLIENT).read_text(encoding="utf-8"))
    cloudfront_cdk = parse_cdk_cloudfront_zones((cdk / CDK_CLOUDFRONT_TARGET).read_text(encoding="utf-8"))
    if cloudfront_tf != cloudfront_cdk:
        raise ValueError(f"CloudFront hosted zones disagree: terraform {cloudfront_tf} vs cdk {cloudfront_cdk}")
    vpce_services, vpce_prefixes, vpce_cn_suffix = parse_vpc_endpoint_exceptions(
        (cdk / CDK_VPC_ENDPOINT).read_text(encoding="utf-8"))
    if elb != alb:
        raise ValueError("the Classic and ALB hosted-zone maps differ; the generator assumed they are one table")
    console_hosts = parse_console_hosts(guides)
    cloudfront_suffixes = parse_cloudfront_suffixes(botocore_data, guides)
    apigateway_zones = parse_apigateway_regional_zones(
        (guides / APIGATEWAY_REGIONAL_ZONE_PAGE).read_text(encoding="utf-8"), partitions_doc)

    partition_ids = [p["id"] for p in partitions_doc["partitions"]]
    region_partition = {r["id"]: p for p in partitions_doc["partitions"] for r in p["regions"]}
    dns_suffix = {p["id"]: p["dnsSuffix"] for p in partitions_doc["partitions"]}

    s3_website = dict(s3_cdk)
    s3_website.update(s3_tf)  # Terraform cites the website-endpoint table; it wins on conflicts
    region_ids = sorted(set(alb) | set(nlb) | set(s3_website) | set(vpce_services) | set(apigateway_zones))
    regions = {}
    for region in region_ids:
        if region not in region_partition:
            raise ValueError(f"{region}: named by a source table but not a published region in partitions.json")
        entry = {}
        if region in alb:
            entry["albHostedZoneId"] = alb[region]
            entry["classicElbHostedZoneId"] = elb[region]
        if region in nlb:
            entry["nlbHostedZoneId"] = nlb[region]
        if region in s3_website:
            entry["s3WebsiteHostedZoneId"] = s3_website[region]
        if region in vpce_services:
            entry["vpcEndpointExceptionPrefix"] = vpce_prefixes[region]
            entry["vpcEndpointPrefixServices"] = vpce_services[region]
        if region in vpce_cn_suffix:
            entry["vpcEndpointCnSuffixServices"] = vpce_cn_suffix[region]
        if region in apigateway_zones:
            entry["apiGatewayRegionalHostedZoneId"] = apigateway_zones[region]
        regions[region] = entry

    partitions = {}
    for partition_id in partition_ids:
        entry = {"vpcEndpointServiceNamePrefix": ".".join(reversed(dns_suffix[partition_id].split("."))) + ".vpce"}
        if partition_id in cloudfront_tf:
            entry["cloudfrontHostedZoneId"] = cloudfront_tf[partition_id]
        if partition_id in saml:
            entry["samlSignOnUrl"] = saml[partition_id]
        if partition_id in console_hosts:
            entry["consoleHost"] = console_hosts[partition_id]
        if partition_id in cloudfront_suffixes:
            entry["cloudfrontDomainSuffix"] = cloudfront_suffixes[partition_id]
        partitions[partition_id] = entry

    return {
        "_source": {
            "generator": "tools/aws/regen_region_facts.py",
            "terraform": f"terraform-provider-aws {TF_ELB}, {TF_ELBV2}, {TF_S3}, {TF_CLIENT}",
            "cdk": f"aws-cdk {CDK_FACT_TABLES}, {CDK_CLOUDFRONT_TARGET}, {CDK_VPC_ENDPOINT}",
            "docs": "aws-docs/partition-guides (snapshots of the China and GovCloud user guides) " + ", ".join(GUIDE_PAGES),
            "botocore": f"botocore {BOTOCORE_CLOUDFRONT} (Distribution$DomainName, DistributionSummary$DomainName)",
            "s3WebsiteConflicts": "terraform wins over the CDK where they disagree (it cites the S3 website-endpoint table)",
        },
        "partitions": partitions,
        "regions": regions,
    }


def render(document: dict) -> str:
    return json.dumps(document, indent=2, ensure_ascii=False) + "\n"


def strip_source(text: str) -> str:
    if not text.strip():
        return ""
    document = json.loads(text)
    document.pop("_source", None)
    return json.dumps(document, sort_keys=True)


HOSTED_ZONE_ID_RE = re.compile(r"Z[0-9A-Z]{9,31}")
SAML_URL_RE = re.compile(r"https://[a-z0-9.-]+/saml")
VPCE_PREFIX_RE = re.compile(r"[a-z0-9-]+(\.[a-z0-9-]+)*\.vpce")
REVERSED_DNS_RE = re.compile(r"[a-z0-9-]+(\.[a-z0-9-]+)+")
CONSOLE_HOST_RE = re.compile(r"console(\.[a-z0-9-]+){2,}")
CLOUDFRONT_SUFFIX_RE = re.compile(r"cloudfront\.[a-z]+")


def self_check(path: Path, partitions_path: Path = PARTITIONS) -> list[str]:
    """Problems with the vendored file that need no sources: every partition partitions.json
    publishes has an entry, its VPC endpoint prefix is exactly the reversed DNS suffix, every region
    is a published one, and every other value has the shape of what it claims to be. It cannot tell
    a stale value from a current one; only a regeneration against local/aws can."""
    problems: list[str] = []
    if not path.exists():
        return [f"{path} is missing"]
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        return [f"{path}: not valid JSON ({e})"]
    for key in ("partitions", "regions"):
        if not isinstance(document.get(key), dict) or not document[key]:
            problems.append(f"{path}: '{key}' is missing or empty")
    if problems:
        return problems
    for key in ("partitions", "regions"):
        for name, entry in document[key].items():
            if not isinstance(entry, dict):
                problems.append(f"{path}: {key}.{name} is not an object: {entry!r}")
    if problems:
        return problems

    partitions_doc = json.loads(partitions_path.read_text(encoding="utf-8"))["partitions"]
    for partition in partitions_doc:
        entry = document["partitions"].get(partition["id"])
        if entry is None:
            problems.append(f"{path}: partition {partition['id']} has no entry")
            continue
        expected = ".".join(reversed(partition["dnsSuffix"].split("."))) + ".vpce"
        if entry.get("vpcEndpointServiceNamePrefix") != expected:
            problems.append(f"{path}: {partition['id']}.vpcEndpointServiceNamePrefix should be {expected!r}")
        if partition["id"] in CONSOLE_HOST_SENTENCES and "consoleHost" not in entry:
            problems.append(f"{path}: {partition['id']}.consoleHost is missing")
        if partition["id"] in {"aws"} | CLOUDFRONT_SUFFIX_SENTENCES.keys() and "cloudfrontDomainSuffix" not in entry:
            problems.append(f"{path}: {partition['id']}.cloudfrontDomainSuffix is missing")
    for region in sorted(apigateway_zone_regions({"partitions": partitions_doc})):
        if "apiGatewayRegionalHostedZoneId" not in document["regions"].get(region, {}):
            problems.append(f"{path}: {region}.apiGatewayRegionalHostedZoneId is missing")
    known = {region["id"] for partition in partitions_doc for region in partition["regions"]}
    for region in document["regions"]:
        if region not in known:
            problems.append(f"{path}: region {region} is not published in partitions.json")

    for partition, entry in document["partitions"].items():
        for key, value in entry.items():
            pattern = {"cloudfrontHostedZoneId": HOSTED_ZONE_ID_RE, "samlSignOnUrl": SAML_URL_RE,
                       "vpcEndpointServiceNamePrefix": VPCE_PREFIX_RE, "consoleHost": CONSOLE_HOST_RE,
                       "cloudfrontDomainSuffix": CLOUDFRONT_SUFFIX_RE}.get(key)
            if pattern is None:
                problems.append(f"{path}: {partition}.{key} is not a known field")
            elif not isinstance(value, str) or not pattern.fullmatch(value):
                problems.append(f"{path}: {partition}.{key} has an unexpected shape: {value!r}")
    for region, entry in document["regions"].items():
        for key, value in entry.items():
            if key.endswith("HostedZoneId"):
                if not isinstance(value, str) or not HOSTED_ZONE_ID_RE.fullmatch(value):
                    problems.append(f"{path}: {region}.{key} is not a hosted zone id: {value!r}")
            elif key == "vpcEndpointExceptionPrefix":
                if not isinstance(value, str) or not REVERSED_DNS_RE.fullmatch(value):
                    problems.append(f"{path}: {region}.{key} is not a reversed DNS prefix: {value!r}")
            elif key in ("vpcEndpointPrefixServices", "vpcEndpointCnSuffixServices"):
                if not isinstance(value, list) or not all(isinstance(v, str) and v for v in value):
                    problems.append(f"{path}: {region}.{key} is not a list of service names")
            else:
                problems.append(f"{path}: {region}.{key} is not a known field")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 when the vendored file differs from a fresh generation")
    parser.add_argument("--terraform", type=Path, default=LOCAL_TERRAFORM, help="terraform-provider-aws checkout")
    parser.add_argument("--cdk", type=Path, default=LOCAL_CDK, help="aws-cdk checkout")
    parser.add_argument("--guides", type=Path, default=LOCAL_GUIDES, help="aws-docs/partition-guides snapshots")
    parser.add_argument("--botocore-data", type=Path, default=LOCAL_BOTOCORE_DATA, help="botocore data directory")
    parser.add_argument("--partitions", type=Path, default=PARTITIONS, help=argparse.SUPPRESS)
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    rel = args.output.relative_to(REPO_ROOT) if args.output.is_relative_to(REPO_ROOT) else args.output

    sources_present = ((args.terraform / TF_ELB).exists() and (args.cdk / CDK_FACT_TABLES).exists()
                       and guides_present(args.guides) and (args.botocore_data / BOTOCORE_CLOUDFRONT).exists())
    if not sources_present:
        if args.check:
            problems = self_check(args.output, args.partitions)
            for problem in problems:
                print(f"error: {problem}")
            if problems:
                return 1
            print(f"{rel}: sources not checked out, shape verified only")
            return 0
        raise SystemExit("error: regenerating needs the terraform-provider-aws, aws-cdk and botocore checkouts "
                         "and the aws-docs/partition-guides snapshots under local/aws")

    document = build(args.terraform, args.cdk, args.guides, args.botocore_data,
                     json.loads(args.partitions.read_text(encoding="utf-8")))
    rendered = render(document)
    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if strip_source(current) != strip_source(rendered):
            print(f"error: {rel} differs from a fresh generation. Run 'make aws-data-sync' and commit the result.")
            return 1
        print(f"{rel} is up to date ({len(document['regions'])} regions, {len(document['partitions'])} partitions)")
        return 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")
    print(f"wrote {rel}: {len(document['regions'])} regions, {len(document['partitions'])} partitions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
