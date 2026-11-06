"""Alias deletion compares equivalent DNS spellings while retaining value checks."""
import copy
import uuid

import boto3
import pytest
from botocore.exceptions import ClientError


@pytest.fixture
def route53(aws_config, client_config):
    return boto3.client("route53", config=client_config, **aws_config)


@pytest.fixture
def zone(route53):
    name = "alias-" + uuid.uuid4().hex[:12] + ".example.test"
    zone_id = route53.create_hosted_zone(Name=name, CallerReference=name)["HostedZone"]["Id"]
    try:
        yield zone_id, name
    finally:
        records = route53.list_resource_record_sets(HostedZoneId=zone_id)["ResourceRecordSets"]
        changes = [{"Action": "DELETE", "ResourceRecordSet": record}
                   for record in records if record["Type"] not in ("NS", "SOA")]
        if changes:
            route53.change_resource_record_sets(HostedZoneId=zone_id, ChangeBatch={"Changes": changes})
        route53.delete_hosted_zone(Id=zone_id)
        with pytest.raises(ClientError) as missing:
            route53.get_hosted_zone(Id=zone_id)
        assert missing.value.response["Error"]["Code"] == "NoSuchHostedZone"


@pytest.mark.parametrize("variant", ["add_dot", "remove_dot", "case_and_dot", "different_name",
                                    "different_zone", "different_health", "unicode_kelvin",
                                    "unicode_long_s", "unicode_dotted_i"])
def test_alias_delete_dns_spelling_and_atomic_mismatch_rejection(route53, zone, variant):
    zone_id, zone_name = zone
    target_name = "target." + zone_name
    target = {"Name": target_name, "Type": "A", "TTL": 60,
              "ResourceRecords": [{"Value": "192.0.2.21"}]}
    alias = {"Name": "alias." + zone_name, "Type": "A", "SetIdentifier": "blue", "Weight": 1,
             "AliasTarget": {"HostedZoneId": zone_id.rsplit("/", 1)[-1],
                             "DNSName": target_name + ("." if variant == "remove_dot" else ""),
                             "EvaluateTargetHealth": False}}
    unicode_targets = {"unicode_kelvin": ("\u212a", "k"), "unicode_long_s": ("\u017f", "s"),
                       "unicode_dotted_i": ("\u0130", "i")}
    if variant in unicode_targets:
        alias["AliasTarget"]["DNSName"] = unicode_targets[variant][0] + "." + zone_name
    route53.change_resource_record_sets(
        HostedZoneId=zone_id,
        ChangeBatch={"Changes": [{"Action": "CREATE", "ResourceRecordSet": record}
                                 for record in (target, alias)]},
    )
    requested = copy.deepcopy(alias)
    if variant == "add_dot":
        requested["AliasTarget"]["DNSName"] += "."
    elif variant == "remove_dot":
        requested["AliasTarget"]["DNSName"] = target_name
    elif variant == "case_and_dot":
        requested["AliasTarget"]["DNSName"] = target_name.upper() + "."
    elif variant == "different_name":
        requested["AliasTarget"]["DNSName"] = "other." + zone_name
    elif variant == "different_zone":
        requested["AliasTarget"]["HostedZoneId"] = "ZOTHER"
    elif variant in unicode_targets:
        requested["AliasTarget"]["DNSName"] = unicode_targets[variant][1] + "." + zone_name
    else:
        requested["AliasTarget"]["EvaluateTargetHealth"] = True
    marker = {"Name": "marker." + zone_name, "Type": "TXT", "TTL": 60,
              "ResourceRecords": [{"Value": '"propagation-marker"'}]}
    batch = {"Changes": [{"Action": "UPSERT", "ResourceRecordSet": marker},
                         {"Action": "DELETE", "ResourceRecordSet": requested}]}
    before = route53.list_resource_record_sets(HostedZoneId=zone_id)["ResourceRecordSets"]
    if variant.startswith("different_") or variant in unicode_targets:
        with pytest.raises(ClientError) as rejected:
            route53.change_resource_record_sets(HostedZoneId=zone_id, ChangeBatch=batch)
        assert rejected.value.response["Error"]["Code"] == "InvalidChangeBatch"
        assert rejected.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
        assert route53.list_resource_record_sets(HostedZoneId=zone_id)["ResourceRecordSets"] == before
    else:
        route53.change_resource_record_sets(HostedZoneId=zone_id, ChangeBatch=batch)
        after = route53.list_resource_record_sets(HostedZoneId=zone_id)["ResourceRecordSets"]
        assert all(record["Name"].rstrip(".") != alias["Name"] for record in after)
        assert any(record["Name"].rstrip(".") == marker["Name"] for record in after)
        assert any(record["Name"].rstrip(".") == target_name for record in after)
