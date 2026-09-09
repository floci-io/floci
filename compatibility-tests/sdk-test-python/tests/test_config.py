"""AWS Config integration tests."""

import datetime

import pytest
from botocore.exceptions import ClientError

LAMBDA_SOURCE = "arn:aws:lambda:us-east-1:000000000000:function:compliance-checker"


def _put_custom_rule(config_client, rule_name, **overrides):
    """Create a minimal CUSTOM_LAMBDA config rule."""
    rule = {
        "ConfigRuleName": rule_name,
        "Source": {"Owner": "CUSTOM_LAMBDA", "SourceIdentifier": LAMBDA_SOURCE},
    }
    rule.update(overrides)
    config_client.put_config_rule(ConfigRule=rule)


class TestConfigRuleLifecycle:
    """Test config rule CRUD with a full-shape round-trip."""

    def test_put_describe_round_trip(self, config_client, unique_name):
        """Test PutConfigRule stores every field and DescribeConfigRules returns them."""
        rule_name = f"rule-{unique_name}"
        _put_custom_rule(
            config_client,
            rule_name,
            Description="Checks bucket policies",
            Scope={"ComplianceResourceTypes": ["AWS::S3::Bucket"], "TagKey": "env"},
            Source={
                "Owner": "CUSTOM_LAMBDA",
                "SourceIdentifier": LAMBDA_SOURCE,
                "SourceDetails": [
                    {"EventSource": "aws.config", "MessageType": "ConfigurationItemChangeNotification"}
                ],
            },
            InputParameters='{"maxPolicySize": 5}',
            MaximumExecutionFrequency="Six_Hours",
        )

        try:
            rules = config_client.describe_config_rules(ConfigRuleNames=[rule_name])["ConfigRules"]
            assert len(rules) == 1
            rule = rules[0]
            assert rule["ConfigRuleName"] == rule_name
            assert rule["ConfigRuleArn"].startswith("arn:aws:config:")
            assert rule["ConfigRuleId"]
            assert rule["ConfigRuleState"] == "ACTIVE"
            assert rule["Description"] == "Checks bucket policies"
            assert rule["Scope"]["ComplianceResourceTypes"] == ["AWS::S3::Bucket"]
            assert rule["Scope"]["TagKey"] == "env"
            assert rule["Source"]["Owner"] == "CUSTOM_LAMBDA"
            assert rule["Source"]["SourceIdentifier"] == LAMBDA_SOURCE
            assert rule["Source"]["SourceDetails"][0]["EventSource"] == "aws.config"
            assert rule["InputParameters"] == '{"maxPolicySize": 5}'
            assert rule["MaximumExecutionFrequency"] == "Six_Hours"
            assert rule["EvaluationModes"] == [{"Mode": "DETECTIVE"}]
        finally:
            config_client.delete_config_rule(ConfigRuleName=rule_name)

    def test_describe_unknown_rule_fails(self, config_client, unique_name):
        """Test DescribeConfigRules with an unknown name raises NoSuchConfigRuleException."""
        with pytest.raises(ClientError) as exc:
            config_client.describe_config_rules(ConfigRuleNames=[f"missing-{unique_name}"])
        assert exc.value.response["Error"]["Code"] == "NoSuchConfigRuleException"

    def test_delete_unknown_rule_fails(self, config_client, unique_name):
        """Test DeleteConfigRule with an unknown name raises NoSuchConfigRuleException."""
        with pytest.raises(ClientError) as exc:
            config_client.delete_config_rule(ConfigRuleName=f"missing-{unique_name}")
        assert exc.value.response["Error"]["Code"] == "NoSuchConfigRuleException"


