"""Exercise capacity group targets through EC2 and Resource Groups SDKs."""
import uuid

import boto3
import pytest
from botocore.exceptions import ClientError

ACCOUNT = "111111111111"
REGION = "us-east-1"


def sdk_client(service, aws_config, client_config, account, region):
    config = dict(aws_config, aws_access_key_id=account, aws_secret_access_key="test", region_name=region)
    return boto3.client(service, config=client_config, **config)


@pytest.fixture
def pool(aws_config, client_config):
    clients = {name: sdk_client(name, aws_config, client_config, ACCOUNT, REGION)
               for name in ("ec2", "resource-groups")}
    ec2, groups = clients["ec2"], clients["resource-groups"]
    created = {"reservations": [], "instances": [], "templates": []}
    group = groups.create_group(Name="pool-" + uuid.uuid4().hex, Configuration=[
        {"Type": "AWS::EC2::CapacityReservationPool"},
        {"Type": "AWS::ResourceGroups::Generic", "Parameters": [
            {"Name": "allowed-resource-types", "Values": ["AWS::EC2::CapacityReservation"]},
        ]},
    ])["Group"]["GroupArn"]

    def reserve(count=1, zone=REGION + "a", member=True):
        reservation = ec2.create_capacity_reservation(
            InstanceType="t3.micro", InstancePlatform="Linux/UNIX", AvailabilityZone=zone,
            InstanceCount=count, InstanceMatchCriteria="targeted" if member else "open",
        )["CapacityReservation"]
        created["reservations"].append(reservation["CapacityReservationId"])
        if member:
            reply = groups.group_resources(Group=group, ResourceArns=[reservation["CapacityReservationArn"]])
            assert reply["Succeeded"] == [reservation["CapacityReservationArn"]]
            assert not reply["Failed"] and not reply["Pending"]
        return reservation["CapacityReservationId"]

    try:
        yield clients, group, reserve, created
    finally:
        if created["instances"]:
            ec2.terminate_instances(InstanceIds=created["instances"])
        for template in created["templates"]:
            ec2.delete_launch_template(LaunchTemplateId=template)
        groups.delete_group(Group=group)
        for reservation in created["reservations"]:
            ec2.cancel_capacity_reservation(CapacityReservationId=reservation)
        for sdk in clients.values():
            sdk.close()


@pytest.mark.parametrize("template", [False, True])
def test_group_round_trip_matching_and_strict_fallback(pool, template):
    clients, group, reserve, created = pool
    ec2 = clients["ec2"]
    members = {reserve(), reserve()}
    outside = reserve(4, member=False)
    specification = {
        "CapacityReservationPreference": "capacity-reservations-only",
        "CapacityReservationTarget": {"CapacityReservationResourceGroupArn": group},
    }
    request = {"MinCount": 2, "MaxCount": 2}
    data = {"ImageId": "ami-test", "InstanceType": "t3.micro",
            "CapacityReservationSpecification": specification}
    if template:
        selected = ec2.create_launch_template(
            LaunchTemplateName="pool-template-" + uuid.uuid4().hex,
            LaunchTemplateData=data)["LaunchTemplate"]["LaunchTemplateId"]
        created["templates"].append(selected)
        request["LaunchTemplate"] = {"LaunchTemplateId": selected}
    else:
        request.update(data)
    launched = ec2.run_instances(**request)["Instances"]
    created["instances"].extend(row["InstanceId"] for row in launched)
    assert {row["CapacityReservationId"] for row in launched} == members
    described = ec2.describe_instances(InstanceIds=created["instances"])["Reservations"]
    response_specification = {"CapacityReservationTarget": specification["CapacityReservationTarget"]}
    assert all(instance["CapacityReservationSpecification"] == response_specification
               for reservation in described for instance in reservation["Instances"])
    with pytest.raises(ClientError) as error:
        ec2.run_instances(**request)
    assert error.value.response["Error"]["Code"] == "InsufficientInstanceCapacity"
    assert ec2.describe_capacity_reservations(CapacityReservationIds=[outside])[
        "CapacityReservations"][0]["AvailableInstanceCount"] == 4


def test_group_default_uses_on_demand_without_outside_reservations(pool):
    clients, group, reserve, created = pool
    ec2 = clients["ec2"]
    outside = reserve(2, member=False)
    instance = ec2.run_instances(
        ImageId="ami-test", InstanceType="t3.micro", MinCount=1, MaxCount=1,
        CapacityReservationSpecification={
            "CapacityReservationTarget": {"CapacityReservationResourceGroupArn": group}},
    )["Instances"][0]
    created["instances"].append(instance["InstanceId"])
    assert "CapacityReservationId" not in instance
    assert "CapacityReservationPreference" not in instance["CapacityReservationSpecification"]
    assert instance["CapacityReservationSpecification"]["CapacityReservationTarget"] == {
        "CapacityReservationResourceGroupArn": group}
    assert ec2.describe_capacity_reservations(CapacityReservationIds=[outside])[
        "CapacityReservations"][0]["AvailableInstanceCount"] == 2


@pytest.mark.parametrize("account,region", [("222222222222", REGION), (ACCOUNT, "us-west-2")])
def test_group_target_cannot_cross_account_or_region(aws_config, client_config, pool, account, region):
    _, group, _, _ = pool
    ec2 = sdk_client("ec2", aws_config, client_config, account, region)
    try:
        before = {instance["InstanceId"] for reservation in ec2.describe_instances()["Reservations"]
                  for instance in reservation["Instances"]}
        with pytest.raises(ClientError) as error:
            ec2.run_instances(
                ImageId="ami-test", InstanceType="t3.micro", MinCount=1, MaxCount=1,
                CapacityReservationSpecification={
                    "CapacityReservationTarget": {"CapacityReservationResourceGroupArn": group}},
            )
        assert error.value.response["Error"]["Code"] == "InvalidParameterValue"
        after = {instance["InstanceId"] for reservation in ec2.describe_instances()["Reservations"]
                 for instance in reservation["Instances"]}
        assert after == before
    finally:
        ec2.close()
