"""DescribeTags validation observed on AWS, 2026-10-17."""

import uuid
from contextlib import ExitStack, closing

import boto3
import pytest
from botocore.config import Config
from botocore.exceptions import ClientError


def test_describe_tags_filter_validation(ec2_client, aws_config, client_config):
    name = "tag-validation-" + uuid.uuid4().hex[:12]
    with closing(boto3.client("autoscaling", config=client_config, **aws_config)) as autoscaling:
        with ExitStack() as cleanup:
            image = ec2_client.register_image(
                Name=name, Architecture="x86_64", RootDeviceName="/dev/sda1"
            )["ImageId"]
            cleanup.callback(ec2_client.deregister_image, ImageId=image)
            template = ec2_client.create_launch_template(
                LaunchTemplateName=name,
                LaunchTemplateData={"ImageId": image, "InstanceType": "t3.micro"},
            )["LaunchTemplate"]["LaunchTemplateId"]
            cleanup.callback(ec2_client.delete_launch_template, LaunchTemplateId=template)
            autoscaling.create_auto_scaling_group(
                AutoScalingGroupName=name, MinSize=0, MaxSize=0, DesiredCapacity=0,
                AvailabilityZones=[aws_config["region_name"] + "a"],
                LaunchTemplate={"LaunchTemplateId": template},
                Tags=[
                    {"Key": "propagated", "Value": "Yes", "PropagateAtLaunch": True},
                    {"Key": "not-propagated", "Value": "No", "PropagateAtLaunch": False},
                ],
            )
            cleanup.callback(autoscaling.delete_auto_scaling_group,
                             AutoScalingGroupName=name, ForceDelete=True)
            group_filter = {"Name": "auto-scaling-group", "Values": [name]}
            for filter_name, values, expected in [
                ("KEY", ["propagated"], ["propagated"]),
                ("\u212Aey", ["propagated"], ["propagated"]),
                ("key", ["PROPAGATED"], []),
                ("propagate-at-launch", ["TrUe"], ["propagated"]),
                ("propagate-at-launch", [" false "], ["not-propagated"]),
                ("propagate-at-launch", ["\tTRUE\n"], ["propagated"]),
                ("propagate-at-launch", [], ["not-propagated", "propagated"]),
            ]:
                tags = autoscaling.describe_tags(Filters=[
                    group_filter, {"Name": filter_name, "Values": values},
                ])["Tags"]
                assert sorted(tag["Key"] for tag in tags) == expected
            for filter_value in [
                {"Name": "key"}, {"Values": ["propagated"]},
                {"Name": "unknown", "Values": []},
                {"Name": "propagate-at-launch", "Values": ["1"]},
                {"Name": "propagate-at-launch", "Values": ["tru e"]},
            ]:
                with pytest.raises(ClientError) as failure:
                    autoscaling.describe_tags(Filters=[group_filter, filter_value])
                assert failure.value.response["Error"]["Code"] == "ValidationError"


def test_describe_tags_pagination_validation(aws_config):
    config = Config(parameter_validation=False, retries={"max_attempts": 1})
    with closing(boto3.client("autoscaling", config=config, **aws_config)) as autoscaling:
        for value in [0, -1, 101, "abc", "", "1.5"]:
            with pytest.raises(ClientError) as failure:
                autoscaling.describe_tags(MaxRecords=value)
            expected = "ValidationError" if isinstance(value, int) else "MalformedInput"
            assert failure.value.response["Error"]["Code"] == expected
        for token in ["invalid", ""]:
            with pytest.raises(ClientError) as failure:
                autoscaling.describe_tags(NextToken=token)
            assert failure.value.response["Error"]["Code"] == "ValidationError"