class TestComplianceLoop:
    """Test the evaluation-driven compliance loop."""

    def test_evaluations_drive_compliance(self, config_client, unique_name):
        """Test PutEvaluations records results that compliance reads aggregate."""
        rule_name = f"loop-{unique_name}"
        resource_id = f"bucket-{unique_name}"
        _put_custom_rule(config_client, rule_name)

        try:
            # TestMode validates but persists nothing
            config_client.put_evaluations(
                ResultToken=rule_name,
                TestMode=True,
                Evaluations=[
                    {
                        "ComplianceResourceType": "AWS::S3::Bucket",
                        "ComplianceResourceId": resource_id,
                        "ComplianceType": "NON_COMPLIANT",
                        "OrderingTimestamp": datetime.datetime(2024, 1, 1, tzinfo=datetime.timezone.utc),
                    }
                ],
            )
            compliance = config_client.describe_compliance_by_config_rule(
                ConfigRuleNames=[rule_name]
            )["ComplianceByConfigRules"][0]["Compliance"]
            assert compliance["ComplianceType"] == "INSUFFICIENT_DATA"

            # Real evaluations flip the rule to NON_COMPLIANT
            response = config_client.put_evaluations(
                ResultToken=rule_name,
                Evaluations=[
                    {
                        "ComplianceResourceType": "AWS::S3::Bucket",
                        "ComplianceResourceId": resource_id,
                        "ComplianceType": "NON_COMPLIANT",
                        "Annotation": "Bucket policy too permissive",
                        "OrderingTimestamp": datetime.datetime(2024, 1, 1, tzinfo=datetime.timezone.utc),
                    }
                ],
            )
            assert response["FailedEvaluations"] == []

            compliance = config_client.describe_compliance_by_config_rule(
                ConfigRuleNames=[rule_name]
            )["ComplianceByConfigRules"][0]["Compliance"]
            assert compliance["ComplianceType"] == "NON_COMPLIANT"
            assert compliance["ComplianceContributorCount"]["CappedCount"] == 1
            assert compliance["ComplianceContributorCount"]["CapExceeded"] is False

            details = config_client.get_compliance_details_by_config_rule(
                ConfigRuleName=rule_name
            )["EvaluationResults"]
            assert len(details) == 1
            qualifier = details[0]["EvaluationResultIdentifier"]["EvaluationResultQualifier"]
            assert qualifier["ConfigRuleName"] == rule_name
            assert qualifier["ResourceType"] == "AWS::S3::Bucket"
            assert qualifier["ResourceId"] == resource_id
            assert details[0]["ComplianceType"] == "NON_COMPLIANT"
            assert details[0]["Annotation"] == "Bucket policy too permissive"
            assert isinstance(details[0]["ResultRecordedTime"], datetime.datetime)

            by_resource = config_client.get_compliance_details_by_resource(
                ResourceType="AWS::S3::Bucket", ResourceId=resource_id
            )["EvaluationResults"]
            assert len(by_resource) == 1
            assert (
                by_resource[0]["EvaluationResultIdentifier"]["EvaluationResultQualifier"]["ConfigRuleName"]
                == rule_name
            )

            resources = config_client.describe_compliance_by_resource(
                ResourceType="AWS::S3::Bucket", ResourceId=resource_id
            )["ComplianceByResources"]
            assert len(resources) == 1
            assert resources[0]["Compliance"]["ComplianceType"] == "NON_COMPLIANT"

            summary = config_client.get_compliance_summary_by_config_rule()["ComplianceSummary"]
            assert summary["NonCompliantResourceCount"]["CappedCount"] >= 1
            assert isinstance(summary["ComplianceSummaryTimestamp"], datetime.datetime)

            by_type = config_client.get_compliance_summary_by_resource_type(
                ResourceTypes=["AWS::S3::Bucket"]
            )["ComplianceSummariesByResourceType"]
            assert len(by_type) == 1
            assert by_type[0]["ResourceType"] == "AWS::S3::Bucket"
            assert by_type[0]["ComplianceSummary"]["NonCompliantResourceCount"]["CappedCount"] >= 1

            # DeleteEvaluationResults returns the rule to INSUFFICIENT_DATA
            config_client.delete_evaluation_results(ConfigRuleName=rule_name)
            compliance = config_client.describe_compliance_by_config_rule(
                ConfigRuleNames=[rule_name]
            )["ComplianceByConfigRules"][0]["Compliance"]
            assert compliance["ComplianceType"] == "INSUFFICIENT_DATA"
        finally:
            config_client.delete_config_rule(ConfigRuleName=rule_name)

    def test_put_external_evaluation(self, config_client, unique_name):
        """Test PutExternalEvaluation records a result for the named rule."""
        rule_name = f"external-{unique_name}"
        _put_custom_rule(config_client, rule_name)

        try:
            config_client.put_external_evaluation(
                ConfigRuleName=rule_name,
                ExternalEvaluation={
                    "ComplianceResourceType": "AWS::EC2::Instance",
                    "ComplianceResourceId": f"i-{unique_name}",
                    "ComplianceType": "COMPLIANT",
                    "OrderingTimestamp": datetime.datetime(2024, 1, 1, tzinfo=datetime.timezone.utc),
                },
            )
            compliance = config_client.describe_compliance_by_config_rule(
                ConfigRuleNames=[rule_name]
            )["ComplianceByConfigRules"][0]["Compliance"]
            assert compliance["ComplianceType"] == "COMPLIANT"
        finally:
            config_client.delete_config_rule(ConfigRuleName=rule_name)

    def test_put_evaluations_error_cases(self, config_client, unique_name):
        """Test PutEvaluations validation errors surface AWS error codes."""
        evaluation = {
            "ComplianceResourceType": "AWS::S3::Bucket",
            "ComplianceResourceId": f"bucket-{unique_name}",
            "ComplianceType": "COMPLIANT",
            "OrderingTimestamp": datetime.datetime(2024, 1, 1, tzinfo=datetime.timezone.utc),
        }

        with pytest.raises(ClientError) as exc:
            config_client.put_evaluations(ResultToken="", Evaluations=[evaluation])
        assert exc.value.response["Error"]["Code"] == "InvalidResultTokenException"

        with pytest.raises(ClientError) as exc:
            config_client.put_evaluations(
                ResultToken=f"missing-{unique_name}", Evaluations=[evaluation]
            )
        assert exc.value.response["Error"]["Code"] == "NoSuchConfigRuleException"


