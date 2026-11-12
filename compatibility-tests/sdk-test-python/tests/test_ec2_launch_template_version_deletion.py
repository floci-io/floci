"""Launch-template version deletion through the public EC2 API."""

import uuid

import boto3
import pytest
from botocore.exceptions import ClientError


@pytest.fixture
def template(ec2_client):
    name = "version-delete-" + uuid.uuid4().hex
    template_id = ec2_client.create_launch_template(
        LaunchTemplateName=name,
        VersionDescription="kept-version-one",
        LaunchTemplateData={"ImageId": "ami-0123456789abcdef0", "InstanceType": "t3.micro"},
    )["LaunchTemplate"]["LaunchTemplateId"]
    try:
        for version in range(2, 6):
            ec2_client.create_launch_template_version(
                LaunchTemplateId=template_id, SourceVersion="1",
                VersionDescription=f"version-{version}",
                LaunchTemplateData={"UserData": "ZWNobyB2ZXJzaW9u"},
            )
        yield template_id, name
    finally:
        ec2_client.delete_launch_template(LaunchTemplateId=template_id)


@pytest.mark.parametrize("by_name", [False, True])
def test_batch_retains_default_and_never_reuses_deleted_version_numbers(
        ec2_client, template, by_name):
    template_id, name = template
    ec2_client.modify_launch_template(
        LaunchTemplateId=template_id, DefaultVersion="3")
    selector = {"LaunchTemplateName": name} if by_name else {"LaunchTemplateId": template_id}
    result = ec2_client.delete_launch_template_versions(
        **selector, Versions=["2", "3", "5", "83"])
    successes = result["SuccessfullyDeletedLaunchTemplateVersions"]
    errors = result["UnsuccessfullyDeletedLaunchTemplateVersions"]
    assert {item["VersionNumber"] for item in successes} == {2, 5}
    assert {item["VersionNumber"] for item in errors} == {3, 83}
    for item in successes + errors:
        assert item["LaunchTemplateId"] == template_id
        assert item["LaunchTemplateName"] == name
    assert all(item["ResponseError"]["Message"] for item in errors)
    assert next(item for item in errors if item["VersionNumber"] == 83)[
        "ResponseError"]["Code"] == "launchTemplateVersionDoesNotExist"
    remaining = ec2_client.describe_launch_template_versions(
        LaunchTemplateId=template_id)["LaunchTemplateVersions"]
    assert [item["VersionNumber"] for item in remaining] == [1, 3, 4]
    assert remaining[0]["VersionDescription"] == "kept-version-one"
    assert remaining[1]["DefaultVersion"]
    created = ec2_client.create_launch_template_version(
        LaunchTemplateId=template_id, SourceVersion="3",
        LaunchTemplateData={})["LaunchTemplateVersion"]
    assert created["VersionNumber"] == 6
    selected = ec2_client.describe_launch_template_versions(
        LaunchTemplateId=template_id, Versions=["$Default", "$Latest"])["LaunchTemplateVersions"]
    assert [item["VersionNumber"] for item in selected] == [3, 6]
    assert selected[0]["LaunchTemplateData"] == selected[1]["LaunchTemplateData"]


def test_dry_run_preserves_all_versions(ec2_client, template):
    template_id, _ = template
    before = ec2_client.describe_launch_template_versions(
        LaunchTemplateId=template_id)["LaunchTemplateVersions"]
    with pytest.raises(ClientError) as caught:
        ec2_client.delete_launch_template_versions(
            LaunchTemplateId=template_id, Versions=["2", "5"], DryRun=True)
    assert caught.value.response["Error"]["Code"] == "DryRunOperation"
    assert caught.value.response["ResponseMetadata"]["HTTPStatusCode"] == 412
    assert ec2_client.describe_launch_template_versions(
        LaunchTemplateId=template_id)["LaunchTemplateVersions"] == before


@pytest.mark.parametrize("other_scope", ["account", "region"])
def test_foreign_template_is_reported_per_version_without_mutation(
        ec2_client, template, other_scope, aws_config, client_config):
    template_id, _ = template
    other_config = dict(aws_config)
    if other_scope == "region":
        other_config["region_name"] = (
            "eu-central-1" if ec2_client.meta.region_name != "eu-central-1" else "eu-west-1")
    else:
        other_config["aws_access_key_id"] = "999900001111"
    other = boto3.client("ec2", config=client_config, **other_config)
    try:
        result = other.delete_launch_template_versions(
            LaunchTemplateId=template_id, Versions=["2", "5"])
        assert result.get("SuccessfullyDeletedLaunchTemplateVersions", []) == []
        errors = result["UnsuccessfullyDeletedLaunchTemplateVersions"]
        assert {item["VersionNumber"] for item in errors} == {2, 5}
        assert all(item["LaunchTemplateId"] == template_id for item in errors)
        assert {item["ResponseError"]["Code"] for item in errors} == {
            "launchTemplateIdDoesNotExist"}
        assert [item["VersionNumber"] for item in ec2_client.describe_launch_template_versions(
            LaunchTemplateId=template_id)["LaunchTemplateVersions"]] == [1, 2, 3, 4, 5]
    finally:
        other.close()
