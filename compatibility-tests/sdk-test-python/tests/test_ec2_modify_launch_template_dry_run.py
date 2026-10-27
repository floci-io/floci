"""ModifyLaunchTemplate dry runs leave the template and its versions unchanged."""

import pytest
from botocore.exceptions import ClientError


@pytest.mark.parametrize("selector", ["LaunchTemplateId", "LaunchTemplateName"])
@pytest.mark.parametrize("set_default", [False, True])
def test_modify_launch_template_dry_run(ec2_client, unique_name, selector, set_default):
    template = ec2_client.create_launch_template(
        LaunchTemplateName=unique_name,
        LaunchTemplateData={"InstanceType": "t3.nano"},
    )["LaunchTemplate"]
    template_id = template["LaunchTemplateId"]
    try:
        ec2_client.create_launch_template_version(
            LaunchTemplateId=template_id,
            SourceVersion="1",
            LaunchTemplateData={"InstanceType": "t3.micro"},
        )
        request = {selector: template[selector], "DryRun": True}
        if set_default:
            request["DefaultVersion"] = "2"
        with pytest.raises(ClientError) as error:
            ec2_client.modify_launch_template(**request)
        assert error.value.response["Error"]["Code"] == "DryRunOperation"
        assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 412

        described = ec2_client.describe_launch_templates(
            LaunchTemplateIds=[template_id]
        )["LaunchTemplates"][0]
        assert described["DefaultVersionNumber"] == 1
        assert described["LatestVersionNumber"] == 2
        versions = ec2_client.describe_launch_template_versions(
            LaunchTemplateId=template_id
        )["LaunchTemplateVersions"]
        assert [
            (row["VersionNumber"], row["DefaultVersion"], row["LaunchTemplateData"]["InstanceType"])
            for row in versions
        ] == [(1, True, "t3.nano"), (2, False, "t3.micro")]

        updated = ec2_client.modify_launch_template(
            **{selector: template[selector], "DefaultVersion": "2", "DryRun": False}
        )["LaunchTemplate"]
        assert updated["DefaultVersionNumber"] == 2
        assert ec2_client.describe_launch_templates(
            LaunchTemplateIds=[template_id]
        )["LaunchTemplates"][0]["DefaultVersionNumber"] == 2
    finally:
        ec2_client.delete_launch_template(LaunchTemplateId=template_id)
