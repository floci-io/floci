"""Group zone readback follows the selected VPC subnets."""

import uuid
from contextlib import ExitStack

import pytest
from botocore.exceptions import ClientError


@pytest.mark.parametrize("explicit_initial_zones", [False, True])
def test_subnet_zones_follow_create_and_update(
    ec2_client, autoscaling_client, test_launch_template, unique_name, explicit_initial_zones
):
    zones = [
        zone["ZoneName"]
        for zone in ec2_client.describe_availability_zones(
            Filters=[{"Name": "state", "Values": ["available"]}]
        )["AvailabilityZones"]
    ][:2]
    assert len(zones) == 2
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
        placement = {"AvailabilityZones": zones} if explicit_initial_zones else {}
        autoscaling_client.create_auto_scaling_group(
            AutoScalingGroupName=unique_name,
            MinSize=0,
            MaxSize=0,
            DesiredCapacity=0,
            LaunchTemplate={"LaunchTemplateId": test_launch_template},
            VPCZoneIdentifier=",".join(subnets),
            **placement,
        )
        cleanup.callback(
            autoscaling_client.delete_auto_scaling_group,
            AutoScalingGroupName=unique_name,
            ForceDelete=True,
        )

        def described():
            return autoscaling_client.describe_auto_scaling_groups(
                AutoScalingGroupNames=[unique_name]
            )["AutoScalingGroups"][0]

        group = described()
        assert sorted(group["AvailabilityZones"]) == sorted(zones)
        assert group["VPCZoneIdentifier"] == ",".join(subnets)
        assert group["Instances"] == []

        autoscaling_client.update_auto_scaling_group(
            AutoScalingGroupName=unique_name, VPCZoneIdentifier=subnets[2]
        )
        group = described()
        assert group["AvailabilityZones"] == [zones[1]]
        assert group["VPCZoneIdentifier"] == subnets[2]
        autoscaling_client.update_auto_scaling_group(
            AutoScalingGroupName=unique_name, DefaultCooldown=60
        )
        group = described()
        assert group["AvailabilityZones"] == [zones[1]]
        assert group["VPCZoneIdentifier"] == subnets[2]
        assert group["DefaultCooldown"] == 60


@pytest.mark.parametrize("include_valid_subnet", [False, True])
def test_missing_subnets_leave_group_unchanged(
    ec2_client, autoscaling_client, test_launch_template, unique_name, include_valid_subnet
):
    with ExitStack() as cleanup:
        vpc = ec2_client.create_vpc(CidrBlock="10.95.0.0/16")["Vpc"]["VpcId"]
        cleanup.callback(ec2_client.delete_vpc, VpcId=vpc)
        subnet = ec2_client.create_subnet(VpcId=vpc, CidrBlock="10.95.0.0/24")["Subnet"]["SubnetId"]
        cleanup.callback(ec2_client.delete_subnet, SubnetId=subnet)
        autoscaling_client.create_auto_scaling_group(
            AutoScalingGroupName=unique_name,
            MinSize=0,
            MaxSize=1,
            DesiredCapacity=0,
            DefaultCooldown=300,
            LaunchTemplate={"LaunchTemplateId": test_launch_template, "Version": "1"},
            VPCZoneIdentifier=subnet,
        )
        cleanup.callback(
            autoscaling_client.delete_auto_scaling_group, AutoScalingGroupName=unique_name, ForceDelete=True
        )
        before = autoscaling_client.describe_auto_scaling_groups(
            AutoScalingGroupNames=[unique_name]
        )["AutoScalingGroups"][0]
        missing = "subnet-" + uuid.uuid4().hex[:17]
        rejected_subnets = [subnet, missing] if include_valid_subnet else [missing]

        with pytest.raises(ClientError) as refused:
            autoscaling_client.update_auto_scaling_group(
                AutoScalingGroupName=unique_name,
                LaunchTemplate={"LaunchTemplateId": test_launch_template, "Version": "$Latest"},
                MaxSize=8,
                DefaultCooldown=60,
                HealthCheckGracePeriod=120,
                VPCZoneIdentifier=",".join(rejected_subnets),
            )
        assert refused.value.response["Error"]["Code"] == "ValidationError"
        assert refused.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
        assert missing in refused.value.response["Error"]["Message"]
        after = autoscaling_client.describe_auto_scaling_groups(
            AutoScalingGroupNames=[unique_name]
        )["AutoScalingGroups"][0]
        assert after == before

        rejected_name = unique_name + "-rejected"
        with pytest.raises(ClientError) as refused:
            autoscaling_client.create_auto_scaling_group(
                AutoScalingGroupName=rejected_name,
                MinSize=0,
                MaxSize=0,
                DesiredCapacity=0,
                LaunchTemplate={"LaunchTemplateId": test_launch_template},
                VPCZoneIdentifier=",".join(rejected_subnets),
            )
            cleanup.callback(
                autoscaling_client.delete_auto_scaling_group,
                AutoScalingGroupName=rejected_name,
                ForceDelete=True,
            )
        assert refused.value.response["Error"]["Code"] == "ValidationError"
        assert missing in refused.value.response["Error"]["Message"]
        assert autoscaling_client.describe_auto_scaling_groups(
            AutoScalingGroupNames=[rejected_name]
        )["AutoScalingGroups"] == []
