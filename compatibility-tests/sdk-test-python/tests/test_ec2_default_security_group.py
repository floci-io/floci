"""Instance launch selects security groups in the subnet's VPC."""

import uuid
from contextlib import ExitStack

import pytest


def terminate(client, instance_id):
    client.terminate_instances(InstanceIds=[instance_id])
    client.get_waiter("instance_terminated").wait(
        InstanceIds=[instance_id], WaiterConfig={"Delay": 1, "MaxAttempts": 20})


@pytest.mark.parametrize("placement", ["default", "custom", "explicit"])
def test_instance_uses_its_subnet_vpc_security_group(ec2_client, placement):
    with ExitStack() as cleanup:
        image = ec2_client.register_image(
            Name="launch-groups-" + uuid.uuid4().hex, Architecture="x86_64",
            RootDeviceName="/dev/sda1")["ImageId"]
        cleanup.callback(ec2_client.deregister_image, ImageId=image)
        if placement == "default":
            vpc = ec2_client.describe_vpcs(
                Filters=[{"Name": "is-default", "Values": ["true"]}])["Vpcs"][0]["VpcId"]
            subnet = ec2_client.describe_subnets(
                Filters=[{"Name": "vpc-id", "Values": [vpc]}])["Subnets"][0]["SubnetId"]
        else:
            vpc = ec2_client.create_vpc(CidrBlock="10.227.0.0/16")["Vpc"]["VpcId"]
            cleanup.callback(ec2_client.delete_vpc, VpcId=vpc)
            subnet = ec2_client.create_subnet(
                VpcId=vpc, CidrBlock="10.227.1.0/24")["Subnet"]["SubnetId"]
            cleanup.callback(ec2_client.delete_subnet, SubnetId=subnet)
        request = {}
        if placement == "explicit":
            expected = ec2_client.create_security_group(
                VpcId=vpc, GroupName="launch-" + uuid.uuid4().hex,
                Description="Explicit launch group")["GroupId"]
            cleanup.callback(ec2_client.delete_security_group, GroupId=expected)
            request["SecurityGroupIds"] = [expected]
        else:
            expected = ec2_client.describe_security_groups(Filters=[
                {"Name": "vpc-id", "Values": [vpc]},
                {"Name": "group-name", "Values": ["default"]},
            ])["SecurityGroups"][0]["GroupId"]

        instance = ec2_client.run_instances(
            ImageId=image, InstanceType="t3.micro", MinCount=1, MaxCount=1,
            SubnetId=subnet, **request)["Instances"][0]
        cleanup.callback(terminate, ec2_client, instance["InstanceId"])
        observed = ec2_client.describe_instances(
            InstanceIds=[instance["InstanceId"]])["Reservations"][0]["Instances"][0]

        assert observed["VpcId"] == vpc
        assert [group["GroupId"] for group in observed["SecurityGroups"]] == [expected]
        assert [group["GroupId"] for group in observed["NetworkInterfaces"][0]["Groups"]] == [expected]
