"""Retained root volumes and allocation lookup through the live EC2 API."""

from uuid import uuid4

import pytest


@pytest.mark.parametrize("override", [False, True])
def test_launch_template_root_retention(ec2_client, override):
    image = ec2_client.describe_images(ImageIds=["ami-alpine"])["Images"][0]
    device = image["RootDeviceName"]
    template = ec2_client.create_launch_template(
        LaunchTemplateName="root-retention-" + uuid4().hex,
        LaunchTemplateData={
            "ImageId": image["ImageId"],
            "InstanceType": "t3.micro",
            "BlockDeviceMappings": [
                {"DeviceName": device, "Ebs": {"DeleteOnTermination": False}}
            ],
        },
    )["LaunchTemplate"]["LaunchTemplateId"]
    instances = []
    volume_ids = set()
    try:
        request = (
            {
                "BlockDeviceMappings": [
                    {"DeviceName": device, "Ebs": {"DeleteOnTermination": True}}
                ]
            }
            if override
            else {}
        )
        instance = ec2_client.run_instances(
            LaunchTemplate={"LaunchTemplateId": template, "Version": "1"},
            MinCount=1,
            MaxCount=1,
            **request,
        )["Instances"][0]
        instances.append(instance["InstanceId"])
        volume = instance["BlockDeviceMappings"][0]["Ebs"]["VolumeId"]
        volume_ids.add(volume)
        assert (
            instance["BlockDeviceMappings"][0]["Ebs"]["DeleteOnTermination"] is override
        )
        described = ec2_client.describe_instances(InstanceIds=instances)[
            "Reservations"
        ][0]["Instances"][0]
        assert (
            described["BlockDeviceMappings"][0]["Ebs"]["DeleteOnTermination"]
            is override
        )
        ec2_client.get_waiter("instance_running").wait(
            InstanceIds=instances, WaiterConfig={"Delay": 1, "MaxAttempts": 90}
        )
        ec2_client.terminate_instances(InstanceIds=instances)
        ec2_client.get_waiter("instance_terminated").wait(
            InstanceIds=instances, WaiterConfig={"Delay": 1, "MaxAttempts": 90}
        )
        remaining = [
            item
            for item in ec2_client.describe_volumes()["Volumes"]
            if item["VolumeId"] == volume
        ]
        if override:
            assert remaining == []
        else:
            assert len(remaining) == 1
            assert remaining[0]["State"] == "available"
            assert remaining[0]["Attachments"] == []
    finally:
        if instances:
            ec2_client.terminate_instances(InstanceIds=instances)
            ec2_client.get_waiter("instance_terminated").wait(
                InstanceIds=instances, WaiterConfig={"Delay": 1, "MaxAttempts": 90}
            )
        for item in ec2_client.describe_volumes()["Volumes"]:
            if item["VolumeId"] in volume_ids:
                ec2_client.delete_volume(VolumeId=item["VolumeId"])
        ec2_client.delete_launch_template(LaunchTemplateId=template)


def test_describe_instances_client_token_isolates_allocations(ec2_client):
    tokens = [uuid4().hex, uuid4().hex]
    instances = []
    try:
        for token in tokens:
            instance = ec2_client.run_instances(
                ImageId="ami-alpine",
                InstanceType="t3.micro",
                MinCount=1,
                MaxCount=1,
                ClientToken=token,
            )["Instances"][0]
            instances.append(instance["InstanceId"])
        response = ec2_client.describe_instances(
            Filters=[{"Name": "client-token", "Values": [tokens[0]]}]
        )
        found = [
            item["InstanceId"]
            for reservation in response["Reservations"]
            for item in reservation["Instances"]
        ]
        assert found == instances[:1]
        assert (
            ec2_client.describe_instances(
                Filters=[{"Name": "client-token", "Values": [uuid4().hex]}]
            )["Reservations"]
            == []
        )
    finally:
        if instances:
            ec2_client.terminate_instances(InstanceIds=instances)
            ec2_client.get_waiter("instance_terminated").wait(
                InstanceIds=instances, WaiterConfig={"Delay": 1, "MaxAttempts": 90}
            )
