"""CloudFormation resource naming compatibility tests.

Tests that CloudFormation correctly handles:
- Auto-generated resource names (when no explicit name is provided)
- Explicit resource names (when specified in Properties)
- Cross-stack references using intrinsic functions
"""

import json
import logging
import time

logger = logging.getLogger(__name__)

import pytest


def wait_for_stack_terminal_state(cfn_client, stack_name, timeout=60):
    """Wait for stack to reach a terminal state.

    Returns (success: bool, status: str).
    """
    success_states = {"CREATE_COMPLETE", "UPDATE_COMPLETE"}
    failure_states = {
        "CREATE_FAILED",
        "ROLLBACK_IN_PROGRESS",
        "ROLLBACK_FAILED",
        "ROLLBACK_COMPLETE",
        "DELETE_FAILED",
        "DELETE_COMPLETE",
    }

    start = time.time()
    while time.time() - start < timeout:
        resp = cfn_client.describe_stacks(StackName=stack_name)
        status = resp.get("Stacks", [{}])[0].get("StackStatus", "")
        if status in success_states:
            return True, status
        if status in failure_states:
            return False, status
        time.sleep(1)
    return False, "TIMEOUT"


def get_physical_id(resources, logical_id):
    """Get PhysicalResourceId for a given LogicalResourceId."""
    for resource in resources:
        if resource.get("LogicalResourceId") == logical_id:
            return resource.get("PhysicalResourceId")
    return None


