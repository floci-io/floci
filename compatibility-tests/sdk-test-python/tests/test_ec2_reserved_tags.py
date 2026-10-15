"""Reserved tag validation checked against live AWS on 2026-10-13 and 2026-10-14."""

import uuid
from contextlib import ExitStack

import pytest
from botocore.exceptions import ClientError


def test_reserved_tags_reject_atomically_before_allocation_and_mutation(ec2_client, aws_config):
    name = "reserved-tags-" + uuid.uuid4().hex[:12]
    with ExitStack() as cleanup:
        vpc = ec2_client.create_vpc(CidrBlock="10.236.0.0/24")["Vpc"]["VpcId"]
        cleanup.callback(ec2_client.delete_vpc, VpcId=vpc)
        subnet = ec2_client.create_subnet(
            VpcId=vpc, CidrBlock="10.236.0.0/26",
            AvailabilityZone=aws_config["region_name"] + "a")["Subnet"]["SubnetId"]
        cleanup.callback(ec2_client.delete_subnet, SubnetId=subnet)
        image = ec2_client.register_image(
            Name=name, Architecture="x86_64", RootDeviceName="/dev/sda1")["ImageId"]
        cleanup.callback(ec2_client.deregister_image, ImageId=image)
        filters = [{"Name": "subnet-id", "Values": [subnet]}]
        before_instances = ec2_client.describe_instances(Filters=filters)["Reservations"]
        before_interfaces = ec2_client.describe_network_interfaces(Filters=filters)["NetworkInterfaces"]
        with pytest.raises(ClientError) as error:
            ec2_client.run_instances(
                ImageId=image, InstanceType="t3.nano", SubnetId=subnet, MinCount=1, MaxCount=1,
                TagSpecifications=[{"ResourceType": "network-interface", "Tags": [
                    {"Key": "Name", "Value": name}, {"Key": "aWs:reserved", "Value": "invalid"},
                ]}])
        assert error.value.response["Error"] == {
            "Code": "InvalidParameterValue",
            "Message": "Tag keys starting with 'aws:' are reserved for internal use",
        }
        assert ec2_client.describe_instances(Filters=filters)["Reservations"] == before_instances
        assert ec2_client.describe_network_interfaces(Filters=filters)["NetworkInterfaces"] == before_interfaces

        instance = ec2_client.run_instances(
            ImageId=image, InstanceType="t3.nano", SubnetId=subnet, MinCount=1, MaxCount=1,
            TagSpecifications=[{"ResourceType": "instance", "Tags": [
                {"Key": "Name", "Value": name},
            ]}])["Instances"][0]["InstanceId"]
        cleanup.callback(ec2_client.terminate_instances, InstanceIds=[instance])
        for operation in (ec2_client.create_tags, ec2_client.delete_tags):
            for key in ("aws:autoscaling:groupName", "AWS:custom", "aWs:custom"):
                with pytest.raises(ClientError) as error:
                    operation(Resources=[instance], Tags=[
                        {"Key": "Name", "Value": "changed" if operation == ec2_client.create_tags else name},
                        {"Key": key, "Value": "spoofed"},
                    ])
                assert error.value.response["Error"]["Code"] == "InvalidParameterValue"
                assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
                assert error.value.response["Error"]["Message"] == (
                    f"Value ( {key} ) for parameter key is invalid. "
                    "Tag keys starting with 'aws:' are reserved for internal use"
                )
                tags = ec2_client.describe_tags(
                    Filters=[{"Name": "resource-id", "Values": [instance]}])["Tags"]
                assert {tag["Key"]: tag["Value"] for tag in tags} == {"Name": name}
        for args in ({"Tags": []}, {}):
            ec2_client.create_tags(Resources=[instance], Tags=[{"Key": "Name", "Value": name}])
            ec2_client.delete_tags(Resources=[instance], **args)
            assert not ec2_client.describe_tags(
                Filters=[{"Name": "resource-id", "Values": [instance]}])["Tags"]


@pytest.mark.parametrize("key", ["aws:reserved", "AWS:reserved", "aWs:reserved"])
def test_volumes_and_spot_requests_reject_reserved_tags(ec2_client, aws_config, key):
    name = "reserved-creation-" + uuid.uuid4().hex[:12]
    filters = [{"Name": "tag:Name", "Values": [name]}]
    tags = [{"Key": "Name", "Value": name}, {"Key": key, "Value": "invalid"}]
    with pytest.raises(ClientError) as error:
        ec2_client.create_volume(
            AvailabilityZone=aws_config["region_name"] + "a", Size=1, VolumeType="gp3",
            TagSpecifications=[{"ResourceType": "volume", "Tags": tags}])
    assert error.value.response["Error"]["Code"] == "InvalidParameterValue"
    assert ec2_client.describe_volumes(Filters=filters)["Volumes"] == []
    with pytest.raises(ClientError) as error:
        ec2_client.request_spot_instances(
            InstanceCount=1, Type="one-time",
            LaunchSpecification={"ImageId": "ami-00000000000000000", "InstanceType": "t3.nano"},
            TagSpecifications=[{"ResourceType": "spot-instances-request", "Tags": tags}])
    assert error.value.response["Error"]["Code"] == "InvalidParameter"
    assert ec2_client.describe_spot_instance_requests(Filters=filters)["SpotInstanceRequests"] == []


def test_nested_launch_template_reserved_prefix_is_case_sensitive(ec2_client):
    name = "reserved-template-" + uuid.uuid4().hex[:12]

    def data(key):
        return {"InstanceType": "t3.nano",
                "TagSpecifications": [{"ResourceType": "instance",
                                       "Tags": [{"Key": key, "Value": "test"}]}]}

    with pytest.raises(ClientError) as error:
        ec2_client.create_launch_template(LaunchTemplateName=name, LaunchTemplateData=data("aws:reserved"))
    assert error.value.response["Error"]["Code"] == "InvalidTagKey.Malformed"
    with pytest.raises(ClientError) as error:
        ec2_client.describe_launch_templates(LaunchTemplateNames=[name])
    assert error.value.response["Error"]["Code"] == "InvalidLaunchTemplateName.NotFoundException"

    template = ec2_client.create_launch_template(
        LaunchTemplateName=name, LaunchTemplateData=data("Name"))["LaunchTemplate"]["LaunchTemplateId"]
    try:
        with pytest.raises(ClientError) as error:
            ec2_client.create_launch_template_version(
                LaunchTemplateId=template, LaunchTemplateData=data("aws:reserved"))
        assert error.value.response["Error"]["Code"] == "InvalidTagKey.Malformed"
        versions = ec2_client.describe_launch_template_versions(LaunchTemplateId=template)["LaunchTemplateVersions"]
        assert [version["VersionNumber"] for version in versions] == [1]
        for key in ("AWS:reserved", "aWs:reserved"):
            version = ec2_client.create_launch_template_version(
                LaunchTemplateId=template, LaunchTemplateData=data(key))["LaunchTemplateVersion"]
            assert version["LaunchTemplateData"]["TagSpecifications"] == data(key)["TagSpecifications"]
    finally:
        ec2_client.delete_launch_template(LaunchTemplateId=template)
