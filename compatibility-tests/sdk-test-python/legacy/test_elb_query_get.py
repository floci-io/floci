"""Boto 2 Query GET and Boto 3 POST share Classic ELB state and errors."""

from urllib.parse import urlsplit
import uuid

from boto.ec2.elb import ELBConnection
from boto.exception import BotoServerError
from boto.regioninfo import RegionInfo
import boto3
from botocore.exceptions import ClientError
import pytest


def test_classic_elb_get_lifecycle(aws_config):
    endpoint = urlsplit(aws_config["endpoint_url"])
    legacy = ELBConnection(
        aws_access_key_id=aws_config["aws_access_key_id"],
        aws_secret_access_key=aws_config["aws_secret_access_key"],
        port=endpoint.port,
        is_secure=endpoint.scheme == "https",
        region=RegionInfo(name=aws_config["region_name"], endpoint=endpoint.hostname),
    )
    legacy.auth_region_name = aws_config["region_name"]
    legacy.auth_service_name = "elasticloadbalancing"
    current = boto3.client("elb", **aws_config)
    name = "query-get-" + uuid.uuid4().hex[:12]
    try:
        current.create_load_balancer(
            LoadBalancerName=name,
            AvailabilityZones=[aws_config["region_name"] + "a"],
            Listeners=[{"Protocol": "HTTP", "LoadBalancerPort": 80, "InstancePort": 8080}],
        )
        assert [item.name for item in legacy.get_all_load_balancers([name])] == [name]
        legacy.delete_load_balancer(name)
        with pytest.raises(BotoServerError) as old_error:
            legacy.get_all_load_balancers([name])
        assert old_error.value.status == 400
        assert old_error.value.error_code == "LoadBalancerNotFound"
        with pytest.raises(ClientError) as new_error:
            current.describe_load_balancers(LoadBalancerNames=[name])
        assert new_error.value.response["Error"]["Code"] == "LoadBalancerNotFound"
    finally:
        current.delete_load_balancer(LoadBalancerName=name)
        legacy.close()
        current.close()
