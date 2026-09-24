"""Tests for regen_partitions.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

import regen_partitions as r


ENTITIES = """
export const AWS_REGIONS_AND_RULES: readonly (string | symbol)[] = [
  'us-east-1', // US East (N. Virginia)
  'us-gov-west-1', // AWS GovCloud (US-West)
  RULE_S3_WEBSITE_REGIONAL_SUBDOMAIN,
  'cn-north-1', // China (Beijing)
  'eu-north-1', // Europe (Stockholm)
  RULE_CLASSIC_PARTITION_BECOMES_OPT_IN,
  'ap-east-1', // Asia Pacific (Hong Kong)
  'cn-northwest-1', // China (Ningxia)
];

export const AWS_REGIONS = AWS_REGIONS_AND_RULES.filter((x) => typeof x === 'string').sort();
"""

PARTITIONS = {
    "partitions": [
        {
            "id": "aws-cn",
            "outputs": {"dnsSuffix": "amazonaws.com.cn", "dualStackDnsSuffix": "api.amazonwebservices.com.cn",
                        "implicitGlobalRegion": "cn-northwest-1", "name": "aws-cn",
                        "supportsDualStack": True, "supportsFIPS": True},
            "regionRegex": "^cn\\-\\w+\\-\\d+$",
            "regions": {"aws-cn-global": {"description": "aws-cn global region"},
                        "cn-north-1": {"description": "China (Beijing)"},
                        "cn-northwest-1": {"description": "China (Ningxia)"}},
        },
        {
            "id": "aws",
            "outputs": {"dnsSuffix": "amazonaws.com", "dualStackDnsSuffix": "api.aws",
                        "implicitGlobalRegion": "us-east-1", "name": "aws",
                        "supportsDualStack": True, "supportsFIPS": True},
            "regionRegex": "^(us|eu|ap)\\-\\w+\\-\\d+$",
            "regions": {"aws-global": {"description": "aws global region"},
                        "us-east-1": {"description": "US East (N. Virginia)"},
                        "eu-north-1": {"description": "Europe (Stockholm)"}},
        },
        {
            "id": "aws-eusc",
            "outputs": {"dnsSuffix": "amazonaws.eu", "dualStackDnsSuffix": "api.amazonwebservices.eu",
                        "implicitGlobalRegion": "eusc-de-east-1", "name": "aws-eusc",
                        "supportsDualStack": True, "supportsFIPS": True},
            "regionRegex": "^eusc\\-(de)\\-\\w+\\-\\d+$",
            "regions": {"eusc-de-east-1": {"description": "EU (Germany)"}},
        },
    ],
    "version": "1.1",
}

ENDPOINTS = {
    "partitions": [
        {
            "partition": "aws",
            "partitionName": "AWS Standard",
            "dnsSuffix": "amazonaws.com",
            "defaults": {"hostname": "{service}.{region}.{dnsSuffix}"},
            "regions": {"us-east-1": {"description": "US East (N. Virginia)"},
                        "eu-north-1": {"description": "Europe (Stockholm)"},
                        "ap-east-1": {"description": "Asia Pacific (Hong Kong)"}},
            "services": {
                "iam": {"endpoints": {"aws-global": {"credentialScope": {"region": "us-east-1"},
                                                     "hostname": "iam.amazonaws.com"}},
                        "isRegionalized": False, "partitionEndpoint": "aws-global"},
                "sts": {"endpoints": {"aws-global": {"credentialScope": {"region": "us-east-1"},
                                                     "hostname": "sts.amazonaws.com"},
                                      "us-east-1": {}},
                        "partitionEndpoint": "aws-global"},
                "codecatalyst": {"endpoints": {"aws-global": {"hostname": "codecatalyst.global.api.aws"}},
                                 "isRegionalized": False, "partitionEndpoint": "aws-global"},
                "nohost": {"endpoints": {"aws-global": {"credentialScope": {"region": "us-east-1"}}},
                           "isRegionalized": False, "partitionEndpoint": "aws-global"},
                "sqs": {"endpoints": {"us-east-1": {}}},
            },
        },
        {
            "partition": "aws-cn",
            "partitionName": "AWS China",
            "dnsSuffix": "amazonaws.com.cn",
            "defaults": {"hostname": "{service}.{region}.{dnsSuffix}"},
            "regions": {"cn-north-1": {"description": "China (Beijing)"},
                        "cn-northwest-1": {"description": "China (Ningxia)"}},
            "services": {
                "iam": {"endpoints": {"aws-cn-global": {"credentialScope": {"region": "cn-north-1"},
                                                        "hostname": "iam.cn-north-1.amazonaws.com.cn"}},
                        "isRegionalized": False, "partitionEndpoint": "aws-cn-global"},
                "sqs": {"endpoints": {"cn-north-1": {}}},
            },
        },
        {
            "partition": "aws-eusc",
            "partitionName": "AWS EUSC",
            "dnsSuffix": "amazonaws.eu",
            "defaults": {"hostname": "{service}.{region}.{dnsSuffix}"},
            "regions": {"eusc-de-east-1": {"description": "EU (Germany)"}},
            "services": {"sqs": {"endpoints": {"eusc-de-east-1": {}}}},
        },
    ],
    "version": 3,
}


def by_id(document: dict, partition_id: str) -> dict:
    return next(p for p in document["partitions"] if p["id"] == partition_id)


def region(partition: dict, region_id: str) -> dict:
    return next(x for x in partition["regions"] if x["id"] == region_id)


# --------------------------------------------------------------------------- #
# CDK rules
# --------------------------------------------------------------------------- #
def test_parse_cdk_entities_keeps_file_order_with_rule_markers():
    assert r.parse_cdk_entities(ENTITIES) == [
        "us-east-1", "us-gov-west-1", r.RULE_S3_WEBSITE, "cn-north-1", "eu-north-1",
        r.RULE_OPT_IN, "ap-east-1", "cn-northwest-1",
    ]


def test_parse_cdk_entities_requires_both_rules():
    with pytest.raises(ValueError, match="rule markers"):
        r.parse_cdk_entities("export const AWS_REGIONS_AND_RULES = [\n  'us-east-1',\n];\n")


@pytest.mark.parametrize("region_id,partition,opt_in,dash", [
    ("us-east-1", "aws", False, True),
    ("us-gov-west-1", "aws-us-gov", False, True),
    ("cn-north-1", "aws-cn", False, False),
    ("eu-north-1", "aws", False, False),
    ("ap-east-1", "aws", True, False),
    ("cn-northwest-1", "aws-cn", False, False),
    ("eu-nowhere-9", "aws", True, False),
    ("cn-nowhere-9", "aws-cn", False, False),
])
def test_cdk_flags_follow_the_two_rules(region_id, partition, opt_in, dash):
    entries = r.parse_cdk_entities(ENTITIES)
    assert r.cdk_flags(entries, region_id, partition) == (opt_in, dash)


# --------------------------------------------------------------------------- #
# Build
# --------------------------------------------------------------------------- #
def test_build_puts_the_commercial_partition_first_then_sorts_by_id():
    document = r.build(PARTITIONS, ENDPOINTS, r.parse_cdk_entities(ENTITIES), None, "test")
    assert [p["id"] for p in document["partitions"]] == ["aws", "aws-cn", "aws-eusc"]


def test_build_unions_regions_from_both_files_and_excludes_the_pseudo_region():
    document = r.build(PARTITIONS, ENDPOINTS, r.parse_cdk_entities(ENTITIES), None, "test")
    aws = by_id(document, "aws")
    assert [x["id"] for x in aws["regions"]] == ["ap-east-1", "eu-north-1", "us-east-1"]
    assert region(aws, "ap-east-1")["description"] == "Asia Pacific (Hong Kong)"
    assert aws["globalPseudoRegion"] == "aws-global"
    assert by_id(document, "aws-eusc")["globalPseudoRegion"] is None


def test_build_carries_partition_outputs_and_names():
    document = r.build(PARTITIONS, ENDPOINTS, r.parse_cdk_entities(ENTITIES), None, "test")
    cn = by_id(document, "aws-cn")
    assert cn["name"] == "AWS China"
    assert cn["dnsSuffix"] == "amazonaws.com.cn"
    assert cn["dualStackDnsSuffix"] == "api.amazonwebservices.com.cn"
    assert cn["implicitGlobalRegion"] == "cn-northwest-1"
    assert cn["regionRegex"] == "^cn\\-\\w+\\-\\d+$"
    assert cn["supportsDualStack"] is True and cn["supportsFips"] is True
    assert cn["services"] == ["iam", "sqs"]


def test_build_derives_global_services_from_partition_endpoint():
    document = r.build(PARTITIONS, ENDPOINTS, r.parse_cdk_entities(ENTITIES), None, "test")
    aws = by_id(document, "aws")["globalServices"]
    assert aws["iam"] == {"hostname": "iam.amazonaws.com", "signingRegion": "us-east-1", "regionalized": False}
    # isRegionalized absent means regional endpoints exist beside the global host (STS)
    assert aws["sts"] == {"hostname": "sts.amazonaws.com", "signingRegion": "us-east-1", "regionalized": True}
    # no credentialScope: the partition's implicit global region signs
    assert aws["codecatalyst"]["signingRegion"] == "us-east-1"
    # no hostname: the partition's default template with the endpoint key as region
    assert aws["nohost"]["hostname"] == "nohost.aws-global.amazonaws.com"
    assert "sqs" not in aws
    assert by_id(document, "aws-cn")["globalServices"]["iam"]["signingRegion"] == "cn-north-1"
    assert by_id(document, "aws-eusc")["globalServices"] == {}


def test_build_applies_opt_in_and_dash_form_flags():
    document = r.build(PARTITIONS, ENDPOINTS, r.parse_cdk_entities(ENTITIES), None, "test")
    aws = by_id(document, "aws")
    assert region(aws, "us-east-1") == {"id": "us-east-1", "description": "US East (N. Virginia)",
                                        "optIn": False, "s3WebsiteDashForm": True}
    assert region(aws, "ap-east-1")["optIn"] is True
    assert region(by_id(document, "aws-cn"), "cn-north-1")["optIn"] is False


def test_build_without_cdk_carries_flags_from_the_previous_file():
    with_cdk = r.build(PARTITIONS, ENDPOINTS, r.parse_cdk_entities(ENTITIES), None, "test")
    carried = r.build(PARTITIONS, ENDPOINTS, None, with_cdk, "test")
    assert r.strip_source(r.render(carried)) == r.strip_source(r.render(with_cdk))
    assert "carried over" in carried["_source"]["regionFlags"]


def test_build_without_cdk_refuses_a_region_it_cannot_carry():
    with pytest.raises(ValueError, match="no aws-cdk checkout"):
        r.build(PARTITIONS, ENDPOINTS, None, {"partitions": []}, "test")


def test_build_refuses_a_partition_missing_from_endpoints_json():
    endpoints = {"partitions": [p for p in ENDPOINTS["partitions"] if p["partition"] != "aws-eusc"], "version": 3}
    with pytest.raises(ValueError, match="aws-eusc"):
        r.build(PARTITIONS, endpoints, r.parse_cdk_entities(ENTITIES), None, "test")


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def write_sources(tmp_path: Path) -> tuple[Path, Path]:
    data = tmp_path / "botocore-data"
    data.mkdir()
    (data / "partitions.json").write_text(json.dumps(PARTITIONS))
    (data / "endpoints.json").write_text(json.dumps(ENDPOINTS))
    cdk = tmp_path / "cdk"
    entities = cdk / r.CDK_ENTITIES
    entities.parent.mkdir(parents=True)
    entities.write_text(ENTITIES)
    return data, cdk


def test_cli_writes_then_reports_up_to_date_then_detects_drift(tmp_path, capsys):
    data, cdk = write_sources(tmp_path)
    output = tmp_path / "out" / "partitions.json"
    common = ["--botocore-data", str(data), "--cdk", str(cdk), "--output", str(output)]

    assert r.main(["--check"] + common) == 1
    assert "differs from a fresh generation" in capsys.readouterr().out

    assert r.main(common) == 0
    assert "3 partitions, 6 regions" in capsys.readouterr().out
    document = json.loads(output.read_text())
    assert document["_source"]["generator"] == "tools/aws/regen_partitions.py"
    assert document["_source"]["partitionsJsonVersion"] == "1.1"
    assert document["_source"]["endpointsJsonVersion"] == 3

    assert r.main(["--check"] + common) == 0
    assert "is up to date" in capsys.readouterr().out

    (data / "endpoints.json").write_text(json.dumps({
        **ENDPOINTS,
        "partitions": [dict(p, partitionName="renamed") if p["partition"] == "aws" else p for p in ENDPOINTS["partitions"]],
    }))
    assert r.main(["--check"] + common) == 1
    assert "make aws-data-sync" in capsys.readouterr().out


def test_cli_check_ignores_provenance_differences(tmp_path):
    data, cdk = write_sources(tmp_path)
    output = tmp_path / "partitions.json"
    common = ["--botocore-data", str(data), "--cdk", str(cdk), "--output", str(output)]
    assert r.main(common) == 0
    document = json.loads(output.read_text())
    document["_source"]["botocore"] = "some other label"
    output.write_text(json.dumps(document))
    assert r.main(["--check"] + common) == 0


def test_repo_file_matches_the_local_checkout_when_present():
    if not (r.LOCAL_BOTOCORE_DATA / "partitions.json").exists() or not (r.LOCAL_CDK / r.CDK_ENTITIES).exists():
        pytest.skip("local/aws checkouts not present")
    document = r.generate(r.LOCAL_BOTOCORE_DATA, "test", r.LOCAL_CDK, r.OUTPUT)
    assert r.strip_source(r.render(document)) == r.strip_source(r.OUTPUT.read_text(encoding="utf-8"))