class TestRecorderChannelLifecycle:
    """Test configuration recorder and delivery channel lifecycle."""

    def test_recorder_and_channel_lifecycle(self, config_client):
        """Test recorder/channel setup, the guarded channel delete, and both deletes."""
        config_client.put_configuration_recorder(
            ConfigurationRecorder={
                "name": "default",
                "roleARN": "arn:aws:iam::000000000000:role/config-role",
                "recordingGroup": {"allSupported": True},
            }
        )

        try:
            config_client.put_delivery_channel(
                DeliveryChannel={"name": "default", "s3BucketName": "config-bucket"}
            )
            config_client.start_configuration_recorder(ConfigurationRecorderName="default")

            with pytest.raises(ClientError) as exc:
                config_client.delete_delivery_channel(DeliveryChannelName="default")
            assert exc.value.response["Error"]["Code"] == "LastDeliveryChannelDeleteFailedException"

            config_client.stop_configuration_recorder(ConfigurationRecorderName="default")
            config_client.delete_delivery_channel(DeliveryChannelName="default")

            with pytest.raises(ClientError) as exc:
                config_client.delete_delivery_channel(DeliveryChannelName="default")
            assert exc.value.response["Error"]["Code"] == "NoSuchDeliveryChannelException"
        finally:
            config_client.delete_configuration_recorder(ConfigurationRecorderName="default")

        assert config_client.describe_configuration_recorders()["ConfigurationRecorders"] == []


class TestRetention:
    """Test retention configuration lifecycle."""

    def test_retention_lifecycle(self, config_client):
        """Test put/describe/delete retention configuration."""
        retention = config_client.put_retention_configuration(RetentionPeriodInDays=365)
        assert retention["RetentionConfiguration"]["Name"] == "default"
        assert retention["RetentionConfiguration"]["RetentionPeriodInDays"] == 365

        try:
            configurations = config_client.describe_retention_configurations()["RetentionConfigurations"]
            assert len(configurations) == 1
            assert configurations[0]["RetentionPeriodInDays"] == 365
        finally:
            config_client.delete_retention_configuration(RetentionConfigurationName="default")

        assert config_client.describe_retention_configurations()["RetentionConfigurations"] == []


class TestPagination:
    """Test NextToken pagination through the boto3 paginator."""

    def test_describe_config_rules_paginates(self, config_client, unique_name):
        """Test the boto3 paginator walks every page of DescribeConfigRules."""
        rule_names = [f"pg-{unique_name}-{i:03d}" for i in range(30)]
        for rule_name in rule_names:
            _put_custom_rule(config_client, rule_name)

        try:
            first_page = config_client.describe_config_rules()
            assert len(first_page["ConfigRules"]) == 25
            assert first_page["NextToken"]

            collected = []
            paginator = config_client.get_paginator("describe_config_rules")
            for page in paginator.paginate():
                collected.extend(rule["ConfigRuleName"] for rule in page["ConfigRules"])
            for rule_name in rule_names:
                assert rule_name in collected
        finally:
            for rule_name in rule_names:
                config_client.delete_config_rule(ConfigRuleName=rule_name)