class TestCloudFormationAutoNaming:
    """Test CloudFormation auto-generated resource names."""

    @pytest.fixture
    def auto_naming_stack(self, cloudformation_client, unique_name):
        """Create a stack with auto-generated resource names."""
        stack_name = f"cfn-auto-naming-{unique_name}"
        template = {
            "Resources": {
                "AutoBucket": {"Type": "AWS::S3::Bucket"},
                "AutoQueue": {"Type": "AWS::SQS::Queue"},
                "AutoTopic": {"Type": "AWS::SNS::Topic"},
                "AutoParameter": {
                    "Type": "AWS::SSM::Parameter",
                    "Properties": {"Type": "String", "Value": "v1"},
                },
                "CrossRefQueue": {
                    "Type": "AWS::SQS::Queue",
                    "Properties": {"QueueName": {"Fn::Sub": "${AutoBucket}-cross"}},
                },
            }
        }

        cloudformation_client.create_stack(
            StackName=stack_name,
            TemplateBody=json.dumps(template),
        )

        yield stack_name

        # Cleanup
        try:
            cloudformation_client.delete_stack(StackName=stack_name)
        except Exception as e:
            logger.warning("Failed to clean up CloudFormation stack %s: %s", stack_name, e)

    def test_auto_naming_create_stack(self, cloudformation_client, auto_naming_stack):
        """Test CreateStack succeeds with auto-named resources."""
        ok, status = wait_for_stack_terminal_state(
            cloudformation_client, auto_naming_stack
        )
        assert ok, f"Stack creation failed with status: {status}"

    def test_auto_naming_describe_stack_resources(
        self, cloudformation_client, auto_naming_stack
    ):
        """Test DescribeStackResources returns created resources."""
        wait_for_stack_terminal_state(cloudformation_client, auto_naming_stack)

        resources = cloudformation_client.describe_stack_resources(
            StackName=auto_naming_stack
        ).get("StackResources", [])

        assert len(resources) > 0, "No resources found in stack"

    def test_auto_naming_s3_bucket_generated(
        self, cloudformation_client, auto_naming_stack
    ):
        """Test S3 bucket gets auto-generated name."""
        wait_for_stack_terminal_state(cloudformation_client, auto_naming_stack)

        resources = cloudformation_client.describe_stack_resources(
            StackName=auto_naming_stack
        ).get("StackResources", [])

        bucket_name = get_physical_id(resources, "AutoBucket")
        assert bucket_name, "S3 bucket physical ID not found"

    def test_auto_naming_s3_bucket_constraints(
        self, cloudformation_client, auto_naming_stack
    ):
        """Test S3 bucket name follows naming constraints."""
        wait_for_stack_terminal_state(cloudformation_client, auto_naming_stack)

        resources = cloudformation_client.describe_stack_resources(
            StackName=auto_naming_stack
        ).get("StackResources", [])

        bucket_name = get_physical_id(resources, "AutoBucket")
        assert bucket_name, "S3 bucket physical ID not found"

        # S3 bucket naming constraints
        assert 3 <= len(bucket_name) <= 63, f"Bucket name length invalid: {len(bucket_name)}"
        assert bucket_name == bucket_name.lower(), "Bucket name must be lowercase"
        assert all(
            ch.islower() or ch.isdigit() or ch in ".-" for ch in bucket_name
        ), "Bucket name contains invalid characters"

    def test_auto_naming_sqs_queue_generated(
        self, cloudformation_client, auto_naming_stack
    ):
        """Test SQS queue gets auto-generated name."""
        wait_for_stack_terminal_state(cloudformation_client, auto_naming_stack)

        resources = cloudformation_client.describe_stack_resources(
            StackName=auto_naming_stack
        ).get("StackResources", [])

        queue_url = get_physical_id(resources, "AutoQueue")
        assert queue_url, "SQS queue physical ID not found"

    def test_auto_naming_sqs_queue_constraints(
        self, cloudformation_client, auto_naming_stack
    ):
        """Test SQS queue name follows naming constraints."""
        wait_for_stack_terminal_state(cloudformation_client, auto_naming_stack)

        resources = cloudformation_client.describe_stack_resources(
            StackName=auto_naming_stack
        ).get("StackResources", [])

        queue_url = get_physical_id(resources, "AutoQueue")
        assert queue_url, "SQS queue physical ID not found"

        # Extract queue name from URL
        queue_name = queue_url.rsplit("/", 1)[-1]
        assert 0 < len(queue_name) <= 80, f"Queue name length invalid: {len(queue_name)}"

    def test_auto_naming_sns_topic_generated(
        self, cloudformation_client, auto_naming_stack
    ):
        """Test SNS topic gets auto-generated name."""
        wait_for_stack_terminal_state(cloudformation_client, auto_naming_stack)

        resources = cloudformation_client.describe_stack_resources(
            StackName=auto_naming_stack
        ).get("StackResources", [])

        topic_arn = get_physical_id(resources, "AutoTopic")
        assert topic_arn, "SNS topic physical ID not found"

    def test_auto_naming_sns_topic_constraints(
        self, cloudformation_client, auto_naming_stack
    ):
        """Test SNS topic name follows naming constraints."""
        wait_for_stack_terminal_state(cloudformation_client, auto_naming_stack)

        resources = cloudformation_client.describe_stack_resources(
            StackName=auto_naming_stack
        ).get("StackResources", [])

        topic_arn = get_physical_id(resources, "AutoTopic")
        assert topic_arn, "SNS topic physical ID not found"

        # Extract topic name from ARN
        topic_name = topic_arn.rsplit(":", 1)[-1]
        assert 0 < len(topic_name) <= 256, f"Topic name length invalid: {len(topic_name)}"

    def test_auto_naming_ssm_parameter_generated(
        self, cloudformation_client, auto_naming_stack
    ):
        """Test SSM parameter gets auto-generated name."""
        wait_for_stack_terminal_state(cloudformation_client, auto_naming_stack)

        resources = cloudformation_client.describe_stack_resources(
            StackName=auto_naming_stack
        ).get("StackResources", [])

        param_name = get_physical_id(resources, "AutoParameter")
        assert param_name, "SSM parameter physical ID not found"

    def test_auto_naming_ssm_parameter_constraints(
        self, cloudformation_client, auto_naming_stack
    ):
        """Test SSM parameter name follows naming constraints."""
        wait_for_stack_terminal_state(cloudformation_client, auto_naming_stack)

        resources = cloudformation_client.describe_stack_resources(
            StackName=auto_naming_stack
        ).get("StackResources", [])

        param_name = get_physical_id(resources, "AutoParameter")
        assert param_name, "SSM parameter physical ID not found"
        assert len(param_name) <= 2048, f"Parameter name too long: {len(param_name)}"

    def test_auto_naming_cross_reference_queue_generated(
        self, cloudformation_client, auto_naming_stack
    ):
        """Test cross-reference queue gets generated with Fn::Sub."""
        wait_for_stack_terminal_state(cloudformation_client, auto_naming_stack)

        resources = cloudformation_client.describe_stack_resources(
            StackName=auto_naming_stack
        ).get("StackResources", [])

        cross_queue = get_physical_id(resources, "CrossRefQueue")
        assert cross_queue, "Cross-reference queue physical ID not found"

    def test_auto_naming_cross_reference_uses_bucket_name(
        self, cloudformation_client, auto_naming_stack
    ):
        """Test cross-reference queue name includes auto-generated bucket name."""
        wait_for_stack_terminal_state(cloudformation_client, auto_naming_stack)

        resources = cloudformation_client.describe_stack_resources(
            StackName=auto_naming_stack
        ).get("StackResources", [])

        bucket_name = get_physical_id(resources, "AutoBucket")
        cross_queue = get_physical_id(resources, "CrossRefQueue")

        assert bucket_name and cross_queue, "Missing resource physical IDs"

        cross_queue_name = cross_queue.rsplit("/", 1)[-1]
        assert cross_queue_name.startswith(
            f"{bucket_name}-cross"
        ), f"Cross-ref queue '{cross_queue_name}' should start with '{bucket_name}-cross'"


