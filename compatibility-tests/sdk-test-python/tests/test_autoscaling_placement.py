"""Synchronous placement inputs, checked against live AWS on 2026-10-12."""

import uuid
from contextlib import ExitStack, closing

import boto3


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
    aws_config, client_config
):
    name = "zone-placement-" + uuid.uuid4().hex[:12]
    aws_config = {**aws_config, "region_name": "us-east-1"}
    with (
        closing(boto3.client("ec2", config=client_config, **aws_config)) as ec2_client,
        closing(boto3.client("autoscaling", config=client_config, **aws_config)) as autoscaling,
    ):
        with ExitStack() as cleanup:
            image = ec2_client.register_image(
                Name=name, Architecture="x86_64", RootDeviceName="/dev/sda1"
            )["ImageId"]
            cleanup.callback(ec2_client.deregister_image, ImageId=image)
            template = ec2_client.create_launch_template(
                LaunchTemplateName=name,
                LaunchTemplateData={"ImageId": image, "InstanceType": "t3.nano"},
            )["LaunchTemplate"]["LaunchTemplateId"]
            cleanup.callback(ec2_client.delete_launch_template, LaunchTemplateId=template)
            autoscaling.create_auto_scaling_group(
                AutoScalingGroupName=name,
                MinSize=0, MaxSize=0, DesiredCapacity=0,
                LaunchTemplate={"LaunchTemplateId": template},
                AvailabilityZones=["us-east-1d"],
            )
            cleanup.callback(autoscaling.delete_auto_scaling_group,
                             AutoScalingGroupName=name, ForceDelete=True)
            group = autoscaling.describe_auto_scaling_groups(
                AutoScalingGroupNames=[name])["AutoScalingGroups"][0]
            assert group["AvailabilityZones"] == ["us-east-1d"]
            autoscaling.update_auto_scaling_group(
                AutoScalingGroupName=name, AvailabilityZones=["us-east-1d", "us-east-1f"])
            group = autoscaling.describe_auto_scaling_groups(
                AutoScalingGroupNames=[name])["AutoScalingGroups"][0]
            assert group["AvailabilityZones"] == ["us-east-1d", "us-east-1f"]
            assert group["Instances"] == []
