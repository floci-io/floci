"""M6g discovery verified against live EC2 in us-east-1 and eu-west-1."""

import boto3
import pytest


@pytest.fixture(params=["us-east-1", "eu-west-1"])
def catalog_client(request, aws_config, client_config):
    return boto3.client(
        "ec2", config=client_config, **{**aws_config, "region_name": request.param})


def test_m6g_descriptor(catalog_client):
    types = catalog_client.describe_instance_types(InstanceTypes=["m6g.large"])["InstanceTypes"]
    assert len(types) == 1
    instance_type = types[0]
    assert instance_type["InstanceType"] == "m6g.large"
    assert instance_type["VCpuInfo"]["DefaultVCpus"] == 2
    assert instance_type["MemoryInfo"]["SizeInMiB"] == 8192
    assert instance_type["ProcessorInfo"]["SupportedArchitectures"] == ["arm64"]
    assert instance_type["InstanceStorageSupported"] is False
    assert instance_type["CurrentGeneration"] is True
    assert set(instance_type["SupportedUsageClasses"]) == {"on-demand", "spot"}
    network = instance_type["NetworkInfo"]
    assert network["DefaultNetworkCardIndex"] == 0
    assert network["Ipv4AddressesPerInterface"] == 10
    assert network["EncryptionInTransitSupported"] is False
    assert len(network["NetworkCards"]) == 1
    assert network["NetworkCards"][0]["NetworkCardIndex"] == 0
    assert network["NetworkCards"][0]["MaximumNetworkInterfaces"] == 3


def test_m6g_offerings(catalog_client):
    region = catalog_client.meta.region_name
    for suffix in ["a", "b", "c"]:
        zone = region + suffix
        offerings = catalog_client.describe_instance_type_offerings(
            LocationType="availability-zone",
            Filters=[{"Name": "location", "Values": [zone]}],
            MaxResults=1000)["InstanceTypeOfferings"]
        assert {"InstanceType": "m6g.large", "LocationType": "availability-zone",
                "Location": zone} in offerings
        assert all(offering["Location"] == zone for offering in offerings)

    offerings = catalog_client.describe_instance_type_offerings(
        LocationType="region",
        Filters=[{"Name": "instance-type", "Values": ["m6g.large"]},
                 {"Name": "location", "Values": [region]}])["InstanceTypeOfferings"]
    assert offerings == [
        {"InstanceType": "m6g.large", "LocationType": "region", "Location": region}]
    for location_type, location, instance_type in [
        ("availability-zone", "other-zone", "m6g.large"),
        ("region", "us-west-2", "m6g.large"),
        ("region", region, "not-a-real-instance-type"),
    ]:
        assert catalog_client.describe_instance_type_offerings(
            LocationType=location_type,
            Filters=[{"Name": "instance-type", "Values": [instance_type]},
                     {"Name": "location", "Values": [location]}])["InstanceTypeOfferings"] == []
