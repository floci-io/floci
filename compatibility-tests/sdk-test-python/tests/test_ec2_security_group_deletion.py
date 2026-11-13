"""Security group deletion through the public EC2 API."""

import uuid
from contextlib import ExitStack

import pytest
from botocore.exceptions import ClientError


def delete_if_present(client, group_id):
    try:
        client.delete_security_group(GroupId=group_id)
    except ClientError as error:
        if error.response["Error"]["Code"] != "InvalidGroup.NotFound":
            raise


def create_group(client, cleanup, vpc, name=None):
    group = client.create_security_group(
        VpcId=vpc, GroupName=name or "delete-" + uuid.uuid4().hex,
        Description="SDK deletion contract",
    )["GroupId"]
    cleanup.callback(delete_if_present, client, group)
    return group


def create_vpc(client, cleanup):
    vpc = client.create_vpc(CidrBlock="10.231.0.0/24")["Vpc"]["VpcId"]
    cleanup.callback(client.delete_vpc, VpcId=vpc)
    return vpc


def test_delete_by_name_only_selects_the_default_vpc(ec2_client):
    default_vpc = ec2_client.describe_vpcs(
        Filters=[{"Name": "is-default", "Values": ["true"]}])["Vpcs"][0]["VpcId"]
    with ExitStack() as cleanup:
        vpc = create_vpc(ec2_client, cleanup)
        name = "same-name-" + uuid.uuid4().hex
        default_group = create_group(ec2_client, cleanup, default_vpc, name)
        custom_group = create_group(ec2_client, cleanup, vpc, name)
        assert ec2_client.delete_security_group(GroupName=name)["Return"]
        groups = ec2_client.describe_security_groups(
            Filters=[{"Name": "group-name", "Values": [name]}])["SecurityGroups"]
        assert [group["GroupId"] for group in groups] == [custom_group]
        assert all(group["GroupId"] != default_group for group in groups)
        with pytest.raises(ClientError) as caught:
            ec2_client.delete_security_group(GroupName=name)
        assert caught.value.response["Error"]["Code"] == "InvalidGroup.NotFound"
        assert ec2_client.describe_security_groups(
            GroupIds=[custom_group])["SecurityGroups"][0]["VpcId"] == vpc


@pytest.mark.parametrize("direction", ["ingress", "egress"])
def test_referenced_group_cannot_be_deleted(ec2_client, direction):
    with ExitStack() as cleanup:
        vpc = create_vpc(ec2_client, cleanup)
        target = create_group(ec2_client, cleanup, vpc)
        referrer = create_group(ec2_client, cleanup, vpc)
        permission = [{
            "IpProtocol": "tcp", "FromPort": 8443, "ToPort": 8443,
            "UserIdGroupPairs": [{"GroupId": target}],
        }]
        authorize = getattr(ec2_client, "authorize_security_group_" + direction)
        revoke = getattr(ec2_client, "revoke_security_group_" + direction)
        authorize(GroupId=referrer, IpPermissions=permission)
        with ExitStack() as rule_cleanup:
            rule_cleanup.callback(revoke, GroupId=referrer, IpPermissions=permission)
            with pytest.raises(ClientError) as caught:
                ec2_client.delete_security_group(GroupId=target)
            assert caught.value.response["Error"]["Code"] == "DependencyViolation"
            assert caught.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
            assert ec2_client.describe_security_groups(
                GroupIds=[target])["SecurityGroups"][0]["GroupId"] == target
        assert ec2_client.delete_security_group(GroupId=target)["Return"]


def test_network_interface_prevents_group_deletion(ec2_client):
    with ExitStack() as cleanup:
        vpc = create_vpc(ec2_client, cleanup)
        subnet = ec2_client.create_subnet(
            VpcId=vpc, CidrBlock="10.231.0.0/26")["Subnet"]["SubnetId"]
        cleanup.callback(ec2_client.delete_subnet, SubnetId=subnet)
        group = create_group(ec2_client, cleanup, vpc)
        interface = ec2_client.create_network_interface(
            SubnetId=subnet, Groups=[group])["NetworkInterface"]["NetworkInterfaceId"]
        with ExitStack() as interface_cleanup:
            interface_cleanup.callback(
                ec2_client.delete_network_interface, NetworkInterfaceId=interface)
            with pytest.raises(ClientError) as caught:
                ec2_client.delete_security_group(GroupId=group)
            assert caught.value.response["Error"]["Code"] == "DependencyViolation"
            assert ec2_client.describe_network_interfaces(
                NetworkInterfaceIds=[interface])["NetworkInterfaces"][0]["Groups"][0]["GroupId"] == group
        assert ec2_client.delete_security_group(GroupId=group)["Return"]


def test_delete_dry_run_preserves_group_and_rules(ec2_client):
    with ExitStack() as cleanup:
        vpc = create_vpc(ec2_client, cleanup)
        group = create_group(ec2_client, cleanup, vpc)
        before = ec2_client.describe_security_groups(GroupIds=[group])["SecurityGroups"]
        with pytest.raises(ClientError) as caught:
            ec2_client.delete_security_group(GroupId=group, DryRun=True)
        assert caught.value.response["Error"]["Code"] == "DryRunOperation"
        assert caught.value.response["ResponseMetadata"]["HTTPStatusCode"] == 412
        assert ec2_client.describe_security_groups(GroupIds=[group])["SecurityGroups"] == before