class TestCloudFormationExplicitNaming:
    """Test CloudFormation explicit resource names."""

    @pytest.fixture
    def explicit_naming_stack(self, cloudformation_client, unique_name):
        """Create a stack with explicit resource names."""
        stack_name = f"cfn-explicit-naming-{unique_name}"
        explicit_bucket = f"cfn-explicit-{unique_name}"
        explicit_queue = f"cfn-explicit-{unique_name}"
        explicit_topic = f"cfn-explicit-{unique_name}"
        explicit_param = f"/cfn-explicit/{unique_name}"

        template = {
            "Resources": {
                "NamedBucket": {
                    "Type": "AWS::S3::Bucket",
                    "Properties": {"BucketName": explicit_bucket},
                },
                "NamedQueue": {
                    "Type": "AWS::SQS::Queue",
                    "Properties": {"QueueName": explicit_queue},
                },
                "NamedTopic": {
                    "Type": "AWS::SNS::Topic",
                    "Properties": {"TopicName": explicit_topic},
                },
                "NamedParameter": {
                    "Type": "AWS::SSM::Parameter",
                    "Properties": {
                        "Name": explicit_param,
                        "Type": "String",
                        "Value": "explicit",
                    },
                },
            }
        }

        cloudformation_client.create_stack(
            StackName=stack_name,
            TemplateBody=json.dumps(template),
        )

        yield {
            "stack_name": stack_name,
            "expected_bucket": explicit_bucket,
            "expected_queue": explicit_queue,
            "expected_topic": explicit_topic,
            "expected_param": explicit_param,
        }

        # Cleanup
        try:
            cloudformation_client.delete_stack(StackName=stack_name)
        except Exception as e:
            logger.warning("Failed to clean up CloudFormation stack %s: %s", stack_name, e)

    def test_explicit_naming_create_stack(
        self, cloudformation_client, explicit_naming_stack
    ):
        """Test CreateStack succeeds with explicit resource names."""
        ok, status = wait_for_stack_terminal_state(
            cloudformation_client, explicit_naming_stack["stack_name"]
        )
        assert ok, f"Stack creation failed with status: {status}"

    def test_explicit_naming_s3_bucket(
        self, cloudformation_client, explicit_naming_stack
    ):
        """Test S3 bucket uses explicit name."""
        wait_for_stack_terminal_state(
            cloudformation_client, explicit_naming_stack["stack_name"]
        )

        resources = cloudformation_client.describe_stack_resources(
            StackName=explicit_naming_stack["stack_name"]
        ).get("StackResources", [])

        actual_bucket = get_physical_id(resources, "NamedBucket")
        assert actual_bucket == explicit_naming_stack["expected_bucket"]

    def test_explicit_naming_sqs_queue(
        self, cloudformation_client, explicit_naming_stack
    ):
        """Test SQS queue uses explicit name."""
        wait_for_stack_terminal_state(
            cloudformation_client, explicit_naming_stack["stack_name"]
        )

        resources = cloudformation_client.describe_stack_resources(
            StackName=explicit_naming_stack["stack_name"]
        ).get("StackResources", [])

        actual_queue = get_physical_id(resources, "NamedQueue")
        assert actual_queue and explicit_naming_stack["expected_queue"] in actual_queue

    def test_explicit_naming_sns_topic(
        self, cloudformation_client, explicit_naming_stack
    ):
        """Test SNS topic uses explicit name."""
        wait_for_stack_terminal_state(
            cloudformation_client, explicit_naming_stack["stack_name"]
        )

        resources = cloudformation_client.describe_stack_resources(
            StackName=explicit_naming_stack["stack_name"]
        ).get("StackResources", [])

        actual_topic = get_physical_id(resources, "NamedTopic")
        assert actual_topic and explicit_naming_stack["expected_topic"] in actual_topic

    def test_explicit_naming_ssm_parameter(
        self, cloudformation_client, explicit_naming_stack
    ):
        """Test SSM parameter uses explicit name."""
        wait_for_stack_terminal_state(
            cloudformation_client, explicit_naming_stack["stack_name"]
        )

        resources = cloudformation_client.describe_stack_resources(
            StackName=explicit_naming_stack["stack_name"]
        ).get("StackResources", [])

        actual_param = get_physical_id(resources, "NamedParameter")
        assert actual_param == explicit_naming_stack["expected_param"]
