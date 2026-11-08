"""Synchronous placement inputs, checked against live AWS on 2026-10-12."""

from contextlib import ExitStack

import pytest


@pytest.fixture
def aws_config(aws_config):
    """Keep the observed us-east-1 placement cases in their original region."""
    return {**aws_config, "region_name": "us-east-1"}


def test_subnet_counts_track_address_allocation(ec2_client):
    with ExitStack() as cleanup:
        vpc = ec2_client.create_vpc(CidrBlock="10.98.0.0/16")["Vpc"]["VpcId"]
        cleanup.callback(ec2_client.delete_vpc, VpcId=vpc)
        for cidr, capacity in [("10.98.0.0/28", 11), ("10.98.1.0/24", 251)]:
            subnet = ec2_client.create_subnet(VpcId=vpc, CidrBlock=cidr)["Subnet"]
            subnet_id = subnet["SubnetId"]
            cleanup.callback(ec2_client.delete_subnet, SubnetId=subnet_id)
            assert subnet["AvailableIpAddressCount"] == capacity
            interface = ec2_client.create_network_interface(SubnetId=subnet_id)["NetworkInterface"]
            try:
                described = ec2_client.describe_subnets(SubnetIds=[subnet_id])["Subnets"][0]
                assert described["AvailableIpAddressCount"] == capacity - 1
            finally:
                ec2_client.delete_network_interface(NetworkInterfaceId=interface["NetworkInterfaceId"])
            described = ec2_client.describe_subnets(SubnetIds=[subnet_id])["Subnets"][0]
            assert described["AvailableIpAddressCount"] == capacity


def test_zone_only_groups_accept_real_zones_outside_local_subnet_catalog(
    autoscaling_client, test_launch_template, unique_name
):
    with ExitStack() as cleanup:
        autoscaling_client.create_auto_scaling_group(
            AutoScalingGroupName=unique_name,
            MinSize=0, MaxSize=0, DesiredCapacity=0,
            LaunchTemplate={"LaunchTemplateId": test_launch_template},
            AvailabilityZones=["us-east-1d"],
        )
        cleanup.callback(autoscaling_client.delete_auto_scaling_group,
                         AutoScalingGroupName=unique_name, ForceDelete=True)
        group = autoscaling_client.describe_auto_scaling_groups(
            AutoScalingGroupNames=[unique_name])["AutoScalingGroups"][0]
        assert group["AvailabilityZones"] == ["us-east-1d"]
        autoscaling_client.update_auto_scaling_group(
            AutoScalingGroupName=unique_name, AvailabilityZones=["us-east-1d", "us-east-1f"])
        group = autoscaling_client.describe_auto_scaling_groups(
            AutoScalingGroupNames=[unique_name])["AutoScalingGroups"][0]
        assert group["AvailabilityZones"] == ["us-east-1d", "us-east-1f"]
        assert group["Instances"] == []
