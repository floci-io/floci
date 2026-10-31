"""Future reservations retain their scheduling metadata and client-token identity."""

from datetime import datetime, timedelta, timezone

import pytest
from botocore.exceptions import ClientError


def test_future_reservation_is_scheduled_and_replayed(ec2_client, unique_name):
    zone = ec2_client.describe_availability_zones()["AvailabilityZones"][0]["ZoneName"]
    start = (datetime.now(timezone.utc) + timedelta(days=7)).replace(microsecond=0)
    request = {
        "InstanceType": "t3.micro", "InstancePlatform": "Linux/UNIX", "InstanceCount": 16, "InstanceMatchCriteria": "targeted",
        "AvailabilityZone": zone, "ClientToken": unique_name,
        "StartDate": start, "CommitmentDuration": 86400, "DeliveryPreference": "incremental",
        "TagSpecifications": [
            {"ResourceType": "capacity-reservation", "Tags": [{"Key": "Name", "Value": unique_name}]}
        ],
    }
    created = ec2_client.create_capacity_reservation(**request)["CapacityReservation"]
    identifier = created["CapacityReservationId"]
    try:
        assert created["State"] == "scheduled"
        assert created["StartDate"] == start
        assert created["AvailableInstanceCount"] == 0
        assert created["DeliveryPreference"] == "incremental"
        assert created["CommitmentInfo"] == {
            "CommitmentDuration": 86400, "CommitmentEndDate": start + timedelta(days=1),
            "CommittedInstanceCount": 16,
        }
        assert ec2_client.describe_tags(Filters=[{"Name": "resource-id", "Values": [identifier]}])["Tags"] == [
            {"ResourceId": identifier, "ResourceType": "capacity-reservation", "Key": "Name", "Value": unique_name}
        ]
        replay = ec2_client.create_capacity_reservation(**request)["CapacityReservation"]
        assert replay == created
        with pytest.raises(ClientError) as error:
            ec2_client.create_capacity_reservation(**{**request, "CommitmentDuration": 86401})
        assert error.value.response["Error"]["Code"] == "IdempotentParameterMismatch"
        described = ec2_client.describe_capacity_reservations(
            Filters=[{"Name": "tag:Name", "Values": [unique_name]}]
        )["CapacityReservations"]
        assert described == [created]
        ec2_client.create_tags(Resources=[identifier], Tags=[{"Key": "Name", "Value": unique_name + "-edited"}])
        replay = ec2_client.create_capacity_reservation(**request)["CapacityReservation"]
        assert replay["Tags"] == [{"Key": "Name", "Value": unique_name + "-edited"}]
        with pytest.raises(ClientError) as tag_error:
            ec2_client.create_capacity_reservation(**{
                **request, "TagSpecifications": [{"ResourceType": "capacity-reservation",
                                                  "Tags": [{"Key": "Name", "Value": "different"}]}],
            })
        assert tag_error.value.response["Error"]["Code"] == "IdempotentParameterMismatch"
        after = ec2_client.describe_capacity_reservations(CapacityReservationIds=[identifier])["CapacityReservations"]
        assert after == [replay]
    finally:
        ec2_client.cancel_capacity_reservation(CapacityReservationId=identifier)


def test_invalid_schedule_does_not_create_capacity(ec2_client, unique_name):
    request = {
        "InstanceType": "t3.micro", "InstancePlatform": "Linux/UNIX", "InstanceCount": 1,
        "AvailabilityZone": ec2_client.describe_availability_zones()["AvailabilityZones"][0]["ZoneName"],
        "ClientToken": unique_name,
        "StartDate": datetime.now(timezone.utc) + timedelta(days=7),
        "CommitmentDuration": 86400, "DeliveryPreference": "fixed",
        "TagSpecifications": [
            {"ResourceType": "capacity-reservation", "Tags": [{"Key": "Name", "Value": unique_name}]}
        ],
    }
    with pytest.raises(ClientError) as error:
        ec2_client.create_capacity_reservation(**request)
    assert error.value.response["Error"]["Code"] == "InvalidParameterValue"
    assert ec2_client.describe_capacity_reservations(
        Filters=[{"Name": "tag:Name", "Values": [unique_name]}]
    )["CapacityReservations"] == []


@pytest.mark.parametrize("overrides", [
    {}, {"InstanceMatchCriteria": "open"},
    {"InstanceMatchCriteria": "targeted", "InstanceCount": 7},
    {"InstanceMatchCriteria": "targeted", "InstanceType": "p5.48xlarge"},
])
def test_future_constraints_reject_before_reservation_or_tags(ec2_client, unique_name, overrides):
    request = {
        "InstanceType": "m5.xlarge", "InstancePlatform": "Linux/UNIX", "InstanceCount": 8,
        "AvailabilityZone": ec2_client.describe_availability_zones()["AvailabilityZones"][0]["ZoneName"],
        "ClientToken": unique_name,
        "StartDate": (datetime.now(timezone.utc) + timedelta(days=7)).replace(microsecond=0),
        "CommitmentDuration": 1209600, "DeliveryPreference": "incremental",
        "TagSpecifications": [{"ResourceType": "capacity-reservation",
                              "Tags": [{"Key": "Name", "Value": unique_name}]}],
        **overrides,
    }
    identifier = None
    try:
        with pytest.raises(ClientError) as error:
            response = ec2_client.create_capacity_reservation(**request)
            identifier = response["CapacityReservation"]["CapacityReservationId"]
        assert error.value.response["Error"]["Code"] == "InvalidParameterValue"
        assert ec2_client.describe_capacity_reservations(
            Filters=[{"Name": "tag:Name", "Values": [unique_name]}])["CapacityReservations"] == []
        assert ec2_client.describe_tags(Filters=[{"Name": "value", "Values": [unique_name]}])["Tags"] == []
        corrected = {**request, "InstanceType": "m5.xlarge", "InstanceCount": 8,
                     "InstanceMatchCriteria": "targeted"}
        identifier = ec2_client.create_capacity_reservation(**corrected)["CapacityReservation"]["CapacityReservationId"]
        assert identifier
    finally:
        if identifier is not None:
            ec2_client.cancel_capacity_reservation(CapacityReservationId=identifier)
