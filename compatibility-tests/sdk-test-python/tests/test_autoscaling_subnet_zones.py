"""Group zone readback follows the selected VPC subnets."""

from contextlib import ExitStack, closing

import boto3
import pytest


@pytest.mark.parametrize("explicit_initial_zones", [False, True])
def test_subnet_zones_follow_create_and_update(
    ec2_client, aws_config, client_config, unique_name, explicit_initial_zones
):
    zones = [
        zone["ZoneName"]
        for zone in ec2_client.describe_availability_zones(
            Filters=[{"Name": "state", "Values": ["available"]}]
        )["AvailabilityZones"]
    ][:2]
    assert len(zones) == 2
    with closing(boto3.client("autoscaling", config=client_config, **aws_config)) as autoscaling:
        with ExitStack() as cleanup:
            vpc = ec2_client.create_vpc(CidrBlock="10.94.0.0/16")["Vpc"]["VpcId"]
            cleanup.callback(ec2_client.delete_vpc, VpcId=vpc)
            subnets = []
            for index, zone in enumerate([zones[0], zones[0], zones[1]]):
                subnet = ec2_client.create_subnet(
                    VpcId=vpc,
                    CidrBlock=f"10.94.{index}.0/24",
                    AvailabilityZone=zone,
                )["Subnet"]["SubnetId"]
                cleanup.callback(ec2_client.delete_subnet, SubnetId=subnet)
                subnets.append(subnet)
            image = ec2_client.register_image(
                Name=unique_name, Architecture="x86_64", RootDeviceName="/dev/sda1"
            )["ImageId"]
            cleanup.callback(ec2_client.deregister_image, ImageId=image)
            template = ec2_client.create_launch_template(
                LaunchTemplateName=unique_name,
                LaunchTemplateData={"ImageId": image, "InstanceType": "t3.nano"},
            )["LaunchTemplate"]["LaunchTemplateId"]
            cleanup.callback(ec2_client.delete_launch_template, LaunchTemplateId=template)
            placement = {"AvailabilityZones": zones} if explicit_initial_zones else {}
            autoscaling.create_auto_scaling_group(
                AutoScalingGroupName=unique_name,
                MinSize=0,
                MaxSize=0,
                DesiredCapacity=0,
                LaunchTemplate={"LaunchTemplateId": template},
                VPCZoneIdentifier=",".join(subnets),
                **placement,
            )
            cleanup.callback(
                autoscaling.delete_auto_scaling_group,
                AutoScalingGroupName=unique_name,
                ForceDelete=True,
            )

            def described():
                return autoscaling.describe_auto_scaling_groups(
                    AutoScalingGroupNames=[unique_name]
                )["AutoScalingGroups"][0]

            group = described()
            assert sorted(group["AvailabilityZones"]) == sorted(zones)
            assert group["VPCZoneIdentifier"] == ",".join(subnets)
            assert group["Instances"] == []

            autoscaling.update_auto_scaling_group(
                AutoScalingGroupName=unique_name, VPCZoneIdentifier=subnets[2]
            )
            group = described()
            assert group["AvailabilityZones"] == [zones[1]]
            assert group["VPCZoneIdentifier"] == subnets[2]
            autoscaling.update_auto_scaling_group(
                AutoScalingGroupName=unique_name, DefaultCooldown=60
            )
            group = described()
            assert group["AvailabilityZones"] == [zones[1]]
            assert group["VPCZoneIdentifier"] == subnets[2]
            assert group["DefaultCooldown"] == 60
