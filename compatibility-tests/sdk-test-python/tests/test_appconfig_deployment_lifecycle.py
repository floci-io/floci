"""AppConfig bake completion and manual rollback through both retrieval APIs."""

from contextlib import ExitStack
from datetime import datetime, timedelta
import time

import boto3
import pytest
from botocore.exceptions import ClientError


@pytest.mark.parametrize("wait_for_completion", [False, True], ids=["baking", "complete"])
def test_deployment_rollback_restores_both_retrieval_apis(
        aws_config, client_config, unique_name, wait_for_completion):
    with ExitStack() as cleanup:
        client = boto3.client("appconfig", config=client_config, **aws_config)
        data = boto3.client("appconfigdata", config=client_config, **aws_config)
        cleanup.callback(client.close)
        cleanup.callback(data.close)
        app = client.create_application(Name=unique_name)["Id"]
        cleanup.callback(client.delete_application, ApplicationId=app)
        profile = client.create_configuration_profile(
            ApplicationId=app, Name="settings", LocationUri="hosted", Type="AWS.Freeform"
        )["Id"]
        cleanup.callback(
            client.delete_configuration_profile, ApplicationId=app, ConfigurationProfileId=profile)
        versions = []
        for content in (b'{"version":1}', b'{"version":2}'):
            version = client.create_hosted_configuration_version(
                ApplicationId=app, ConfigurationProfileId=profile,
                Content=content, ContentType="application/json",
            )["VersionNumber"]
            versions.append(version)
            cleanup.callback(
                client.delete_hosted_configuration_version, ApplicationId=app,
                ConfigurationProfileId=profile, VersionNumber=version,
            )
        strategies = []
        for bake in (0, 1):
            strategy = client.create_deployment_strategy(
                Name=f"{unique_name}-{bake}", DeploymentDurationInMinutes=0,
                FinalBakeTimeInMinutes=bake, GrowthFactor=100,
                GrowthType="LINEAR", ReplicateTo="NONE",
            )["Id"]
            strategies.append(strategy)
            cleanup.callback(client.delete_deployment_strategy, DeploymentStrategyId=strategy)
        env = client.create_environment(ApplicationId=app, Name="local")["Id"]
        cleanup.callback(client.delete_environment, ApplicationId=app, EnvironmentId=env)
        scope = {"ApplicationId": app, "EnvironmentId": env}

        def deploy(version, strategy):
            return client.start_deployment(
                **scope, ConfigurationProfileId=profile,
                ConfigurationVersion=str(version), DeploymentStrategyId=strategy)

        def assert_content(version, content):
            response = client.get_configuration(
                Application=app, Environment=env, Configuration=profile, ClientId=unique_name)
            with response["Content"] as stream:
                assert stream.read() == content
            assert response["ConfigurationVersion"] == str(version)
            token = data.start_configuration_session(
                ApplicationIdentifier=app, EnvironmentIdentifier=env,
                ConfigurationProfileIdentifier=profile,
            )["InitialConfigurationToken"]
            response = data.get_latest_configuration(ConfigurationToken=token)
            with response["Configuration"] as stream:
                assert stream.read() == content

        first = deploy(versions[0], strategies[0])
        assert first["State"] == "COMPLETE"
        assert isinstance(first["StartedAt"], datetime)
        assert first["CompletedAt"] == first["StartedAt"]
        second = deploy(versions[1], strategies[1])
        target = {**scope, "DeploymentNumber": second["DeploymentNumber"]}
        assert second["State"] == "BAKING"
        assert second["PercentageComplete"] == 100
        assert second["FinalBakeTimeInMinutes"] == 1
        assert "CompletedAt" not in second
        assert_content(versions[1], b'{"version":2}')
        assert client.get_deployment(**target)["State"] == "BAKING"
        with pytest.raises(ClientError) as error:
            client.stop_deployment(**target)
        assert error.value.response["Error"]["Code"] == "BadRequestException"
        assert client.get_deployment(**target)["State"] == "BAKING"

        if wait_for_completion:
            deadline = time.monotonic() + 75
            while time.monotonic() < deadline:
                observed = client.get_deployment(**target)
                if observed["State"] == "COMPLETE":
                    break
                assert observed["State"] == "BAKING"
                time.sleep(1)
            else:
                pytest.fail("The one-minute final bake did not complete")
            assert observed["CompletedAt"] - observed["StartedAt"] == timedelta(minutes=1)
            with pytest.raises(ClientError) as error:
                client.stop_deployment(**target)
            assert error.value.response["Error"]["Code"] == "BadRequestException"
            assert_content(versions[1], b'{"version":2}')

        stopped = client.stop_deployment(**target, AllowRevert=True)
        expected = "REVERTED" if wait_for_completion else "ROLLED_BACK"
        assert stopped["ResponseMetadata"]["HTTPStatusCode"] == 202
        assert stopped["State"] == expected
        assert isinstance(stopped["CompletedAt"], datetime)
        assert client.get_deployment(**target)["State"] == expected
        listed = {
            row["DeploymentNumber"]: row for row in client.list_deployments(**scope)["Items"]}
        assert listed[second["DeploymentNumber"]]["State"] == expected
        assert listed[second["DeploymentNumber"]]["CompletedAt"] == stopped["CompletedAt"]
        assert_content(versions[0], b'{"version":1}')
        with pytest.raises(ClientError) as error:
            client.stop_deployment(**target, AllowRevert=True)
        assert error.value.response["Error"]["Code"] == "BadRequestException"
        assert_content(versions[0], b'{"version":1}')
