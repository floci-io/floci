"""Tests for regen_region_facts.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

import regen_region_facts as r


GO_ELB = '''
// See https://docs.aws.amazon.com/general/latest/gr/elb.html#elb_region.
var hostedZoneIDPerRegionMap = map[string]string{
	endpoints.UsEast1RegionID:      "Z35SXDOTRQ7X7K",
	endpoints.UsGovEast1RegionID:   "Z166TLBEWOO7G0",
	endpoints.CnNorthwest1RegionID: "ZM7IZAIOVVDZF",
	// endpoints.MxCentral1RegionID: "",
}
'''
GO_ELBV2 = GO_ELB.replace("hostedZoneIDPerRegionMap", "hostedZoneIDPerRegionALBMap") + '''
var hostedZoneIDPerRegionNLBMap = map[string]string{
	endpoints.UsEast1RegionID:      "Z26RNL4JYFTOTI",
	endpoints.UsGovEast1RegionID:   "Z1ZSMQQ6Q24QQ8",
	endpoints.CnNorthwest1RegionID: "ZQEIKTCZ8352D",
}
'''
GO_S3 = '''
var hostedZoneIDsMap = map[string]string{
	endpoints.UsEast1RegionID:      "Z3AQBSTGFYJSTF",
	endpoints.AfSouth1RegionID:     "Z83WF9RJE8B12",
}
'''
GO_CLIENT = '''
func (c *AWSClient) CloudFrontDistributionHostedZoneID(ctx context.Context) string {
	if c.Partition(ctx) == endpoints.AwsCnPartitionID {
		return "Z3RFFRIM2A3IF5" // See https://docs.amazonaws.cn/en_us/aws/latest/userguide/route53.html
	}
	return "Z2FDTNDATAQYW2" // See https://docs.aws.amazon.com/Route53/latest/APIReference/API_AliasTarget.html
}
'''
TS_FACTS = '''
export const ROUTE_53_BUCKET_WEBSITE_ZONE_IDS: { [region: string]: string } = {
  'af-south-1': 'Z11KHD8FBVPUYU',
  'us-isof-south-1': 'Z03376072I8GXC2DXUFXI',
};

enum Partition {
  Default = 'aws',
  Cn = 'aws-cn',
  UsGov = 'aws-us-gov',
  UsIso = 'aws-iso',
  UsIsoB = 'aws-iso-b',
  UsIsoF = 'aws-iso-f',
  EuIsoE = 'aws-iso-e',
  Eusc = 'aws-eusc',
}

export const PARTITION_SAML_SIGN_ON_URL: Partial<Record<Partition, string>> = {
  [Partition.Default]: 'https://signin.aws.amazon.com/saml',
  [Partition.Cn]: 'https://signin.amazonaws.cn/saml',
};
'''
TS_CLOUDFRONT = '''
        mapping: {
          ['aws']: {
            zoneId: 'Z2FDTNDATAQYW2', // docs
          },
          ['aws-cn']: {
            zoneId: 'Z3RFFRIM2A3IF5', // docs
          },
        },
'''
TS_VPCE = '''
  private getDefaultEndpointPrefix(name: string, region: string) {
    const VPC_ENDPOINT_SERVICE_EXCEPTIONS: { [region: string]: string[] } = {
      'cn-northwest-1': ['s3', 'ecr.api',
        'sts'],
      'us-isof-south-1': ['ebs', 'ecr.api'],
    };
    if (VPC_ENDPOINT_SERVICE_EXCEPTIONS[region]?.includes(name)) {
      switch (region) {
        case 'us-isof-south-1':
        case 'us-isof-east-1':
          return 'gov.ic.hci.csp';
        case 'cn-north-1':
        case 'cn-northwest-1':
          return 'cn.com.amazonaws';
      }
    }
    return 'com.amazonaws';
  }

  private getDefaultEndpointSuffix(name: string, region: string) {
    const VPC_ENDPOINT_SERVICE_EXCEPTIONS: { [region: string]: string[] } = {
      'cn-north-1': ['transcribe'],
      'cn-northwest-1': ['transcribe'],
    };
    return VPC_ENDPOINT_SERVICE_EXCEPTIONS[region]?.includes(name) ? '.cn' : '';
  }
'''
PARTITIONS = {"partitions": [
    {"id": "aws", "dnsSuffix": "amazonaws.com", "regions": [{"id": "us-east-1"}, {"id": "af-south-1"}]},
    {"id": "aws-cn", "dnsSuffix": "amazonaws.com.cn", "regions": [{"id": "cn-north-1"}, {"id": "cn-northwest-1"}]},
    {"id": "aws-us-gov", "dnsSuffix": "amazonaws.com", "regions": [{"id": "us-gov-east-1"}]},
    {"id": "aws-iso-f", "dnsSuffix": "csp.hci.ic.gov", "regions": [{"id": "us-isof-south-1"}]},
]}


def write_sources(tmp_path: Path) -> tuple[Path, Path]:
    tf = tmp_path / "terraform"
    cdk = tmp_path / "cdk"
    for root, rel, body in [
        (tf, r.TF_ELB, GO_ELB), (tf, r.TF_ELBV2, GO_ELBV2), (tf, r.TF_S3, GO_S3), (tf, r.TF_CLIENT, GO_CLIENT),
        (cdk, r.CDK_FACT_TABLES, TS_FACTS), (cdk, r.CDK_CLOUDFRONT_TARGET, TS_CLOUDFRONT), (cdk, r.CDK_VPC_ENDPOINT, TS_VPCE),
    ]:
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)
    return tf, cdk


@pytest.mark.parametrize("camel,region", [
    ("UsEast1", "us-east-1"), ("UsGovEast1", "us-gov-east-1"), ("CnNorthwest1", "cn-northwest-1"),
    ("IlCentral1", "il-central-1"), ("MxCentral1", "mx-central-1"), ("ApSoutheast7", "ap-southeast-7"),
    ("CaWest1", "ca-west-1"), ("EuIsoeWest1", "eu-isoe-west-1"),
])
def test_go_constants_become_region_ids(camel, region):
    assert r.region_id_from_go_constant(camel) == region


def test_parse_go_map_skips_commented_rows():
    assert r.parse_go_map(GO_ELB, "hostedZoneIDPerRegionMap") == {
        "us-east-1": "Z35SXDOTRQ7X7K", "us-gov-east-1": "Z166TLBEWOO7G0", "cn-northwest-1": "ZM7IZAIOVVDZF"}


def test_parse_ts_tables_and_partition_enum():
    assert r.parse_ts_table(TS_FACTS, "ROUTE_53_BUCKET_WEBSITE_ZONE_IDS") == {
        "af-south-1": "Z11KHD8FBVPUYU", "us-isof-south-1": "Z03376072I8GXC2DXUFXI"}
    enum = r.parse_ts_partition_enum(TS_FACTS)
    assert enum["UsIsoB"] == "aws-iso-b" and enum["Eusc"] == "aws-eusc"
    assert r.parse_ts_partition_table(TS_FACTS, "PARTITION_SAML_SIGN_ON_URL", enum) == {
        "aws": "https://signin.aws.amazon.com/saml", "aws-cn": "https://signin.amazonaws.cn/saml"}


def test_cloudfront_zones_from_both_sources_agree():
    assert r.parse_go_cloudfront_zones(GO_CLIENT) == {"aws": "Z2FDTNDATAQYW2", "aws-cn": "Z3RFFRIM2A3IF5"}
    assert r.parse_cdk_cloudfront_zones(TS_CLOUDFRONT) == r.parse_go_cloudfront_zones(GO_CLIENT)


def test_vpc_endpoint_exceptions_are_per_region_and_service():
    services, prefixes, cn_suffix = r.parse_vpc_endpoint_exceptions(TS_VPCE)
    assert services == {"cn-northwest-1": ["ecr.api", "s3", "sts"], "us-isof-south-1": ["ebs", "ecr.api"]}
    assert prefixes["cn-northwest-1"] == "cn.com.amazonaws"
    assert prefixes["us-isof-east-1"] == "gov.ic.hci.csp"
    assert cn_suffix == {"cn-north-1": ["transcribe"], "cn-northwest-1": ["transcribe"]}


def test_build_merges_sources_with_terraform_winning_the_s3_conflicts(tmp_path):
    tf, cdk = write_sources(tmp_path)
    document = r.build(tf, cdk, PARTITIONS)
    regions = document["regions"]
    assert regions["us-east-1"] == {"albHostedZoneId": "Z35SXDOTRQ7X7K", "classicElbHostedZoneId": "Z35SXDOTRQ7X7K",
                                    "nlbHostedZoneId": "Z26RNL4JYFTOTI", "s3WebsiteHostedZoneId": "Z3AQBSTGFYJSTF"}
    assert regions["af-south-1"]["s3WebsiteHostedZoneId"] == "Z83WF9RJE8B12"
    assert regions["us-isof-south-1"] == {"s3WebsiteHostedZoneId": "Z03376072I8GXC2DXUFXI",
                                          "vpcEndpointExceptionPrefix": "gov.ic.hci.csp",
                                          "vpcEndpointPrefixServices": ["ebs", "ecr.api"]}
    assert regions["cn-northwest-1"]["vpcEndpointCnSuffixServices"] == ["transcribe"]
    assert "cn-north-1" not in regions or "albHostedZoneId" not in regions["cn-north-1"]
    partitions = document["partitions"]
    assert partitions["aws-cn"] == {"vpcEndpointServiceNamePrefix": "cn.com.amazonaws.vpce",
                                    "cloudfrontHostedZoneId": "Z3RFFRIM2A3IF5",
                                    "samlSignOnUrl": "https://signin.amazonaws.cn/saml"}
    assert partitions["aws-iso-f"] == {"vpcEndpointServiceNamePrefix": "gov.ic.hci.csp.vpce"}


def test_build_refuses_a_region_the_partition_data_does_not_publish(tmp_path):
    tf, cdk = write_sources(tmp_path)
    with pytest.raises(ValueError, match="af-south-1"):
        r.build(tf, cdk, {"partitions": [p for p in PARTITIONS["partitions"] if p["id"] != "aws"]
                          + [{"id": "aws", "dnsSuffix": "amazonaws.com", "regions": [{"id": "us-east-1"}]}]})


def test_cli_write_check_and_self_check(tmp_path, capsys):
    tf, cdk = write_sources(tmp_path)
    partitions = tmp_path / "partitions.json"
    partitions.write_text(json.dumps(PARTITIONS))
    output = tmp_path / "out" / "region-facts.json"
    common = ["--terraform", str(tf), "--cdk", str(cdk), "--partitions", str(partitions), "--output", str(output)]
    assert r.main(["--check"] + common) == 1
    assert r.main(common) == 0
    assert r.main(["--check"] + common) == 0
    assert "is up to date" in capsys.readouterr().out
    # without the checkouts, --check verifies the shape only
    assert r.main(["--check", "--terraform", str(tmp_path / "nowhere"), "--cdk", str(tmp_path / "nowhere"),
                   "--partitions", str(partitions), "--output", str(output)]) == 0
    assert "shape verified only" in capsys.readouterr().out
    output.write_text('{"partitions": {}, "regions": {"us-east-1": {"albHostedZoneId": "nope"}}}')
    assert r.main(["--check", "--terraform", str(tmp_path / "nowhere"), "--cdk", str(tmp_path / "nowhere"),
                   "--partitions", str(partitions), "--output", str(output)]) == 1


def test_repo_file_matches_the_local_checkouts_when_present():
    if not (r.LOCAL_TERRAFORM / r.TF_ELB).exists() or not (r.LOCAL_CDK / r.CDK_FACT_TABLES).exists():
        pytest.skip("local/aws checkouts not present")
    document = r.build(r.LOCAL_TERRAFORM, r.LOCAL_CDK, json.loads(r.PARTITIONS.read_text(encoding="utf-8")))
    assert r.strip_source(r.render(document)) == r.strip_source(r.OUTPUT.read_text(encoding="utf-8"))


def test_self_check_holds_the_values_to_what_partitions_json_publishes(tmp_path):
    partitions = tmp_path / "partitions.json"
    partitions.write_text(json.dumps(PARTITIONS))
    tf, cdk = write_sources(tmp_path)
    good = r.build(tf, cdk, PARTITIONS)
    output = tmp_path / "region-facts.json"

    def problems(mutate):
        document = json.loads(json.dumps(good))
        mutate(document)
        output.write_text(json.dumps(document))
        return r.self_check(output, partitions)

    region = next(iter(good["regions"]))
    partition = next(iter(good["partitions"]))
    accepted = {
        "unchanged": lambda d: None,
        # ap-southeast-1's NLB zone is twelve characters; real zone ids run from twelve to twenty-one
        "twelve-character zone": lambda d: d["regions"][region].update(nlbHostedZoneId="ZKVM4W9LS7TM"),
    }
    rejected = {
        "not a zone id": lambda d: d["regions"][region].update(nlbHostedZoneId="nope"),
        "unpublished region": lambda d: d["regions"].update({"xx-nowhere-1": {}}),
        "wrong endpoint prefix": lambda d: d["partitions"][partition].update(
            vpcEndpointServiceNamePrefix="com.example.vpce"),
        "plain-http SAML URL": lambda d: d["partitions"][partition].update(
            samlSignOnUrl="http://signin.example/saml"),
        "missing partition": lambda d: d["partitions"].pop(partition),
        "partition entry not an object": lambda d: d["partitions"].update({partition: "invalid"}),
        "region entry not an object": lambda d: d["regions"].update({region: ["invalid"]}),
    }
    for label, mutate in accepted.items():
        found = problems(mutate)
        assert found == [], label
    for label, mutate in rejected.items():
        found = problems(mutate)
        assert found, label
